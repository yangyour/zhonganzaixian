package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.sla.OnCallEscalationRepository;
import cn.mop.domain.sla.OnCallEscalationRepository.*;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real designated-PG verification for V36 On-Call Roster, Append-Only Handoffs,
 * Configurable Escalation Policies/Step Runs, and SLA Monthly Results (M5-9, M5-10, M5-12, M5-13, M5-19).
 */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_ONCALL_ESCALATION_TESTS", matches = "true")
class PostgresOnCallEscalationIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository platform;
    static PostgresOnCallEscalationRepository oncall;
    static PostgresTicketWorkflowRepository workflow;
    static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
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
        oncall = new PostgresOnCallEscalationRepository(pool, platform);
        workflow = new PostgresTicketWorkflowRepository(pool, platform, mapper);
        assertEquals(List.of(), oncall.readinessProblems());
        padmin = platform.users().stream().filter(u -> "padmin".equals(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        msp = platform.users().stream().filter(u -> "msp".equals(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        viewer = platform.users().stream().filter(u -> Set.of("viewer", "tadmin").contains(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        padminVersion = platform.membership(padmin, TENANT).orElseThrow().permissionVersion();
        mspVersion = platform.membership(msp, TENANT).orElseThrow().permissionVersion();
        viewerVersion = platform.membership(viewer, TENANT).orElseThrow().permissionVersion();
    }

    @AfterAll static void close() { if (pool != null) pool.close(); }

    static OnCallEscalationRepository.Access ocAccess(String actor, long version) {
        return new OnCallEscalationRepository.Access(TENANT, actor, version, UUID.randomUUID().toString());
    }
    static TicketWorkflowRepository.Access wfAccess(String actor, long version) {
        return new TicketWorkflowRepository.Access(TENANT, actor, version, UUID.randomUUID().toString());
    }

    static String registerAndOpenTicket() {
        String key = "oncall-it-" + UUID.randomUUID(), text = "SYNTHETIC_D16_ONCALL_" + UUID.randomUUID();
        var report = new CustomerReport("D16 oncall ticket", text, "其他", "individual", "Synthetic reporter", "13800138000", "normal", null);
        String ticketId = ((CustomerReportRegistration) platform.idempotentAuthorized(viewer, TENANT, "POST /integration/oncall-ticket-register", key, "a".repeat(64), viewerVersion, () -> {}, () -> platform.registerCustomerReport(TENANT, viewer, report))).ticketId();
        var wfInternal = wfAccess(msp, mspVersion);
        platform.idempotentAuthorized(msp, TENANT, "POST /integration/oncall/open/" + ticketId, UUID.randomUUID().toString(), "b".repeat(64), mspVersion,
            () -> workflow.authorizeCommand(wfInternal, ticketId, false),
            () -> workflow.activate(wfInternal, ticketId, 0, "P1", Tier.STANDARD, "SYNTHETIC_D16_ONCALL_OPEN",
                (x, p, t, s) -> TicketSlaSnapshot.freeze("P1", Tier.STANDARD, s, Duration.ofMinutes(10), Duration.ofHours(2), null, 48L, null, List.of(), Map.of())));
        return ticketId;
    }

    @Test void onCallBoardHandoffCountsEscalationPolicyModificationAndTwoUnansweredCalls_M5_12_M5_13() {
        String ticketId = registerAndOpenTicket();
        var mspAcc = ocAccess(msp, mspVersion);
        var adminAcc = ocAccess(padmin, padminVersion);

        var boardBefore = oncall.board(mspAcc, 25);
        LocalDate shiftDate = LocalDate.of(2026, 10, 18);
        long rosterExpected = boardBefore.rosters().stream()
            .filter(r -> r.shiftDate().equals(shiftDate) && r.shiftCode() == ShiftCode.DAY)
            .map(RosterEntry::version).findFirst().orElse(0L);

        RosterEntry roster = (RosterEntry) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/oncall/rosters", UUID.randomUUID().toString(), "c".repeat(64), mspVersion,
            () -> oncall.authorizeWrite(mspAcc),
            () -> oncall.upsertRoster(mspAcc, new RosterCommand(rosterExpected, shiftDate, ShiftCode.DAY, msp, padmin, "SYNTHETIC_D16_DAY_ROSTER")));
        assertEquals(rosterExpected + 1, roster.version());
        assertEquals("18:00", roster.handoffLocalTime());

        // Record shift handoff from msp to padmin; verify live queue counts match board KPIs (M5-12)
        HandoffEntry handoff = (HandoffEntry) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/oncall/handoffs", UUID.randomUUID().toString(), "d".repeat(64), mspVersion,
            () -> oncall.authorizeWrite(mspAcc),
            () -> oncall.recordHandoff(mspAcc, new HandoffCommand(roster.id(), padmin, "SYNTHETIC_D16_1800_HANDOFF")));
        var boardAfter = oncall.board(mspAcc, 25);
        assertEquals(boardAfter.kpis().unacknowledgedCount(), handoff.unacknowledgedCount());
        assertEquals(boardAfter.kpis().inProgressCount(), handoff.inProgressCount());
        assertEquals(boardAfter.kpis().pendingReviewCount(), handoff.pendingReviewCount());

        // Trigger COUNTDOWN_ZERO escalation at step 1 (M5-12)
        EscalationStepRun zeroRun = (EscalationStepRun) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/oncall/escalations/zero", UUID.randomUUID().toString(), "e".repeat(64), mspVersion,
            () -> oncall.authorizeWrite(mspAcc),
            () -> oncall.triggerEscalation(mspAcc, new EscalationTriggerCommand(ticketId, PolicyTier.STANDARD, 1, TriggerCondition.COUNTDOWN_ZERO, 0)));
        assertEquals(2, zeroRun.escalatedToStep());

        // Modify STANDARD escalation policy so step 2 is DUTY_LEAD and step 3 is SERVICE_MANAGER (M5-13)
        long stdVer = oncall.policies(adminAcc).stream().filter(p -> p.tier() == PolicyTier.STANDARD && p.persisted()).map(EscalationPolicy::version).findFirst().orElse(0L);
        List<EscalationStep> updatedSteps = List.of(
            new EscalationStep(1, TriggerCondition.IMMEDIATE, 0, TargetRole.L1_ONCALL),
            new EscalationStep(2, TriggerCondition.UNACKNOWLEDGED, 15, TargetRole.DUTY_LEAD),
            new EscalationStep(3, TriggerCondition.UNRESOLVED, 120, TargetRole.SERVICE_MANAGER)
        );
        EscalationPolicy updatedPolicy = (EscalationPolicy) platform.idempotentAuthorized(
            padmin, TENANT, "PATCH /integration/escalation-policies", UUID.randomUUID().toString(), "f".repeat(64), padminVersion,
            () -> {},
            () -> oncall.updatePolicy(adminAcc, new PolicyUpdateCommand(PolicyTier.STANDARD, stdVer, 2, updatedSteps)));
        assertEquals(stdVer + 1, updatedPolicy.version());
        assertEquals(3, updatedPolicy.steps().size());

        // Verify 1 unanswered phone call does NOT escalate, while 2 unanswered calls escalate to DUTY_LEAD with SMS record (M5-13)
        EscalationStepRun oneCall = (EscalationStepRun) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/oncall/escalations/phone1", UUID.randomUUID().toString(), "1".repeat(64), mspVersion,
            () -> oncall.authorizeWrite(mspAcc),
            () -> oncall.triggerEscalation(mspAcc, new EscalationTriggerCommand(ticketId, PolicyTier.STANDARD, 1, TriggerCondition.PHONE_UNANSWERED, 1)));
        assertNull(oneCall.escalatedToStep());
        assertEquals(SmsNoticeStatus.NOT_REQUIRED, oneCall.smsNoticeStatus());

        EscalationStepRun twoCalls = (EscalationStepRun) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/oncall/escalations/phone2", UUID.randomUUID().toString(), "2".repeat(64), mspVersion,
            () -> oncall.authorizeWrite(mspAcc),
            () -> oncall.triggerEscalation(mspAcc, new EscalationTriggerCommand(ticketId, PolicyTier.STANDARD, 1, TriggerCondition.PHONE_UNANSWERED, 2)));
        assertEquals(2, twoCalls.escalatedToStep());
        assertEquals(TargetRole.DUTY_LEAD, twoCalls.escalatedTargetRole());
        assertEquals(SmsNoticeStatus.INTENT_RECORDED_PENDING_GATEWAY, twoCalls.smsNoticeStatus());
        assertNotNull(twoCalls.smsNoticeSummary());
    }

    @Test void slaReportMonthlyResultFreezeAndImmutabilityGuards_M5_9_M5_10_M5_19() {
        var mspAcc = ocAccess(msp, mspVersion);
        String currentPeriod = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();
        SlaReportView report = oncall.slaReport(mspAcc, currentPeriod);
        assertEquals(TENANT, report.tenantId());
        assertEquals(2, report.services().size());
        assertTrue(report.liveMonthlyEvaluation().totalTicketCount() >= 1);

        // Pick an un-frozen historical month or evaluate and verify DB guards
        int randomYear = 2000 + new Random().nextInt(20);
        int randomMonth = 1 + new Random().nextInt(12);
        String testMonth = String.format("%04d-%02d", randomYear, randomMonth);
        var existing = oncall.slaReport(mspAcc, testMonth).monthlyResult();
        if (existing.isEmpty()) {
            MonthlyResultRecord frozenRec = (MonthlyResultRecord) platform.idempotentAuthorized(
                msp, TENANT, "POST /integration/sla/monthly-results/" + testMonth, UUID.randomUUID().toString(), "3".repeat(64), mspVersion,
                () -> oncall.authorizeWrite(mspAcc),
                () -> oncall.evaluateMonthlyResult(mspAcc, new MonthlyResultCommand(testMonth, 0L, true)));
            assertTrue(frozenRec.frozen());
            assertNotNull(frozenRec.frozenAt());
            assertEquals("CONFIG_PENDING_Q26", frozenRec.compensationStatus());
            assertNull(frozenRec.compensationAmountFen());

            // Attempting to overwrite a frozen monthly result is rejected with 409 (M5-10)
            assertEquals(409, assertThrows(DomainException.class, () -> platform.idempotentAuthorized(
                msp, TENANT, "POST /integration/sla/monthly-results/overwrite/" + testMonth, UUID.randomUUID().toString(), "4".repeat(64), mspVersion,
                () -> oncall.authorizeWrite(mspAcc),
                () -> oncall.evaluateMonthlyResult(mspAcc, new MonthlyResultCommand(testMonth, 1L, false)))).status);
        }

        // Verify DB triggers and ACLs block direct mutation/deletion of append-only and frozen tables
        assertThrows(SQLException.class, () -> exec("UPDATE public.mop_oncall_handoff SET note='tampered' WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_oncall_handoff WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("UPDATE public.mop_escalation_step_run SET wait_minutes=999 WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_escalation_step_run WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_sla_monthly_result WHERE tenant_id=?::uuid", TENANT));
    }

    static void exec(String sql, String... args) throws SQLException {
        try (Connection c = pool.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setString(i + 1, args[i]);
            s.executeUpdate();
        }
    }
}
