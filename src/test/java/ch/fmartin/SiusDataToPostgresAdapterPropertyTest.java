package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.nio.file.WatchService;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class SiusDataToPostgresAdapterPropertyTest {

    private static SiusDataToPostgresAdapter newAdapter() {
        Logger logger = Mockito.mock(Logger.class);
        ExecutorService executor = Mockito.mock(ExecutorService.class);
        HikariDataSource dataSource = Mockito.mock(HikariDataSource.class);
        WatchService watchService = Mockito.mock(WatchService.class);
        return new SiusDataToPostgresAdapter(
            logger,
            executor,
            dataSource,
            watchService,
            "/tmp",
            "jdbc:postgresql://localhost/test",
            "user",
            "secret",
            "push",
            "https://gotify",
            "token",
            5
        );
    }

    private static Arbitrary<String> surroundingWhitespace() {
        return Arbitraries.strings().withChars(' ', '\t').ofMinLength(0).ofMaxLength(3);
    }

    @Provide
    Arbitrary<String> validCsvFilenames() {
        Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofLength(8);
        Arbitrary<String> middle = Arbitraries.strings()
            .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
            .ofMinLength(0)
            .ofMaxLength(12);
        return Combinators.combine(digits, middle)
            .as((prefix, body) -> prefix + body + ".csv")
            .filter(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return !lower.endsWith("_stl.csv") && !lower.endsWith("_mod.csv");
            });
    }

    @Provide
    Arbitrary<String> invalidCsvFilenames() {
        Arbitrary<String> shortPrefix = Arbitraries.strings()
            .withCharRange('0', '9')
            .ofMinLength(0)
            .ofMaxLength(7)
            .map(prefix -> prefix + ".csv");
        Arbitrary<String> wrongExtension = Arbitraries.strings()
            .withCharRange('0', '9')
            .ofLength(8)
            .map(prefix -> prefix + "_data.txt");
        Arbitrary<String> bannedSuffix = Arbitraries.strings()
            .withCharRange('0', '9')
            .ofLength(8)
            .flatMap(prefix -> Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-")
                .ofMinLength(0)
                .ofMaxLength(5)
                .flatMap(body -> Arbitraries.of("_stl.csv", "_STL.csv", "_sTl.csv", "_mod.csv", "_MOD.csv", "_mOd.csv")
                    .map(suffix -> prefix + body + suffix)));
        return Arbitraries.oneOf(shortPrefix, wrongExtension, bannedSuffix);
    }

    @Provide
    Arbitrary<Tuple2<Integer, String>> integerStringInputs() {
        Arbitrary<Integer> values = Arbitraries.integers().between(Integer.MIN_VALUE, Integer.MAX_VALUE);
        return Combinators.combine(values, surroundingWhitespace(), surroundingWhitespace())
            .as((value, leading, trailing) -> Tuple.of(value, leading + Integer.toString(value) + trailing));
    }

    @Provide
    Arbitrary<String> nonParsableIntegerStrings() {
        Arbitrary<String> alpha = Arbitraries.strings()
            .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .ofMinLength(1)
            .ofMaxLength(10);
        Arbitrary<String> whitespaceOnly = surroundingWhitespace();
        return Arbitraries.oneOf(alpha, whitespaceOnly).injectNull(0.2);
    }

    @Provide
    Arbitrary<Tuple2<Long, String>> longStringInputs() {
        Arbitrary<Long> values = Arbitraries.longs().between(Long.MIN_VALUE / 10, Long.MAX_VALUE / 10);
        return Combinators.combine(values, surroundingWhitespace(), surroundingWhitespace())
            .as((value, leading, trailing) -> Tuple.of(value, leading + Long.toString(value) + trailing));
    }

    @Provide
    Arbitrary<String> nonParsableLongStrings() {
        Arbitrary<String> alpha = Arbitraries.strings()
            .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .ofMinLength(1)
            .ofMaxLength(15);
        Arbitrary<String> whitespaceOnly = surroundingWhitespace();
        return Arbitraries.oneOf(alpha, whitespaceOnly.map(s -> s + " "));
    }

    @Provide
    Arbitrary<String> validYearStrings() {
        Arbitrary<Integer> years = Arbitraries.integers().between(1, 9999);
        Arbitrary<String> suffix = Arbitraries.strings()
            .withChars("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-")
            .ofMinLength(0)
            .ofMaxLength(20);
        return Combinators.combine(years, suffix)
            .as((year, extra) -> String.format(Locale.ROOT, "%04d", year) + extra);
    }

    @Provide
    Arbitrary<Tuple2<String, String>> validTimestampInputs() {
        Arbitrary<Long> values = Arbitraries.longs().between(Long.MIN_VALUE / 10, Long.MAX_VALUE / 10);
        Arbitrary<Integer> years = Arbitraries.integers().between(1, 9999);
        Arbitrary<String> extra = Arbitraries.strings()
            .withChars("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_-")
            .ofMinLength(0)
            .ofMaxLength(20);
        return Combinators.combine(values, years, extra, surroundingWhitespace(), surroundingWhitespace())
            .as((value, year, suffix, leading, trailing) -> {
                String numeric = leading + Long.toString(value) + trailing;
                String yearPart = String.format(Locale.ROOT, "%04d", year) + suffix;
                return Tuple.of(numeric, yearPart);
            });
    }

    @Provide
    Arbitrary<String> shortYearStrings() {
        return Arbitraries.strings()
            .withChars("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
            .ofMaxLength(3);
    }

    @Provide
    Arbitrary<String> booleanTrueStrings() {
        return Combinators.combine(surroundingWhitespace(), surroundingWhitespace())
            .as((leading, trailing) -> leading + "1" + trailing);
    }

    @Provide
    Arbitrary<String> booleanFalseStrings() {
        Arbitrary<String> core = Arbitraries.strings()
            .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
            .ofMinLength(1)
            .ofMaxLength(5)
            .filter(value -> !value.equals("1"));
        return Combinators.combine(surroundingWhitespace(), core, surroundingWhitespace())
            .as((leading, value, trailing) -> leading + value + trailing);
    }

    @Provide
    Arbitrary<String> blankOrNullStrings() {
        return surroundingWhitespace().injectNull(0.5);
    }

    @Provide
    Arbitrary<String> nonBlankTextStrings() {
        Arbitrary<String> core = Arbitraries.strings()
            .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_- ")
            .ofMinLength(1)
            .ofMaxLength(20)
            .filter(value -> !value.trim().isEmpty());
        return Combinators.combine(surroundingWhitespace(), core, surroundingWhitespace())
            .as((leading, value, trailing) -> leading + value + trailing);
    }

    @Property(tries = 250)
    void validCsvFilesAreAccepted(@ForAll("validCsvFilenames") String filename) {
        SiusDataToPostgresAdapter adapter = newAdapter();
        Assertions.assertTrue(adapter.isValidCsvFile(filename));
    }

    @Property(tries = 250)
    void invalidCsvFilesAreRejected(@ForAll("invalidCsvFilenames") String filename) {
        SiusDataToPostgresAdapter adapter = newAdapter();
        Assertions.assertFalse(adapter.isValidCsvFile(filename));
    }

    @Property(tries = 200)
    void calculateTimestampMatchesExpectedInstant(@ForAll("validTimestampInputs") Tuple2<String, String> inputs) {
        SiusDataToPostgresAdapter adapter = newAdapter();
        String numericString = inputs.get1();
        String yearString = inputs.get2();
        Timestamp timestamp = adapter.calculateTimestamp(numericString, yearString);
        Assertions.assertNotNull(timestamp);
        long numeric = Long.parseLong(numericString.trim());
        int year = Integer.parseInt(yearString.substring(0, 4));
        Instant base = LocalDateTime.of(year, 1, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant();
        Timestamp expected = Timestamp.from(base.plusMillis(numeric * 10L));
        Assertions.assertEquals(expected, timestamp);
    }

    @Property(tries = 100)
    void calculateTimestampReturnsNullForInvalidNumbers(
        @ForAll("nonParsableLongStrings") String numericString,
        @ForAll("validYearStrings") String yearString
    ) {
        SiusDataToPostgresAdapter adapter = newAdapter();
        Timestamp timestamp = adapter.calculateTimestamp(numericString, yearString);
        Assertions.assertNull(timestamp);
    }

    @Property(tries = 50)
    void calculateTimestampRejectsShortYearStrings(@ForAll("shortYearStrings") String yearString) {
        SiusDataToPostgresAdapter adapter = newAdapter();
        Assertions.assertThrows(IllegalStateException.class, () -> adapter.calculateTimestamp("1", yearString));
    }

    @Property(tries = 200)
    void integerStringsSetPreparedStatement(@ForAll("integerStringInputs") Tuple2<Integer, String> input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 1;
        adapter.setIntegerField(statement, parameterIndex, input.get2());
        verify(statement).setInt(parameterIndex, input.get1());
        verify(statement, never()).setNull(anyInt(), anyInt());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 200)
    void invalidIntegerStringsFallbackToNull(@ForAll("nonParsableIntegerStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 2;
        adapter.setIntegerField(statement, parameterIndex, input);
        verify(statement).setNull(parameterIndex, Types.INTEGER);
        verify(statement, never()).setInt(anyInt(), anyInt());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 200)
    void longStringsSetPreparedStatement(@ForAll("longStringInputs") Tuple2<Long, String> input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 3;
        adapter.setLongField(statement, parameterIndex, input.get2());
        verify(statement).setLong(parameterIndex, input.get1());
        verify(statement, never()).setNull(anyInt(), anyInt());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 200)
    void invalidLongStringsFallbackToNull(@ForAll("nonParsableLongStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 4;
        adapter.setLongField(statement, parameterIndex, input);
        verify(statement).setNull(parameterIndex, Types.BIGINT);
        verify(statement, never()).setLong(anyInt(), Mockito.anyLong());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 150)
    void booleanTrueStringsSetBooleanTrue(@ForAll("booleanTrueStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 5;
        adapter.setBooleanField(statement, parameterIndex, input);
        verify(statement).setBoolean(parameterIndex, true);
        verify(statement, never()).setNull(anyInt(), anyInt());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 150)
    void booleanFalseStringsSetBooleanFalse(@ForAll("booleanFalseStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 6;
        adapter.setBooleanField(statement, parameterIndex, input);
        verify(statement).setBoolean(parameterIndex, false);
        verify(statement, never()).setNull(anyInt(), anyInt());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 150)
    void blankBooleanStringsSetNull(@ForAll("blankOrNullStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 7;
        adapter.setBooleanField(statement, parameterIndex, input);
        verify(statement).setNull(parameterIndex, Types.BOOLEAN);
        verify(statement, never()).setBoolean(anyInt(), Mockito.anyBoolean());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 150)
    void textStringsAreTrimmedBeforeSetting(@ForAll("nonBlankTextStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 8;
        adapter.setTextField(statement, parameterIndex, input);
        verify(statement).setString(parameterIndex, input.trim());
        verify(statement, never()).setNull(anyInt(), anyInt());
        verifyNoMoreInteractions(statement);
    }

    @Property(tries = 150)
    void blankTextStringsSetNull(@ForAll("blankOrNullStrings") String input) throws SQLException {
        SiusDataToPostgresAdapter adapter = newAdapter();
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        int parameterIndex = 9;
        adapter.setTextField(statement, parameterIndex, input);
        verify(statement).setNull(parameterIndex, Types.VARCHAR);
        verify(statement, never()).setString(anyInt(), Mockito.anyString());
        verifyNoMoreInteractions(statement);
    }
}
