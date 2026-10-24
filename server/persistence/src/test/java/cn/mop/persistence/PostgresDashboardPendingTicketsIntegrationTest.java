package cn.mop.persistence;

import cn.mop.domain.CustomerReport;
import cn.mop.domain.CustomerReportRegistration;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Access;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.TicketWorkflowRepository;
import cn.mop.domain.ticket.WorkflowLifecycle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh explicit isolated tenants on the designated PG; no old identity or actual-customer fixture writes. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_DASHBOARD_PENDING_PG_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresDashboardPendingTicketsIntegrationTest {
    static PostgresDashboardPendingTicketsReader reader;
    static PostgresTicketWorkflowRepository workflow;
    static String viewer;
    static String otherViewer;
    static String otherTicket;

    @BeforeAll
    static void prepare() throws Exception {
        PostgresReportCaptureIsolatedIntegrationTest.connect();
        viewer = uuidEnv("MOP_DASHBOARD_TEST_VIEWER");
        otherViewer = uuidEnv("MOP_DASHBOARD_TEST_OTHER_VIEWER");
        reader = new PostgresDashboardPendingTicketsReader(pool, platform);
        workflow = new PostgresTicketWorkflowRepository(pool, platform, mapper);
        assertEquals(0, reader.read(who(actor, tenant)).registrations());
        for (String user : List.of(viewer, otherViewer)) {
            assertEquals("viewer", platform.membership(user, tenant).orElseThrow().role());
            try (var connection = pool.getConnection();
                 var statement = connection.prepareStatement("SELECT identity_source FROM public.mop_user WHERE id=?::uuid")) {
                statement.setString(1, user);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("isolated_report_capture", rows.getString(1));
                }
            }
        }
        var tickets = new ArrayList<String>();
        for (int i = 0; i < 7; i++) {
            String ticket = register(viewer, tenant);
            activate(ticket, tenant);
            tickets.add(ticket);
        }
        transition(tickets.get(1), WorkflowLifecycle.Action.ACCEPT, 1);
        transition(tickets.get(1), WorkflowLifecycle.Action.START, 2);
        transition(tickets.get(2), WorkflowLifecycle.Action.ACCEPT, 1);
        transition(tickets.get(2), WorkflowLifecycle.Action.START, 2);
        transition(tickets.get(2), WorkflowLifecycle.Action.RESOLVE, 3);
        transition(tickets.get(6), WorkflowLifecycle.Action.CANCEL, 1);
        register(viewer, tenant);
        otherTicket = register(otherViewer, tenant);
        activate(otherTicket, tenant);
        activate(register(viewer, foreignTenant), foreignTenant);
    }

    @AfterAll
    static void finish() {
        PostgresReportCaptureIsolatedIntegrationTest.close();
    }

    static Access who(String user, String scope) {
        return new Access(user, scope, platform.membership(user, scope).orElseThrow().permissionVersion());
    }

    static String register(String user, String scope) {
        var report = new CustomerReport("SYNTHETIC_DASHBOARD_PENDING", "SYNTHETIC isolated dashboard aggregation",
                "其他", "individual", "SYNTHETIC", "00000000000", "normal", null);
        var access = who(user, scope);
        return ((CustomerReportRegistration) platform.idempotentAuthorized(user, scope,
                "POST /integration/dashboard-registration", UUID.randomUUID().toString(), "d".repeat(64),
                access.permissionVersion(), () -> { }, () -> platform.registerCustomerReport(scope, user, report))).ticketId();
    }

    static Object command(String ticket, String scope, Supplier<Object> action) {
        var access = new TicketWorkflowRepository.Access(scope, actor, who(actor, scope).permissionVersion(), UUID.randomUUID().toString());
        return platform.idempotentAuthorized(actor, scope, "POST /integration/dashboard-workflow/" + ticket,
                UUID.randomUUID().toString(), "e".repeat(64), access.permissionVersion(),
                () -> workflow.authorizeCommand(access, ticket, false), action);
    }

    static TicketWorkflowRepository.Access workflowAccess(String scope) {
        return new TicketWorkflowRepository.Access(scope, actor, who(actor, scope).permissionVersion(), UUID.randomUUID().toString());
    }

    static void activate(String ticket, String scope) {
        command(ticket, scope, () -> workflow.activate(workflowAccess(scope), ticket, 0, "P1", Tier.STANDARD,
                "SYNTHETIC isolated workflow configuration", (access, priority, tier, start) ->
                        TicketSlaSnapshot.freeze(priority, tier, start, null, null, null, null, null, List.of(), Map.of())));
    }

    static void transition(String ticket, WorkflowLifecycle.Action action, long version) {
        command(ticket, tenant, () -> workflow.transition(workflowAccess(tenant), ticket,
                new WorkflowLifecycle.Command(action, version, null,
                        "SYNTHETIC only: isolated report workflow result, no real device action was performed", null, false)));
    }

    @Test
    @Order(1)
    void countsAndTopFiveIncludeAllNonterminalStatesWithoutGuessingRegistrationState() throws Exception {
        var before = counts();
        var snapshot = reader.read(who(actor, tenant));
        assertEquals(9, snapshot.registrations());
        assertEquals(7, snapshot.knownPending());
        assertEquals(1, snapshot.terminal());
        assertEquals(1, snapshot.unconfigured());
        assertNull(snapshot.pendingTickets());
        assertEquals(5, snapshot.items().size());
        assertEquals(otherTicket, snapshot.items().get(0).ticketId().toString());
        assertTrue(snapshot.items().stream().anyMatch(item -> "RESOLVED".equals(item.state())));
        assertFalse(snapshot.items().stream().anyMatch(item -> "CANCELLED".equals(item.state())));
        assertEquals(before, counts());
    }

    @Test
    @Order(1)
    void actionableProjectionExcludesResolvedBeforeTopFiveAndCounting() {
        var snapshot = reader.readSnapshot(who(actor, tenant), true, (connection, value) -> value);
        assertEquals(9, snapshot.registrations());
        assertEquals(6, snapshot.knownPending());
        assertEquals(2, snapshot.terminal()); // Closed/cancelled OR resolved are excluded for M1-6.
        assertEquals(1, snapshot.unconfigured());
        assertEquals(5, snapshot.items().size());
        assertTrue(snapshot.items().stream().noneMatch(item -> "RESOLVED".equals(item.state())));
    }

    @Test
    @Order(2)
    void customerOnlySeesOwnRegistrationsAndWorkflowHeads() {
        var own = reader.read(who(viewer, tenant));
        assertEquals("REPORTER_ONLY", own.scope());
        assertEquals(8, own.registrations());
        assertEquals(6, own.knownPending());
        assertFalse(own.items().stream().anyMatch(item -> otherTicket.equals(item.ticketId().toString())));
        var other = reader.read(who(otherViewer, tenant));
        assertEquals(1, other.registrations());
        assertEquals(1L, other.pendingTickets());
        assertEquals(otherTicket, other.items().get(0).ticketId().toString());
    }

    @Test
    @Order(3)
    void tenantScopeAndStaleEpochCannotLeakCounts() {
        assertEquals(1, reader.read(who(actor, foreignTenant)).registrations());
        var access = who(actor, tenant);
        assertEquals(401, assertThrows(DomainException.class,
                () -> reader.read(new Access(actor, tenant, access.permissionVersion() + 1))).status);
        assertEquals(401, assertThrows(DomainException.class,
                () -> reader.read(new Access(actor, UUID.randomUUID().toString(), 1))).status);
    }

    @Test
    @Order(4)
    void totalsAndTopFiveShareReadonlyRepeatableSnapshotDuringConcurrentWrite() {
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
                        assertFalse(connection.getAutoCommit());
                        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (sp, sm, sa) -> {
                            Object rows = invoke(sm, statement, sa);
                            if (sm.getName().equals("executeQuery") && injected.compareAndSet(false, true)) {
                                register(otherViewer, tenant);
                            }
                            return rows;
                        });
                    });
                });
        var snapshot = new PostgresDashboardPendingTicketsReader(wrapped, new PostgresPlatformRepository(wrapped, mapper))
                .read(who(actor, tenant));
        assertTrue(injected.get());
        assertEquals(9, snapshot.registrations());
        assertEquals(1, snapshot.unconfigured());
        assertEquals(10, reader.read(who(actor, tenant)).registrations());
    }

    static Object invoke(java.lang.reflect.Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
