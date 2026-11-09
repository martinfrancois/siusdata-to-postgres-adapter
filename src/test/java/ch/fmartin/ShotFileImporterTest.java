package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShotFileImporterTest {

    private final Logger logger = mock(Logger.class);
    private final HikariDataSource dataSource = mock(HikariDataSource.class);
    private final ShotFileImporter importer = newImporter();

    private ShotFileImporter newImporter() {
        // Without a Pushbullet key or a Gotify URL the notifier only logs.
        Notifier notifier = new Notifier(logger, null, null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY);
        return new ShotFileImporter(logger, dataSource, new ShotDatabase(logger, dataSource, notifier),
                new ShotRecordMapper(notifier), notifier);
    }

    @Test
    void testParseNewCsvRecords_Exception() throws IOException {
        // given
        Path tempFile = Files.createTempFile("test", ".csv");
        int lastProcessedLine = 0;

        // Delete the file to cause an IOException
        Files.delete(tempFile);

        // when
        Exception exception = assertThrows(IOException.class, () -> {
            ShotFileImporter.parseNewCsvRecords(tempFile, lastProcessedLine);
        });

        // then
        assertNotNull(exception);
    }

    @Test
    void testProcessFile_Exception() throws Exception {
        // given
        Path filePath = mock(Path.class);
        when(filePath.getFileName()).thenReturn(Path.of("20210000.csv"));
        when(dataSource.getConnection()).thenThrow(new SQLException("Test exception"));

        // when
        Exception exception = assertThrows(SQLException.class, () -> importer.processFile(filePath));

        // then
        assertEquals("Test exception", exception.getMessage());
    }
}
