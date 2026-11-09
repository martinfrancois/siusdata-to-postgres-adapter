package ch.fmartin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DirectoryWatcherTest {

    @Mock
    private Logger logger;

    @Mock
    private ExecutorService executorService;

    @Mock
    private WatchService watchService;

    @Mock
    private ShotFileImporter importer;

    private DirectoryWatcher watcher;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Without a Pushbullet key or a Gotify URL the notifier only logs.
        Notifier notifier = new Notifier(logger, null, null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY);
        watcher = spy(new DirectoryWatcher(logger, executorService, watchService, "test_directory", importer, notifier));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678.csv", "87654321_data.csv", "12345678.CSV"})
    void testIsValidCsvFile_Valid(String fileName) {
        // when
        boolean result = DirectoryWatcher.isValidCsvFile(fileName);

        // then
        assertTrue(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file.csv", "1234.csv", "file.txt", "invalid_file"})
    void testIsValidCsvFile_Invalid(String fileName) {
        // when
        boolean result = DirectoryWatcher.isValidCsvFile(fileName);

        // then
        assertFalse(result);
    }

    @Test
    void testSubmitFileForProcessing_NewFile() {
        // given
        Path filePath = Path.of("test_directory/testfile.csv");

        // when
        watcher.submitFileForProcessing(filePath, false);

        // then
        verify(logger).info("Enqueued file {} for processing.", "testfile.csv");
        verify(executorService).submit(any(Runnable.class));
    }

    @Test
    void testSubmitFileForProcessing_AlreadyQueued() {
        // given
        Path filePath = Path.of("test_directory/testfile.csv");

        // Simulate the file is already queued by submitting it once
        watcher.submitFileForProcessing(filePath, false);

        // Reset interactions to focus on the second submission
        reset(logger, executorService);

        // when
        watcher.submitFileForProcessing(filePath, false);

        // then
        verify(logger).debug("File {} is already queued or being processed. Skipping submission.", "testfile.csv");
        verifyNoInteractions(executorService);
    }

    @Test
    void testWatchDirectoryStopsUpdatesFlag() throws InterruptedException {
        // given
        doThrow(new InterruptedException("Test interruption")).when(watchService).take();

        // when
        watcher.watchDirectory();

        // then
        assertFalse(watcher.isWatching());
        verify(logger).info("Starting directory watch loop.");
        verify(logger).warn("Watch service interrupted.");
        verify(logger).info("Directory watch loop stopped.");

        // clear interrupted state for subsequent tests
        Thread.interrupted();
    }

    @Test
    void testProcessExistingFiles() throws IOException {
        // given
        Path tempDir = Files.createTempDirectory("testDir");
        Path csvFile = tempDir.resolve("20210000.csv");
        Files.createFile(csvFile);

        doNothing().when(watcher).submitFileForProcessing(any(Path.class), eq(true));

        // when
        watcher.processExistingFiles(tempDir);

        // then
        verify(logger).info("Scanning directory for existing CSV files to process...");
        verify(logger).info("Found existing file to process: {}", "20210000.csv");
        verify(watcher).submitFileForProcessing(csvFile, true);

        // Clean up
        Files.delete(csvFile);
        Files.delete(tempDir);
    }

    @Test
    void testProcessFileWithRetries_FailureThenSuccess() throws Exception {
        // given
        Path filePath = Path.of("testfile.csv");

        IOException exception = new IOException("Test exception");
        doThrow(exception)
                .doNothing()
                .when(importer).processFile(filePath);

        // when
        watcher.processFileWithRetries(filePath);

        // then
        verify(importer, times(2)).processFile(filePath);
        verify(logger).error("Failed to process file testfile.csv: Test exception", exception);
        verify(logger).info("Waiting for {} milliseconds before retrying...", 5000);
        verify(logger).info("Successfully processed file: {}", "testfile.csv");
    }
}
