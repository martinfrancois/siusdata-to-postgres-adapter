package ch.fmartin;

import com.pholser.junit.quickcheck.From;
import de.siegmar.fastcsv.reader.CsvRecord;
import edu.berkeley.cs.jqf.junit5.FuzzTest;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coverage-guided fuzz tests for the path a shot line takes from the file into the insert statement.
 * {@code ./gradlew fuzz} runs a campaign. {@code ./gradlew check} replays the corpus the last campaign
 * saved and the inputs under {@value #REGRESSION_INPUTS}, where failing inputs go once they are fixed.
 */
class SiusDataToPostgresAdapterFuzzTest {

    private static final String REGRESSION_INPUTS = "src/fuzzTest/regression/everyLineWithAllFieldsFillsEveryColumn";

    // Column 1 is the file name, columns 2 to 29 hold the fields of the line.
    private static final List<Integer> ALL_COLUMNS = IntStream.rangeClosed(1, ShotFile.FIELD_COUNT + 1).boxed().toList();

    private static final SiusDataToPostgresAdapter ADAPTER = new SiusDataToPostgresAdapter(
            NOPLogger.NOP_LOGGER, null, null, null, null, null, null, null, null) {
        @Override
        void sendNotifications(String title, String message) {
            // Every malformed field reports an error, and the fuzzer must not send those anywhere.
        }
    };

    /**
     * A line with a value the column cannot hold must still fill every column, with NULL where the
     * value does not fit. An exception or an unset parameter would make the insert fail, and the
     * adapter would then retry the whole file forever.
     */
    @FuzzTest(seeds = REGRESSION_INPUTS)
    void everyLineWithAllFieldsFillsEveryColumn(@From(ShotFileGenerator.class) ShotFile shotFile) {
        List<CsvRecord> records = parse(shotFile);
        assertEquals(shotFile.lines().size(), records.size(), "lines read from the file");

        for (CsvRecord csvRecord : records) {
            List<Integer> filledColumns = new ArrayList<>();
            try {
                ADAPTER.populateInsertStatement(recordingStatement(filledColumns), csvRecord, shotFile.name());
            } catch (SQLException e) {
                throw new AssertionError("the recording statement never throws", e);
            }
            assertEquals(ALL_COLUMNS, filledColumns.stream().sorted().toList(), "columns filled for " + csvRecord);
        }
    }

    private static List<CsvRecord> parse(ShotFile shotFile) {
        try {
            Path file = Files.createTempFile("siusdata-fuzz", ".csv");
            try {
                Files.writeString(file, shotFile.content());
                return ADAPTER.parseNewCsvRecords(file, 0);
            } finally {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Records the parameter index of every set call. Any other call fails the test, because filling
    // the statement must not execute it.
    private static PreparedStatement recordingStatement(List<Integer> filledColumns) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (!method.getName().startsWith("set")) {
                        throw new AssertionError("unexpected call to " + method.getName());
                    }
                    filledColumns.add((Integer) args[0]);
                    return null;
                });
    }
}
