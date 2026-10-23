package cn.mop.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only designated PostgreSQL observation. No tenants, roles, grants, schema or data are created. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_RUNTIME_GUARD_PG_TESTS", matches = "true")
class RuntimeDatabaseGuardPostgresReadTest {
    @Test
    void actualRestrictedRuntimeMeetsStartupGuardAndRemainsReadOnly() throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        assertEquals("mop_test_runtime", System.getenv("SPRING_DATASOURCE_USERNAME"));
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5000);
        config.setReadOnly(true);
        try (var pool = RuntimeDataSources.openRestrictedTest(config); var connection = pool.getConnection()) {
            assertTrue(connection.isReadOnly());
            var observation = RuntimeDatabaseGuard.inspect(connection);
            assertTrue(observation.accepted(), "Designated runtime exceeds the documented privilege boundary");
            assertNotNull(observation.inspectedAt());
            assertTrue(observation.serverVersion().startsWith("17."));
            RuntimeDatabaseGuard.requireRestrictedTestConnection(connection);
            assertTrue(connection.isReadOnly());
        }
    }
}
