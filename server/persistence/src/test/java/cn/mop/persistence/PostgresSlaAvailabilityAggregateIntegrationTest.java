package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.sla.OnCallEscalationRepository.Access;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Designated V55 PG reads in fresh runner-owned tenants; no health observations or real customer data are fabricated. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_SLA_AVAILABILITY_AGGREGATE_PG_TESTS", matches = "true")
class PostgresSlaAvailabilityAggregateIntegrationTest {

    static HikariDataSource source;
    static PostgresPlatformRepository platform;
    static PostgresOnCallEscalationRepository reports;
    static final String TENANT = System.getenv("MOP_REPORT_CAPTURE_TENANT");
    static final String FOREIGN = System.getenv("MOP_REPORT_CAPTURE_FOREIGN_TENANT");
    static final String ACTOR = System.getenv("MOP_REPORT_CAPTURE_ACTOR");
    static final String ASSET = System.getenv("MOP_REPORT_CAPTURE_ASSET");

    @BeforeAll
    static void connect() throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"));
        assertNotEquals("55000000-0000-4000-8000-000000000001", TENANT);
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(2);
        source = new HikariDataSource(config);
        try (
            var connection = source.getConnection();
            var statement = connection.prepareStatement(
                "SELECT current_database(),current_user,u.identity_source,u.subject,t.name " +
                    "FROM public.mop_user u JOIN public.mop_membership m ON m.user_id=u.id " +
                    "JOIN public.mop_tenant t ON t.id=m.tenant_id WHERE u.id=?::uuid AND t.id=?::uuid"
            )
        ) {
            statement.setString(1, ACTOR);
            statement.setString(2, TENANT);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("mop_test", rows.getString(1));
                assertEquals("mop_test_runtime", rows.getString(2));
                assertEquals("isolated_report_capture", rows.getString(3));
                assertEquals("SYNTHETIC_REPORT_CAPTURE_" + ACTOR, rows.getString(4));
                assertEquals("SYNTHETIC_REPORT_CAPTURE_" + ACTOR + "_0", rows.getString(5));
                assertFalse(rows.next());
            }
        }
        platform = new PostgresPlatformRepository(source, new ObjectMapper().findAndRegisterModules());
        reports = new PostgresOnCallEscalationRepository(source, platform);
    }

    @AfterAll
    static void close() {
        if (source != null) {
            source.close();
        }
    }

    Access access(String tenant) {
        return new Access(
            tenant,
            ACTOR,
            platform.membership(ACTOR, tenant).orElseThrow().permissionVersion(),
            "availability-pg"
        );
    }

    @Test
    void noHealthSourceProducesExplicitInsufficientCoverageAndNullPercentage() {
        String month = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        var report = reports.slaReport(access(TENANT), month);
        assertEquals(1, report.services().size());
        assertEquals("ASSET_" + ASSET, report.services().get(0).serviceId());
        assertNull(report.meanVerifiedAvailabilityPct());
        assertNotNull(report.availabilityAggregate());
        assertEquals(0, report.availabilityAggregate().knownAvailableMillis());
        assertEquals(0, report.availabilityAggregate().effectiveServiceMillis());
        assertEquals(1, report.availabilityAggregate().serviceCount());
        assertEquals(0, report.availabilityAggregate().qualifiedServiceCount());
        assertEquals("INSUFFICIENT_COVERAGE", report.availabilityAggregate().coverageStatus());
        assertTrue(report.monthlyResult().isEmpty());
    }

    @Test
    void otherTenantReadUsesItsOwnServicePopulation() {
        var report = reports.slaReport(access(FOREIGN), YearMonth.now(ZoneId.of("Asia/Shanghai")).toString());
        assertEquals(FOREIGN, report.tenantId());
        assertEquals(1, report.services().size());
        assertNotEquals("ASSET_" + ASSET, report.services().get(0).serviceId());
        assertEquals(1, report.availabilityAggregate().serviceCount());
    }

    @Test
    void absentMembershipNeverReturnsAnotherScopeAsAnEmptySuccessfulReport() {
        var missing = new Access(TENANT, UUID.randomUUID().toString(), 1, "availability-pg-denied");
        assertEquals(
            401,
            assertThrows(DomainException.class, () ->
                reports.slaReport(missing, YearMonth.now(ZoneId.of("Asia/Shanghai")).toString())
            ).status
        );
    }
}
