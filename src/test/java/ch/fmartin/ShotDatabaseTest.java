package ch.fmartin;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShotDatabaseTest {

    private final Logger logger = mock(Logger.class);
    private final HikariDataSource dataSource = mock(HikariDataSource.class);
    // Without a Pushbullet key or a Gotify URL the notifier only logs.
    private final ShotDatabase database = new ShotDatabase(logger, dataSource,
            new Notifier(logger, null, null, null, AdapterConfiguration.DEFAULT_GOTIFY_PRIORITY));

    @Test
    void testInitializeDatabaseSchema() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(conn.isValid(anyInt())).thenReturn(true);

        // when
        database.initializeDatabaseSchema();

        // then
        verify(stmt, times(2)).executeUpdate(anyString());
        verify(logger).info("'siusdata_shots' table created or exists already.");
        verify(logger).info("'file_progress' table created or exists already.");
    }

    @Test
    void testInitializeDatabaseSchema_Exception() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenThrow(new SQLException("Test exception"));

        // when
        Exception exception = assertThrows(RuntimeException.class, database::initializeDatabaseSchema);

        // then
        verify(logger).error("Error initializing database schema: Test exception", exception.getCause());
    }

    @Test
    void testGetLastProcessedLine_Found() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt("last_processed_line")).thenReturn(10);

        // when
        int lastProcessedLine = database.getLastProcessedLine(conn, "file.csv");

        // then
        assertEquals(10, lastProcessedLine);
    }

    @Test
    void testGetLastProcessedLine_NotFound() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        // when
        int lastProcessedLine = database.getLastProcessedLine(conn, "file.csv");

        // then
        assertEquals(0, lastProcessedLine);
    }

    @Test
    void testGetLastProcessedLine_Exception() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenThrow(new SQLException("Test exception"));

        // when
        Exception exception = assertThrows(SQLException.class, () -> database.getLastProcessedLine(conn, "file.csv"));

        // then
        assertEquals("Test exception", exception.getMessage());
    }

    @Test
    void testUpdateLastProcessedLine() throws SQLException {
        // given
        Connection conn = mock(Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);

        when(conn.prepareStatement(anyString())).thenReturn(stmt);

        // when
        database.updateLastProcessedLine(conn, "file.csv", 20);

        // then
        verify(stmt).setString(1, "file.csv");
        verify(stmt).setInt(2, 20);
        verify(stmt).executeUpdate();
    }
}
