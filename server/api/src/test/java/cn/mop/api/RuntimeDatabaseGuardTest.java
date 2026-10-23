package cn.mop.api;

import cn.mop.persistence.RuntimeDatabaseGuard;
import cn.mop.persistence.RuntimeDataSources;
import com.zaxxer.hikari.HikariConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runtime-only guards extracted from the deferred diagnostic UI suite for an independently verifiable commit. */
class RuntimeDatabaseGuardTest {
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet rows;

    @BeforeEach
    void setup() throws Exception {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(anyInt())).thenReturn(true);
        when(rows.getString(6)).thenReturn("SYNTHETIC_UNIT_PG");
        when(rows.getTimestamp(7)).thenReturn(Timestamp.from(Instant.parse("2026-10-23T12:00:00Z")));
    }

    @Test
    void everyEffectiveRoleBoundaryIsRequired() throws Exception {
        assertTrue(RuntimeDatabaseGuard.inspect(connection).accepted());
        assertDoesNotThrow(() -> RuntimeDatabaseGuard.requireRestrictedTestConnection(connection));
        for (int index = 1; index <= 5; index++) {
            when(rows.getBoolean(index)).thenReturn(false);
            assertFalse(RuntimeDatabaseGuard.inspect(connection).accepted());
            assertThrows(IllegalStateException.class, () -> RuntimeDatabaseGuard.requireRestrictedTestConnection(connection));
            when(rows.getBoolean(index)).thenReturn(true);
        }
        verify(statement, atLeastOnce()).setQueryTimeout(3);
    }

    @Test
    void missingRoleObservationCannotPass() throws Exception {
        when(rows.next()).thenReturn(false);
        assertThrows(SQLException.class, () -> RuntimeDatabaseGuard.requireRestrictedTestConnection(connection));
    }

    @Test
    void administrativeRolesRemoteTargetsAndUrlOptionsAreRejectedBeforeOpeningPool() {
        var configuration = new HikariConfig();
        configuration.setJdbcUrl("jdbc:postgresql://127.0.0.1:25432/mop_test");
        configuration.setUsername("mop_test_runtime");
        configuration.setPassword("SYNTHETIC_UNIT_SECRET");
        assertDoesNotThrow(() -> RuntimeDataSources.validateConfiguration(configuration));
        for (String user : List.of("postgres", "mop_test_migrator")) {
            configuration.setUsername(user);
            assertThrows(IllegalArgumentException.class, () -> RuntimeDataSources.validateConfiguration(configuration));
        }
        configuration.setUsername("mop_test_runtime");
        for (String url : List.of("jdbc:postgresql://example.test/mop_test", "jdbc:postgresql://127.0.0.1:0/mop_test",
            "jdbc:postgresql://127.0.0.1:65536/mop_test", "jdbc:postgresql://127.0.0.1:25432/another",
            "jdbc:postgresql://127.0.0.1:25432/mop_test?password=SYNTHETIC_UNIT_SECRET")) {
            configuration.setJdbcUrl(url);
            var rejected = assertThrows(IllegalArgumentException.class, () -> RuntimeDataSources.validateConfiguration(configuration));
            assertFalse(rejected.getMessage().contains("SYNTHETIC_UNIT_SECRET"));
        }
    }
}
