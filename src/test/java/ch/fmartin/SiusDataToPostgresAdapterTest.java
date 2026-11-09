package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class SiusDataToPostgresAdapterTest {

    private static final AdapterConfiguration CONFIGURATION = new AdapterConfiguration("test_directory",
            "jdbc:postgresql://localhost/testdb", "user", "password", null, null, null,
            AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY);

    @Mock
    private Logger logger;

    @Mock
    private ExecutorService executorService;

    @Mock
    private HikariDataSource dataSource;

    @Mock
    private WatchService watchService;

    private SiusDataToPostgresAdapter adapter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adapter = new SiusDataToPostgresAdapter(logger, executorService, dataSource, watchService, CONFIGURATION);
    }

    @Test
    void testConfigureDNSCaching() {
        // when
        adapter.configureDNSCaching();

        // then
        assertEquals("60", java.security.Security.getProperty("networkaddress.cache.ttl"));
        verify(logger).info("Configured DNS caching with TTL=60 seconds.");
    }

    @Test
    void testShutdown() throws InterruptedException {
        // given
        ExecutorService execService = Executors.newSingleThreadExecutor();
        HikariDataSource mockDataSource = mock(HikariDataSource.class);
        adapter = new SiusDataToPostgresAdapter(logger, execService, mockDataSource, watchService, CONFIGURATION);

        // when
        adapter.shutdown();

        // then
        assertTrue(execService.isShutdown() || execService.isTerminated());
        verify(logger).info("Shutting down application...");
        verify(logger).info("Executor service shutdown complete.");
        verify(logger).info("HikariCP DataSource closed.");
        verify(logger).info("Application shutdown complete.");
    }

    @Test
    void testShutdown_WatchServiceException() throws IOException {
        // given
        IOException exception = new IOException("Test exception");
        doThrow(exception).when(watchService).close();

        // when
        adapter.shutdown();

        // then
        verify(logger).error("Error closing watch service: Test exception", exception);
    }
}
