package ch.fmartin;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShotRecordMapperTest {

    private final Logger logger = mock(Logger.class);
    // Without a Pushbullet key or a Gotify URL the notifier only logs.
    private final ShotRecordMapper mapper = new ShotRecordMapper(
            new Notifier(logger, null, null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY));

    @Test
    void testSetIntegerField_Valid() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        mapper.setIntegerField(stmt, 1, "123");

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
        mapper.setIntegerField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.INTEGER);
    }

    @Test
    void testSetLongField_Valid() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        mapper.setLongField(stmt, 1, "123456789");

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
        mapper.setLongField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.BIGINT);
    }

    @Test
    void testSetBooleanField_ValidTrue() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        mapper.setBooleanField(stmt, 1, "1");

        // then
        verify(stmt).setBoolean(1, true);
    }

    @Test
    void testSetBooleanField_ValidFalse() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        mapper.setBooleanField(stmt, 1, "0");

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
        mapper.setBooleanField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.BOOLEAN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "false", "yes"})
    void testSetBooleanField_InvalidValue(String valueStr) throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        mapper.setBooleanField(stmt, 1, valueStr);

        // then
        verify(stmt).setBoolean(1, false);
    }

    @Test
    void testSetTextField_Valid() throws SQLException {
        // given
        PreparedStatement stmt = mock(PreparedStatement.class);

        // when
        mapper.setTextField(stmt, 1, "text");

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
        mapper.setTextField(stmt, 1, valueStr);

        // then
        verify(stmt).setNull(1, Types.VARCHAR);
    }

    @Test
    void testCalculateTimestamp_Valid() {
        // given
        String dateValue = "100000"; // intervals
        String fileName = "20210000.csv";

        // when
        Timestamp timestamp = mapper.calculateTimestamp(dateValue, fileName);

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
        Timestamp timestamp = mapper.calculateTimestamp(dateValue, fileName);

        // then
        assertNull(timestamp);
    }

    @Test
    void testCalculateTimestamp_ValueBeyondTimestampRange() {
        // given: 10 ms intervals that land about 29 million years after 2021, past what Timestamp can hold
        String dateValue = Long.toString(Long.MAX_VALUE / 10);
        String fileName = "20210000.csv";

        // when
        Timestamp timestamp = mapper.calculateTimestamp(dateValue, fileName);

        // then: the same result on every JDK, logged and stored as NULL like a non-numeric value
        assertNull(timestamp);
        verify(logger).error(startsWith("Invalid Date value '" + dateValue + "'"), any(ArithmeticException.class));
    }

    @Test
    void testCalculateTimestamp_InvalidFileName() {
        // given
        String dateValue = "100000";
        String fileName = "abc"; // filename less than 4 characters

        // when
        Exception exception = assertThrows(IllegalStateException.class, () -> mapper.calculateTimestamp(dateValue, fileName));

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
        mapper.populateInsertStatement(mockStatement, csvRecord, "20210000.csv");

        // then
        verify(mockStatement, atLeastOnce()).setInt(anyInt(), anyInt());
        verify(mockStatement, atLeastOnce()).setString(anyInt(), anyString());
        verify(mockStatement, atLeastOnce()).setBoolean(anyInt(), anyBoolean());
        verify(mockStatement, atLeastOnce()).setLong(anyInt(), anyLong());
        verify(mockStatement, atLeastOnce()).setTimestamp(anyInt(), any(Timestamp.class));
    }
}
