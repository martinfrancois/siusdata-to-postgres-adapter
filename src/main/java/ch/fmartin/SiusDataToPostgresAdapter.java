package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Adapter for monitoring a directory for CSV files, processing them, and inserting data into PostgreSQL.
 * It ensures single-threaded processing and queues file changes to be handled sequentially.
 * This class is the entry point: it reads the configuration, wires the parts together, and starts and
 * stops them. Every part logs through the same logger, so every log line carries this class's name.
 */
public class SiusDataToPostgresAdapter {

    // Dependencies
    private final Logger logger;
    private final ExecutorService executorService;
    private final HikariDataSource dataSource;
    private final WatchService watchService;
    private final String directoryToWatch;
    private final Notifier notifier;
    private final DirectoryWatcher directoryWatcher;

    /**
     * Default constructor that initializes all dependencies internally.
     */
    public SiusDataToPostgresAdapter() {
        this(System::getenv);
    }

    /**
     * Initializes all dependencies internally and reads the configuration through {@code environment},
     * which returns the value of a variable or {@code null} when it is not set.
     */
    SiusDataToPostgresAdapter(UnaryOperator<String> environment) {
        this.logger = LoggerFactory.getLogger(SiusDataToPostgresAdapter.class);

        // Configure DNS caching
        configureDNSCaching();

        // Validate and load environment variables
        AdapterConfiguration configuration = AdapterConfiguration.fromEnvironment(environment, logger);
        this.directoryToWatch = configuration.directoryToWatch();
        this.notifier = newNotifier(logger, configuration);
        configuration.validate(logger);

        // Initialize DataSource
        this.dataSource = ShotDatabase.createDataSource(configuration, logger);

        // Initialize database schema
        ShotDatabase database = new ShotDatabase(logger, dataSource, notifier);
        database.initializeDatabaseSchema();

        // Initialize single-threaded Executor Service
        this.executorService = Executors.newSingleThreadExecutor();
        logger.info("Initialized single-threaded executor for file processing.");

        // Initialize WatchService
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            notifier.logError("Failed to initialize WatchService: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }

        this.directoryWatcher = newDirectoryWatcher(database);
    }

    /**
     * Takes the dependencies and the configuration from the caller, so tests can pass mocks. It does not
     * validate the configuration.
     */
    SiusDataToPostgresAdapter(Logger logger,
                              ExecutorService executorService,
                              HikariDataSource dataSource,
                              WatchService watchService,
                              AdapterConfiguration configuration) {
        this.logger = logger;
        this.executorService = executorService;
        this.dataSource = dataSource;
        this.watchService = watchService;
        this.directoryToWatch = configuration.directoryToWatch();
        this.notifier = newNotifier(logger, configuration);
        this.directoryWatcher = newDirectoryWatcher(new ShotDatabase(logger, dataSource, notifier));
    }

    private static Notifier newNotifier(Logger logger, AdapterConfiguration configuration) {
        return new Notifier(logger, configuration.pushbulletApiKey(), configuration.gotifyUrl(),
                configuration.gotifyToken(), configuration.gotifyPriority());
    }

    private DirectoryWatcher newDirectoryWatcher(ShotDatabase database) {
        ShotFileImporter importer = new ShotFileImporter(logger, dataSource, database, new ShotRecordMapper(notifier), notifier);
        return new DirectoryWatcher(logger, executorService, watchService, directoryToWatch, importer, notifier);
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
            directoryWatcher.processExistingFiles(directoryPath);

            // Start watching the directory for new files
            directoryWatcher.watchDirectory();

        } catch (Exception e) {
            notifier.logError("Unexpected error in start method: " + e.getMessage(), e);
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
     * Shuts down the application gracefully.
     */
    void shutdown() {
        logger.info("Shutting down application...");
        // Mark shutdown to allow running tasks to terminate promptly
        directoryWatcher.stopRetrying();

        // Close WatchService
        if (watchService != null) {
            try {
                watchService.close();
                logger.info("Watch service closed.");
            } catch (IOException e) {
                notifier.logError("Error closing watch service: " + e.getMessage(), e);
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

    public boolean isInitialized() {
        return directoryWatcher.isInitialized();
    }

    public boolean isWatching() {
        return directoryWatcher.isWatching();
    }

    public boolean isProcessing() {
        return directoryWatcher.isProcessing();
    }
}
