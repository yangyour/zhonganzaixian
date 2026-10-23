package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardAlertBellReader.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit existing-DEMO read-only verification against the designated PostgreSQL; no DDL, seed or lifecycle writes. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_ALERT_BELL_READ_TESTS", matches = "true")
class PostgresDashboardAlertBellReadTest {
    static PGSimpleDataSource source;
    static PostgresPlatformRepository platform;
    static PostgresDashboardAlertBellReader reader;
    static String actor;
    static String adminTenant;
    static String engineerTenant;
    static String customerTenant;

    @BeforeAll
    static void connect() throws Exception {
        assertEquals("EXISTING_DEMO_READ_ONLY", System.getenv("MOP_ALERT_BELL_TEST_SCOPE"));
        String jdbc = System.getenv("MOP_ALERT_BELL_TEST_JDBC");
        assertNotNull(jdbc);
        assertTrue(jdbc.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"));
        actor = uuid("MOP_ALERT_BELL_TEST_ACTOR");
        adminTenant = uuid("MOP_ALERT_BELL_ADMIN_TENANT");
        engineerTenant = uuid("MOP_ALERT_BELL_ENGINEER_TENANT");
        customerTenant = uuid("MOP_ALERT_BELL_CUSTOMER_TENANT");
        for (String tenant : List.of(adminTenant, engineerTenant, customerTenant)) {
            assertFalse(tenant.startsWith("55000000-0000-4000-8000-"));
        }
        source = new PGSimpleDataSource();
        source.setURL(jdbc);
        source.setUser("mop_test_runtime");
        source.setPassword(System.getenv("MOP_ALERT_BELL_RUNTIME_PASSWORD"));
        source.setLoginTimeout(10);
        platform = new PostgresPlatformRepository(source, new ObjectMapper().findAndRegisterModules());
        reader = new PostgresDashboardAlertBellReader(source, platform);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            connection.setReadOnly(true);
            try (var rows = statement.executeQuery("SELECT current_database(),current_user")) {
                assertTrue(rows.next());
                assertEquals("mop_test", rows.getString(1));
                assertEquals("mop_test_runtime", rows.getString(2));
            }
        }
        assertEquals("padmin", platform.membership(actor, adminTenant).orElseThrow().role());
        assertEquals("msp", platform.membership(actor, engineerTenant).orElseThrow().role());
        assertEquals("tadmin", platform.membership(actor, customerTenant).orElseThrow().role());
    }

    static String uuid(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name);
        assertEquals(UUID.fromString(value).toString(), value);
        return value;
    }

    static Access access(String tenant) {
        return new Access(actor, tenant, platform.membership(actor, tenant).orElseThrow().permissionVersion());
    }

    @Test
    void realInternalScopesMatchTheExactPredicateAndNeverContainOtherTenantRows() throws Exception {
        for (String tenant : List.of(adminTenant, engineerTenant)) {
            var result = reader.read(access(tenant));
            assertEquals(tenant, result.tenantId().toString());
            assertEquals(actor, result.actorId().toString());
            List<UUID> expected = new ArrayList<>();
            long total = 0;
            try (Connection connection = source.getConnection()) {
                connection.setReadOnly(true);
                try (var statement = connection.prepareStatement("SELECT id,monitor_state,handling_state FROM public.mop_monitoring_event "
                        + "WHERE tenant_id=?::uuid ORDER BY last_occurred_at DESC,id DESC")) {
                    statement.setQueryTimeout(10);
                    statement.setString(1, tenant);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if ("PROBLEM".equals(rows.getString(2)) && "NEW".equals(rows.getString(3))) {
                                total++;
                                if (expected.size() < 5) {
                                    expected.add(UUID.fromString(rows.getString(1)));
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(total, result.total());
            assertEquals(expected, result.items().stream().map(item -> item.eventId()).toList());
        }
    }

    @Test
    void actualCustomerRoleCannotReadTheInternalBell() {
        assertEquals(403, assertThrows(DomainException.class, () -> reader.read(access(customerTenant))).status);
    }

    @Test
    void changedPermissionVersionAndForeignMembershipAreRejected() {
        var current = access(adminTenant);
        assertEquals(401, assertThrows(DomainException.class, () -> reader.read(new Access(
                current.actorId(), current.tenantId(), current.permissionVersion() + 1))).status);
        assertEquals(401, assertThrows(DomainException.class, () -> reader.read(new Access(
                actor, UUID.randomUUID().toString(), 1))).status);
    }
}
