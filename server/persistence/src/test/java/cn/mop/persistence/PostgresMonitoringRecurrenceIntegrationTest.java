package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.TicketWorkflowRepository;
import cn.mop.domain.ticket.WorkflowLifecycle.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Only fresh designated-server namespaces from verify-monitoring-isolated.py; no old/demo/actual identity. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_MONITORING_RECURRENCE_PG_TESTS", matches = "true")
class PostgresMonitoringRecurrenceIntegrationTest {
    static HikariDataSource pool;
    static PostgresPlatformRepository platform;
    static PostgresMonitoringEventCenterRepository events;
    static PostgresTicketWorkflowRepository workflow;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static String tenant;
    static String foreignTenant;
    static String actor;
    static String asset;
    static String foreignAsset;
    static long permissionVersion;

    @BeforeAll
    static void connect() throws Exception {
        tenant = scope("TENANT");
        foreignTenant = scope("FOREIGN_TENANT");
        actor = scope("ACTOR");
        asset = scope("ASSET");
        foreignAsset = scope("FOREIGN_ASSET");
        var config = new HikariConfig();
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        assertEquals("mop_test_runtime", System.getenv("SPRING_DATASOURCE_USERNAME"));
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(6);
        config.setConnectionTimeout(5000);
        pool = new HikariDataSource(config);
        try (var c = pool.getConnection()) {
            c.setReadOnly(true);
            for (String id : List.of(tenant, foreignTenant)) {
                try (var s = c.prepareStatement("SELECT name FROM public.mop_tenant WHERE id=?::uuid")) {
                    s.setString(1, id);
                    try (var r = s.executeQuery()) {
                        assertTrue(r.next());
                        assertTrue(r.getString(1).startsWith("SYNTHETIC_MONITORING_"));
                    }
                }
            }
            try (var s = c.prepareStatement("SELECT identity_source,"
                    + "EXISTS(SELECT 1 FROM public.mop_local_account WHERE user_id=u.id),"
                    + "EXISTS(SELECT 1 FROM public.mop_external_identity_binding WHERE user_id=u.id),"
                    + "EXISTS(SELECT 1 FROM public.mop_membership WHERE user_id=u.id AND tenant_id NOT IN (?::uuid,?::uuid)) "
                    + "FROM public.mop_user u WHERE id=?::uuid AND active")) {
                s.setString(1, tenant);
                s.setString(2, foreignTenant);
                s.setString(3, actor);
                try (var r = s.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals("isolated_monitoring", r.getString(1));
                    assertFalse(r.getBoolean(2));
                    assertFalse(r.getBoolean(3));
                    assertFalse(r.getBoolean(4));
                }
            }
        }
        platform = new PostgresPlatformRepository(pool, JSON);
        events = new PostgresMonitoringEventCenterRepository(pool, platform);
        workflow = new PostgresTicketWorkflowRepository(pool, platform, JSON);
        permissionVersion = platform.membership(actor, tenant).orElseThrow().permissionVersion();
        assertTrue(events.readinessProblems().isEmpty());
        assertTrue(workflow.readinessProblems().isEmpty());
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    static String scope(String suffix) {
        String value = System.getenv("MOP_MONITORING_" + suffix);
        assertNotNull(value);
        assertEquals(UUID.fromString(value).toString(), value);
        assertFalse(value.startsWith("55000000-"));
        return value;
    }

    static Access access() {
        return new Access(tenant, actor, permissionVersion, UUID.randomUUID().toString());
    }

    static TicketWorkflowRepository.Access ticketAccess() {
        return new TicketWorkflowRepository.Access(tenant, actor, permissionVersion, UUID.randomUUID().toString());
    }

    static EventSummary signal(String rule, MonitorState phase, String sourceId) {
        return signal(platform, events, rule, phase, sourceId);
    }

    static EventSummary signal(PostgresPlatformRepository p, PostgresMonitoringEventCenterRepository repo,
                               String rule, MonitorState phase, String sourceId) {
        Access a = access();
        var command = new IngestEventCommand(asset, rule, "SYNTHETIC_MONITORING_RECURRENCE", Severity.HIGH,
            phase, sourceId, Instant.now().truncatedTo(ChronoUnit.MILLIS), false);
        Object result = p.idempotentAuthorized(actor, tenant, "POST /integration/monitoring-signal/" + rule,
            UUID.randomUUID().toString(), "a".repeat(64), permissionVersion,
            () -> repo.authorizeWrite(a), () -> repo.ingestEvent(a, command));
        return JSON.convertValue(result, EventSummary.class);
    }

    static Object command(String ticket, Supplier<Object> action) {
        var a = ticketAccess();
        return platform.idempotentAuthorized(actor, tenant, "POST /integration/monitoring-workflow/" + ticket,
            UUID.randomUUID().toString(), "b".repeat(64), permissionVersion,
            () -> workflow.authorizeCommand(a, ticket, false), action);
    }

    record Scenario(String rule, String event, String ticket) {}

    static Scenario active() {
        String rule = "SYNTH_M01_" + UUID.randomUUID().toString().substring(0, 8);
        var problem = signal(rule, MonitorState.PROBLEM, "first-" + rule);
        String ticket = problem.ticketId();
        assertNotNull(ticket);
        command(ticket, () -> workflow.activate(ticketAccess(), ticket, 0, "P2", Tier.STANDARD,
            "SYNTHETIC_MONITORING_TEST_POLICY", (a, p, t, start) -> TicketSlaSnapshot.freeze(p, t, start,
                Duration.ofMillis(5), Duration.ofMillis(10), null, 48L, null, List.of(), Map.of())));
        command(ticket, () -> workflow.transition(ticketAccess(), ticket,
            new Command(Action.ACCEPT, 1, null, null, null, false)));
        command(ticket, () -> workflow.transition(ticketAccess(), ticket,
            new Command(Action.START, 2, null, null, null, false)));
        return new Scenario(rule, problem.id(), ticket);
    }

    static long count(String table, String ticket, String extra) throws Exception {
        try (var c = pool.getConnection();
             var s = c.prepareStatement("SELECT count(*) FROM public." + table
                 + " WHERE tenant_id=?::uuid AND ticket_id=?::uuid " + extra)) {
            s.setString(1, tenant);
            s.setString(2, ticket);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    static long recurrenceTasks(String ticket) throws Exception {
        return count("mop_ticket_workflow_clock", ticket, "AND clock_key LIKE 'recovery:%:monitor:%'");
    }

    @Test
    void recoveryThenRecurrenceSchedulesNewTasksAtOriginalDeadlinesAndRelayConsumesOneIntent() throws Exception {
        var scenario = active();
        var before = workflow.read(ticketAccess(), scenario.ticket());
        signal(scenario.rule(), MonitorState.RECOVERED, "recover-" + scenario.rule());
        workflow.tick(tenant, 100);
        var recurrent = signal(scenario.rule(), MonitorState.PROBLEM, "recur-" + scenario.rule());
        assertEquals(scenario.event(), recurrent.id());
        assertEquals(scenario.ticket(), recurrent.ticketId());
        assertNull(recurrent.recoveredAt());
        assertEquals(3, recurrenceTasks(scenario.ticket()));
        assertEquals(before.get("snapshot_hash"), workflow.read(ticketAccess(), scenario.ticket()).get("snapshot_hash"));
        workflow.tick(tenant, 100);
        assertEquals(3, count("mop_ticket_workflow_outbox", scenario.ticket(), "AND effect_key LIKE 'clock:recovery:%:monitor:%'"));
        assertEquals(1, count("mop_ticket_workflow_outbox", scenario.ticket(), "AND event_type='ticket.monitoring_recurred'"));
        var notifications = new PostgresTicketNotificationRepository(pool, platform, JSON);
        notifications.relay(tenant, 100);
        assertEquals(1, count("mop_notification_intent", scenario.ticket(), "AND event_type='ticket.monitoring_recurred' AND external_dispatch='NOT_CONFIGURED'"));
        assertEquals(1, count("mop_notification_message", scenario.ticket(), "AND title LIKE '%监控故障复发'"));
        assertEquals(0, notifications.relay(tenant, 100));
    }

    @Test
    void duplicateDeliveryAndConcurrentProblemSignalsCreateOnlyOneCycle() throws Exception {
        var scenario = active();
        signal(scenario.rule(), MonitorState.RECOVERED, "recover-" + scenario.rule());
        var ready = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> { ready.await(); return signal(scenario.rule(), MonitorState.PROBLEM, "one-" + scenario.rule()); });
            var two = executor.submit(() -> { ready.await(); return signal(scenario.rule(), MonitorState.PROBLEM, "two-" + scenario.rule()); });
            ready.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        signal(scenario.rule(), MonitorState.PROBLEM, "one-" + scenario.rule());
        assertEquals(3, recurrenceTasks(scenario.ticket()));
        assertEquals(1, count("mop_ticket_workflow_outbox", scenario.ticket(), "AND event_type='ticket.monitoring_recurred'"));
    }

    @Test
    void newerProblemOnsetRetiresOlderCycleAndConcurrentTicksEmitEachNewThresholdOnce() throws Exception {
        var scenario = active();
        signal(scenario.rule(), MonitorState.RECOVERED, "recover1-" + scenario.rule());
        signal(scenario.rule(), MonitorState.PROBLEM, "recur1-" + scenario.rule());
        signal(scenario.rule(), MonitorState.RECOVERED, "recover2-" + scenario.rule());
        signal(scenario.rule(), MonitorState.PROBLEM, "recur2-" + scenario.rule());
        assertEquals(6, recurrenceTasks(scenario.ticket()));
        var ready = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> { ready.await(); return workflow.tick(tenant, 100); });
            var two = executor.submit(() -> { ready.await(); return workflow.tick(tenant, 100); });
            ready.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        workflow.tick(tenant, 100);
        assertEquals(3, count("mop_ticket_workflow_outbox", scenario.ticket(), "AND effect_key LIKE 'clock:recovery:%:monitor:%'"));
        assertEquals(0, count("mop_ticket_workflow_outbox", scenario.ticket(), "AND effect_key IN ('clock:recovery:50','clock:recovery:80','clock:recovery:100')"));
    }

    @Test
    void injectedTaskFailureRollsBackEventMembersOutboxAndAttemptTogether() throws Exception {
        var scenario = active();
        var recovered = signal(scenario.rule(), MonitorState.RECOVERED, "recover-" + scenario.rule());
        var failureSeen = new AtomicBoolean();
        DataSource faulted = failingSource("INSERT INTO public.mop_ticket_workflow_clock(", failureSeen);
        var p = new PostgresPlatformRepository(faulted, JSON);
        var repo = new PostgresMonitoringEventCenterRepository(faulted, p);
        assertEquals(503, assertThrows(DomainException.class, () -> signal(p, repo,
            scenario.rule(), MonitorState.PROBLEM, "retryable-" + scenario.rule())).status);
        assertTrue(failureSeen.get());
        var after = events.eventDetail(access(), scenario.event());
        assertEquals(recovered.version(), after.event().version());
        assertEquals(MonitorState.RECOVERED, after.event().monitorState());
        assertEquals(recovered.occurrenceCount(), after.members().size());
        assertEquals(0, recurrenceTasks(scenario.ticket()));
        assertEquals(0, count("mop_ticket_workflow_outbox", scenario.ticket(), "AND event_type='ticket.monitoring_recurred'"));
        signal(scenario.rule(), MonitorState.PROBLEM, "retryable-" + scenario.rule());
        assertEquals(3, recurrenceTasks(scenario.ticket()));
    }

    @Test
    void terminalWorkflowIsNeverReusedEvenInsideEventMergeWindow() throws Exception {
        var scenario = active();
        signal(scenario.rule(), MonitorState.RECOVERED, "recover-" + scenario.rule());
        command(scenario.ticket(), () -> workflow.transition(ticketAccess(), scenario.ticket(),
            new Command(Action.CANCEL, 3, null, "SYNTHETIC_CANCEL", null, false)));
        var next = signal(scenario.rule(), MonitorState.PROBLEM, "new-" + scenario.rule());
        assertNotEquals(scenario.event(), next.id());
        assertNotEquals(scenario.ticket(), next.ticketId());
        assertEquals(0, recurrenceTasks(scenario.ticket()));
    }

    @Test
    void newEventLinkedToSameFormalTicketCreatesCycleAndRetainsOriginalSnapshot() throws Exception {
        var scenario = active();
        var recovered = signal(scenario.rule(), MonitorState.RECOVERED, "recover-" + scenario.rule());
        Access a = access();
        String rootCause = "SYNTHETIC_REVIEW_ROOT_CAUSE_WITH_AT_LEAST_TWENTY_CHARACTERS";
        var resolved = (EventSummary) platform.idempotentAuthorized(actor, tenant, "POST /integration/resolve",
            UUID.randomUUID().toString(), "c".repeat(64), permissionVersion, () -> events.authorizeWrite(a),
            () -> events.transitionEvent(a, new TransitionEventCommand(recovered.id(), recovered.version(), "RESOLVE", rootCause)));
        platform.idempotentAuthorized(actor, tenant, "POST /integration/close-event",
            UUID.randomUUID().toString(), "d".repeat(64), permissionVersion, () -> events.authorizeWrite(a),
            () -> events.transitionEvent(a, new TransitionEventCommand(resolved.id(), resolved.version(), "CLOSE", null)));
        var before = workflow.read(ticketAccess(), scenario.ticket());
        var next = signal(scenario.rule(), MonitorState.PROBLEM, "new-" + scenario.rule());
        assertNotEquals(scenario.event(), next.id());
        assertEquals(scenario.ticket(), next.ticketId());
        assertEquals(3, recurrenceTasks(scenario.ticket()));
        assertEquals(before.get("snapshot_hash"), workflow.read(ticketAccess(), scenario.ticket()).get("snapshot_hash"));
    }

    @Test
    void crossTenantObjectsRemain404() {
        var a = access();
        assertEquals(404, assertThrows(DomainException.class, () -> platform.idempotentAuthorized(actor, tenant,
            "POST /integration/foreign-asset", UUID.randomUUID().toString(), "f".repeat(64), permissionVersion,
            () -> events.authorizeWrite(a), () -> events.ingestEvent(a, new IngestEventCommand(foreignAsset,
                "SYNTH_FOREIGN", "SYNTHETIC", Severity.HIGH, MonitorState.PROBLEM, "foreign", Instant.now(), false)))).status);
    }

    static DataSource failingSource(String prefix, AtomicBoolean seen) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
                Object value = invoke(pool, method, args);
                if (!method.getName().equals("getConnection")) {
                    return value;
                }
                Connection real = (Connection) value;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (cp, cm, ca) -> {
                        Object result = invoke(real, cm, ca);
                        if (!cm.getName().equals("prepareStatement") || !((String) ca[0]).startsWith(prefix)) {
                            return result;
                        }
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                            (sp, sm, sa) -> {
                                if (sm.getName().equals("executeUpdate") && seen.compareAndSet(false, true)) {
                                    throw new SQLException("Synthetic rollback injection", "40001");
                                }
                                return invoke(result, sm, sa);
                            });
                    });
            });
    }

    static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
