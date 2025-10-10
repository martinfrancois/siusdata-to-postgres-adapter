package ch.fmartin;

import com.google.common.base.Throwables;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Adapter for monitoring a directory for CSV files, processing them, and inserting data into PostgreSQL.
 * It ensures single-threaded processing and queues file changes to be handled sequentially.
 */
public class SiusDataToPostgresAdapter {

    private static final int DELAY = 5000;
    // Configuration properties from environment variables
    private final String directoryToWatch;
    private String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final String pushbulletApiKey;
    private final String gotifyUrl;
    private final String gotifyToken;
    private final int gotifyPriority;

    private static final int DEFAULT_GOTIFY_PRIORITY = 5;

    // Dependencies
    private final Logger logger;
    private final ExecutorService executorService;
    private final HikariDataSource dataSource;
    private final WatchService watchService;

    // Set to track files that are already queued or being processed
    private final Set<String> queuedFiles = ConcurrentHashMap.newKeySet();

    // CSV file must start with eight digits, can be followed by anything, must end with .csv
    private static final Pattern CSV_FILE_PATTERN = Pattern.compile("^\\d{8}.*\\.csv$", Pattern.CASE_INSENSITIVE);

    // is true when it has processed all existing files upon startup
    private boolean initialized = false;

    // is true when it is watching file changes in the folder
    private boolean watching = false;

    // is true when it is currently reading the csv and writing it to the db
    private boolean processing = false;

    // indicates shutdown in progress; used to interrupt retry loops promptly
    private volatile boolean shuttingDown = false;

    private final AtomicInteger existingFilesTaskCount = new AtomicInteger(0);

    /**
     * Default constructor that initializes all dependencies internally.
     */
    public SiusDataToPostgresAdapter() {
        this.logger = LoggerFactory.getLogger(SiusDataToPostgresAdapter.class);

        // Configure DNS caching
        configureDNSCaching();

        // Validate and load environment variables
        this.directoryToWatch = System.getenv("CSV_MONITOR_PATH");
        this.jdbcUrl = System.getenv("POSTGRESQL_URL");
        this.jdbcUser = System.getenv("POSTGRESQL_USER");
        this.jdbcPassword = System.getenv("POSTGRESQL_PASSWORD");
        this.pushbulletApiKey = System.getenv("PUSHBULLET_API_KEY");
        this.gotifyUrl = System.getenv("GOTIFY_URL");
        this.gotifyToken = System.getenv("GOTIFY_TOKEN");

        int resolvedPriority = DEFAULT_GOTIFY_PRIORITY;
        String gotifyPriorityEnv = System.getenv("GOTIFY_PRIORITY");
        if (gotifyPriorityEnv != null && !gotifyPriorityEnv.isBlank()) {
            try {
                resolvedPriority = Integer.parseInt(gotifyPriorityEnv.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid GOTIFY_PRIORITY value '{}'. Using default {}.", gotifyPriorityEnv, DEFAULT_GOTIFY_PRIORITY);
            }
        }
        this.gotifyPriority = resolvedPriority;

        validateEnvironmentVariables();

        // Initialize DataSource
        this.dataSource = initializeDataSource();

        // Initialize database schema
        initializeDatabaseSchema();

        // Initialize single-threaded Executor Service
        this.executorService = Executors.newSingleThreadExecutor();
        logger.info("Initialized single-threaded executor for file processing.");

        // Initialize WatchService
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            logError("Failed to initialize WatchService: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Overloaded constructor that allows passing in dependencies for testing purposes.
     *
     * @param logger           The Logger instance.
     * @param executorService  The ExecutorService instance.
     * @param dataSource       The HikariDataSource instance.
     * @param watchService     The WatchService instance.
     * @param directoryToWatch The directory path to monitor.
     * @param jdbcUrl          The JDBC URL for PostgreSQL.
     * @param jdbcUser         The PostgreSQL username.
     * @param jdbcPassword     The PostgreSQL password.
     * @param pushbulletApiKey The Pushbullet API key.
     */
    public SiusDataToPostgresAdapter(Logger logger,
                                     ExecutorService executorService,
                                     HikariDataSource dataSource,
                                     WatchService watchService,
                                     String directoryToWatch,
                                     String jdbcUrl,
                                     String jdbcUser,
                                     String jdbcPassword,
                                     String pushbulletApiKey) {
        this(logger, executorService, dataSource, watchService, directoryToWatch, jdbcUrl, jdbcUser, jdbcPassword, pushbulletApiKey, null, null, DEFAULT_GOTIFY_PRIORITY);
    }

    public SiusDataToPostgresAdapter(Logger logger,
                                     ExecutorService executorService,
                                     HikariDataSource dataSource,
                                     WatchService watchService,
                                     String directoryToWatch,
                                     String jdbcUrl,
                                     String jdbcUser,
                                     String jdbcPassword,
                                     String pushbulletApiKey,
                                     String gotifyUrl,
                                     String gotifyToken,
                                     int gotifyPriority) {
        this.logger = logger;
        this.executorService = executorService;
        this.dataSource = dataSource;
        this.watchService = watchService;
        this.directoryToWatch = directoryToWatch;
        this.jdbcUrl = jdbcUrl;
        this.jdbcUser = jdbcUser;
        this.jdbcPassword = jdbcPassword;
        this.pushbulletApiKey = pushbulletApiKey;
        this.gotifyUrl = gotifyUrl;
        this.gotifyToken = gotifyToken;
        this.gotifyPriority = gotifyPriority;

        // No environment variable validation in this constructor to allow flexibility in tests
    }

    /**
     * Entry point of the application.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        SiusDataToPostgresAdapter adapter = new SiusDataToPostgresAdapter();
        adapter.start();
    }

    /**
     * Starts the adapter by initializing necessary components and beginning directory monitoring.
     */
    public void start() {
        try {
            // Register directory with WatchService
            Path directoryPath = Path.of(directoryToWatch);

            // Validate directory
            if (!Files.isDirectory(directoryPath)) {
                logger.error("The provided path is not a directory: {}", directoryPath.toString());
                shutdown();
                return;
            }

            directoryPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            logger.info("Monitoring directory: {}", directoryPath.toAbsolutePath());

            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            // Process existing CSV files upon startup
            processExistingFiles(directoryPath);

            // Start watching the directory for new files
            watchDirectory();

        } catch (Exception e) {
            logError("Unexpected error in start method: " + e.getMessage(), e);
            shutdown();
        }
    }

    /**
     * Configures DNS caching to reduce dependency on real-time DNS resolution.
     */
    void configureDNSCaching() {
        // Set DNS cache TTL to 60 seconds
        java.security.Security.setProperty("networkaddress.cache.ttl", "60");
        logger.info("Configured DNS caching with TTL=60 seconds.");
    }

    /**
     * Validates that all necessary environment variables are set.
     * Exits the application if any required variable is missing.
     */
    void validateEnvironmentVariables() {
        if (directoryToWatch == null) {
            logger.error("Environment variable CSV_MONITOR_PATH is not set.");
            throw new IllegalStateException("Environment variable CSV_MONITOR_PATH is not set.");
        }
        if (jdbcUrl == null) {
            logger.error("Environment variable POSTGRESQL_URL is not set.");
            throw new IllegalStateException("Environment variable POSTGRESQL_URL is not set.");
        }
        if (jdbcUser == null) {
            logger.error("Environment variable POSTGRESQL_USER is not set.");
            throw new IllegalStateException("Environment variable POSTGRESQL_USER is not set.");
        }
        if (jdbcPassword == null) {
            logger.error("Environment variable POSTGRESQL_PASSWORD is not set.");
            throw new IllegalStateException("Environment variable POSTGRESQL_PASSWORD is not set.");
        }
        logger.info("All required environment variables are set.");
    }

    /**
     * Initializes the HikariCP DataSource with a configurable pool size.
     *
     * @return Initialized HikariDataSource.
     */
    HikariDataSource initializeDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(jdbcUser);
        config.setPassword(jdbcPassword);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setIdleTimeout(120_000); // 2 minutes
        config.setMaxLifetime(300_000); // 5 minutes
        config.setConnectionTimeout(30_000); // 30 seconds
        config.setValidationTimeout(5_000); // 5 seconds
        config.setKeepaliveTime(180_000); // 3 minutes
        config.setPoolName("SiusDataHikariCP");

        HikariDataSource ds = new HikariDataSource(config);
        logger.info("HikariCP DataSource initialized with pool name '{}', maximum pool size {}.", config.getPoolName(), config.getMaximumPoolSize());
        return ds;
    }

    /**
     * Initializes the database schema by creating necessary tables if they don't exist,
     * including the new 'filename' column in 'siusdata_shots'.
     */
    void initializeDatabaseSchema() {
        String createSiusdataShotsTable = "CREATE TABLE IF NOT EXISTS siusdata_shots (" +
                "id SERIAL PRIMARY KEY," +
                "filename TEXT," +
                "start_number INT," +
                "score TEXT," +
                "phase INT," +
                "target_number INT," +
                "score2 TEXT," +
                "score3 TEXT," +
                "time TEXT," +
                "is_inner_ten BOOLEAN," +
                "coordinate_x TEXT," +
                "coordinate_y TEXT," +
                "is_in_time BOOLEAN," +
                "light_phase_time_span TEXT," +
                "is_right_sweep BOOLEAN," +
                "is_demo BOOLEAN," +
                "shoot_ordinal INT," +
                "practice_ordinal INT," +
                "manual_status INT," +
                "total_kind INT," +
                "group_ordinal INT," +
                "fire_kind INT," +
                "log_event_id BIGINT," +
                "log_type INT," +
                "date TIMESTAMP," +
                "relay INT," +
                "weapon INT," +
                "position INT," +
                "target_code INT," +
                "external_number INT" +
                ");";

        String createFileProgressTable = "CREATE TABLE IF NOT EXISTS file_progress (" +
                "file_name TEXT PRIMARY KEY," +
                "last_processed_line INT" +
                ");";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            if (!conn.isValid(5)) {
                throw new SQLException("Obtained an invalid connection from the pool.");
            }

            stmt.executeUpdate(createSiusdataShotsTable);
            logger.info("'siusdata_shots' table created or exists already.");

            stmt.executeUpdate(createFileProgressTable);
            logger.info("'file_progress' table created or exists already.");

        } catch (SQLException e) {
            logError("Error initializing database schema: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Sends a notification through Pushbullet if the API key is set.
     *
     * @param title   The title of the notification.
     * @param message The message body of the notification.
     */
    void sendPushbulletNotification(String title, String message) {
        if (pushbulletApiKey == null || pushbulletApiKey.isEmpty()) {
            logger.debug("Pushbullet API key not set. Skipping notification.");
            return;
        }

        HttpURLConnection conn = null;
        try {
            URL url = URI.create("https://api.pushbullet.com/v2/pushes").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Access-Token", pushbulletApiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            // Create the JSON payload
            JSONObject json = new JSONObject();
            json.put("type", "note");
            json.put("title", title);
            json.put("body", message);

            // Using try-with-resources for OutputStream
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                logger.error("Failed to send Pushbullet notification. Response Code: {}", responseCode);

                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    // Using try-with-resources for BufferedReader to read the error response body
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder responseBody = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBody.append(line);
                        }
                        logger.error("Response body: {}", responseBody.toString());
                    } catch (IOException e) {
                        logger.error("Error reading response body: {}", e.getMessage(), e);
                    }
                }
            } else {
                logger.debug("Pushbullet notification sent successfully.");
            }

        } catch (IOException e) {
            logger.error("Error sending Pushbullet notification: {}", e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    void sendGotifyNotification(String title, String message) {
        if (gotifyUrl == null || gotifyUrl.isBlank()) {
            logger.debug("Gotify URL not set. Skipping notification.");
            return;
        }
        if (gotifyToken == null || gotifyToken.isBlank()) {
            logger.debug("Gotify token not set. Skipping notification.");
            return;
        }

        HttpURLConnection conn = null;
        try {
            String endpoint = gotifyUrl.endsWith("/") ? gotifyUrl + "message" : gotifyUrl + "/message";
            URL url = URI.create(endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-Gotify-Key", gotifyToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject json = new JSONObject();
            json.put("title", title);
            json.put("message", message);
            json.put("priority", gotifyPriority);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                logger.error("Failed to send Gotify notification. Response Code: {}", responseCode);

                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder responseBody = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            responseBody.append(line);
                        }
                        logger.error("Response body: {}", responseBody.toString());
                    } catch (IOException e) {
                        logger.error("Error reading Gotify response body: {}", e.getMessage(), e);
                    }
                }
            } else {
                logger.debug("Gotify notification sent successfully.");
            }

        } catch (IOException e) {
            logger.error("Error sending Gotify notification: {}", e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    void sendNotifications(String title, String message) {
        sendPushbulletNotification(title, message);
        sendGotifyNotification(title, message);
    }

    /**
     * Watches the configured directory for CSV file creation and modification events.
     * Submits detected files for processing.
     */
    void watchDirectory() {
        logger.info("Starting directory watch loop.");
        setWatching(true);
        while (true) {
            WatchKey key;
            try {
                key = watchService.take();  // Wait for a watch key to be available
            } catch (InterruptedException e) {
                logger.warn("Watch service interrupted.");
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                logger.info("Watch service closed.");
                break;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // Overflow event
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    logger.warn("File system event overflow occurred.");
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                String fileName = ev.context().toString();
                Path filePath = Path.of(directoryToWatch).resolve(fileName);

                // Check if the file matches the CSV pattern
                if (Files.isRegularFile(filePath) && isValidCsvFile(fileName)) {
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        logger.info("Detected {} event for file: {}", kind.name(), fileName);
                        submitFileForProcessing(filePath, false);
                    }
                } else {
                    logger.debug("Skipping non-matching file or directory: {}", fileName);
                }
            }

            // Reset the key -- this step is critical to receive further watch events.
            boolean valid = key.reset();
            if (!valid) {
                logError("Watch key is no longer valid. Stopping watch service.", null);
                break;
            }
        }
    }

    /**
     * Checks if the file name matches the expected CSV pattern.
     *
     * @param fileName The name of the file.
     * @return True if it matches, false otherwise.
     */
    boolean isValidCsvFile(String fileName) {
        if (!CSV_FILE_PATTERN.matcher(fileName).matches()) {
            return false;
        }
        String lower = fileName.toLowerCase();
        // Explicitly exclude SIUS sidecar files
        if (lower.endsWith("_stl.csv") || lower.endsWith("_mod.csv")) {
            return false;
        }
        return true;
    }

    /**
     * Submits a file for processing by enqueuing the task.
     * Ensures that a file is only queued once at a time.
     *
     * @param filePath The path to the file to process.
     */
    void submitFileForProcessing(Path filePath, boolean isExistingFile) {
        String fileName = filePath.getFileName().toString();
        boolean isQueued = queuedFiles.add(fileName);

        if (isQueued) {
            logger.info("Enqueued file {} for processing.", fileName);

            // Increment the task counter only for existing files
            if (isExistingFile) {
                existingFilesTaskCount.incrementAndGet();
            }

            executorService.submit(() -> {
                try {
                    processFileWithRetries(filePath);
                } finally {
                    // Decrement the task counter when the task completes
                    if (isExistingFile) {
                        int remainingTasks = existingFilesTaskCount.decrementAndGet();

                        if (remainingTasks == 0) {
                            // All existing tasks have completed
                            setInitialized(true);
                            logger.info("All existing files processed!");
                        }
                    }
                }
            });
        } else {
            logger.info("File {} is already queued or being processed. Skipping submission.", fileName);
        }
    }


    /**
     * Processes a single CSV file with infinite retry logic.
     * Retries indefinitely with a fixed 5-second wait between attempts.
     *
     * @param filePath The path to the CSV file.
     */
    void processFileWithRetries(Path filePath) {
        String fileName = filePath.getFileName().toString();

        while (true) {
            // Exit promptly if shutdown has been initiated or thread interrupted
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                logger.info("Aborting processing of file {} due to shutdown.", fileName);
                setProcessing(false);
                queuedFiles.remove(fileName);
                break;
            }
            try {
                setProcessing(true);
                processFile(filePath);
                setProcessing(false);
                queuedFiles.remove(fileName);
                logger.info("Successfully processed file: {}", fileName);

                break; // Exit loop on success
            } catch (Exception e) {
                logError("Failed to process file " + fileName + ": " + e.getMessage(), e);

                try {
                    if (shuttingDown || Thread.currentThread().isInterrupted()) {
                        logger.info("Stopping retries for file {} due to shutdown.", fileName);
                        setProcessing(false);
                        queuedFiles.remove(fileName);
                        break;
                    }
                    logger.info("Waiting for {} milliseconds before retrying...", DELAY);
                    Thread.sleep(DELAY);
                } catch (InterruptedException ie) {
                    logger.warn("Retry sleep interrupted.");
                    Thread.currentThread().interrupt();
                    setProcessing(false);
                    queuedFiles.remove(fileName);
                    break;
                }
            }
        }
    }

    /**
     * Processes a single CSV file: reads new lines and inserts data into the database.
     * Continues processing until no new lines are detected.
     *
     * @param filePath The path to the CSV file.
     */
    void processFile(Path filePath) throws SQLException, IOException {
        String fileNameWithExtension = filePath.getFileName().toString();
        logger.info("Started processing file: {}", fileNameWithExtension);

        boolean keepProcessing = true;
        while (keepProcessing) {
            try (Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(5)) {
                    throw new SQLException("Obtained an invalid connection from the pool.");
                }

                // Disable auto-commit for transaction management
                conn.setAutoCommit(false);

                try {
                    // Retrieve last processed line
                    int lastProcessedLine = getLastProcessedLine(conn, fileNameWithExtension);

                    // Parse CSV and get new records
                    List<CsvRecord> newRecords = parseNewCsvRecords(filePath, lastProcessedLine);

                    if (newRecords.isEmpty()) {
                        logger.info("No new records to process in file: {}", fileNameWithExtension);
                        keepProcessing = false;
                        continue;
                    }

                    // Insert records into the database
                    int processedCount = 0;
                    for (CsvRecord record : newRecords) {
                        try {
                            insertRecordIntoDatabase(conn, record, fileNameWithExtension);
                            processedCount++;
                        } catch (SQLException e) {
                            logError("Failed to insert record at line " + (lastProcessedLine + processedCount + 1) + " in file " + fileNameWithExtension + ": " + e.getMessage(), e);
                            throw e; // rethrow to trigger retry
                        }
                    }

                    // Update last processed line
                    updateLastProcessedLine(conn, fileNameWithExtension, lastProcessedLine + processedCount);

                    // Commit transaction
                    conn.commit();
                    logger.info("Successfully processed {} records from file: {}", processedCount, fileNameWithExtension);

                } catch (Exception e) {
                    // Rollback transaction on error
                    conn.rollback();
                    logError("Error processing file " + fileNameWithExtension + ": " + e.getMessage(), e);
                    throw e; // Rethrow to trigger retry
                } finally {
                    // Restore auto-commit
                    conn.setAutoCommit(true);
                }

            } catch (SQLException e) {
                logError("Failed to process file " + fileNameWithExtension + ": " + e.getMessage(), e);
                throw e; // Rethrow to trigger retry
            }
        }
    }

    /**
     * Retrieves the last processed line number for a given file from the database.
     *
     * @param conn     The database connection.
     * @param fileName The name of the file.
     * @return The last processed line number.
     * @throws SQLException If a database access error occurs.
     */
    int getLastProcessedLine(Connection conn, String fileName) throws SQLException {
        String query = "SELECT last_processed_line FROM file_progress WHERE file_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, fileName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int line = rs.getInt("last_processed_line");
                    logger.debug("Last processed line for file {}: {}", fileName, line);
                    return line;
                }
            }
        }
        logger.debug("No previous processing record found for file {}. Starting from line 0.", fileName);
        return 0;  // Start from the beginning if not found
    }

    /**
     * Updates the last processed line number for a given file in the database.
     *
     * @param conn             The database connection.
     * @param fileName         The name of the file.
     * @param newLastProcessed The new last processed line number.
     * @throws SQLException If a database access error occurs.
     */
    void updateLastProcessedLine(Connection conn, String fileName, int newLastProcessed) throws SQLException {
        String query = "INSERT INTO file_progress (file_name, last_processed_line) VALUES (?, ?) " +
                "ON CONFLICT (file_name) DO UPDATE SET last_processed_line = EXCLUDED.last_processed_line";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, fileName);
            stmt.setInt(2, newLastProcessed);
            stmt.executeUpdate();
            logger.debug("Updated last processed line for file {} to {}", fileName, newLastProcessed);
        }
    }

    /**
     * Parses new CSV records from the file starting from the specified line.
     *
     * @param filePath          The path to the CSV file.
     * @param lastProcessedLine The last processed line number.
     * @return A list of new CSV records.
     * @throws IOException If an I/O error occurs.
     */
    List<CsvRecord> parseNewCsvRecords(Path filePath, int lastProcessedLine) throws IOException {
        List<CsvRecord> list = new ArrayList<>();
        try (CsvReader<CsvRecord> csv = CsvReader.builder().fieldSeparator(';').ofCsvRecord(filePath)) {
            int lineCounter = 0;
            for (final CsvRecord csvRecord : csv) {
                if (lineCounter++ < lastProcessedLine) {
                    continue;  // Skip already processed lines
                }

                list.add(csvRecord);
            }
        }
        return list;
    }

    /**
     * Inserts a single CSV record into the siusdata_shots table.
     *
     * @param conn                  The database connection.
     * @param record                The CSV record to insert.
     * @param fileNameWithExtension The name of the file being processed.
     * @throws SQLException If a database access error occurs.
     */
    void insertRecordIntoDatabase(Connection conn, CsvRecord record, String fileNameWithExtension) throws SQLException {
        String query = "INSERT INTO siusdata_shots (" +
                "filename, start_number, score, phase, target_number, score2, score3, time, " +
                "is_inner_ten, coordinate_x, coordinate_y, is_in_time, light_phase_time_span, " +
                "is_right_sweep, is_demo, shoot_ordinal, practice_ordinal, manual_status, " +
                "total_kind, group_ordinal, fire_kind, log_event_id, log_type, date, " +
                "relay, weapon, position, target_code, external_number" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            // Parsing and setting fields with validation
            // Field indices based on CSV column order (0-based)

            // 1. filename of the csv file (TEXT)
            stmt.setString(1, fileNameWithExtension);

            // 2. start_number (INT) - Column 0
            setIntegerField(stmt, 2, record.getField(0));

            // 3. score (TEXT) - Column 1
            setTextField(stmt, 3, record.getField(1));

            // 4. phase (INT) - Column 2
            setIntegerField(stmt, 4, record.getField(2));

            // 5. target_number (INT) - Column 3
            setIntegerField(stmt, 5, record.getField(3));

            // 6. score2 (TEXT) - Column 4
            setTextField(stmt, 6, record.getField(4));

            // 7. score3 (TEXT) - Column 5
            setTextField(stmt, 7, record.getField(5));

            // 8. time (TEXT) - Column 6
            setTextField(stmt, 8, record.getField(6));

            // 9. is_inner_ten (BOOLEAN) - Column 7
            setBooleanField(stmt, 9, record.getField(7));

            // 10. coordinate_x (TEXT) - Column 8
            setTextField(stmt, 10, record.getField(8));

            // 11. coordinate_y (TEXT) - Column 9
            setTextField(stmt, 11, record.getField(9));

            // 12. is_in_time (BOOLEAN) - Column 10
            setBooleanField(stmt, 12, record.getField(10));

            // 13. light_phase_time_span (TEXT) - Column 11
            setTextField(stmt, 13, record.getField(11));

            // 14. is_right_sweep (BOOLEAN) - Column 12
            setBooleanField(stmt, 14, record.getField(12));

            // 15. is_demo (BOOLEAN) - Column 13
            setBooleanField(stmt, 15, record.getField(13));

            // 16. shoot_ordinal (INT) - Column 14
            setIntegerField(stmt, 16, record.getField(14));

            // 17. practice_ordinal (INT) - Column 15
            setIntegerField(stmt, 17, record.getField(15));

            // 18. manual_status (INT) - Column 16
            setIntegerField(stmt, 18, record.getField(16));

            // 19. total_kind (INT) - Column 17
            setIntegerField(stmt, 19, record.getField(17));

            // 20. group_ordinal (INT) - Column 18
            setIntegerField(stmt, 20, record.getField(18));

            // 21. fire_kind (INT) - Column 19
            setIntegerField(stmt, 21, record.getField(19));

            // 22. log_event_id (BIGINT) - Column 20
            setLongField(stmt, 22, record.getField(20));

            // 23. log_type (INT) - Column 21
            setIntegerField(stmt, 23, record.getField(21));

            // 24. date (TIMESTAMP) - Column 22
            Timestamp calculatedTimestamp = calculateTimestamp(record.getField(22), fileNameWithExtension);
            if (calculatedTimestamp != null) {
                stmt.setTimestamp(24, calculatedTimestamp);
            } else {
                stmt.setNull(24, Types.TIMESTAMP);
            }

            // 25. relay (INT) - Column 23
            setIntegerField(stmt, 25, record.getField(23));

            // 26. weapon (INT) - Column 24
            setIntegerField(stmt, 26, record.getField(24));

            // 27. position (INT) - Column 25
            setIntegerField(stmt, 27, record.getField(25));

            // 28. target_code (INT) - Column 26
            setIntegerField(stmt, 28, record.getField(26));

            // 29. external_number (INT) - Column 27
            setIntegerField(stmt, 29, record.getField(27));

            // Execute the insert statement
            stmt.executeUpdate();
        }
    }

    /**
     * Calculates the Timestamp based on the Date field value and the year from the filename.
     *
     * @param dateValue The Date field value from CSV.
     * @param fileName  The name of the file to extract the year.
     * @return The calculated Timestamp, or null if invalid.
     */
    Timestamp calculateTimestamp(String dateValue, String fileName) {
        try {
            long intervals = Long.parseLong(dateValue.trim());
            long millisecondsToAdd = intervals * 10;

            // Extract year from filename (first four digits)
            if (fileName.length() < 4) {
                logError("Filename '" + fileName + "' is too short to extract year.");
                throw new IllegalStateException("Should never happen, as only files that match the pattern should be processed!");
            }
            String yearStr = fileName.substring(0, 4);
            int year = Integer.parseInt(yearStr);

            LocalDateTime startOfYear = LocalDateTime.of(year, 1, 1, 0, 0, 0, 0);
            Instant startOfYearInstant = startOfYear.atZone(ZoneId.systemDefault()).toInstant();

            // Add the milliseconds
            Instant calculatedInstant = startOfYearInstant.plusMillis(millisecondsToAdd);

            // Convert to Timestamp
            return Timestamp.from(calculatedInstant);
        } catch (NumberFormatException e) {
            logError("Invalid Date value '" + dateValue + "': " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Sets an integer field in the PreparedStatement with proper error handling.
     *
     * @param stmt     The PreparedStatement.
     * @param index    The parameter index.
     * @param valueStr The string value to parse and set.
     * @throws SQLException If a database access error occurs.
     */
    void setIntegerField(PreparedStatement stmt, int index, String valueStr) throws SQLException {
        try {
            int value = Integer.parseInt(valueStr.trim());
            stmt.setInt(index, value);
        } catch (NumberFormatException | NullPointerException e) {
            String errorMsg = "Invalid integer value '" + valueStr + "' for parameter index " + index + ". Setting NULL.";
            logError(errorMsg, e);
            stmt.setNull(index, Types.INTEGER);
        }
    }

    /**
     * Sets a long field in the PreparedStatement with proper error handling.
     *
     * @param stmt     The PreparedStatement.
     * @param index    The parameter index.
     * @param valueStr The string value to parse and set.
     * @throws SQLException If a database access error occurs.
     */
    void setLongField(PreparedStatement stmt, int index, String valueStr) throws SQLException {
        try {
            long value = Long.parseLong(valueStr.trim());
            stmt.setLong(index, value);
        } catch (NumberFormatException | NullPointerException e) {
            String errorMsg = "Invalid long value '" + valueStr + "' for parameter index " + index + ". Setting NULL.";
            logError(errorMsg, e);
            stmt.setNull(index, Types.BIGINT);
        }
    }

    /**
     * Sets a boolean field in the PreparedStatement based on "0" or "1".
     *
     * @param stmt     The PreparedStatement.
     * @param index    The parameter index.
     * @param valueStr The string value to parse and set.
     * @throws SQLException If a database access error occurs.
     */
    void setBooleanField(PreparedStatement stmt, int index, String valueStr) throws SQLException {
        if (valueStr != null && !valueStr.trim().isEmpty()) {
            boolean value = "1".equals(valueStr.trim());
            stmt.setBoolean(index, value);
        } else {
            String errorMsg = "Missing boolean value '" + valueStr + "' for parameter index " + index + ". Setting NULL.";
            logError(errorMsg);
            stmt.setNull(index, Types.BOOLEAN);
        }
    }

    /**
     * Sets a text field in the PreparedStatement with proper error handling.
     *
     * @param stmt     The PreparedStatement.
     * @param index    The parameter index.
     * @param valueStr The string value to set.
     * @throws SQLException If a database access error occurs.
     */
    void setTextField(PreparedStatement stmt, int index, String valueStr) throws SQLException {
        if (valueStr != null && !valueStr.trim().isEmpty()) {
            stmt.setString(index, valueStr.trim());
        } else {
            String errorMsg = "Missing String value '" + valueStr + "' for parameter index " + index + ". Setting NULL.";
            logError(errorMsg);
            stmt.setNull(index, Types.VARCHAR);
        }
    }

    /**
     * Processes existing CSV files in the directory upon application startup.
     *
     * @param directoryPath The path to the directory to scan.
     */
    void processExistingFiles(Path directoryPath) {
        logger.info("Scanning directory for existing CSV files to process...");
        int fileCount = 0;  // Track how many files are found
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                path -> Files.isRegularFile(path) && isValidCsvFile(path.getFileName().toString()))
        ) {
            for (Path filePath : stream) {
                String fileName = filePath.getFileName().toString();
                logger.info("Found existing file to process: {}", fileName);
                submitFileForProcessing(filePath, true);  // true indicates it's an existing file
                fileCount++;  // Increment for each file found
            }
        } catch (IOException e) {
            String dir = directoryPath.toAbsolutePath().toString();
            logError("Error scanning directory " + dir + ": " + e.getMessage(), e);
        }

        // If no existing files were found, set initialized to true immediately
        if (fileCount == 0) {
            logger.info("No existing files found to process!");
            setInitialized(true);
        }
    }


    /**
     * Shuts down the application gracefully.
     */
    void shutdown() {
        logger.info("Shutting down application...");
        // Mark shutdown to allow running tasks to terminate promptly
        this.shuttingDown = true;

        // Close WatchService
        if (watchService != null) {
            try {
                watchService.close();
                logger.info("Watch service closed.");
            } catch (IOException e) {
                logError("Error closing watch service: " + e.getMessage(), e);
            }
        }

        // Shutdown ExecutorService
        if (executorService != null) {
            // Interrupt running tasks immediately to stop retry sleeps
            executorService.shutdownNow();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    // Best-effort: force again
                    executorService.shutdownNow();
                }
                logger.info("Executor service shutdown complete.");
            } catch (InterruptedException e) {
                logger.warn("Interrupted during executor service shutdown.");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Close HikariCP DataSource
        if (dataSource != null) {
            dataSource.close();
            logger.info("HikariCP DataSource closed.");
        }

        logger.info("Application shutdown complete.");
    }

    /**
     * Centralized method to log errors and send Pushbullet notifications.
     *
     * @param message   The error message to log and send.
     * @param throwable The throwable associated with the error (can be null).
     */
    void logError(String message, Throwable throwable) {
        if (throwable != null) {
            logger.error(message, throwable);
            sendNotifications("SiusData Adapter Error", message + "\n" + Throwables.getStackTraceAsString(throwable));
        } else {
            logError(message); // Delegate to the overloaded method
        }
    }

    /**
     * Overloaded method to log errors without a Throwable.
     *
     * @param message The error message to log and send.
     */
    void logError(String message) {
        logger.error(message);
        sendNotifications("SiusData Adapter Error", message);
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean isWatching() {
        return watching;
    }

    private void setWatching(boolean watching) {
        this.watching = watching;
    }

    public boolean isProcessing() {
        return processing;
    }

    private void setProcessing(boolean processing) {
        this.processing = processing;
    }
}
