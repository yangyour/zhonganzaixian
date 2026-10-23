package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.RepositoryMetadata;
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
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure JDBC and authorized-controller failure tests; actual role observations are tested separately on designated PG. */
class ProductionReadinessTest {
    private PlatformRepository platform;
    private DataSource source;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet rows;
    private ProductionReadinessController controller;

    @BeforeEach
    void setup() throws Exception {
        platform = mock(PlatformRepository.class);
        source = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        rows = mock(ResultSet.class);
        when(platform.membership("unit-actor", "unit-tenant"))
            .thenReturn(Optional.of(new Membership("unit-actor", "unit-tenant", "padmin", 8, true)));
        when(platform.metadata()).thenReturn(new RepositoryMetadata("remote_test", "postgresql", true, "project_local"));
        doReturn(connection).when(source).getConnection();
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(anyInt())).thenReturn(true);
        when(rows.getString(6)).thenReturn("SYNTHETIC_UNIT_PG");
        when(rows.getTimestamp(7)).thenReturn(Timestamp.from(Instant.parse("2026-10-23T12:00:00Z")));
        controller = new ProductionReadinessController(platform, source, new MockEnvironment());
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/operations/production-readiness");
        request.getSession(true).setAttribute("mop.tenant_id", "unit-tenant");
        request.setUserPrincipal(() -> "unit-actor");
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Object value) {
        return (Map<String, Object>) ((Map<?, ?>) value).get("data");
    }

    @Test
    void allRequiredRoleRestrictionsMustPass() throws Exception {
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
    void unobservableRoleNeverPassesStartupGuard() throws Exception {
        when(rows.next()).thenReturn(false);
        assertThrows(SQLException.class, () -> RuntimeDatabaseGuard.requireRestrictedTestConnection(connection));
    }

    @Test
    void acceptedTestRuntimeDoesNotApproveProductionOrChooseQ02() {
        Map<String, Object> result = data(controller.inspect(request()));
        assertEquals(true, result.get("runtime_database_restricted"));
        assertEquals(false, result.get("production_ready"));
        assertEquals(false, result.get("activation_performed"));
        assertNull(result.get("production_database_kind"));
        assertNull(result.get("production_isolation_strategy"));
        assertTrue(result.toString().contains("Q02_PENDING"));
        assertTrue(result.toString().contains("PUBLIC_ENTRY_PROBE_REQUIRED"));
    }

    @Test
    void noSessionOrConfigurationCapabilityCannotOpenProbeConnection() {
        var anonymous = request();
        anonymous.setUserPrincipal(null);
        assertEquals(401, assertThrows(DomainException.class, () -> controller.inspect(anonymous)).status);
        when(platform.membership("unit-actor", "unit-tenant"))
            .thenReturn(Optional.of(new Membership("unit-actor", "unit-tenant", "viewer", 8, true)));
        assertEquals(403, assertThrows(DomainException.class, () -> controller.inspect(request())).status);
        verifyNoInteractions(source);
    }

    @Test
    void scopeAndConnectionParametersCannotBeSuppliedByCaller() {
        var request = request();
        request.setQueryString("tenant_id=another&url=private");
        assertEquals(400, assertThrows(DomainException.class, () -> controller.inspect(request)).status);
        verifyNoInteractions(source);
    }

    @Test
    void disconnectAndConnectionCloseFailureKeepObservationUnknownAndRedacted() throws Exception {
        when(source.getConnection()).thenThrow(new SQLException("SYNTHETIC_PRIVATE_CONNECTION"));
        Map<String, Object> unavailable = data(controller.inspect(request()));
        assertEquals(false, unavailable.get("runtime_database_restricted"));
        assertNull(unavailable.get("database_observed_at"));
        assertFalse(unavailable.toString().contains("SYNTHETIC_PRIVATE_CONNECTION"));
        doReturn(connection).when(source).getConnection();
        doThrow(new SQLException("SYNTHETIC_CLOSE_FAILURE")).when(connection).close();
        Map<String, Object> closeFailure = data(controller.inspect(request()));
        assertEquals(false, closeFailure.get("runtime_database_restricted"));
        assertNull(closeFailure.get("database_version"));
        assertTrue(closeFailure.toString().contains("DATABASE_OBSERVATION_UNAVAILABLE"));
    }

    @Test
    void privilegedRoleIsReportedBlockedWithoutEchoingRoleNamesOrSecrets() throws Exception {
        when(rows.getBoolean(2)).thenReturn(false);
        Map<String, Object> result = data(controller.inspect(request()));
        assertEquals(false, result.get("runtime_database_restricted"));
        assertTrue(result.toString().contains("ELEVATED_ROLE_REJECTED"));
        assertFalse(result.containsKey("password"));
        assertFalse(result.containsKey("jdbc_url"));
    }

    @Test
    void runtimeFactoryRejectsAdminRemoteCredentialsAndUrlOptionsBeforePoolCreation() {
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
