package ch.fmartin;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SiusDataToPostgresAdapterIntegrationTest {

    private PostgreSQLContainer<?> postgreSQLContainer;
    private Path tempDir;
    private SiusDataToPostgresAdapter adapter;
    private Thread adapterThread;
    private Network network;
    private ToxiproxyContainer toxiproxy;
    private Proxy proxy;
    private ToxiproxyClient toxiproxyClient;

    @BeforeEach
    public void setUp() throws Exception {
        network = Network.newNetwork();

        postgreSQLContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.3"))
                .withDatabaseName("test")
                .withUsername("test")
                .withPassword("test")
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withExposedPorts(5432)
                .waitingFor(new TestContainerPostgresWaitStrategy());

        postgreSQLContainer.start();

        // Initialize Toxiproxy container and proxy for PostgreSQL
        toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.10.0")
                .withNetwork(network);
        toxiproxy.start();

        toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        proxy = toxiproxyClient.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");

        // Create a temporary directory to act as the CSV directory to watch
        tempDir = Files.createTempDirectory("siusdata_test");
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Shutdown the adapter
        if (adapter != null) {
            adapter.shutdown();
        }
        if (adapterThread != null) {
            adapterThread.interrupt();
            adapterThread.join();
        }

        // Delete temporary directory and files
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);

        postgreSQLContainer.stop();
        toxiproxy.stop();
    }

    @Test
    public void testProcessingExistingCsvFile() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    String csvData = """
                            244062;10;0;3;10.2;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152;0;0;0;0;0;5;3;2373669100;0;0;0;64;0
                            244062;10;0;3;10.0;752;17:32:13.05;0;-4.07372;-6.32181;1;655.35;0;0;152;0;0;0;0;0;6;3;2373673305;0;0;0;64;0
                            """;

                    String csvFileName = "20231014_test.csv";
                    Path csvFilePath = tempDir.resolve(csvFileName);

                    Files.write(csvFilePath, csvData.getBytes());

                    // when
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isInitialized());

                    // then
                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                        rs.next();
                        int count = rs.getInt(1);
                        assertEquals(2, count, "There should be 2 records in siusdata_shots table");
                    }
                });

    }

    @Test
    public void testProcessingNewCsvFile() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // Wait for the adapter to process the initial files
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isWatching() && adapter.isInitialized());

                    // Write a new CSV file
                    String csvData = """
                            244062;10;0;3;10.1;691;17:32:54.90;0;0.83045;-6.86711;1;655.35;0;0;152;0;0;0;0;0;7;3;2373677490;0;0;0;64;0
                            244062;10;0;3;10.0;798;17:33:41.15;0;-7.69908;2.11161;1;655.35;0;0;152;0;0;0;0;0;8;3;2373682115;0;0;0;64;0
                            """;
                    String csvFileName = "20231015_test.csv";
                    Path csvFilePath = tempDir.resolve(csvFileName);

                    Files.write(csvFilePath, csvData.getBytes());

                    // then
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        postgreSQLContainer.getJdbcUrl(),
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(2, count, "There should be 2 records in siusdata_shots table");
                                }
                            });
                });
    }

    @Test
    public void testProcessingUpdatedCsvFile() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    String csvData = "244062;10;0;3;10.2;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152;0;0;0;0;0;5;3;2373669100;0;0;0;64;0\n";
                    String csvFileName = "20231016_test.csv";
                    Path csvFilePath = tempDir.resolve(csvFileName);

                    Files.write(csvFilePath, csvData.getBytes());

                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // Wait for the adapter to process the initial files
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isWatching() && adapter.isInitialized());

                    // Append new data to the CSV file
                    String newCsvData = "244062;10;0;3;10.0;752;17:32:13.05;0;-4.07372;-6.32181;1;655.35;0;0;152;0;0;0;0;0;6;3;2373673305;0;0;0;64;0\n";
                    Files.write(csvFilePath, newCsvData.getBytes(), StandardOpenOption.APPEND);

                    // then
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        postgreSQLContainer.getJdbcUrl(),
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(2, count, "There should be 2 records in siusdata_shots table after appending data");
                                }
                            });
                });
    }

    @Test
    public void testProcessingMultipleCsvFiles() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    String csvData1 = "244062;10;0;3;10.2;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152;0;0;0;0;0;1;3;2373669100;0;0;0;64;0\n";
                    String csvData2 = "244062;10;0;3;10.1;691;17:32:54.90;0;0.83045;-6.86711;1;655.35;0;0;152;0;0;0;0;0;2;3;2373677490;0;0;0;64;0\n";
                    String csvData3 = "244062;10;0;3;10.0;798;17:33:41.15;0;-7.69908;2.11161;1;655.35;0;0;152;0;0;0;0;0;3;3;2373682115;0;0;0;64;0\n";

                    Path csvFilePath1 = tempDir.resolve("20231017_test1.csv");
                    Path csvFilePath2 = tempDir.resolve("20231017_test2.csv");
                    Path csvFilePath3 = tempDir.resolve("20231017_test3.csv");

                    Files.write(csvFilePath1, csvData1.getBytes());
                    Files.write(csvFilePath2, csvData2.getBytes());
                    Files.write(csvFilePath3, csvData3.getBytes());

                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isInitialized());

                    // then
                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                        rs.next();
                        int count = rs.getInt(1);
                        assertEquals(3, count, "There should be 3 records in siusdata_shots table");
                    }
                });

    }

    @Test
    public void testProcessingLargeCsvFile() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    StringBuilder csvDataBuilder = new StringBuilder();
                    for (int i = 1; i <= 6000; i++) {
                        csvDataBuilder.append("244062;10;0;3;10.")
                                .append(i % 10)
                                .append(";")
                                .append(500 + i)
                                .append(";17:31:")
                                .append("%02d".formatted(i))
                                .append(".00;0;")
                                .append(2.0 + i)
                                .append(";")
                                .append(4.0 + i)
                                .append(";1;655.35;0;0;152;0;0;0;0;0;")
                                .append(i)
                                .append(";3;2373669")
                                .append(100 + i)
                                .append(";0;0;0;64;0\n");
                    }

                    String csvData = csvDataBuilder.toString();
                    String csvFileName = "20231018_large_test.csv";
                    Path csvFilePath = tempDir.resolve(csvFileName);

                    Files.write(csvFilePath, csvData.getBytes());

                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // then
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isInitialized());

                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                        rs.next();
                        int count = rs.getInt(1);
                        assertEquals(6000, count, "There should be 6000 records in siusdata_shots table");
                    }
                });

    }

    @Test
    public void testProcessingNewFileAfterStart() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    StringBuilder csvDataBuilder = new StringBuilder();
                    for (int i = 1; i <= 30000; i++) {
                        csvDataBuilder.append("244062;10;0;3;10.")
                                .append(i % 10)
                                .append(";")
                                .append(500 + i)
                                .append(";17:31:")
                                .append("%02d".formatted(i))
                                .append(".00;0;")
                                .append(2.0 + i)
                                .append(";")
                                .append(4.0 + i)
                                .append(";1;655.35;0;0;152;0;0;0;0;0;")
                                .append(i)
                                .append(";3;2373669")
                                .append(100 + i)
                                .append(";0;0;0;64;0\n");
                    }

                    String csvData = csvDataBuilder.toString();
                    Path csvFilePath1 = tempDir.resolve("20230222.csv");
                    Path csvFilePath2 = tempDir.resolve("20240502_1.csv");
                    Files.write(csvFilePath1, csvData.getBytes());

                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    String extraData = "244062;10;0;3;10.0;800;17:40:00.00;0;2.0;4.0;1;655.35;0;0;152;0;0;0;0;0;3001;3;2373690001;0;0;0;64;0\n";
                    // Wait until the existing files started processing and add more lines
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(100, TimeUnit.MILLISECONDS).until(() -> adapter != null && adapter.isWatching() && adapter.isProcessing() && !adapter.isInitialized());
                    Files.write(csvFilePath2, extraData.getBytes());

                    // then
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        postgreSQLContainer.getJdbcUrl(),
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(30001, count, "There should be 30001 records in siusdata_shots table");
                                }
                            });
                });
    }

    @Test
    public void testProcessingFileModificationAfterStart() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    StringBuilder csvDataBuilder = new StringBuilder();
                    for (int i = 1; i <= 15000; i++) {
                        csvDataBuilder.append("244062;10;0;3;10.")
                                .append(i % 10)
                                .append(";")
                                .append(500 + i)
                                .append(";17:31:")
                                .append("%02d".formatted(i))
                                .append(".00;0;")
                                .append(2.0 + i)
                                .append(";")
                                .append(4.0 + i)
                                .append(";1;655.35;0;0;152;0;0;0;0;0;")
                                .append(i)
                                .append(";3;2373669")
                                .append(100 + i)
                                .append(";0;0;0;64;0\n");
                    }

                    String csvData = csvDataBuilder.toString();
                    Path csvFilePath1 = tempDir.resolve("20230222.csv");
                    Path csvFilePath2 = tempDir.resolve("20240502_1.csv");
                    Files.write(csvFilePath1, csvData.getBytes());
                    Files.write(csvFilePath2, csvData.getBytes());

                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    String extraData = "244062;10;0;3;10.0;800;17:40:00.00;0;2.0;4.0;1;655.35;0;0;152;0;0;0;0;0;3001;3;2373690001;0;0;0;64;0\n";
                    // Wait until the existing files started processing and add more lines
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(100, TimeUnit.MILLISECONDS).until(() -> adapter != null && adapter.isWatching() && adapter.isProcessing() && !adapter.isInitialized());
                    for (int i = 0; i < 100; i++) {
                        Files.write(csvFilePath1, extraData.getBytes(), StandardOpenOption.APPEND);
                        Files.write(csvFilePath2, extraData.getBytes(), StandardOpenOption.APPEND);
                    }

                    // then
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        postgreSQLContainer.getJdbcUrl(),
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(30200, count, "There should be 30200 records in siusdata_shots table");
                                }
                            });
                });
    }

    @Test
    public void testProcessingFilesCreatedAfterAdapterStart() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // Wait for the adapter to be watching and done with the existing files
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isWatching() && adapter.isInitialized());

                    // Create two files with 5000 lines each
                    StringBuilder csvDataBuilder = new StringBuilder();
                    for (int i = 1; i <= 15000; i++) {
                        csvDataBuilder.append("244062;10;0;3;10.")
                                .append(i % 10)
                                .append(";")
                                .append(500 + i)
                                .append(";17:31:")
                                .append("%02d".formatted(i))
                                .append(".00;0;")
                                .append(2.0 + i)
                                .append(";")
                                .append(4.0 + i)
                                .append(";1;655.35;0;0;152;0;0;0;0;0;")
                                .append(i)
                                .append(";3;2373669")
                                .append(100 + i)
                                .append(";0;0;0;64;0\n");
                    }

                    String csvData = csvDataBuilder.toString();
                    Path csvFilePath1 = tempDir.resolve("20230222.csv");
                    Path csvFilePath2 = tempDir.resolve("20240502_1.csv");
                    Files.write(csvFilePath1, csvData.getBytes());
                    Files.write(csvFilePath2, csvData.getBytes());

                    // Wait until it picked up the changes and started processing
                    String extraData = "244062;10;0;3;10.0;800;17:40:00.00;0;2.0;4.0;1;655.35;0;0;152;0;0;0;0;0;3001;3;2373690001;0;0;0;64;0\n";
                    Awaitility.await().atMost(1, TimeUnit.MINUTES)
                            .pollInterval(100, TimeUnit.MILLISECONDS).until(() -> adapter != null && adapter.isProcessing());
                    for (int i = 0; i < 100; i++) {
                        Files.write(csvFilePath1, extraData.getBytes(), StandardOpenOption.APPEND);
                        Files.write(csvFilePath2, extraData.getBytes(), StandardOpenOption.APPEND);
                    }

                    // then
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        postgreSQLContainer.getJdbcUrl(),
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(30200, count, "There should be 30200 records in siusdata_shots table");
                                }
                            });
                });
    }

    @Test
    public void testDatabaseConnectionInterruptedDuringProcessing() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", "jdbc:postgresql://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666) + "/test")
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    StringBuilder csvDataBuilder = new StringBuilder();
                    for (int i = 1; i <= 30000; i++) {
                        csvDataBuilder.append("244062;10;0;3;10.")
                                .append(i % 10)
                                .append(";")
                                .append(500 + i)
                                .append(";17:31:")
                                .append("%02d".formatted(i))
                                .append(".00;0;")
                                .append(2.0 + i)
                                .append(";")
                                .append(4.0 + i)
                                .append(";1;655.35;0;0;152;0;0;0;0;0;")
                                .append(i)
                                .append(";3;2373669")
                                .append(100 + i)
                                .append(";0;0;0;64;0\n");
                    }

                    String csvData = csvDataBuilder.toString();
                    String csvFileName = "20231018_large_test.csv";
                    Path csvFilePath = tempDir.resolve(csvFileName);

                    Files.write(csvFilePath, csvData.getBytes());

                    // Start the adapter in a separate thread
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // Wait until the adapter starts processing
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isProcessing());

                    // Simulate the DB being unreachable on the internet by cutting off the connection using Toxiproxy
                    proxy.toxics().bandwidth("CUT_CONNECTION_DOWNSTREAM", ToxicDirection.DOWNSTREAM, 0);
                    proxy.toxics().bandwidth("CUT_CONNECTION_UPSTREAM", ToxicDirection.UPSTREAM, 0);

                    // Wait for a while to ensure the adapter detects the DB disconnection
                    Thread.sleep(10000);

                    // Restore the DB connection using Toxiproxy
                    proxy.toxics().get("CUT_CONNECTION_DOWNSTREAM").remove();
                    proxy.toxics().get("CUT_CONNECTION_UPSTREAM").remove();

                    // Wait for the adapter to reconnect and resume processing
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        "jdbc:postgresql://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666) + "/test",
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(30000, count, "There should be 30000 records in siusdata_shots table");
                                }
                            });
                });
    }

    @Test
    public void testDatabaseConnectionRefusedDuringProcessing() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", "jdbc:postgresql://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666) + "/test")
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .and("PUSHBULLET_API_KEY", "")
                .execute(() -> {
                    // given
                    StringBuilder csvDataBuilder = new StringBuilder();
                    for (int i = 1; i <= 30000; i++) {
                        csvDataBuilder.append("244062;10;0;3;10.")
                                .append(i % 10)
                                .append(";")
                                .append(500 + i)
                                .append(";17:31:")
                                .append("%02d".formatted(i))
                                .append(".00;0;")
                                .append(2.0 + i)
                                .append(";")
                                .append(4.0 + i)
                                .append(";1;655.35;0;0;152;0;0;0;0;0;")
                                .append(i)
                                .append(";3;2373669")
                                .append(100 + i)
                                .append(";0;0;0;64;0\n");
                    }

                    String csvData = csvDataBuilder.toString();
                    String csvFileName = "20231018_large_test.csv";
                    Path csvFilePath = tempDir.resolve(csvFileName);

                    Files.write(csvFilePath, csvData.getBytes());

                    // Start the adapter in a separate thread
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // Wait until the adapter starts processing
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isProcessing());

                    // Simulate the DB returning "connection refused" using Toxiproxy
                    proxy.disable();

                    // Wait for a while to ensure the adapter detects the DB disconnection
                    Thread.sleep(10000);

                    // Restore the DB connection using Toxiproxy
                    proxy.enable();

                    // Wait for the adapter to reconnect and resume processing
                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(2, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        "jdbc:postgresql://" + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8666) + "/test",
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(30000, count, "There should be 30000 records in siusdata_shots table");
                                }
                            });
                });
    }

    private void startAdapterAndProcess(Path csvFilePath) throws Exception {
        adapterThread = new Thread(() -> {
            try {
                adapter = new SiusDataToPostgresAdapter();
                adapter.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        adapterThread.setDaemon(true);
        adapterThread.start();

        Awaitility.await()
                .atMost(1, TimeUnit.MINUTES)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> adapter != null && adapter.isWatching());

        Awaitility.await()
                .atMost(2, TimeUnit.MINUTES)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        try (PreparedStatement ps = conn.prepareStatement("SELECT last_processed_line FROM file_progress WHERE file_name = ?")) {
                            ps.setString(1, csvFilePath.getFileName().toString());
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    int last = rs.getInt(1);
                                    return last > 0; // processed at least one line
                                }
                            }
                        }
                        return false;
                    }
                });
    }

    @Test
    public void testEmptyIntegerFields() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .execute(() -> {
                    // Write a CSV file with empty integer fields
                    String csvData = "244062;10;;3;;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152;0;0;0;0;0;5;3;2373669100;0;0;0;64;0\n";
                    Path csvFilePath = tempDir.resolve("20250101_test_empty_integers.csv");
                    Files.write(csvFilePath, csvData.getBytes());

                    startAdapterAndProcess(csvFilePath);

                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                        rs.next();
                        int count = rs.getInt(1);
                        assertEquals(1, count, "There should be 1 record with empty integer fields handled.");
                    }
                });
    }

    @Test
    public void testNonIntegerInIntegerField() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .execute(() -> {
                    // Write a CSV file with non-integer in integer fields
                    String csvData = "244062;Shooter 678267;0;3;10.2;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152;0;0;0;0;0;5;3;2373669100;0;0;0;64;0\n";
                    Path csvFilePath = tempDir.resolve("20250101_test_non_integer.csv");
                    Files.write(csvFilePath, csvData.getBytes());

                    startAdapterAndProcess(csvFilePath);

                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                        rs.next();
                        int count = rs.getInt(1);
                        assertEquals(1, count, "There should be 1 record with non-integer values handled.");
                    }
                });
    }

    @Test
    public void testFewerColumns() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .execute(() -> {
                    // Write a CSV file with fewer than 27 columns
                    String csvData = "244062;10;0;3;10.2;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152\n";
                    Path csvFilePath = tempDir.resolve("20250101_test_fewer_columns.csv");
                    Files.write(csvFilePath, csvData.getBytes());

                    // Start the adapter (do not wait for processed rows, as this file is invalid)
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    // Ensure the adapter is watching; avoid waiting for inserts that will never happen
                    Awaitility.await()
                            .atMost(30, TimeUnit.SECONDS)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isWatching());

                    try (Connection conn = DriverManager.getConnection(
                            postgreSQLContainer.getJdbcUrl(),
                            postgreSQLContainer.getUsername(),
                            postgreSQLContainer.getPassword())) {
                        Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                        rs.next();
                        int count = rs.getInt(1);
                        assertEquals(0, count, "No records should be inserted due to missing columns.");
                    }
                });
    }

    @Test
    public void testPartialLineCompletion() throws Exception {
        SystemLambda.withEnvironmentVariable("CSV_MONITOR_PATH", tempDir.toAbsolutePath().toString())
                .and("POSTGRESQL_URL", postgreSQLContainer.getJdbcUrl())
                .and("POSTGRESQL_USER", postgreSQLContainer.getUsername())
                .and("POSTGRESQL_PASSWORD", postgreSQLContainer.getPassword())
                .execute(() -> {
                    // Write a partial line to the CSV file
                    String partialLine = "244062;10;0;3;10.2;564;17:31:31.00;0;2.78276;4.90984;1;655.35;0;0;152;0";
                    String remainingLine = ";0;0;0;0;0;5;3;2373669100;0;0;0;64;0\n";
                    Path csvFilePath = tempDir.resolve("20250101_test_partial_line.csv");
                    Files.write(csvFilePath, partialLine.getBytes());

                    // Start the adapter in a separate thread
                    adapterThread = new Thread(() -> {
                        try {
                            adapter = new SiusDataToPostgresAdapter();
                            adapter.start();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    adapterThread.setDaemon(true);
                    adapterThread.start();

                    Awaitility.await()
                            .atMost(1, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .until(() -> adapter != null && adapter.isWatching() && adapter.isProcessing());

                    Thread.sleep(2000);

                    Files.write(csvFilePath, remainingLine.getBytes(), StandardOpenOption.APPEND);

                    Awaitility.await()
                            .atMost(2, TimeUnit.MINUTES)
                            .pollInterval(1, TimeUnit.SECONDS)
                            .untilAsserted(() -> {
                                try (Connection conn = DriverManager.getConnection(
                                        postgreSQLContainer.getJdbcUrl(),
                                        postgreSQLContainer.getUsername(),
                                        postgreSQLContainer.getPassword())) {
                                    Statement stmt = conn.createStatement();
                                    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM siusdata_shots");
                                    rs.next();
                                    int count = rs.getInt(1);
                                    assertEquals(1, count, "There should be 1 record after the line is completed.");
                                }
                            });
                });
    }

}
