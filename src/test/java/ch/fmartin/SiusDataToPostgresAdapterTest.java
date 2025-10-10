package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SiusDataToPostgresAdapterTest {

    @Mock
    private Logger logger;

    @Mock
    private ExecutorService executorService;

    @Mock
    private HikariDataSource dataSource;

    @Mock
    private WatchService watchService;

    private SiusDataToPostgresAdapter adapter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adapter = spy(new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "test_directory",
                "jdbc:postgresql://localhost/testdb",
                "user",
                "password",
                "pushbulletApiKey"
        ));
        // ensure we don't call the real pushbullet endpoint
        lenient().doNothing().when(adapter).sendPushbulletNotification(anyString(), anyString());
    }

    @Test
    void testConfigureDNSCaching() {
        // when
        adapter.configureDNSCaching();

        // then
        assertEquals("60", java.security.Security.getProperty("networkaddress.cache.ttl"));
        verify(logger).info("Configured DNS caching with TTL=60 seconds.");
    }

    @Test
    void testValidateEnvironmentVariables_AllSet() {
        // when
        adapter.validateEnvironmentVariables();

        // then
        verify(logger).info("All required environment variables are set.");
    }

    @Test
    void testValidateEnvironmentVariables_MissingDirectory() throws Exception {
        // given
        adapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                null,  // directoryToWatch is null
                "jdbc:postgresql://localhost/testdb",
                "user",
                "password",
                "pushbulletApiKey"
        );

        // when
        Exception exception = assertThrows(IllegalStateException.class, adapter::validateEnvironmentVariables);

        // then
        assertEquals("Environment variable CSV_MONITOR_PATH is not set.", exception.getMessage());
        verify(logger).error("Environment variable CSV_MONITOR_PATH is not set.");
    }

    @Test
    void testValidateEnvironmentVariables_MissingJdbcUrl() throws Exception {
        // given
        adapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "directoryToWatch",
                null,  // jdbcUrl is null
                "jdbcUser",
                "jdbcPassword",
                "pushbulletApiKey"
        );

        // when
        Exception exception = assertThrows(IllegalStateException.class, adapter::validateEnvironmentVariables);

        // then
        assertEquals("Environment variable POSTGRESQL_URL is not set.", exception.getMessage());
        verify(logger).error("Environment variable POSTGRESQL_URL is not set.");
    }

    @Test
    void testValidateEnvironmentVariables_MissingJdbcUser() throws Exception {
        // given
        adapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "directoryToWatch",
                "jdbc:postgresql://localhost/testdb",
                null,  // jdbcUser is null
                "jdbcPassword",
                "pushbulletApiKey"
        );

        // when
        Exception exception = assertThrows(IllegalStateException.class, adapter::validateEnvironmentVariables);

        // then
        assertEquals("Environment variable POSTGRESQL_USER is not set.", exception.getMessage());
        verify(logger).error("Environment variable POSTGRESQL_USER is not set.");
    }

    @Test
    void testValidateEnvironmentVariables_MissingJdbcPassword() throws Exception {
        // given
        adapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "directoryToWatch",
                "jdbc:postgresql://localhost/testdb",
                "jdbcUser",
                null,  // jdbcPassword is null
                "pushbulletApiKey"
        );

        // when
        Exception exception = assertThrows(IllegalStateException.class, adapter::validateEnvironmentVariables);

        // then
        assertEquals("Environment variable POSTGRESQL_PASSWORD is not set.", exception.getMessage());
        verify(logger).error("Environment variable POSTGRESQL_PASSWORD is not set.");
    }

    @Test
    void testInitializeDatabaseSchema() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.isValid(anyInt())).thenReturn(true);

        // when
        adapter.initializeDatabaseSchema();

        // then
        verify(stmt, times(2)).executeUpdate(anyString());
        verify(logger).info("'siusdata_shots' table created or exists already.");
        verify(logger).info("'file_progress' table created or exists already.");
    }

    @Test
    void testInitializeDatabaseSchema_Exception() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenThrow(new SQLException("Test exception"));

        // when
        Exception exception = assertThrows(RuntimeException.class, () -> adapter.initializeDatabaseSchema());

        // then
        verify(logger).error("Error initializing database schema: Test exception", exception.getCause());
    }

    @Test
    void testSendPushbulletNotification_ApiKeyNotSet() {
        // given
        adapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "test_directory",
                "jdbc:postgresql://localhost/testdb",
                "user",
                "password",
                null  // pushbulletApiKey is null
        );

        // when
        adapter.sendPushbulletNotification("title", "message");

        // then
        verify(logger).debug("Pushbullet API key not set. Skipping notification.");
    }

    @Test
    void testSendGotifyNotification_UrlNotSet() {
        // given
        SiusDataToPostgresAdapter gotifyAdapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "test_directory",
                "jdbc:postgresql://localhost/testdb",
                "user",
                "password",
                "pushbulletApiKey"
        );

        // when
        gotifyAdapter.sendGotifyNotification("title", "message");

        // then
        verify(logger).debug("Gotify URL not set. Skipping notification.");
    }

    @Test
    void testSendGotifyNotification_TokenNotSet() {
        // given
        SiusDataToPostgresAdapter gotifyAdapter = new SiusDataToPostgresAdapter(
                logger,
                executorService,
                dataSource,
                watchService,
                "test_directory",
                "jdbc:postgresql://localhost/testdb",
                "user",
                "password",
                null,
                "https://gotify.example.com",
                null,
                5
        );

        // when
        gotifyAdapter.sendGotifyNotification("title", "message");

        // then
        verify(logger).debug("Gotify token not set. Skipping notification.");
    }

    @Test
    void testSendNotifications() {
        // given
        doNothing().when(adapter).sendGotifyNotification(anyString(), anyString());

        // when
        adapter.sendNotifications("title", "message");

        // then
        verify(adapter).sendPushbulletNotification("title", "message");
        verify(adapter).sendGotifyNotification("title", "message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678.csv", "87654321_data.csv", "12345678.CSV"})
    void testIsValidCsvFile_Valid(String fileName) {
        // when
        boolean result = adapter.isValidCsvFile(fileName);

        // then
        assertTrue(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file.csv", "1234.csv", "file.txt", "invalid_file"})
    void testIsValidCsvFile_Invalid(String fileName) {
        // when
        boolean result = adapter.isValidCsvFile(fileName);

        // then
        assertFalse(result);
    }

    @Test
    void testSubmitFileForProcessing_NewFile() {
        // given
        Path filePath = Path.of("test_directory/testfile.csv");

        // when
        adapter.submitFileForProcessing(filePath, false);

        // then
        verify(logger).info("Enqueued file {} for processing.", "testfile.csv");
        verify(executorService).submit(any(Runnable.class));
    }

    @Test
    void testSubmitFileForProcessing_AlreadyQueued() {
        // given
        Path filePath = Path.of("test_directory/testfile.csv");

        // Simulate the file is already queued by submitting it once
        adapter.submitFileForProcessing(filePath, false);

        // Reset interactions to focus on the second submission
        reset(logger, executorService);

        // when
        adapter.submitFileForProcessing(filePath, false);

        // then
        verify(logger).info("File {} is already queued or being processed. Skipping submission.", "testfile.csv");
        verifyNoInteractions(executorService);
    }

    @Test
    void testSetIntegerField_Valid() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setIntegerField(stmt, 1, "123");

        // then
        verify(stmt).setInt(1, 123);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", " "})
    void testSetIntegerField_Invalid(String valueStr) throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setIntegerField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.INTEGER);
    }

    @Test
    void testSetLongField_Valid() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setLongField(stmt, 1, "123456789");

        // then
        verify(stmt).setLong(1, 123456789L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", " "})
    void testSetLongField_Invalid(String valueStr) throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setLongField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.BIGINT);
    }

    @Test
    void testSetBooleanField_ValidTrue() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setBooleanField(stmt, 1, "1");

        // then
        verify(stmt).setBoolean(1, true);
    }

    @Test
    void testSetBooleanField_ValidFalse() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setBooleanField(stmt, 1, "0");

        // then
        verify(stmt).setBoolean(1, false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void testSetBooleanField_NullOrEmpty(String valueStr) throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setBooleanField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.BOOLEAN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "false", "yes"})
    void testSetBooleanField_InvalidValue(String valueStr) throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setBooleanField(stmt, 1, valueStr);

        // then
        verify(stmt).setBoolean(1, false);
    }

    @Test
    void testSetTextField_Valid() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setTextField(stmt, 1, "text");

        // then
        verify(stmt).setString(1, "text");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void testSetTextField_Invalid(String valueStr) throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        adapter.setTextField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.VARCHAR);
    }

    @Test
    void testCalculateTimestamp_Valid() {
        // given
        String dateValue = "100000"; // intervals
        String fileName = "20210000.csv";

        // when
        Timestamp timestamp = adapter.calculateTimestamp(dateValue, fileName);

        // then
        LocalDateTime expectedDateTime = LocalDateTime.of(2021, 1, 1, 0, 16, 40);
        assertEquals(Timestamp.valueOf(expectedDateTime), timestamp);
    }

    @Test
    void testCalculateTimestamp_InvalidDateValue() {
        // given
        String dateValue = "abc";
        String fileName = "20210000.csv";

        // when
        Timestamp timestamp = adapter.calculateTimestamp(dateValue, fileName);

        // then
        assertNull(timestamp);
    }

    @Test
    void testCalculateTimestamp_InvalidFileName() {
        // given
        String dateValue = "100000";
        String fileName = "abc"; // filename less than 4 characters

        // when
        Exception exception = assertThrows(IllegalStateException.class, () -> adapter.calculateTimestamp(dateValue, fileName));

        // then
        assertNotNull(exception);
    }

    @Test
    void testGetLastProcessedLine_Found() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt("last_processed_line")).thenReturn(10);

        // when
        int lastProcessedLine = adapter.getLastProcessedLine(conn, "file.csv");

        // then
        assertEquals(10, lastProcessedLine);
    }

    @Test
    void testGetLastProcessedLine_NotFound() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        // when
        int lastProcessedLine = adapter.getLastProcessedLine(conn, "file.csv");

        // then
        assertEquals(0, lastProcessedLine);
    }

    @Test
    void testGetLastProcessedLine_Exception() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenThrow(new SQLException("Test exception"));

        // when
        Exception exception = assertThrows(SQLException.class, () -> adapter.getLastProcessedLine(conn, "file.csv"));

        // then
        assertEquals("Test exception", exception.getMessage());
    }

    @Test
    void testUpdateLastProcessedLine() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);

        when(conn.prepareStatement(anyString())).thenReturn(stmt);

        // when
        adapter.updateLastProcessedLine(conn, "file.csv", 20);

        // then
        verify(stmt).setString(1, "file.csv");
        verify(stmt).setInt(2, 20);
        verify(stmt).executeUpdate();
    }

    @Test
    void testShutdown() throws InterruptedException {
        // given
        ExecutorService execService = Executors.newSingleThreadExecutor();
        HikariDataSource mockDataSource = mock(HikariDataSource.class);
        adapter = new SiusDataToPostgresAdapter(
                logger,
                execService,
                mockDataSource,
                watchService,
                "test_directory",
                "jdbc:postgresql://localhost/testdb",
                "user",
                "password",
                "pushbulletApiKey"
        );

        // when
        adapter.shutdown();

        // then
        assertTrue(execService.isShutdown() || execService.isTerminated());
        verify(logger).info("Shutting down application...");
        verify(logger).info("Executor service shutdown complete.");
        verify(logger).info("HikariCP DataSource closed.");
        verify(logger).info("Application shutdown complete.");
    }

    @Test
    void testShutdown_WatchServiceException() throws IOException {
        // given
        IOException exception = new IOException("Test exception");
        doThrow(exception).when(watchService).close();

        // when
        adapter.shutdown();

        // then
        verify(logger).error("Error closing watch service: Test exception", exception);
    }

    @Test
    void testLogError_WithThrowable() {
        // given
        Throwable throwable = new RuntimeException("Test exception");

        // when
        adapter.logError("Error message", throwable);

        // then
        verify(logger).error("Error message", throwable);
        verify(adapter).sendPushbulletNotification(anyString(), anyString());
        verify(adapter).sendGotifyNotification(anyString(), anyString());
    }

    @Test
    void testLogError_WithoutThrowable() {
        // when
        adapter.logError("Error message");

        // then
        verify(logger).error("Error message");
        verify(adapter).sendPushbulletNotification(anyString(), anyString());
        verify(adapter).sendGotifyNotification(anyString(), anyString());
    }

    @Test
    void testWatchDirectoryStopsUpdatesFlag() throws InterruptedException {
        // given
        doThrow(new InterruptedException("Test interruption")).when(watchService).take();

        // when
        adapter.watchDirectory();

        // then
        assertFalse(adapter.isWatching());
        verify(logger).info("Starting directory watch loop.");
        verify(logger).warn("Watch service interrupted.");
        verify(logger).info("Directory watch loop stopped.");

        // clear interrupted state for subsequent tests
        Thread.interrupted();
    }

    @Test
    void testProcessExistingFiles() throws IOException {
        // given
        Path tempDir = Files.createTempDirectory("testDir");
        Path csvFile = tempDir.resolve("20210000.csv");
        Files.createFile(csvFile);

        doNothing().when(adapter).submitFileForProcessing(any(Path.class), eq(true));

        // when
        adapter.processExistingFiles(tempDir);

        // then
        verify(logger).info("Scanning directory for existing CSV files to process...");
        verify(logger).info("Found existing file to process: {}", "20210000.csv");
        verify(adapter).submitFileForProcessing(csvFile, true);

        // Clean up
        Files.delete(csvFile);
        Files.delete(tempDir);
    }

    @Test
    void testProcessFileWithRetries_FailureThenSuccess() throws Exception {
        // given
        Path filePath = Path.of("testfile.csv");

        IOException exception = new IOException("Test exception");
        doThrow(exception)
                .doNothing()
                .when(adapter).processFile(filePath);

        // when
        adapter.processFileWithRetries(filePath);

        // then
        verify(adapter, times(2)).processFile(filePath);
        verify(logger).error("Failed to process file testfile.csv: Test exception", exception);
        verify(logger).info("Waiting for {} milliseconds before retrying...", 5000);
        verify(logger).info("Successfully processed file: {}", "testfile.csv");
    }

    @Test
    void testParseNewCsvRecords_Exception() throws IOException {
        // given
        Path tempFile = Files.createTempFile("test", ".csv");
        int lastProcessedLine = 0;

        // Delete the file to cause an IOException
        Files.delete(tempFile);

        // when
        Exception exception = assertThrows(IOException.class, () -> {
            adapter.parseNewCsvRecords(tempFile, lastProcessedLine);
        });

        // then
        assertNotNull(exception);
    }

    @Test
    void testInsertRecordIntoDatabase() throws SQLException, IOException {
        // given
        Connection mockConnection = mock(Connection.class);
        PreparedStatement mockStatement = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);

        String[] fields = {
                "1", "Score", "2", "3", "4", "5", "6", "1",
                "8", "9", "0", "11", "12", "13", "14", "15",
                "16", "17", "18", "19", "20", "21", "100000",
                "23", "24", "25", "26", "27", "28"
        };
        String csvLine = String.join(";", fields);

        List<CsvRecord> csvRecords = new ArrayList<>();
        try (CsvReader<CsvRecord> csv = CsvReader.builder().fieldSeparator(';').ofCsvRecord(new StringReader(csvLine))) {
            for (final CsvRecord csvRecord : csv) {
                csvRecords.add(csvRecord);
            }
        }
        CsvRecord csvRecord = csvRecords.getFirst();

        // when
        adapter.insertRecordIntoDatabase(mockConnection, csvRecord, "20210000.csv");

        // then
        verify(mockStatement, atLeastOnce()).setInt(anyInt(), anyInt());
        verify(mockStatement, atLeastOnce()).setString(anyInt(), anyString());
        verify(mockStatement, atLeastOnce()).setBoolean(anyInt(), anyBoolean());
        verify(mockStatement, atLeastOnce()).setLong(anyInt(), anyLong());
        verify(mockStatement, atLeastOnce()).setTimestamp(anyInt(), any(Timestamp.class));
        verify(mockStatement).executeUpdate();
        verify(mockStatement).close();
    }

    @Test
    void testProcessFile_Exception() throws Exception {
        // given
        Path filePath = mock(Path.class);
        when(filePath.getFileName()).thenReturn(Path.of("20210000.csv"));
        when(dataSource.getConnection()).thenThrow(new SQLException("Test exception"));

        // when
        Exception exception = assertThrows(SQLException.class, () -> adapter.processFile(filePath));

        // then
        assertEquals("Test exception", exception.getMessage());
    }
}
