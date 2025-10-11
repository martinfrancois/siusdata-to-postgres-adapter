package ch.fmartin;

import ch.fmartin.fuzz.generators.CsvCandidateGenerator;
import ch.fmartin.fuzz.generators.IntervalStringGenerator;
import ch.fmartin.fuzz.generators.NullableAsciiStringGenerator;
import ch.fmartin.fuzz.generators.YearPrefixedCsvFileGenerator;
import com.google.common.util.concurrent.MoreExecutors;
import com.zaxxer.hikari.HikariDataSource;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.InRange;
import edu.berkeley.cs.jqf.fuzz.Fuzz;
import edu.berkeley.cs.jqf.fuzz.JQF;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.runner.RunWith;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * JQF fuzz tests that target the parsing and validation helpers of {@link SiusDataToPostgresAdapter}.
 */
@RunWith(JQF.class)
public class SiusDataToPostgresAdapterFuzzTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SiusDataToPostgresAdapterFuzzTest.class);
    private static final ExecutorService EXECUTOR = MoreExecutors.newDirectExecutorService();
    private static final HikariDataSource DATA_SOURCE = Mockito.mock(HikariDataSource.class, Mockito.RETURNS_DEEP_STUBS);
    private static final java.nio.file.WatchService WATCH_SERVICE = Mockito.mock(java.nio.file.WatchService.class);
    private static final PreparedStatement PREPARED_STATEMENT = Mockito.mock(PreparedStatement.class);

    private static final NoNotificationAdapter ADAPTER = new NoNotificationAdapter(
            LOGGER,
            EXECUTOR,
            DATA_SOURCE,
            WATCH_SERVICE,
            "/tmp",
            "jdbc:postgresql://localhost/test",
            "user",
            "password",
            null,
            null,
            null,
            5
    );

    static {
        try {
            Mockito.doNothing().when(PREPARED_STATEMENT).setInt(anyInt(), anyInt());
            Mockito.doNothing().when(PREPARED_STATEMENT).setNull(anyInt(), anyInt());
            Mockito.doNothing().when(PREPARED_STATEMENT).setLong(anyInt(), anyLong());
            Mockito.doNothing().when(PREPARED_STATEMENT).setBoolean(anyInt(), anyBoolean());
            Mockito.doNothing().when(PREPARED_STATEMENT).setString(anyInt(), any());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to configure mock PreparedStatement", e);
        }
    }

    private SiusDataToPostgresAdapterFuzzTest() {
        // No instances
    }

    @Fuzz
    public void fuzzIsValidCsvFile(@From(CsvCandidateGenerator.class) String candidate) {
        if (candidate == null) {
            return;
        }
        ADAPTER.isValidCsvFile(candidate);
    }

    @Fuzz
    public void fuzzCalculateTimestamp(
            @From(IntervalStringGenerator.class) String intervals,
            @From(YearPrefixedCsvFileGenerator.class) String fileName) {
        if (intervals == null || fileName == null) {
            return;
        }
        try {
            ADAPTER.calculateTimestamp(intervals, fileName);
        } catch (IllegalStateException ignored) {
            // Expected when filenames are shorter than four characters.
        }
    }

    @Fuzz
    public void fuzzSetIntegerField(
            @InRange(minInt = 1, maxInt = 29) int parameterIndex,
            @From(NullableAsciiStringGenerator.class) String value) throws SQLException {
        ADAPTER.setIntegerField(PREPARED_STATEMENT, parameterIndex, value);
    }

    @Fuzz
    public void fuzzSetLongField(
            @InRange(minInt = 1, maxInt = 29) int parameterIndex,
            @From(NullableAsciiStringGenerator.class) String value) throws SQLException {
        ADAPTER.setLongField(PREPARED_STATEMENT, parameterIndex, value);
    }

    @Fuzz
    public void fuzzSetBooleanField(
            @InRange(minInt = 1, maxInt = 29) int parameterIndex,
            @From(NullableAsciiStringGenerator.class) String value) throws SQLException {
        ADAPTER.setBooleanField(PREPARED_STATEMENT, parameterIndex, value);
    }

    @Fuzz
    public void fuzzSetTextField(
            @InRange(minInt = 1, maxInt = 29) int parameterIndex,
            @From(NullableAsciiStringGenerator.class) String value) throws SQLException {
        ADAPTER.setTextField(PREPARED_STATEMENT, parameterIndex, value);
    }

    private static class NoNotificationAdapter extends SiusDataToPostgresAdapter {
        NoNotificationAdapter(Logger logger,
                               ExecutorService executorService,
                               HikariDataSource dataSource,
                               java.nio.file.WatchService watchService,
                               String directoryToWatch,
                               String jdbcUrl,
                               String jdbcUser,
                               String jdbcPassword,
                               String pushbulletApiKey,
                               String gotifyUrl,
                               String gotifyToken,
                               int gotifyPriority) {
            super(logger, executorService, dataSource, watchService, directoryToWatch, jdbcUrl, jdbcUser, jdbcPassword,
                    pushbulletApiKey, gotifyUrl, gotifyToken, gotifyPriority);
        }

        @Override
        void sendNotifications(String title, String message) {
            // Suppress external side effects during fuzzing runs.
        }
    }
}
