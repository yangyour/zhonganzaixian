package cn.mop.persistence;

import cn.mop.domain.dashboard.DashboardPendingTicketSlaReader;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import java.time.Duration;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static cn.mop.persistence.PostgresDashboardPendingTicketsIntegrationTest.*;
import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PG in fresh isolated tenants. The base fixture verifies scope before any registration. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_DASHBOARD_SLA_PG_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresDashboardPendingTicketSlaIntegrationTest {
    static PostgresDashboardPendingTicketSlaReader slaReader;
    static String breachedTicket;
    static String runningTicket;

    @BeforeAll
    static void prepareSla() throws Exception {
        PostgresDashboardPendingTicketsIntegrationTest.prepare();
        slaReader = new PostgresDashboardPendingTicketSlaReader(pool, platform,
                new PostgresTicketLifecycleRepository(pool, platform, mapper, false), mapper);
        breachedTicket = register(viewer, tenant);
        openConfigured(breachedTicket, "P1", Duration.ofMillis(1), Duration.ofMillis(1));
        runningTicket = register(viewer, tenant);
        openConfigured(runningTicket, "P2", Duration.ofHours(1), Duration.ofHours(2));
    }

    @AfterAll
    static void closeSla() {
        PostgresDashboardPendingTicketsIntegrationTest.finish();
    }

    static void openConfigured(String ticket, String priority, Duration response, Duration recovery) {
        PostgresDashboardPendingTicketsIntegrationTest.command(ticket, tenant,
                () -> workflow.activate(workflowAccess(tenant), ticket, 0, priority, Tier.STANDARD,
                        "SYNTHETIC dashboard clock fixture, not customer approved",
                        (access, selectedPriority, selectedTier, start) -> TicketSlaSnapshot.freeze(selectedPriority,
                                selectedTier, start, response, recovery, null, null, null, List.of(), Map.of())));
    }

    @Test
    @Order(1)
    void sameSnapshotEvaluatesKnownBreachesAndUnknownsWithoutFalseCompleteCount() throws Exception {
        var before = counts();
        var snapshot = slaReader.read(who(actor, tenant));
        assertEquals(11, snapshot.base().registrations());
        assertEquals(9, snapshot.base().knownPending());
        assertEquals(9, snapshot.evaluatedPending());
        assertEquals(1, snapshot.knownOverdue());
        assertEquals(7, snapshot.unknownEvaluated());
        assertEquals(8, snapshot.unknownSla());
        assertNull(snapshot.overdueSla());
        var running = find(snapshot, runningTicket);
        assertEquals("P2", running.sla().priority());
        assertEquals(false, running.sla().overdue());
        assertTrue(running.sla().response().remainingMillis() > 0);
        assertTrue(running.sla().response().remainingMillis() <= Duration.ofHours(1).toMillis());
        assertEquals("FROZEN_SERVICE_WINDOWS", running.sla().response().remainingBasis());
        var breached = find(snapshot, breachedTicket);
        assertEquals("P1", breached.sla().priority());
        assertEquals(true, breached.sla().overdue());
        assertEquals(0L, breached.sla().response().remainingMillis());
        for (var item : snapshot.items()) {
            assertEquals(item.ticket().version(), item.sla().version());
        }
        assertEquals(before, counts());
    }

    @Test
    @Order(2)
    void reporterAndTenantScopeApplyToBothCountsAndSlaDetails() {
        var own = slaReader.read(who(otherViewer, tenant));
        assertEquals("REPORTER_ONLY", own.base().scope());
        assertEquals(1, own.base().registrations());
        assertEquals(0, own.knownOverdue());
        assertEquals(1, own.unknownSla());
        assertEquals(otherTicket, own.items().get(0).ticket().ticketId().toString());
        assertTrue(own.items().stream().noneMatch(item -> item.ticket().ticketId().toString().equals(breachedTicket)));
        var foreign = slaReader.read(who(actor, foreignTenant));
        assertEquals(1, foreign.base().registrations());
        assertEquals(0, foreign.knownOverdue());
        assertEquals(1, foreign.unknownSla());
    }

    @Test
    @Order(3)
    void legacyProjectionRemainsIndependentAndUnchanged() {
        var v1 = reader.read(who(actor, tenant));
        var v2 = slaReader.read(who(actor, tenant));
        assertEquals(v1.items(), v2.base().items());
        assertEquals(v1.knownPending(), v2.base().knownPending());
        assertEquals(v1.unconfigured(), v2.base().unconfigured());
        assertEquals(5, v1.items().size());
    }

    @Test
    @Order(4)
    void workflowTransitionDuringReadCannotMixItemAndSlaVersions() {
        var injected = new AtomicBoolean();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(method, pool, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (cp, cm, ca) -> {
                        Object value = invoke(cm, connection, ca);
                        if (!cm.getName().equals("prepareStatement") || !ca[0].toString().contains("GROUP BY model,state")) {
                            return value;
                        }
                        assertTrue(connection.isReadOnly());
                        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (sp, sm, sa) -> {
                            Object rows = invoke(sm, statement, sa);
                            if (sm.getName().equals("executeQuery") && injected.compareAndSet(false, true)) {
                                transition(runningTicket, cn.mop.domain.ticket.WorkflowLifecycle.Action.ACCEPT, 1);
                            }
                            return rows;
                        });
                    });
                });
        var isolatedPlatform = new PostgresPlatformRepository(wrapped, mapper);
        var composed = new PostgresDashboardPendingTicketSlaReader(wrapped, isolatedPlatform,
                new PostgresTicketLifecycleRepository(wrapped, isolatedPlatform, mapper, false), mapper);
        var original = find(composed.read(who(actor, tenant)), runningTicket);
        assertTrue(injected.get());
        assertEquals(1, original.ticket().version());
        assertEquals(1, original.sla().version());
        assertEquals("RUNNING", original.sla().response().status());
        var updated = find(slaReader.read(who(actor, tenant)), runningTicket);
        assertEquals(2, updated.ticket().version());
        assertEquals(2, updated.sla().version());
        assertEquals("MET", updated.sla().response().status());
    }

    static DashboardPendingTicketSlaReader.Item find(DashboardPendingTicketSlaReader.Snapshot snapshot, String ticket) {
        return snapshot.items().stream().filter(item -> item.ticket().ticketId().toString().equals(ticket)).findFirst().orElseThrow();
    }
}
