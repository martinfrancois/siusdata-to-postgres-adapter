package ch.fmartin;

import de.siegmar.fastcsv.reader.CsvRecord;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Maps one line of a SIUS shot file to the parameters of the insert into {@code siusdata_shots}.
 * A field that does not fit its column becomes NULL and is reported through the {@link Notifier}.
 */
class ShotRecordMapper {

    static final String INSERT_SIUSDATA_SHOT_SQL = "INSERT INTO siusdata_shots (" +
            "filename, start_number, score, phase, target_number, score2, score3, time, is_inner_ten, coordinate_x, " +
            "coordinate_y, is_in_time, light_phase_time_span, is_right_sweep, is_demo, shoot_ordinal, practice_ordinal, " +
            "manual_status, total_kind, group_ordinal, fire_kind, log_event_id, log_type, date, relay, weapon, position, " +
            "target_code, external_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final Notifier notifier;

    ShotRecordMapper(Notifier notifier) {
        this.notifier = notifier;
    }

    /**
     * Fills the insert statement with one CSV record.
     *
     * @param stmt                  The insert statement prepared from {@link #INSERT_SIUSDATA_SHOT_SQL}.
     * @param record                The CSV record to insert.
     * @param fileNameWithExtension The name of the file being processed.
     * @throws SQLException If a database access error occurs.
     */
    void populateInsertStatement(PreparedStatement stmt, CsvRecord record, String fileNameWithExtension) throws SQLException {
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
            long millisecondsToAdd = Math.multiplyExact(intervals, 10L);

            // Extract year from filename (first four digits)
            if (fileName.length() < 4) {
                notifier.logError("Filename '" + fileName + "' is too short to extract year.");
                throw new IllegalStateException("Should never happen, as only files that match the pattern should be processed!");
            }
            String yearStr = fileName.substring(0, 4);
            int year = Integer.parseInt(yearStr);

            LocalDateTime startOfYear = LocalDateTime.of(year, 1, 1, 0, 0, 0, 0);
            Instant startOfYearInstant = startOfYear.atZone(ZoneId.systemDefault()).toInstant();

            // Add the milliseconds
            Instant calculatedInstant = startOfYearInstant.plusMillis(millisecondsToAdd);

            // This guard is for malformed input only. Field 23 counts hundredths of a second since
            // 1 January of the file's year, so a real export stays far inside long and Instant. Only
            // garbage input can reach the overflow that Timestamp.from would hit, because Timestamp
            // keeps epoch milliseconds in a long. Such a row gets NULL and a log line instead of a
            // wrong date or an exception that would send the file into the retry loop.
            Math.multiplyExact(calculatedInstant.getEpochSecond(), 1000L);

            // Convert to Timestamp
            return Timestamp.from(calculatedInstant);
        } catch (NumberFormatException | ArithmeticException e) {
            notifier.logError("Invalid Date value '" + dateValue + "': " + e.getMessage(), e);
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
            notifier.logError(errorMsg, e);
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
            notifier.logError(errorMsg, e);
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
            notifier.logError(errorMsg);
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
            notifier.logError(errorMsg);
            stmt.setNull(index, Types.VARCHAR);
        }
    }
}
