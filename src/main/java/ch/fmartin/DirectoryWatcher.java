package ch.fmartin;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Finds the shot files in the watched directory and queues each one for import, at most once at a time,
 * on a single-threaded executor that retries a failed file until it succeeds or the adapter shuts down.
 */
class DirectoryWatcher {

    private static final int DELAY = 5000;

    // CSV file must start with eight digits, can be followed by anything, must end with .csv
    private static final Pattern CSV_FILE_PATTERN = Pattern.compile("^\\d{8}.*\\.csv$", Pattern.CASE_INSENSITIVE);

    private final Logger logger;
    private final ExecutorService executorService;
    private final WatchService watchService;
    private final String directoryToWatch;
    private final ShotFileImporter importer;
    private final Notifier notifier;

    // Set to track files that are already queued or being processed
    private final Set<String> queuedFiles = ConcurrentHashMap.newKeySet();

    // is true when it has processed all existing files upon startup
    private boolean initialized = false;

    // is true when it is watching file changes in the folder
    private boolean watching = false;

    // is true when it is currently reading the csv and writing it to the db
    private boolean processing = false;

    // indicates shutdown in progress; used to interrupt retry loops promptly
    private volatile boolean shuttingDown = false;

    private final AtomicInteger existingFilesTaskCount = new AtomicInteger(0);

    DirectoryWatcher(Logger logger, ExecutorService executorService, WatchService watchService, String directoryToWatch,
                     ShotFileImporter importer, Notifier notifier) {
        this.logger = logger;
        this.executorService = executorService;
        this.watchService = watchService;
        this.directoryToWatch = directoryToWatch;
        this.importer = importer;
        this.notifier = notifier;
    }

    /**
     * Watches the configured directory for CSV file creation and modification events.
     * Submits detected files for processing.
     */
    void watchDirectory() {
        logger.info("Starting directory watch loop.");
        setWatching(true);
        try {
            while (true) {
                WatchKey key;
                try {
                    key = watchService.take();  // Wait for a watch key to be available
                } catch (InterruptedException e) {
                    logger.warn("Watch service interrupted.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    logger.info("Watch service closed.");
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    // Overflow event
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        logger.warn("File system event overflow occurred.");
                        continue;
                    }

                    // The directory was registered as a Path, so the context of a create or modify event
                    // is the entry's Path.
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    String fileName = ev.context().toString();
                    Path filePath = Path.of(directoryToWatch).resolve(fileName);

                    // Check if the file matches the CSV pattern
                    if (Files.isRegularFile(filePath) && isValidCsvFile(fileName)) {
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            logger.debug("Detected {} event for file: {}", kind.name(), fileName);
                            submitFileForProcessing(filePath, false);
                        }
                    } else {
                        logger.debug("Skipping non-matching file or directory: {}", fileName);
                    }
                }

                // Reset the key -- this step is critical to receive further watch events.
                boolean valid = key.reset();
                if (!valid) {
                    notifier.logError("Watch key is no longer valid. Stopping watch service.", null);
                    break;
                }
            }
        } finally {
            setWatching(false);
            logger.info("Directory watch loop stopped.");
        }
    }

    /**
     * Checks if the file name matches the expected CSV pattern.
     *
     * @param fileName The name of the file.
     * @return True if it matches, false otherwise.
     */
    static boolean isValidCsvFile(String fileName) {
        if (!CSV_FILE_PATTERN.matcher(fileName).matches()) {
            return false;
        }
        String lower = fileName.toLowerCase();
        // Explicitly exclude SIUS sidecar files
        if (lower.endsWith("_stl.csv") || lower.endsWith("_mod.csv")) {
            return false;
        }
        return true;
    }

    /**
     * Submits a file for processing by enqueuing the task.
     * Ensures that a file is only queued once at a time.
     *
     * @param filePath The path to the file to process.
     */
    void submitFileForProcessing(Path filePath, boolean isExistingFile) {
        String fileName = filePath.getFileName().toString();
        boolean isQueued = queuedFiles.add(fileName);

        if (isQueued) {
            logger.info("Enqueued file {} for processing.", fileName);

            // Increment the task counter only for existing files
            if (isExistingFile) {
                existingFilesTaskCount.incrementAndGet();
            }

            executorService.submit(() -> {
                try {
                    processFileWithRetries(filePath);
                } finally {
                    // Decrement the task counter when the task completes
                    if (isExistingFile) {
                        int remainingTasks = existingFilesTaskCount.decrementAndGet();

                        if (remainingTasks == 0) {
                            // All existing tasks have completed
                            setInitialized(true);
                            logger.info("All existing files processed!");
                        }
                    }
                }
            });
        } else {
            logger.debug("File {} is already queued or being processed. Skipping submission.", fileName);
        }
    }

    /**
     * Processes a single CSV file with infinite retry logic.
     * Retries indefinitely with a fixed 5-second wait between attempts.
     *
     * @param filePath The path to the CSV file.
     */
    void processFileWithRetries(Path filePath) {
        String fileName = filePath.getFileName().toString();

        while (true) {
            // Exit promptly if shutdown has been initiated or thread interrupted
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                logger.info("Aborting processing of file {} due to shutdown.", fileName);
                setProcessing(false);
                queuedFiles.remove(fileName);
                break;
            }
            try {
                setProcessing(true);
                importer.processFile(filePath);
                setProcessing(false);
                queuedFiles.remove(fileName);
                logger.info("Successfully processed file: {}", fileName);

                break; // Exit loop on success
            } catch (Exception e) {
                notifier.logError("Failed to process file " + fileName + ": " + e.getMessage(), e);

                try {
                    if (shuttingDown || Thread.currentThread().isInterrupted()) {
                        logger.info("Stopping retries for file {} due to shutdown.", fileName);
                        setProcessing(false);
                        queuedFiles.remove(fileName);
                        break;
                    }
                    logger.info("Waiting for {} milliseconds before retrying...", DELAY);
                    Thread.sleep(DELAY);
                } catch (InterruptedException ie) {
                    logger.warn("Retry sleep interrupted.");
                    Thread.currentThread().interrupt();
                    setProcessing(false);
                    queuedFiles.remove(fileName);
                    break;
                }
            }
        }
    }

    /**
     * Processes existing CSV files in the directory upon application startup.
     *
     * @param directoryPath The path to the directory to scan.
     */
    void processExistingFiles(Path directoryPath) {
        logger.info("Scanning directory for existing CSV files to process...");
        int fileCount = 0;  // Track how many files are found
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directoryPath,
                path -> Files.isRegularFile(path) && isValidCsvFile(path.getFileName().toString()))
        ) {
            for (Path filePath : stream) {
                String fileName = filePath.getFileName().toString();
                logger.info("Found existing file to process: {}", fileName);
                submitFileForProcessing(filePath, true);  // true indicates it's an existing file
                fileCount++;  // Increment for each file found
            }
        } catch (IOException e) {
            String dir = directoryPath.toAbsolutePath().toString();
            notifier.logError("Error scanning directory " + dir + ": " + e.getMessage(), e);
        }

        // If no existing files were found, set initialized to true immediately
        if (fileCount == 0) {
            logger.info("No existing files found to process!");
            setInitialized(true);
        }
    }

    /**
     * Makes a running or waiting retry loop give up at its next check instead of trying the file again.
     */
    void stopRetrying() {
        this.shuttingDown = true;
    }

    boolean isInitialized() {
        return initialized;
    }

    private void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    boolean isWatching() {
        return watching;
    }

    private void setWatching(boolean watching) {
        this.watching = watching;
    }

    boolean isProcessing() {
        return processing;
    }

    private void setProcessing(boolean processing) {
        this.processing = processing;
    }
}
