package ch.fmartin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The connection pool, the database tables, and the per-file record of how many lines are imported.
 */
class ShotDatabase {

    private final Logger logger;
    private final HikariDataSource dataSource;
    private final Notifier notifier;

    ShotDatabase(Logger logger, HikariDataSource dataSource, Notifier notifier) {
        this.logger = logger;
        this.dataSource = dataSource;
        this.notifier = notifier;
    }

    /**
     * Initializes the HikariCP DataSource with a configurable pool size.
     *
     * @return Initialized HikariDataSource.
     */
    static HikariDataSource createDataSource(AdapterConfiguration configuration, Logger logger) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(configuration.jdbcUrl());
        config.setUsername(configuration.jdbcUser());
        config.setPassword(configuration.jdbcPassword());

        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);            // keep one warm connection
        config.setIdleTimeout(0);            // don’t retire the only idle connection
        config.setMaxLifetime(30 * 60_000L); // rotate before server/network does
        config.setKeepaliveTime(5 * 60_000L);// keep NAT/state fresh
        config.setConnectionTimeout(10_000); // fail fast, your retry loop handles it
        config.setValidationTimeout(5_000);

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
            notifier.logError("Error initializing database schema: " + e.getMessage(), e);
            throw new RuntimeException(e);
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
}
