package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports the lines of a shot file that are not in the database yet, one transaction per pass.
 */
class ShotFileImporter {

    private final Logger logger;
    private final HikariDataSource dataSource;
    private final ShotDatabase database;
    private final ShotRecordMapper recordMapper;
    private final Notifier notifier;

    ShotFileImporter(Logger logger, HikariDataSource dataSource, ShotDatabase database, ShotRecordMapper recordMapper,
                     Notifier notifier) {
        this.logger = logger;
        this.dataSource = dataSource;
        this.database = database;
        this.recordMapper = recordMapper;
        this.notifier = notifier;
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

                boolean transactionStarted = false;

                try {
                    // Retrieve last processed line
                    int lastProcessedLine = database.getLastProcessedLine(conn, fileNameWithExtension);

                    // Parse CSV and get new records
                    List<CsvRecord> newRecords = parseNewCsvRecords(filePath, lastProcessedLine);

                    if (newRecords.isEmpty()) {
                        logger.info("No new records to process in file: {}", fileNameWithExtension);
                        keepProcessing = false;
                        continue;
                    }

                    // Disable auto-commit for transaction management after parsing the CSV
                    conn.setAutoCommit(false);
                    transactionStarted = true;
                    try (Statement s = conn.createStatement()) {
                        s.execute("SET LOCAL statement_timeout = '10s'");
                        s.execute("SET LOCAL lock_timeout = '2s'");
                        s.execute("SET LOCAL idle_in_transaction_session_timeout = '30s'");
                    }

                    // Insert records into the database
                    int processedCount = 0;
                    try (PreparedStatement insertStmt = conn.prepareStatement(ShotRecordMapper.INSERT_SIUSDATA_SHOT_SQL)) {
                        for (CsvRecord record : newRecords) {
                            try {
                                recordMapper.populateInsertStatement(insertStmt, record, fileNameWithExtension);
                                insertStmt.executeUpdate();
                                processedCount++;
                            } catch (SQLException e) {
                                notifier.logError("Failed to insert record at line " + (lastProcessedLine + processedCount + 1) + " in file " + fileNameWithExtension + ": " + e.getMessage(), e);
                                throw e; // rethrow to trigger retry
                            } finally {
                                try {
                                    insertStmt.clearParameters();
                                } catch (SQLException clearEx) {
                                    logger.warn("Failed to clear parameters for insert statement: {}", clearEx.getMessage(), clearEx);
                                }
                            }
                        }
                    }

                    // Update last processed line
                    database.updateLastProcessedLine(conn, fileNameWithExtension, lastProcessedLine + processedCount);

                    // Commit transaction
                    conn.commit();
                    logger.info("Successfully processed {} records from file: {}", processedCount, fileNameWithExtension);

                } catch (Exception e) {
                    if (transactionStarted) {
                        // Rollback transaction on error
                        conn.rollback();
                    }
                    notifier.logError("Error processing file " + fileNameWithExtension + ": " + e.getMessage(), e);
                    throw e; // Rethrow to trigger retry
                } finally {
                    if (transactionStarted) {
                        // Restore auto-commit
                        conn.setAutoCommit(true);
                    }
                }

            } catch (SQLException e) {
                notifier.logError("Failed to process file " + fileNameWithExtension + ": " + e.getMessage(), e);
                throw e; // Rethrow to trigger retry
            }
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
    static List<CsvRecord> parseNewCsvRecords(Path filePath, int lastProcessedLine) throws IOException {
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
}
