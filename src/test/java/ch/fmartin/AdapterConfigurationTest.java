package ch.fmartin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdapterConfigurationTest {

    private final Logger logger = mock(Logger.class);

    private static AdapterConfiguration configuration(String directoryToWatch, String jdbcUrl, String jdbcUser, String jdbcPassword) {
        return new AdapterConfiguration(directoryToWatch, jdbcUrl, jdbcUser, jdbcPassword, "pushbulletApiKey", null, null,
                AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY);
    }

    private static UnaryOperator<String> onlyGotifyPriority(String gotifyPriority) {
        Map<String, String> environment = new HashMap<>();
        environment.put("GOTIFY_PRIORITY", gotifyPriority);
        return environment::get;
    }

    @Test
    void testValidateEnvironmentVariables_AllSet() {
        // when
        configuration("test_directory", "jdbc:postgresql://localhost/testdb", "user", "password").validate(logger);

        // then
        verify(logger).info("All required environment variables are set.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "   ")
    void testValidateEnvironmentVariables_MissingOrBlankDirectory(String directoryToWatch) {
        // given
        AdapterConfiguration configuration = configuration(directoryToWatch, "jdbc:postgresql://localhost/testdb", "user", "password");

        // when
        Exception exception = assertThrows(IllegalStateException.class, () -> configuration.validate(logger));

        // then
        assertEquals("Environment variable CSV_MONITOR_PATH is not set or blank.", exception.getMessage());
        verify(logger).error("Environment variable CSV_MONITOR_PATH is not set or blank.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "  ")
    void testValidateEnvironmentVariables_MissingOrBlankJdbcUrl(String jdbcUrl) {
        // given
        AdapterConfiguration configuration = configuration("directoryToWatch", jdbcUrl, "jdbcUser", "jdbcPassword");

        // when
        Exception exception = assertThrows(IllegalStateException.class, () -> configuration.validate(logger));

        // then
        assertEquals("Environment variable POSTGRESQL_URL is not set or blank.", exception.getMessage());
        verify(logger).error("Environment variable POSTGRESQL_URL is not set or blank.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "  ")
    void testValidateEnvironmentVariables_MissingOrBlankJdbcUser(String jdbcUser) {
        // given
        AdapterConfiguration configuration = configuration("directoryToWatch", "jdbc:postgresql://localhost/testdb", jdbcUser, "jdbcPassword");

        // when
        Exception exception = assertThrows(IllegalStateException.class, () -> configuration.validate(logger));

        // then
        assertEquals("Environment variable POSTGRESQL_USER is not set or blank.", exception.getMessage());
        verify(logger).error("Environment variable POSTGRESQL_USER is not set or blank.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "   ")
    void testValidateEnvironmentVariables_MissingOrBlankJdbcPassword(String jdbcPassword) {
        // given
        AdapterConfiguration configuration = configuration("directoryToWatch", "jdbc:postgresql://localhost/testdb", "jdbcUser", jdbcPassword);

        // when
        Exception exception = assertThrows(IllegalStateException.class, () -> configuration.validate(logger));

        // then
        assertEquals("Environment variable POSTGRESQL_PASSWORD is not set or blank.", exception.getMessage());
        verify(logger).error("Environment variable POSTGRESQL_PASSWORD is not set or blank.");
    }

    @Test
    void readsEveryVariableFromTheEnvironment() {
        // given
        Map<String, String> environment = Map.of(
                "CSV_MONITOR_PATH", "/data",
                "POSTGRESQL_URL", "jdbc:postgresql://db/siusdata",
                "POSTGRESQL_USER", "sius",
                "POSTGRESQL_PASSWORD", "secret",
                "PUSHBULLET_API_KEY", "pushbullet-key",
                "GOTIFY_URL", "https://gotify.example.com",
                "GOTIFY_TOKEN", "gotify-token",
                "GOTIFY_PRIORITY", " 8 ");

        // when
        AdapterConfiguration configuration = AdapterConfiguration.fromEnvironment(environment::get, logger);

        // then
        assertEquals(new AdapterConfiguration("/data", "jdbc:postgresql://db/siusdata", "sius", "secret",
                "pushbullet-key", "https://gotify.example.com", "gotify-token", 8), configuration);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "high"})
    void fallsBackToTheDefaultGotifyPriority(String gotifyPriority) {
        // when
        AdapterConfiguration configuration = AdapterConfiguration.fromEnvironment(onlyGotifyPriority(gotifyPriority), logger);

        // then
        assertEquals(AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY, configuration.gotifyPriority());
    }

    @Test
    void warnsAboutAnUnparseableGotifyPriority() {
        // when
        AdapterConfiguration.fromEnvironment(onlyGotifyPriority("high"), logger);

        // then
        verify(logger).warn("Invalid GOTIFY_PRIORITY value '{}'. Using default {}.", "high", AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY);
    }

    @Test
    void toStringLeavesOutTheSecrets() {
        // given
        AdapterConfiguration configuration = new AdapterConfiguration("/data", "jdbc:postgresql://db/siusdata?password=url-secret",
                "sius", "db-secret", "pushbullet-secret", "https://gotify.example.com", "gotify-secret", 5);

        // when
        String text = configuration.toString();

        // then
        for (String secret : new String[]{"url-secret", "db-secret", "pushbullet-secret", "gotify-secret"}) {
            assertFalse(text.contains(secret), text + " contains " + secret);
        }
    }
}
