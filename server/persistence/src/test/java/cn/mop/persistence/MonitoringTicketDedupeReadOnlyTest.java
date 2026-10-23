package cn.mop.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PostgreSQL syntax/configuration reads only. No fixture rows, namespace or identity is created. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_MONITORING_DEDUPE_READ_TESTS", matches = "true")
class MonitoringTicketDedupeReadOnlyTest {
    @Test
    void runtimeRoleCanReadDefaultAndExecuteEmptyScopedCandidateQuery() throws Exception {
        HikariConfig settings = new HikariConfig();
        settings.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));
        settings.setUsername("mop_test_runtime");
        settings.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        settings.setMaximumPoolSize(1);
        settings.setMinimumIdle(0);
        settings.setConnectionTimeout(5000);
        try (var pool = new HikariDataSource(settings); Connection c = pool.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                try (var query = c.prepareStatement("SELECT current_database(),current_user,current_setting('transaction_read_only')");
                     var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("mop_test", rows.getString(1));
                    assertEquals("mop_test_runtime", rows.getString(2));
                    assertEquals("on", rows.getString(3));
                }
                String tenant = UUID.randomUUID().toString();
                try (var query = c.prepareStatement("SELECT count(*) FROM public.mop_tenant WHERE id=?::uuid")) {
                    query.setString(1, tenant);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1), "Read probe must use an absent scope");
                    }
                }
                Instant now = Instant.now();
                assertEquals(1800, MonitoringTicketDedupe.policy(c, tenant, now).seconds());
                assertTrue(MonitoringTicketDedupe.find(c, tenant, UUID.randomUUID().toString(),
                    "FP_V1|" + tenant + "|READ_ONLY_ABSENT_SCOPE", now.minusSeconds(3600), now).isEmpty());
            } finally {
                c.rollback();
            }
        }
    }
}
