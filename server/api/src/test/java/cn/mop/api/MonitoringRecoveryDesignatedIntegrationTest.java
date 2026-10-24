package cn.mop.api;

import cn.mop.adapters.monitoring.ZabbixReadClient;
import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import cn.mop.domain.monitoring.RecoveryObservation;
import cn.mop.domain.monitoring.RecoverySourceBatch;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.TicketWorkflowRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh designated-PG scopes only. Stub source cases and the optional live test-server source are explicit. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_MONITORING_RECOVERY_PG_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MonitoringRecoveryDesignatedIntegrationTest {
    static HikariDataSource pool;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static PostgresPlatformRepository platform;
    static PostgresMonitoringEventCenterRepository events;
    static PostgresRuntimeConfigurationRepository runtime;
    static PostgresTicketWorkflowRepository workflows;
    static RuntimeConfigurationCatalog catalog;
    static String tenant, foreignTenant, actor, reviewer, asset, foreignAsset, address, definitionHash;
    static final String KEY = PostgresMonitoringRecoveryRepository.POLICY_KEY;

    /** Compare the exact HTTP JSON value, not Jackson's in-memory IntNode versus LongNode implementation. */
    static com.fasterxml.jackson.databind.JsonNode wire(Object value) throws java.io.IOException {
        return JSON.readTree(JSON.writeValueAsBytes(value));
    }

    @BeforeAll
    static void connect() throws Exception {
        tenant = id("TENANT"); foreignTenant = id("FOREIGN_TENANT"); actor = id("ACTOR");
        reviewer = id("REVIEWER"); asset = id("ASSET"); foreignAsset = id("FOREIGN_ASSET");
        var config = new HikariConfig();
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(6);
        pool = new HikariDataSource(config);
        try (var c = pool.getConnection()) {
            c.setReadOnly(true);
            for (String scope : List.of(tenant, foreignTenant)) {
                try (var s = c.prepareStatement("SELECT name FROM public.mop_tenant WHERE id=?::uuid")) {
                    s.setString(1, scope);
                    try (var r = s.executeQuery()) {
                        assertTrue(r.next());
                        assertTrue(r.getString(1).startsWith("SYNTHETIC_MONITORING_"));
                    }
                }
            }
            for (String user : List.of(actor, reviewer)) {
                try (var s = c.prepareStatement("SELECT identity_source,"
                        + "EXISTS(SELECT 1 FROM public.mop_local_account WHERE user_id=u.id),"
                        + "EXISTS(SELECT 1 FROM public.mop_external_identity_binding WHERE user_id=u.id),"
                        + "EXISTS(SELECT 1 FROM public.mop_membership WHERE user_id=u.id AND tenant_id NOT IN (?::uuid,?::uuid)) "
                        + "FROM public.mop_user u WHERE id=?::uuid AND active")) {
                    s.setString(1, tenant); s.setString(2, foreignTenant); s.setString(3, user);
                    try (var r = s.executeQuery()) {
                        assertTrue(r.next()); assertEquals("isolated_monitoring", r.getString(1));
                        assertFalse(r.getBoolean(2)); assertFalse(r.getBoolean(3)); assertFalse(r.getBoolean(4));
                    }
                }
            }
            try (var s = c.prepareStatement("SELECT management_ip FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid")) {
                s.setString(1, tenant); s.setString(2, asset);
                try (var r = s.executeQuery()) { assertTrue(r.next()); address = r.getString(1); }
            }
        }
        platform = new PostgresPlatformRepository(pool, JSON);
        events = new PostgresMonitoringEventCenterRepository(pool, platform);
        workflows = new PostgresTicketWorkflowRepository(pool, platform, JSON);
        runtime = new PostgresRuntimeConfigurationRepository(pool, platform, JSON);
        catalog = new RuntimeConfigurationCatalog(JSON);
        definitionHash = catalog.hash(KEY);
    }

    @AfterAll
    static void close() { if (pool != null) { pool.close(); } }

    static String id(String suffix) {
        String value = System.getenv("MOP_MONITORING_" + suffix);
        assertNotNull(value); assertEquals(UUID.fromString(value).toString(), value); assertFalse(value.startsWith("55000000-"));
        return value;
    }
    static Access access() {
        return new Access(tenant, actor, platform.membership(actor, tenant).orElseThrow().permissionVersion(), UUID.randomUUID().toString());
    }
    static RuntimeConfigurationRepository.Access configAccess(String user) {
        return new RuntimeConfigurationRepository.Access(tenant, user,
            platform.membership(user, tenant).orElseThrow().permissionVersion(), UUID.randomUUID().toString());
    }
    static Object transaction(String user, String route, String key, Supplier<Object> command) {
        var a = configAccess(user);
        return platform.idempotentAuthorized(user, tenant, route, key, "a".repeat(64), a.permissionVersion(), () -> { }, command);
    }
    static EventSummary problem(String rule) {
        var a = access();
        return (EventSummary) platform.idempotentAuthorized(actor, tenant, "POST /integration/recovery/problem/" + rule,
            UUID.randomUUID().toString(), "b".repeat(64), a.permissionVersion(), () -> events.authorizeWrite(a),
            () -> events.ingestEvent(a, new IngestEventCommand(asset, rule, "SYNTHETIC_RECOVERY_OBSERVATION", Severity.HIGH,
                MonitorState.PROBLEM, "problem-" + rule, Instant.now().truncatedTo(ChronoUnit.MILLIS), false)));
    }
    static String ruleCode() { return "SYNTH_REC_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> publish(String rule, String hold, int minimum, Duration expiryAfter) {
        var value = Map.of("recovery_observation", Map.of("schema_version", 1, "rules", List.of(Map.of(
            "asset_id", asset, "rule_code", rule, "metric", "icmp", "comparison", "GE", "threshold", "1", "unit", "",
            "hold_seconds", hold, "max_gap_seconds", "180", "minimum_samples", minimum))));
        catalog.validate(KEY, JSON.valueToTree(value));
        var proposal = (Map<String, Object>) transaction(actor, "POST /integration/recovery/config-create", UUID.randomUUID().toString(),
            () -> platform.createConfigurationRevision(tenant, actor, KEY, value, "SYNTHETIC_RECOVERY_POLICY_NOT_CUSTOMER_APPROVAL",
                List.of(Map.of("reference", "document-ref:synthetic-monitoring-recovery", "title", "SYNTHETIC isolated verification", "sha256", "c".repeat(64)))));
        String revision = proposal.get("id").toString();
        transaction(actor, "POST /integration/recovery/config-submit", UUID.randomUUID().toString(),
            () -> platform.transitionConfigurationRevision(tenant, actor, revision, 1, "SUBMIT", null));
        transaction(reviewer, "POST /integration/recovery/config-approve", UUID.randomUUID().toString(),
            () -> platform.transitionConfigurationRevision(tenant, reviewer, revision, 2, "APPROVE", "SYNTHETIC_ISOLATED_REVIEW"));
        var a = configAccess(actor);
        long version = ((Number) runtime.read(a, KEY).get("latest_version")).longValue();
        Instant expiry = expiryAfter == null ? null : dbNow().plus(expiryAfter);
        var command = new RuntimeConfigurationRepository.Command(version, "PUBLISH", revision, 3L, null,
            "IMMEDIATE", null, expiry, "SYNTHETIC_RECOVERY_POLICY_NOT_CUSTOMER_APPROVAL");
        return (Map<String, Object>) platform.idempotentAuthorized(actor, tenant, "POST /integration/recovery/config-release",
            UUID.randomUUID().toString(), "d".repeat(64), a.permissionVersion(), () -> runtime.authorizeCommand(a, KEY),
            () -> runtime.publish(a, KEY, command, definitionHash));
    }

    static Instant dbNow() {
        try (var c = pool.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT clock_timestamp()")) {
            r.next(); return r.getObject(1, OffsetDateTime.class).toInstant();
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }
    static RecoverySourceBatch samples(List<RecoveryObservation.Sample> values) {
        Instant from = values.isEmpty() ? dbNow().minusSeconds(60) : values.get(0).recordedAt().minusSeconds(1);
        Instant to = values.isEmpty() ? dbNow().plusSeconds(1) : values.get(values.size() - 1).recordedAt().plusSeconds(1);
        return new RecoverySourceBatch(UUID.fromString("10000000-0000-4000-8000-000000000099"),
            UUID.fromString(tenant), UUID.fromString(asset), address, "icmp", "", "e".repeat(64), from, to, values);
    }
    static RecoverySourceBatch matching() {
        Instant recovered = dbNow().minusMillis(2);
        return samples(List.of(new RecoveryObservation.Sample(recovered.minusMillis(1), RecoveryObservation.Quality.KNOWN, BigDecimal.ZERO),
                new RecoveryObservation.Sample(recovered, RecoveryObservation.Quality.KNOWN, BigDecimal.ONE)));
    }
    static PostgresMonitoringRecoveryRepository repository(PostgresMonitoringRecoveryRepository.SourceReader reader) {
        return new PostgresMonitoringRecoveryRepository(pool, platform, reader, definitionHash);
    }
    static PostgresMonitoringRecoveryRepository.Command command(EventSummary event, Map<String, Object> release, String hash) {
        return new PostgresMonitoringRecoveryRepository.Command(event.id(), event.version(), release.get("id").toString(),
            ((Number) release.get("version")).longValue(), hash);
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> observe(PostgresPlatformRepository p, PostgresMonitoringRecoveryRepository repo,
                                       PostgresMonitoringRecoveryRepository.Command command, String key) {
        var a = access();
        return JSON.convertValue(p.idempotentAuthorized(actor, tenant, "POST /integration/recovery-observe/" + command.eventId(),
            key, "f".repeat(64), a.permissionVersion(), () -> repo.authorizeCommand(a, command.eventId()), () -> repo.observe(a, command)),
            java.util.LinkedHashMap.class);
    }
    static long proofs(String event) throws Exception {
        try (var c = pool.getConnection(); var s = c.prepareStatement("SELECT count(*) FROM public.mop_raw_monitoring_event "
                + "WHERE tenant_id=?::uuid AND source_event_id LIKE ?")) {
            s.setString(1, tenant); s.setString(2, "recovery-observation:" + event + ":%");
            try (var r = s.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }
    static TicketWorkflowRepository.Access activate(EventSummary event) {
        var access = new TicketWorkflowRepository.Access(tenant, actor, access().permissionVersion(), UUID.randomUUID().toString());
        transaction(actor, "POST /integration/recovery/workflow-open", UUID.randomUUID().toString(),
            () -> workflows.activate(access, event.ticketId(), 0, "P2", Tier.STANDARD, "SYNTHETIC_RECOVERY_SLA",
                (a, p, t, start) -> TicketSlaSnapshot.freeze(p, t, start, Duration.ofMinutes(5), Duration.ofHours(1), null, 48L, null, List.of(), Map.of())));
        return access;
    }

    @Test
    @Order(1)
    void missingPublicationCannotReadSourceOrConfirm() throws Exception {
        var event = problem(ruleCode()); var calls = new AtomicInteger();
        var repo = repository((t, a, m) -> { calls.incrementAndGet(); return matching(); });
        assertEquals("CONFIG_PENDING", repo.inspect(access(), event.id()).get("configuration_status"));
        var command = new PostgresMonitoringRecoveryRepository.Command(event.id(), event.version(), UUID.randomUUID().toString(), 1, "1".repeat(64));
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class, () -> observe(platform, repo, command, "missing")).code);
        assertEquals(0, calls.get()); assertEquals(0, proofs(event.id()));
    }

    @Test
    @Order(2)
    void confirmedEvidenceCommitsWithEventAndSlaAndOriginalReplayDoesNotResample() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "0", 1, null);
        var access = activate(event);
        var calls = new AtomicInteger(); var repo = repository((t, a, m) -> { calls.incrementAndGet(); return matching(); });
        var command = command(event, release, "2".repeat(64));
        var result = observe(platform, repo, command, "confirmed");
        assertEquals("RECOVERY_CONFIRMED", result.get("status")); assertEquals(true, result.get("applied"));
        var current = events.eventDetail(access(), event.id());
        assertEquals(HandlingState.PENDING_REVIEW, current.event().handlingState());
        assertEquals(Instant.parse(result.get("recovered_at").toString()).truncatedTo(ChronoUnit.MILLIS), current.event().recoveredAt());
        assertEquals(result.get("proof_id"), current.members().get(1).rawEventId());
        var sla = workflows.readSla(access, event.ticketId(), Tier.STANDARD);
        assertEquals(result.get("recovered_at"), ((Map<?, ?>) sla.get("recovery_clock")).get("completed_at"));
        assertEquals(wire(result), wire(observe(platform, repo, command, "confirmed")));
        assertEquals(wire(result), wire(repo.receipt(access(), event.id(), command.commandSha256())));
        assertEquals(2, ((Number) result.get("evidence_version")).intValue());
        assertEquals("SOURCE_NTH_MATCHING_SAMPLE", result.get("recovery_time_basis"));
        var otherActor = new Access(tenant, reviewer, platform.membership(reviewer, tenant).orElseThrow().permissionVersion(), "other-actor");
        assertEquals(404, assertThrows(DomainException.class,
                () -> repo.receipt(otherActor, event.id(), command.commandSha256())).status);
        assertEquals(1, calls.get()); assertEquals(1, proofs(event.id()));
    }

    @Test
    @Order(3)
    void insufficientEvidenceIsDurableButDoesNotAdvanceEventAndNewCommandCanObserveAgain() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "60", 2, null);
        var repo = repository((t, a, m) -> matching());
        var result = observe(platform, repo, command(event, release, "3".repeat(64)), "observing");
        assertEquals("OBSERVING", result.get("status")); assertEquals(false, result.get("applied"));
        assertEquals(event.version(), events.eventDetail(access(), event.id()).event().version());
        assertEquals(1, proofs(event.id()));
        assertEquals(result.get("status"), repo.receipt(access(), event.id(), "3".repeat(64)).get("status"));
    }

    @Test
    @Order(4)
    void stalePolicyVersionAndForeignEventDoNotReadSource() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var old = publish(rule, "0", 1, null);
        publish(rule, "1", 2, null); var calls = new AtomicInteger();
        var repo = repository((t, a, m) -> { calls.incrementAndGet(); return matching(); });
        assertEquals("CONFIG_RELEASE_CONFLICT", assertThrows(DomainException.class,
            () -> observe(platform, repo, command(event, old, "4".repeat(64)), "stale")).code);
        var foreign = new Access(foreignTenant, actor, platform.membership(actor, foreignTenant).orElseThrow().permissionVersion(), "foreign");
        assertEquals(404, assertThrows(DomainException.class, () -> repo.inspect(foreign, event.id())).status);
        assertEquals(0, calls.get()); assertEquals(0, proofs(event.id()));
    }

    @Test
    @Order(5)
    void concurrentConfirmationsHaveOneWinnerAndOneEvidenceRecord() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "0", 1, null);
        var calls = new AtomicInteger(); var repo = repository((t, a, m) -> { calls.incrementAndGet(); return matching(); });
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var one = executor.submit(() -> { start.await(); try { observe(platform, repo, command(event, release, "5".repeat(64)), "race-one"); return 200; } catch (DomainException failure) { return failure.status; } });
            var two = executor.submit(() -> { start.await(); try { observe(platform, repo, command(event, release, "6".repeat(64)), "race-two"); return 200; } catch (DomainException failure) { return failure.status; } });
            start.countDown();
            assertEquals(java.util.Set.of(200, 409), java.util.Set.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS)));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(1, calls.get()); assertEquals(1, proofs(event.id()));
    }

    @Test
    @Order(6)
    void failedEventCasRollsBackRawProofAndReceiptThenOriginalRequestCanRetry() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "0", 1, null);
        var hit = new AtomicBoolean(); DataSource faulted = missedCas(hit);
        var p = new PostgresPlatformRepository(faulted, JSON);
        var repo = new PostgresMonitoringRecoveryRepository(faulted, p, (t, a, m) -> matching(), definitionHash);
        var command = command(event, release, "7".repeat(64));
        assertEquals(409, assertThrows(DomainException.class, () -> observe(p, repo, command, "rollback")).status);
        assertTrue(hit.get()); assertEquals(0, proofs(event.id()));
        assertEquals(event.version(), events.eventDetail(access(), event.id()).event().version());
        assertEquals(404, assertThrows(DomainException.class, () -> repository((t, a, m) -> matching()).receipt(access(), event.id(), command.commandSha256())).status);
        assertEquals(true, observe(platform, repository((t, a, m) -> matching()), command, "rollback").get("applied"));
    }

    @Test
    @Order(7)
    void sourceErrorsAndBindingMismatchCannotStoreEvidence() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "0", 1, null);
        var command = command(event, release, "8".repeat(64));
        var unavailable = repository((t, a, m) -> { throw new DomainException(503, "SOURCE_UNKNOWN", "source unavailable"); });
        assertEquals(503, assertThrows(DomainException.class, () -> observe(platform, unavailable, command, "source-error")).status);
        var mismatch = repository((t, a, m) -> new RecoverySourceBatch(UUID.randomUUID(), UUID.fromString(foreignTenant),
            UUID.fromString(asset), address, "icmp", "", "e".repeat(64), dbNow().minusSeconds(60), dbNow().plusSeconds(60), matching().samples()));
        assertEquals(503, assertThrows(DomainException.class, () -> observe(platform, mismatch, command, "source-mismatch")).status);
        assertEquals(0, proofs(event.id())); assertEquals(MonitorState.PROBLEM, events.eventDetail(access(), event.id()).event().monitorState());
    }

    @Test
    @Order(8)
    void legacyRawRecoveryCannotStopSlaUntilBoundObservationIsConfirmed() throws Exception {
        String rule = ruleCode(); var original = problem(rule); var ticketAccess = activate(original); var a = access();
        var legacy = (EventSummary) platform.idempotentAuthorized(actor, tenant, "POST /integration/recovery/legacy-shape",
            UUID.randomUUID().toString(), "a".repeat(64), a.permissionVersion(), () -> events.authorizeWrite(a),
            () -> events.ingestEvent(a, new IngestEventCommand(asset, rule, "SYNTHETIC_LEGACY_SIGNAL", Severity.HIGH,
                MonitorState.RECOVERED, "legacy-" + rule, Instant.now().truncatedTo(ChronoUnit.MILLIS), false)));
        assertNull(((Map<?, ?>) workflows.readSla(ticketAccess, legacy.ticketId(), Tier.STANDARD).get("recovery_clock")).get("completed_at"));
        for (String action : List.of("RESOLVE", "CLOSE")) {
            var rejected = assertThrows(DomainException.class, () -> platform.idempotentAuthorized(actor, tenant,
                    "POST /integration/recovery/legacy-gate/" + action, UUID.randomUUID().toString(), "a".repeat(64),
                    a.permissionVersion(), () -> events.authorizeWrite(a), () -> events.transitionEvent(a,
                            new TransitionEventCommand(legacy.id(), legacy.version(), action, "SYNTHETIC root cause"))));
            assertEquals("RECOVERY_OBSERVATION_REQUIRED", rejected.code);
        }
        var release = publish(rule, "0", 1, null); var repo = repository((t, target, m) -> matching());
        assertEquals("UNVERIFIED", repo.inspect(a, legacy.id()).get("recovery_evidence_status"));
        var result = observe(platform, repo, command(legacy, release, "9".repeat(64)), "legacy-observation");
        assertEquals(true, result.get("applied"));
        assertEquals("CONFIRMED", repo.inspect(a, legacy.id()).get("recovery_evidence_status"));
        assertEquals(result.get("recovered_at"), ((Map<?, ?>) workflows.readSla(ticketAccess, legacy.ticketId(), Tier.STANDARD).get("recovery_clock")).get("completed_at"));
        var current = events.eventDetail(a, legacy.id()).event();
        assertEquals("RECOVERY_NOT_APPLICABLE", assertThrows(DomainException.class,
            () -> observe(platform, repo, command(current, release, "a".repeat(64)), "already-confirmed")).code);
    }

    @Test
    @Order(9)
    void expiryDuringSourceReadCannotCommitRecoveryProof() throws Exception {
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "0", 1, Duration.ofSeconds(10));
        Instant expiry = Instant.parse(release.get("expires_at").toString()); var calls = new AtomicInteger();
        var repo = repository((t, target, m) -> {
            calls.incrementAndGet();
            while (dbNow().isBefore(expiry)) {
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return matching();
        });
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class,
            () -> observe(platform, repo, command(event, release, "b".repeat(64)), "expires-during-read")).code);
        assertEquals(1, calls.get()); assertEquals(0, proofs(event.id()));
        assertEquals(MonitorState.PROBLEM, events.eventDetail(access(), event.id()).event().monitorState());
    }

    @Test
    @Order(10)
    @EnabledIfEnvironmentVariable(named = "MOP_MONITORING_LIVE_SOURCE", matches = "true")
    void liveBoundSourceReadCannotClaimRecoveryForAnUnobservedSyntheticFault() throws Exception {
        var bindings = NetworkMonitoringService.parseBindings(System.getenv("MOP_MONITORING_SOURCE_BINDING"));
        assertEquals(1, bindings.size()); assertEquals(tenant, bindings.get(0).tenantId()); assertEquals(asset, bindings.get(0).assetId());
        assertEquals("192.168.1.215", bindings.get(0).ip()); assertEquals("DEMO", bindings.get(0).brand());
        String endpoint = System.getenv("MOP_MONITORING_SOURCE_ENDPOINT");
        var client = new ZabbixReadClient(endpoint, System.getenv("MOP_MONITORING_SOURCE_TOKEN"));
        var source = new MonitoringRecoverySource(new NetworkMonitoringService(bindings, client::call, Clock.systemUTC(), true), endpoint);
        // Real source read evidence remains separate from the synthetic PG transition cases above.
        // Capture before registering the synthetic fault: these points cannot prove recovery of that later fault.
        RecoverySourceBatch actual = source.read(tenant, asset, "icmp");
        assertFalse(actual.samples().isEmpty());
        String rule = ruleCode(); var event = problem(rule); var release = publish(rule, "30", 2, null);
        var ticketAccess = activate(event);
        var repo = repository((t, a, m) -> actual);
        var result = observe(platform, repo, command(event, release, "c".repeat(64)), "actual-source-prior-to-fault");
        assertEquals(false, result.get("applied"));
        assertEquals("NO_SAMPLES", result.get("status"));
        assertNull(result.get("recovered_at"));
        assertNull(result.get("confirmed_at"));
        assertEquals(event.version(), events.eventDetail(access(), event.id()).event().version());
        assertEquals(MonitorState.PROBLEM, events.eventDetail(access(), event.id()).event().monitorState());
        assertNull(((Map<?, ?>) workflows.readSla(ticketAccess, event.ticketId(), Tier.STANDARD)
                .get("recovery_clock")).get("completed_at"));
    }

    @Test
    @Order(11)
    void healthyHistoryTailWithoutThisFaultTransitionCannotCommitALaterInventedT2() throws Exception {
        String rule = ruleCode();
        var event = problem(rule);
        var release = publish(rule, "0", 1, null);
        var repository = repository((t, a, m) -> {
            Instant latest = dbNow().minusMillis(2);
            return samples(List.of(new RecoveryObservation.Sample(latest.minusMillis(1), RecoveryObservation.Quality.KNOWN, BigDecimal.ONE),
                    new RecoveryObservation.Sample(latest, RecoveryObservation.Quality.KNOWN, BigDecimal.ONE)));
        });
        var error = assertThrows(DomainException.class,
                () -> observe(platform, repository, command(event, release, "d".repeat(64)), "healthy-tail-only"));
        assertEquals("RECOVERY_HISTORY_INCOMPLETE", error.code);
        assertEquals(0, proofs(event.id()));
        assertEquals(event.version(), events.eventDetail(access(), event.id()).event().version());
        assertEquals(MonitorState.PROBLEM, events.eventDetail(access(), event.id()).event().monitorState());
    }

    static DataSource missedCas(AtomicBoolean hit) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            Object value = invoke(pool, method, args);
            if (!method.getName().equals("getConnection")) { return value; }
            Connection real = (Connection) value;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
                Object result = invoke(real, cm, ca);
                if (!cm.getName().equals("prepareStatement") || !((String) ca[0]).startsWith("UPDATE public.mop_monitoring_event SET version=version+1")) { return result; }
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                    if (sm.getName().equals("executeUpdate") && hit.compareAndSet(false, true)) { return 0; }
                    return invoke(result, sm, sa);
                });
            });
        });
    }
    static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
