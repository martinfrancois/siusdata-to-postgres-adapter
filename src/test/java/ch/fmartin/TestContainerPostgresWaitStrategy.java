package ch.fmartin;

import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.delegate.DatabaseDelegate;

import java.util.concurrent.TimeUnit;

import static org.rnorth.ducttape.unreliables.Unreliables.retryUntilSuccess;

// from https://www.xavierbouclet.com/2022/08/08/TestContainer-Wait-DB-SQL.html
public class TestContainerPostgresWaitStrategy extends AbstractWaitStrategy {
    private static final String SELECT_VERSION_QUERY = "SELECT 666";
    private static final String TIMEOUT_ERROR = "Timed out waiting for PostgresSQL to be accessible for query execution";

    @Override
    protected void waitUntilReady() {
        // execute select version query until success or timeout
        try {
            retryUntilSuccess((int) startupTimeout.getSeconds(), TimeUnit.SECONDS, () -> {
                getRateLimiter().doWhenReady(() -> {
                    try (DatabaseDelegate databaseDelegate = getDatabaseDelegate()) {
                        databaseDelegate.execute(SELECT_VERSION_QUERY, "", 1, false, false);
                    }
                });
                return true;
            });
        } catch (Exception e) {
            throw new ContainerLaunchException(TIMEOUT_ERROR);
        }
    }

    private DatabaseDelegate getDatabaseDelegate() {
        return new TestContainerPostgresDelegate(waitStrategyTarget);
    }
}