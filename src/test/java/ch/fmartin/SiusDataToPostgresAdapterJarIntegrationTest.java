package ch.fmartin;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the shadow jar the way a user does, `java -jar` with the documented environment variables,
 * and checks that a result file ends up in the database. This is the only test that runs the
 * packaged artifact, so it covers what the in-process tests cannot: the merged JDBC driver service
 * file, the packaged logback.xml and the main method.
 */
class SiusDataToPostgresAdapterJarIntegrationTest {

    private static final String FIXTURE = "20250928.csv";

    @Test
    void shadowJarImportsAResultFileEndToEnd() throws Exception {
        String jarPath = System.getProperty("siusdata.shadowJar");
        assertNotNull(jarPath, "the Gradle test task passes the shadow jar location as siusdata.shadowJar");
        Path jar = Path.of(jarPath);
        assertTrue(Files.isRegularFile(jar), "shadow jar not found at " + jar);

        Path workDir = Files.createTempDirectory("siusdata_jar");
        Path csvDir = Files.createDirectory(workDir.resolve("incoming"));
        Path output = workDir.resolve("adapter.out");
        int expectedShots = copyFixture(csvDir.resolve(FIXTURE));

        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestImages.POSTGRES)
                .withDatabaseName("test")
                .withUsername("test")
                .withPassword("test")
                .waitingFor(new TestContainerPostgresWaitStrategy())) {
            postgres.start();

            ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable(), "-jar", jar.toString())
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            processBuilder.environment().put("CSV_MONITOR_PATH", csvDir.toString());
            processBuilder.environment().put("POSTGRESQL_URL", postgres.getJdbcUrl());
            processBuilder.environment().put("POSTGRESQL_USER", postgres.getUsername());
            processBuilder.environment().put("POSTGRESQL_PASSWORD", postgres.getPassword());
            processBuilder.environment().remove("PUSHBULLET_API_KEY");
            processBuilder.environment().remove("GOTIFY_URL");
            processBuilder.environment().remove("GOTIFY_TOKEN");

            Process adapter = processBuilder.start();
            try {
                Awaitility.await()
                        .atMost(2, TimeUnit.MINUTES)
                        .pollInterval(1, TimeUnit.SECONDS)
                        .until(() -> !adapter.isAlive() || countShots(postgres) == expectedShots);

                assertTrue(adapter.isAlive(), "the adapter should keep watching the folder after the import");
                assertEquals(expectedShots, countShots(postgres), "every line of " + FIXTURE + " should be one row");
                assertEquals(expectedShots, lastProcessedLine(postgres), "file_progress should record the whole file");
                assertTrue(Files.isRegularFile(workDir.resolve("logs").resolve("siusdata-adapter.log")),
                        "the packaged logback.xml should write the log file next to the jar's working directory");
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError("adapter output:\n" + Files.readString(output), e);
            } finally {
                adapter.destroy();
                if (!adapter.waitFor(10, TimeUnit.SECONDS)) {
                    adapter.destroyForcibly();
                }
            }
        }
    }

    private static int copyFixture(Path target) throws IOException {
        try (InputStream fixture = Objects.requireNonNull(
                SiusDataToPostgresAdapterJarIntegrationTest.class.getResourceAsStream("/reproduction/" + FIXTURE),
                "fixture missing from the test resources")) {
            String content = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(target, content);
            return (int) content.lines().filter(line -> !line.isBlank()).count();
        }
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static int countShots(PostgreSQLContainer postgres) throws SQLException {
        return queryInt(postgres, "SELECT COUNT(*) FROM siusdata_shots");
    }

    private static int lastProcessedLine(PostgreSQLContainer postgres) throws SQLException {
        return queryInt(postgres, "SELECT last_processed_line FROM file_progress WHERE file_name = '" + FIXTURE + "'");
    }

    private static int queryInt(PostgreSQLContainer postgres, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            // The tables exist only once the adapter created them; before that the query throws and the poll retries.
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }
}
