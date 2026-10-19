package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.sla.*;
import cn.mop.domain.sla.OnCallEscalationRepository.*;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.*;
import cn.mop.domain.sla.SlaComplianceScenario.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.time.IntervalSets.Interval;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnCallEscalationControllerTest {
    static final String TENANT = "66000000-0000-4000-8000-000000000001";
    static final String ACTOR = "66000000-0000-4000-8000-000000000011";
    static final String BACKUP = "66000000-0000-4000-8000-000000000012";
    static final String TICKET = "66000000-0000-4000-8000-000000000021";
    static final String ROSTER_ID = "66000000-0000-4000-8000-000000000031";
    static final String HANDOFF_ID = "66000000-0000-4000-8000-000000000041";
    static final String RUN_ID = "66000000-0000-4000-8000-000000000051";
    static final String MONTHLY_ID = "66000000-0000-4000-8000-000000000061";
    static final Instant AT = Instant.parse("2026-10-18T10:00:00Z");

    PlatformRepository platform;
    OnCallEscalationRepository repository;
    OnCallEscalationController controller;

    @BeforeEach
    void setup() {
        platform = mock(PlatformRepository.class);
        repository = mock(OnCallEscalationRepository.class);
        controller = new OnCallEscalationController(platform, repository);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 3, true)));
        when(platform.idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(x -> {
            ((Runnable) x.getArgument(6)).run();
            return ((Supplier<?>) x.getArgument(7)).get();
        });
    }

    MockHttpServletRequest request(String method, String path, String body) {
        var r = new MockHttpServletRequest(method, "/api/v1" + path);
        r.setUserPrincipal(() -> ACTOR);
        r.getSession().setAttribute("mop.tenant_id", TENANT);
        r.setAttribute("mop.trace_id", "oncall-sla-test");
        r.addHeader("Idempotency-Key", "SYNTHETIC-oncall-sla-key");
        r.setContentType("application/json");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> data(Object value) {
        return (Map<String, Object>) ((Map<?, ?>) value).get("data");
    }

    @Test
    void m5_9_unknownIntervalsAreExcludedFromVerifiedAvailabilityAndExplainedInDrilldown() {
        Interval window = new Interval(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z")); // 86400s
        Interval exclusion = new Interval(Instant.parse("2026-10-01T01:00:00Z"), Instant.parse("2026-10-01T03:00:00Z")); // 7200s -> effective = 79200s
        Interval unavailable = new Interval(Instant.parse("2026-10-01T04:00:00Z"), Instant.parse("2026-10-01T05:00:00Z")); // 3600s
        Interval unknownGap = new Interval(Instant.parse("2026-10-01T06:00:00Z"), Instant.parse("2026-10-01T08:00:00Z")); // 7200s

        ServiceRow row = SlaAvailabilityAndCompensation.evaluateService(
            "CORE_NETWORK", "核心网络互联服务", new BigDecimal("99.90"),
            window, List.of(exclusion), List.of(unavailable), List.of(unknownGap), List.of()
        );
        assertEquals(86400L, row.agreedServiceSec());
        assertEquals(7200L, row.approvedExclusionSec());
        assertEquals(79200L, row.effectiveServiceSec());
        assertEquals(3600L, row.unavailableUnionSec());
        assertEquals(7200L, row.unknownIntervalSec());
        // Known available strictly excludes BOTH unavailable (3600s) AND unknown (7200s): 79200 - 3600 - 7200 = 68400s
        assertEquals(68400L, row.knownAvailableSec());
        assertTrue(row.verifiedAvailabilityPct().compareTo(row.upperBoundAvailabilityPct()) < 0);
        assertEquals(new BigDecimal("86.3636"), row.verifiedAvailabilityPct());
        assertEquals(new BigDecimal("95.4545"), row.upperBoundAvailabilityPct());
        assertEquals("CONFIG_PENDING_Q05_UNKNOWN_NOT_COUNTED_AVAILABLE", row.unknownPolicyStatus());
        assertTrue(row.intervalDetails().stream().anyMatch(d -> "UNKNOWN_DATA_GAP".equals(d.kind()) && d.explanation().contains("不默认视为可用")));
    }

    @Test
    void m5_19_tenTicketsWithThreeBreachesMatchHandCalculationAndProvideDrilldownEvidence() {
        List<TicketSample> samples = new ArrayList<>();
        Instant base = Instant.parse("2026-10-05T08:00:00Z");
        for (int i = 1; i <= 10; i++) {
            Instant created = base.plusSeconds(i * 600L);
            Instant respDue = created.plusSeconds(1800);
            Instant recDue = created.plusSeconds(14400);
            Instant respAct = i == 8 || i == 10 ? respDue.plusSeconds(300) : created.plusSeconds(600);
            Instant recAct = i == 9 || i == 10 ? recDue.plusSeconds(900) : created.plusSeconds(7200);
            samples.add(new TicketSample(
                String.format("66000000-0000-4000-8000-%012d", i),
                i <= 3 ? Priority.P1 : i <= 7 ? Priority.P2 : Priority.P3,
                Tier.STANDARD,
                Exclusion.NONE,
                null,
                created,
                respDue,
                respAct,
                recDue,
                recAct
            ));
        }
        MonthlyEvaluation eval = SlaAvailabilityAndCompensation.evaluateMonthly(Tier.STANDARD, 1_000_000L, samples);
        assertEquals(10, eval.totalTicketCount());
        assertEquals(10, eval.applicableCount());
        assertEquals(7, eval.metCount());
        assertEquals(3, eval.notMetCount());
        assertEquals(7, eval.achievementNumerator());
        assertEquals(10, eval.achievementDenominator());
        assertEquals(new BigDecimal("70.00"), eval.achievementRatePct());
        assertEquals(new BigDecimal("0.20"), eval.compensationRatio());
        assertNull(eval.compensationAmountFen());
        assertEquals("CONFIG_PENDING_Q26", eval.compensationStatus());
        // Candidate formulas for 1,000,000 fen monthly fee at 20% ratio and 30% shortfall:
        assertEquals(200_000L, eval.candidateFixedRatioFen());
        assertEquals(60_000L, eval.candidateShortfallWeightedFen());
        assertEquals(10, eval.items().size());
        assertEquals(3, eval.items().stream().filter(j -> "NOT_MET".equals(j.outcome())).count());
    }

    @Test
    void m5_12_and_m5_13_boardRostersHandoffsPoliciesAndEscalationsAreExposedViaController() {
        RosterEntry roster = new RosterEntry(ROSTER_ID, TENANT, 1L, LocalDate.of(2026, 10, 18),
            ShiftCode.DAY, "18:00", ACTOR, BACKUP, "白班值守", "CONFIG_PENDING_Q15", ACTOR, AT);
        HandoffEntry handoff = new HandoffEntry(HANDOFF_ID, TENANT, ROSTER_ID, ACTOR, BACKUP,
            "18:00", 2, 3, 1, "18:00晚班交接，2单待接、3单处理中、1单待复核", AT);
        EscalationPolicy policy = new EscalationPolicy(TENANT, PolicyTier.STANDARD, 1L, 2, null,
            OnCallEscalationRepository.defaultSteps(PolicyTier.STANDARD), "CONFIG_PENDING_Q15", ACTOR, AT, true);
        EscalationStepRun run = new EscalationStepRun(RUN_ID, TENANT, TICKET, PolicyTier.STANDARD, 1L, 1,
            TriggerCondition.PHONE_UNANSWERED, 0, TargetRole.L1_ONCALL, 2, 2, TargetRole.SERVICE_MANAGER,
            SmsNoticeStatus.INTENT_RECORDED_PENDING_GATEWAY, "电话连续2次未接听，已自动升级至步骤2(SERVICE_MANAGER)并登记短信告知记录（外部短信网关待配置）", ACTOR, AT);
        QueueItem qItem = new QueueItem(TICKET, "P1", "OPEN", "STANDARD", ACTOR, AT,
            AT.plusSeconds(600), AT.plusSeconds(7200), 0L, new BigDecimal("1.0000"), true, 1, 2, "SERVICE_MANAGER");
        OnCallBoardView boardView = new OnCallBoardView(
            TENANT, new BoardKpis(2, 1, 3, 1, 4, 180L, 300L),
            List.of(qItem), List.of(roster), List.of(handoff), List.of(policy), List.of(run),
            30, "18:00", AT
        );
        when(repository.board(any(), eq(25))).thenReturn(boardView);
        var boardRes = data(controller.board(request("GET", "/oncall/board", "")));
        assertEquals(30, boardRes.get("auto_refresh_sec"));
        assertEquals("18:00", boardRes.get("handoff_local_time"));
        assertEquals(2, ((Map<?, ?>) boardRes.get("kpis")).get("unacknowledged_count"));

        when(repository.upsertRoster(any(), any())).thenReturn(roster);
        var rosterRes = data(controller.upsertRoster(request("POST", "/oncall/rosters",
            "{\"expected_version\":0,\"shift_date\":\"2026-10-18\",\"shift_code\":\"DAY\",\"primary_oncall_user_id\":\"" + ACTOR + "\",\"backup_oncall_user_id\":\"" + BACKUP + "\",\"note\":\"白班值守\"}")));
        assertEquals("DAY", rosterRes.get("shift_code"));

        when(repository.recordHandoff(any(), any())).thenReturn(handoff);
        var handoffRes = data(controller.recordHandoff(request("POST", "/oncall/handoffs",
            "{\"roster_id\":\"" + ROSTER_ID + "\",\"incoming_user_id\":\"" + BACKUP + "\",\"note\":\"18:00晚班交接\"}")));
        assertEquals(2, handoffRes.get("unacknowledged_count"));
        assertEquals(3, handoffRes.get("in_progress_count"));
        assertEquals(1, handoffRes.get("pending_review_count"));

        when(repository.updatePolicy(any(), any())).thenReturn(policy);
        var policyRes = data(controller.updatePolicy(request("PATCH", "/escalation-policies",
            "{\"tier\":\"STANDARD\",\"expected_version\":0,\"unanswered_escalate_count\":2,\"steps\":[{\"step_order\":1,\"trigger_condition\":\"IMMEDIATE\",\"wait_minutes\":0,\"target_role\":\"L1_ONCALL\"},{\"step_order\":2,\"trigger_condition\":\"UNRESOLVED\",\"wait_minutes\":240,\"target_role\":\"SERVICE_MANAGER\"}]}")));
        assertEquals("STANDARD", policyRes.get("tier"));

        when(repository.triggerEscalation(any(), any())).thenReturn(run);
        var escRes = data(controller.triggerEscalation(request("POST", "/oncall/escalations",
            "{\"ticket_id\":\"" + TICKET + "\",\"tier\":\"STANDARD\",\"step_order\":1,\"trigger_condition\":\"PHONE_UNANSWERED\",\"unanswered_phone_count\":2}")));
        assertEquals(2, escRes.get("escalated_to_step"));
        assertEquals("SERVICE_MANAGER", escRes.get("escalated_target_role"));
        assertEquals("INTENT_RECORDED_PENDING_GATEWAY", escRes.get("sms_notice_status"));
    }

    @Test
    void slaReportAndMonthlyResultEndpointsAndCustomerRoleForbidden() {
        Interval window = new Interval(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));
        ServiceRow core = SlaAvailabilityAndCompensation.evaluateService(
            "CORE_NETWORK", "核心网络互联服务", new BigDecimal("99.90"), window, List.of(), List.of(), List.of(), List.of()
        );
        MonthlyEvaluation eval = SlaAvailabilityAndCompensation.evaluateMonthly(Tier.STANDARD, 1_000_000L, List.of());
        MonthlyResultRecord rec = new MonthlyResultRecord(
            MONTHLY_ID, TENANT, "2026-10", 1L, Tier.STANDARD, 10, 10, 7, 3, 0, 0, 7, 10,
            new BigDecimal("70.00"), new BigDecimal("0.20"), 1_000_000L, null, "CONFIG_PENDING_Q26",
            200_000L, 60_000L, SlaAvailabilityAndCompensation.FORMULA_VERSION, List.of(), true, AT, ACTOR, AT
        );
        SlaReportView reportView = new SlaReportView(
            TENANT, "2026-10", Tier.STANDARD, new BigDecimal("99.90"), new BigDecimal("100.0000"),
            0L, core.mtta(), core.mttr(), 0, 0L, List.of(core), Optional.of(rec), eval,
            SlaAvailabilityAndCompensation.FORMULA_VERSION, AT
        );
        when(repository.slaReport(any(), eq("2026-10"))).thenReturn(reportView);
        var repReq = request("GET", "/sla/reports", "");
        repReq.addParameter("period", "2026-10");
        var repRes = data(controller.slaReport(repReq));
        assertEquals("2026-10", repRes.get("period"));
        assertEquals(SlaAvailabilityAndCompensation.FORMULA_VERSION, repRes.get("formula_version"));

        var detReq = request("GET", "/sla/reports/CORE_NETWORK/details", "");
        detReq.addParameter("period", "2026-10");
        var detRes = data(controller.slaReportDetails("CORE_NETWORK", detReq));
        assertEquals("CORE_NETWORK", ((Map<?, ?>) detRes.get("service")).get("service_id"));
        assertEquals(404, assertThrows(DomainException.class, () -> controller.slaReportDetails("UNKNOWN_SVC", detReq)).status);

        when(repository.evaluateMonthlyResult(any(), any())).thenReturn(rec);
        var monRes = data(controller.evaluateMonthlyResult(request("POST", "/sla/monthly-results",
            "{\"report_month\":\"2026-10\",\"expected_version\":0,\"freeze\":true}")));
        assertEquals(true, monRes.get("frozen"));
        assertEquals(new BigDecimal("70.00"), monRes.get("achievement_rate_pct"));

        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "tadmin", 3, true)));
        assertEquals(403, assertThrows(DomainException.class, () -> controller.board(request("GET", "/oncall/board", ""))).status);
    }
}
