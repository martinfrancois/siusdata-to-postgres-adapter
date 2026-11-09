package ch.fmartin;

import org.slf4j.Logger;

import java.util.function.UnaryOperator;

/**
 * The adapter's settings, read from environment variables.
 */
record AdapterConfiguration(String directoryToWatch,
                            String jdbcUrl,
                            String jdbcUser,
                            String jdbcPassword,
                            String pushbulletApiKey,
                            String gotifyUrl,
                            String gotifyToken,
                            int gotifyPriority) {

    static final int DEFAULT_GOTIFY_PRIORITY = 5;

    /**
     * Reads the configuration through {@code environment}, which returns the value of a variable or
     * {@code null} when it is not set. An unparseable GOTIFY_PRIORITY falls back to the default with a warning.
     */
    static AdapterConfiguration fromEnvironment(UnaryOperator<String> environment, Logger logger) {
        String directoryToWatch = environment.apply("CSV_MONITOR_PATH");
        String jdbcUrl = environment.apply("POSTGRESQL_URL");
        String jdbcUser = environment.apply("POSTGRESQL_USER");
        String jdbcPassword = environment.apply("POSTGRESQL_PASSWORD");
        String pushbulletApiKey = environment.apply("PUSHBULLET_API_KEY");
        String gotifyUrl = environment.apply("GOTIFY_URL");
        String gotifyToken = environment.apply("GOTIFY_TOKEN");

        int resolvedPriority = DEFAULT_GOTIFY_PRIORITY;
        String gotifyPriorityEnv = environment.apply("GOTIFY_PRIORITY");
        if (gotifyPriorityEnv != null && !gotifyPriorityEnv.isBlank()) {
            try {
                resolvedPriority = Integer.parseInt(gotifyPriorityEnv.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid GOTIFY_PRIORITY value '{}'. Using default {}.", gotifyPriorityEnv, DEFAULT_GOTIFY_PRIORITY);
            }
        }
        return new AdapterConfiguration(directoryToWatch, jdbcUrl, jdbcUser, jdbcPassword, pushbulletApiKey,
                gotifyUrl, gotifyToken, resolvedPriority);
    }

    /**
     * Validates that all necessary environment variables are set.
     * Throws if any required variable is missing.
     */
    void validate(Logger logger) {
        if (directoryToWatch == null || directoryToWatch.isBlank()) {
            logger.error("Environment variable CSV_MONITOR_PATH is not set or blank.");
            throw new IllegalStateException("Environment variable CSV_MONITOR_PATH is not set or blank.");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            logger.error("Environment variable POSTGRESQL_URL is not set or blank.");
            throw new IllegalStateException("Environment variable POSTGRESQL_URL is not set or blank.");
        }
        if (jdbcUser == null || jdbcUser.isBlank()) {
            logger.error("Environment variable POSTGRESQL_USER is not set or blank.");
            throw new IllegalStateException("Environment variable POSTGRESQL_USER is not set or blank.");
        }
        if (jdbcPassword == null || jdbcPassword.isBlank()) {
            logger.error("Environment variable POSTGRESQL_PASSWORD is not set or blank.");
            throw new IllegalStateException("Environment variable POSTGRESQL_PASSWORD is not set or blank.");
        }
        logger.info("All required environment variables are set.");
    }

    // The generated toString would print the password, both notification tokens, and a JDBC URL that can
    // carry credentials as parameters.
    @Override
    public String toString() {
        return "AdapterConfiguration[directoryToWatch=" + directoryToWatch + ", jdbcUser=" + jdbcUser
                + ", gotifyUrl=" + gotifyUrl + ", gotifyPriority=" + gotifyPriority + "]";
    }
}
