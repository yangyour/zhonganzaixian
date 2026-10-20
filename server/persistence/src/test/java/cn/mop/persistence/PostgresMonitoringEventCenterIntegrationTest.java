package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real designated-PG verification for V37 D15 Monitoring Event Center, Deduplication (xN),
 * Flap Silence, Dependency Suppression, Maintenance Windows, Quarantine Manual Decisions,
 * and Auto-Ticket Attempts (M1-2, M4-7..M4-11, M4-14, M5-4).
 */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_MONITORING_EVENT_CENTER_TESTS", matches = "true")
class PostgresMonitoringEventCenterIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository platform;
    static PostgresMonitoringEventCenterRepository events;
    static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    static final String TENANT = "55000000-0000-4000-8000-000000000001";
    static String padmin, msp, assetA, assetB;
    static long padminVersion, mspVersion;

    @BeforeAll static void connect() throws SQLException {
        var cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));
        cfg.setUsername("mop_test_runtime");
        cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        cfg.setMaximumPoolSize(6);
        pool = new HikariDataSource(cfg);
        platform = new PostgresPlatformRepository(pool, mapper);
        events = new PostgresMonitoringEventCenterRepository(pool, platform);
        assertEquals(List.of(), events.readinessProblems());
        padmin = platform.users().stream().filter(u -> "padmin".equals(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        msp = platform.users().stream().filter(u -> "msp".equals(platform.membership(u.id(), TENANT).filter(Membership::active).map(Membership::role).orElse(""))).findFirst().orElseThrow().id();
        padminVersion = platform.membership(padmin, TENANT).orElseThrow().permissionVersion();
        mspVersion = platform.membership(msp, TENANT).orElseThrow().permissionVersion();
        try (Connection c = pool.getConnection();
             var s = c.prepareStatement("SELECT id::text FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id ASC LIMIT 2")) {
            s.setString(1, TENANT);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                assetA = r.getString(1);
                assetB = r.next() ? r.getString(1) : assetA;
            }
        }
    }

    @AfterAll static void close() { if (pool != null) pool.close(); }

    static Access access(String actor, long version) {
        return new Access(TENANT, actor, version, UUID.randomUUID().toString());
    }

    @Test void deduplicationTimesFiveFlapSilenceAndPendingReviewLifecycle_M4_7_M4_8_M4_11() {
        var mspAcc = access(msp, mspVersion);
        String ruleCode = "SYNTH_DEDUP_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Instant base = Instant.now().minusSeconds(120);

        EventSummary last = null;
        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            last = (EventSummary) platform.idempotentAuthorized(
                msp, TENANT, "POST /integration/events/dedup/" + ruleCode + "/" + idx, UUID.randomUUID().toString(), "a".repeat(64), mspVersion,
                () -> events.authorizeWrite(mspAcc),
                () -> events.ingestEvent(mspAcc, new IngestEventCommand(
                    assetA, ruleCode, "合成5合1归并测试告警", Severity.HIGH, MonitorState.PROBLEM,
                    "src-" + ruleCode + "-" + idx, base.plusSeconds(idx * 10L), false
                )));
        }
        assertNotNull(last);
        assertEquals(5, last.occurrenceCount());
        assertNotNull(last.ticketId());

        EventDetailView detail = events.eventDetail(mspAcc, last.id());
        assertEquals(5, detail.members().size());

        // Flip state 3 times within 10 minutes -> triggers 5-minute flap silence (M4-7)
        final String eventId = last.id();
        EventSummary r1 = (EventSummary) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/events/flap1/" + ruleCode, UUID.randomUUID().toString(), "b".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.ingestEvent(mspAcc, new IngestEventCommand(
                assetA, ruleCode, "合成5合1归并测试告警", Severity.HIGH, MonitorState.RECOVERED,
                "src-" + ruleCode + "-r1", base.plusSeconds(60), false
            )));
        // Recovery transitions event to PENDING_REVIEW, never directly to RESOLVED or CLOSED (M4-11)
        assertEquals(HandlingState.PENDING_REVIEW, r1.handlingState());

        EventSummary p2 = (EventSummary) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/events/flap2/" + ruleCode, UUID.randomUUID().toString(), "c".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.ingestEvent(mspAcc, new IngestEventCommand(
                assetA, ruleCode, "合成5合1归并测试告警", Severity.HIGH, MonitorState.PROBLEM,
                "src-" + ruleCode + "-p2", base.plusSeconds(70), false
            )));
        assertEquals(HandlingState.IN_PROGRESS, p2.handlingState());

        EventSummary r2 = (EventSummary) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/events/flap3/" + ruleCode, UUID.randomUUID().toString(), "d".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.ingestEvent(mspAcc, new IngestEventCommand(
                assetA, ruleCode, "合成5合1归并测试告警", Severity.HIGH, MonitorState.RECOVERED,
                "src-" + ruleCode + "-r2", base.plusSeconds(80), false
            )));
        assertTrue(r2.flapFlipCount() >= 3);
        assertNotNull(r2.flapSilencedUntil());
        assertEquals(SuppressionState.FLAP_SILENCED, r2.suppressionState());
        assertEquals(HandlingState.PENDING_REVIEW, r2.handlingState());

        // Resolving from PENDING_REVIEW requires root_cause_note (M4-11)
        EventSummary resolved = (EventSummary) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/events/resolve/" + eventId, UUID.randomUUID().toString(), "e".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.transitionEvent(mspAcc, new TransitionEventCommand(eventId, r2.version(), "RESOLVE", "人工复核确认上联光模块抖动原因并已固定修复，复测网络连接稳定")));
        assertEquals(HandlingState.RESOLVED, resolved.handlingState());

        EventSummary closed = (EventSummary) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/events/close/" + eventId, UUID.randomUUID().toString(), "f".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.transitionEvent(mspAcc, new TransitionEventCommand(eventId, resolved.version(), "CLOSE", null)));
        assertEquals(HandlingState.CLOSED, closed.handlingState());
    }

    @Test void longProblemKeepsIdentityAndLateRecoveryClosesTheSameSignalOnly() {
        var a = access(msp, mspVersion);
        String rule = "SYNTH_LONG_" + UUID.randomUUID().toString().substring(0,8);
        Instant start = Instant.now().minusSeconds(1800);
        EventSummary first = ingest(a,rule,MonitorState.PROBLEM,"first",start);
        EventSummary repeated = ingest(a,rule,MonitorState.PROBLEM,"repeat",start.plusSeconds(600));
        assertEquals(first.id(), repeated.id());
        EventSummary recovered = ingest(a,rule,MonitorState.RECOVERED,"recovery",start.plusSeconds(1200));
        assertEquals(first.id(), recovered.id());
        assertEquals(MonitorState.RECOVERED, recovered.monitorState());
        assertEquals(HandlingState.PENDING_REVIEW,recovered.handlingState());
        assertNull(recovered.closedAt());
        EventSummary replay = ingest(a,rule,MonitorState.RECOVERED,"recovery",start.plusSeconds(1200));
        assertEquals(recovered.version(),replay.version());
        assertEquals(3,events.eventDetail(a,first.id()).members().size());
        assertEquals(422,assertThrows(DomainException.class,()->ingest(a,rule,MonitorState.PROBLEM,"late",start.plusSeconds(1000))).status);
        assertEquals(400,assertThrows(DomainException.class,()->platform.idempotentAuthorized(
            msp,TENANT,"POST /integration/review-short/"+first.id(),UUID.randomUUID().toString(),"a".repeat(64),mspVersion,
            ()->events.authorizeWrite(a),()->events.transitionEvent(a,new TransitionEventCommand(first.id(),recovered.version(),"RESOLVE","字".repeat(19))))).status);
    }

    static EventSummary ingest(Access a,String rule,MonitorState phase,String suffix,Instant time) {
        return (EventSummary)platform.idempotentAuthorized(a.actorId(),a.tenantId(),"POST /integration/customer-policy/"+rule+"/"+suffix,
            UUID.randomUUID().toString(),"c".repeat(64),a.permissionVersion(),()->events.authorizeWrite(a),
            ()->events.ingestEvent(a,new IngestEventCommand(assetA,rule,"SYNTHETIC_CUSTOMER_POLICY",Severity.INFO,phase,rule+"-"+suffix,time,false)));
    }

    @Test void maintenanceWindowSlaExclusionQuarantineBindAndAutoTicketFiveRetries_M4_10_M4_14_M5_4() throws SQLException {
        var mspAcc = access(msp, mspVersion);
        var adminAcc = access(padmin, padminVersion);
        Instant now = Instant.now();

        // 1. Create active maintenance window requesting SLA exclusion (M4-10)
        MaintenanceWindow mw = (MaintenanceWindow) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/mw/create/" + UUID.randomUUID(), UUID.randomUUID().toString(), "1".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.createMaintenanceWindow(mspAcc, new CreateMaintenanceWindowCommand(
                "SYNTH_MW_" + UUID.randomUUID().toString().substring(0, 6), assetB,
                now.minusSeconds(300), now.plusSeconds(3600), "SUPPRESS_NOTIFY", true, "计划内核心设备维护窗口"
            )));
        assertFalse(mw.slaExclusionConfirmed());

        // Event during maintenance window is recorded and silenced
        String mwRule = "SYNTH_MW_RULE_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        EventSummary mwEvent = (EventSummary) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/events/mw/" + mwRule, UUID.randomUUID().toString(), "2".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.ingestEvent(mspAcc, new IngestEventCommand(
                assetB, mwRule, "维护窗口期告警测试", Severity.WARNING, MonitorState.PROBLEM,
                "src-mw-" + mwRule, now, false
            )));
        assertEquals(SuppressionState.MAINTENANCE_SILENCED, mwEvent.suppressionState());
        assertEquals(NotificationEmptyState.MAINTENANCE_WINDOW_SUPPRESSED, mwEvent.notificationEmptyState());

        // Platform admin confirms SLA exclusion (M4-10)
        MaintenanceWindow confirmedMw = (MaintenanceWindow) platform.idempotentAuthorized(
            padmin, TENANT, "POST /integration/mw/confirm/" + mw.id(), UUID.randomUUID().toString(), "3".repeat(64), padminVersion,
            () -> events.authorizeAdminWrite(adminAcc),
            () -> events.updateMaintenanceWindow(adminAcc, new UpdateMaintenanceWindowCommand(mw.id(), mw.version(), "CONFIRM_SLA_EXCLUSION", null)));
        assertTrue(confirmedMw.slaExclusionConfirmed());
        assertEquals(padmin, confirmedMw.slaExclusionConfirmedBy());

        // End maintenance window so subsequent auto-ticket test on assetB is not silenced
        platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/mw/end/" + mw.id(), UUID.randomUUID().toString(), "4".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.updateMaintenanceWindow(mspAcc, new UpdateMaintenanceWindowCommand(confirmedMw.id(), confirmedMw.version(), "END", null)));

        // 2. Simulate 5 failed auto-ticket attempts -> QUARANTINED_MANUAL_REQUIRED -> manual creation (M5-4)
        String retryRule = "SYNTH_RETRY_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        EventSummary retryEvent = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            final int n = attempt;
            retryEvent = (EventSummary) platform.idempotentAuthorized(
                msp, TENANT, "POST /integration/events/retry/" + retryRule + "/" + n, UUID.randomUUID().toString(), "5".repeat(64), mspVersion,
                () -> events.authorizeWrite(mspAcc),
                () -> events.ingestEvent(mspAcc, new IngestEventCommand(
                    assetB, retryRule, "自动建单5次重试隔离测试", Severity.DISASTER, MonitorState.PROBLEM,
                    "src-retry-" + retryRule + "-" + n, now.plusSeconds(n), true
                )));
        }
        assertNotNull(retryEvent);
        EventDetailView retryDetail = events.eventDetail(mspAcc, retryEvent.id());
        AutoTicketAttempt quarantinedAttempt = retryDetail.autoTicketAttempt().orElseThrow();
        assertEquals(5, quarantinedAttempt.attemptCount());
        assertEquals(AutoTicketStatus.QUARANTINED_MANUAL_REQUIRED, quarantinedAttempt.status());
        assertNull(quarantinedAttempt.ticketId());

        AutoTicketAttempt manualCreated = (AutoTicketAttempt) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/autoticket/manual/" + quarantinedAttempt.id(), UUID.randomUUID().toString(), "6".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.manualCreateAutoTicket(mspAcc, new ManualAutoTicketCommand(quarantinedAttempt.id(), quarantinedAttempt.version(), "隔离队列人工复核补建工单")));
        assertEquals(AutoTicketStatus.MANUAL_CREATED, manualCreated.status());
        assertNotNull(manualCreated.ticketId());

        // 3. Insert a synthetic quarantine candidate and verify manual BIND_ASSET resolution (M4-14)
        String qId = UUID.randomUUID().toString();
        try (Connection c = pool.getConnection();
             var s = c.prepareStatement("INSERT INTO public.mop_monitoring_quarantine(id,pool_id,source_event_id,source_phase,claimed_tenant_id,claimed_asset_id,quarantine_reason,occurred_epoch_second,occurred_nano,received_epoch_second,received_nano,payload) VALUES(?::uuid,?::uuid,?,'PROBLEM',?::uuid,NULL,'UNKNOWN_MAPPING',?,?,?,?,'{\"synthetic\":true}'::bytea)")) {
            s.setString(1, qId);
            s.setString(2, UUID.randomUUID().toString());
            s.setString(3, "synth-q-" + qId.substring(0, 8));
            s.setString(4, TENANT);
            s.setLong(5, now.getEpochSecond());
            s.setInt(6, 0);
            s.setLong(7, now.getEpochSecond());
            s.setInt(8, 0);
            s.executeUpdate();
        }
        QuarantineItem decided = (QuarantineItem) platform.idempotentAuthorized(
            msp, TENANT, "POST /integration/quarantine/decide/" + qId, UUID.randomUUID().toString(), "7".repeat(64), mspVersion,
            () -> events.authorizeWrite(mspAcc),
            () -> events.resolveQuarantine(mspAcc, new QuarantineDecisionCommand(qId, "BIND_ASSET", assetA, "核验映射归属至当前租户核心资产")));
        assertEquals("BIND_ASSET", decided.decision());
        assertNotNull(decided.resultEventId());

        // 4. Verify DB triggers block append-only table mutations and deletions
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_monitoring_event WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("UPDATE public.mop_monitoring_event_member SET sequence_no=99 WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_event_noise_record WHERE tenant_id=?::uuid", TENANT));
        assertThrows(SQLException.class, () -> exec("DELETE FROM public.mop_monitoring_quarantine_decision WHERE tenant_id=?::uuid", TENANT));
    }

    static void exec(String sql, String... args) throws SQLException {
        try (Connection c = pool.getConnection(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setString(i + 1, args[i]);
            s.executeUpdate();
        }
    }
}
