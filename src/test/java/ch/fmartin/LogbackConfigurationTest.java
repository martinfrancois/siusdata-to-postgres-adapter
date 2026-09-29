package ch.fmartin;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped logback.xml is on the test classpath, so the logging seen here is the logging users
 * get. A logback release that no longer understands the configuration reports it as a status error
 * and keeps logging to the console only; this test turns that into a failure.
 */
class LogbackConfigurationTest {

    @Test
    void logbackXmlLoadsWithoutErrorsAndWritesTheLogFile() throws IOException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        List<Status> errors = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() == Status.ERROR)
                .toList();
        assertTrue(errors.isEmpty(), "logback reported configuration errors: " + errors);

        String marker = "logback configuration test " + UUID.randomUUID();
        LoggerFactory.getLogger(LogbackConfigurationTest.class).info(marker);

        Path logFile = Path.of("logs", "siusdata-adapter.log");
        assertTrue(Files.isRegularFile(logFile), "the FILE appender should write " + logFile);
        String logged = Files.readString(logFile);
        assertTrue(logged.contains("INFO  ch.fmartin.LogbackConfigurationTest - " + marker),
                "the log file should contain the line in the configured pattern");
    }
}
