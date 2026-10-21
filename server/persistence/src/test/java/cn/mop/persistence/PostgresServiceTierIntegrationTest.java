package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.domain.sla.ServiceTierRepository.*;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.WorkflowLifecycle.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated-PG verification for V35 service tiers, append-only change history, and M2-10/M5-8/M5-20 in-flight ticket SLA immutability. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_SERVICE_TIER_TESTS", matches = "true")
class PostgresServiceTierIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository platform;
    static PostgresServiceTierRepository tiers;
    static PostgresTicketWorkflowRepository workflow;
    static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    static final String TENANT = "55000000-0000-4000-8000-000000000001";
    static String padmin, msp, viewer;
    static long padminVersion, mspVersion, viewerVersion;

    @BeforeAll static void connect() {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));
        cfg.setUsername("mop_test_runtime");
        cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        cfg.setMaximumPoolSize(6);
        pool = new HikariDataSource(cfg);
        platform = new PostgresPlatformRepository(pool, mapper);
        tiers = new PostgresServiceTierRepository(pool, platform);
        workflow = new PostgresTicketWorkflowRepository(pool, platform, mapper);
        assertEquals(List.of(), tiers.readinessProblems());
        assertEquals(List.of(), workflow.readinessProblems());
        padmin = platform.users().stream().filter(u -> "padmin".equals(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        msp = platform.users().stream().filter(u -> "msp".equals(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        viewer = platform.users().stream().filter(u -> Set.of("viewer", "tadmin").contains(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        padminVersion = platform.membership(padmin, TENANT).orElseThrow().permissionVersion();
        mspVersion = platform.membership(msp, TENANT).orElseThrow().permissionVersion();
        viewerVersion = platform.membership(viewer, TENANT).orElseThrow().permissionVersion();
    }

    @AfterAll static void close() { if (pool != null) pool.close(); }

    static ServiceTierRepository.Access stAccess(String actor, long version) {
        return new ServiceTierRepository.Access(TENANT, actor, version, UUID.randomUUID().toString());
    }
    static TicketWorkflowRepository.Access wfAccess(String actor, long version) {
        return new TicketWorkflowRepository.Access(TENANT, actor, version, UUID.randomUUID().toString());
    }

    static String registerTicket() {
        String key = "service-tier-it-" + UUID.randomUUID(), text = "SYNTHETIC_D16_TIER_" + UUID.randomUUID();
        var report = new CustomerReport("D16 synthetic ticket", text, "其他", "individual", "Synthetic reporter", "13800138000", "normal", null);
        return ((CustomerReportRegistration) platform.idempotentAuthorized(viewer, TENANT, "POST /integration/tier-ticket-register", key, "a".repeat(64), viewerVersion, () -> {}, () -> platform.registerCustomerReport(TENANT, viewer, report))).ticketId();
    }

    static ServiceTierRepository.Snapshot changeTier(ServiceTierRepository.Access a, Command cmd, BigDecimal avail) {
        return (ServiceTierRepository.Snapshot) platform.idempotentAuthorized(
            a.actorId(), a.tenantId(), "POST /integration/service-tiers/changes/" + cmd.expectedVersion(),
            UUID.randomUUID().toString(), "b".repeat(64), a.permissionVersion(),
            () -> tiers.authorizeWrite(a), () -> tiers.applyChange(a, cmd, avail));
    }

    @Test void tierChangesAreAppendOnlyAndThreeInFlightTicketsKeepFrozenSlaDeadlines_M2_10() {
        var admin = stAccess(padmin, padminVersion);
        long baseVersion = tiers.current(admin).map(ServiceTierRepository.Snapshot::version).orElse(0L);
        var setBasic = changeTier(admin, new Command(baseVersion, Tier.BASIC, EffectiveMode.IMMEDIATE, 40, BillingCycle.MONTHLY, 1200000L, "SYNTHETIC_D16_INIT_BASIC"), new BigDecimal("99.50"));
        assertEquals(baseVersion + 1, setBasic.version());
        assertEquals(Tier.BASIC, setBasic.tier());

        // Open 3 in-flight tickets under BASIC tier (OPEN, ACCEPTED, RESOLVED)
        var wfInternal = wfAccess(msp, mspVersion);
        var wfCustomer = wfAccess(viewer, viewerVersion);
        String t1 = registerTicket(), t2 = registerTicket(), t3 = registerTicket();
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/open/" + t1, UUID.randomUUID().toString(), "c".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t1, false),
            () -> workflow.activate(wfInternal, t1, 0, "P1", Tier.BASIC, "SYNTHETIC_D16_OPEN_1",
                (x, p, t, s) -> TicketSlaSnapshot.freeze("P1", Tier.BASIC, s, Duration.ofMinutes(30), Duration.ofHours(4), null, 48L, null, List.of(), Map.of("response", "CALENDAR_UNCONFIGURED"))));
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/open/" + t2, UUID.randomUUID().toString(), "c".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t2, false),
            () -> workflow.activate(wfInternal, t2, 0, "P2", Tier.STANDARD, "SYNTHETIC_D16_OPEN_2",
                (x, p, t, s) -> TicketSlaSnapshot.freeze("P2", Tier.STANDARD, s, Duration.ofMinutes(30), Duration.ofHours(4), null, 48L, null, List.of(), Map.of())));
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/accept/" + t2, UUID.randomUUID().toString(), "d".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t2, false),
            () -> workflow.transition(wfInternal, t2, new WorkflowLifecycle.Command(Action.ACCEPT, 1, null, "accept", null, false)));
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/open/" + t3, UUID.randomUUID().toString(), "c".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t3, false),
            () -> workflow.activate(wfInternal, t3, 0, "P1", Tier.STANDARD, "SYNTHETIC_D16_OPEN_3",
                (x, p, t, s) -> TicketSlaSnapshot.freeze("P1", Tier.STANDARD, s, Duration.ofMinutes(10), Duration.ofHours(2), null, 48L, null, List.of(), Map.of())));
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/accept/" + t3, UUID.randomUUID().toString(), "d".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t3, false),
            () -> workflow.transition(wfInternal, t3, new WorkflowLifecycle.Command(Action.ACCEPT, 1, null, "accept", null, false)));
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/start/" + t3, UUID.randomUUID().toString(), "e".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t3, false),
            () -> workflow.transition(wfInternal, t3, new WorkflowLifecycle.Command(Action.START, 2, null, "start", null, false)));
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/d16/resolve/" + t3, UUID.randomUUID().toString(), "f".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, t3, false),
            () -> workflow.transition(wfInternal, t3, new WorkflowLifecycle.Command(Action.RESOLVE, 3, null, "已完成核心交换机双上联切换与丢包验证，请客户确认。", null, false)));

        var before1 = workflow.readSla(wfInternal, t1, Tier.BASIC);
        var before2 = workflow.readSla(wfInternal, t2, Tier.STANDARD);
        var before3 = workflow.readSla(wfInternal, t3, Tier.STANDARD);

        // Upgrade tenant tier to ADVANCED (next month first day)
        var setAdvanced = changeTier(admin, new Command(setBasic.version(), Tier.ADVANCED, EffectiveMode.NEXT_MONTH_FIRST_DAY, 60, BillingCycle.QUARTERLY, 2800000L, "SYNTHETIC_D16_UPGRADE_ADVANCED"), new BigDecimal("99.99"));
        assertEquals(setBasic.version() + 1, setAdvanced.version());
        assertEquals(Tier.ADVANCED, setAdvanced.tier());

        // Verify all 3 in-flight tickets retain their frozen SLA deadlines and report tier_changed_since_open=true
        var after1 = workflow.readSla(wfInternal, t1, setAdvanced.tier());
        var after2 = workflow.readSla(wfInternal, t2, setAdvanced.tier());
        var after3 = workflow.readSla(wfInternal, t3, setAdvanced.tier());
        assertEquals("BASIC", after1.get("frozen_service_tier"));
        assertEquals("ADVANCED", after1.get("current_tenant_service_tier"));
        assertEquals(Boolean.TRUE, after1.get("tier_changed_since_open"));
        assertEquals(before1.get("response_clock"), after1.get("response_clock"));
        assertEquals(before2.get("recovery_clock"), after2.get("recovery_clock"));
        assertEquals(before3.get("recovery_clock"), after3.get("recovery_clock"));
        assertEquals("PAUSED_FOR_CUSTOMER_CONFIRM", ((Map<?, ?>) after3.get("recovery_clock")).get("status"));

        // Customer SLA view hides internal clock_tasks and sla_references
        var custSla = workflow.readSla(wfCustomer, t3, setAdvanced.tier());
        assertEquals(List.of(), custSla.get("clock_tasks"));
        assertEquals(List.of(), custSla.get("sla_references"));

        // Stale version rejected with 409; non-padmin rejected with 403
        assertEquals(409, assertThrows(DomainException.class, () -> changeTier(admin, new Command(setBasic.version(), Tier.STANDARD, EffectiveMode.IMMEDIATE, null, null, null, "stale"), null)).status);
        assertEquals(403, assertThrows(DomainException.class, () -> changeTier(stAccess(msp, mspVersion), new Command(setAdvanced.version(), Tier.STANDARD, EffectiveMode.IMMEDIATE, null, null, null, "msp forbidden"), null)).status);
        assertEquals(403, assertThrows(DomainException.class, () -> tiers.history(stAccess(viewer, viewerVersion), 10, null)).status);

        var page = tiers.history(stAccess(msp, mspVersion), 10, null);
        assertTrue(page.current().isPresent());
        assertEquals(setAdvanced.version(), page.current().get().version());
        assertTrue(page.changes().size() >= 2);
        assertEquals(Tier.ADVANCED, page.changes().get(0).toTier());
        assertEquals(Tier.BASIC, page.changes().get(0).fromTier());
    }

    @Test void dbGuardsRejectHistoryMutationDeleteAndUnsynchronizedWrites() {
        var admin = stAccess(padmin, padminVersion);
        if (tiers.current(admin).isEmpty()) {
            changeTier(admin, new Command(0L, Tier.STANDARD, EffectiveMode.IMMEDIATE, 20, BillingCycle.MONTHLY, 800000L, "SYNTHETIC_D16_GUARD_SEED"), new BigDecimal("99.90"));
        }
        assertThrows(SQLException.class, () -> exec("UPDATE public.mop_service_tier_change SET reason='tampered' WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_service_tier_change WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_service_tier WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("UPDATE public.mop_service_tier SET version=version+1,updated_at=clock_timestamp() WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec(
            "INSERT INTO public.mop_service_tier_change(id,tenant_id,version,previous_version,from_tier,to_tier,effective_mode,effective_from,reason,approved_by,changed_at,customer_approval_status) "
            + "SELECT gen_random_uuid(),tenant_id,version+1,version,tier,'BASIC','IMMEDIATE',clock_timestamp(),'orphan',?::uuid,clock_timestamp(),'UNVERIFIED' FROM public.mop_service_tier WHERE tenant_id=?::uuid",
            padmin, TENANT));
    }

    static void exec(String sql, String... args) throws SQLException {
        try (Connection c = pool.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setString(i + 1, args[i]);
            s.executeUpdate();
        }
    }
}
