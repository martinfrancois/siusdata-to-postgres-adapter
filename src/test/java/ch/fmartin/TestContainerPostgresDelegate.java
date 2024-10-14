package ch.fmartin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.delegate.AbstractDatabaseDelegate;
import org.testcontainers.exception.ConnectionCreationException;
import org.testcontainers.ext.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Properties;

// from https://www.xavierbouclet.com/2022/08/08/TestContainer-Wait-DB-SQL.html
public class TestContainerPostgresDelegate extends AbstractDatabaseDelegate<Connection> {
    private static Logger log = LoggerFactory.getLogger(TestContainerPostgresDelegate.class);

    private final ContainerState container;

    public TestContainerPostgresDelegate(ContainerState container) {
        this.container = container;
    }

    @Override
    protected Connection createNewConnection() {
        try {
            Properties connectionProps = new Properties();
            connectionProps.put("user", "test");
            connectionProps.put("password", "test");
            return DriverManager.getConnection(
                    "jdbc:postgresql://localhost:%s/test".formatted(container.getFirstMappedPort()),
                    connectionProps);
        } catch (Exception e) {
            log.error("Could not obtain PostgresSQL connection");
            throw new ConnectionCreationException("Could not obtain PostgresSQL connection", e);
        }
    }

    @Override
    public void execute(String statement, String scriptPath, int lineNumber, boolean continueOnError, boolean ignoreFailedDrops) {
        try {
            ResultSet result = getConnection().prepareStatement(statement).executeQuery();
            result.next();
            if (result.getObject(1, Integer.class).equals(666)) {
                log.debug("Statement {} was applied", statement);
            } else {
                throw new ScriptUtils.ScriptStatementFailedException(statement, lineNumber, scriptPath);
            }
        } catch (Exception e) {
            throw new ScriptUtils.ScriptStatementFailedException(statement, lineNumber, scriptPath, e);
        }
    }

    @Override
    protected void closeConnectionQuietly(Connection connection) {
        try {
            connection.close();
        } catch (Exception e) {
            log.error("Could not close PostgresSQL connection", e);
        }
    }
}