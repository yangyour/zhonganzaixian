package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.DashboardActionableTicketsReader.Page;
import cn.mop.domain.dashboard.DashboardPendingTicketsReader.Access;
import cn.mop.domain.ticket.TicketLifecycleRepository;
import cn.mop.domain.ticket.WorkflowLifecycle;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static cn.mop.persistence.PostgresDashboardPendingTicketsIntegrationTest.*;
import static cn.mop.persistence.PostgresDashboardPendingTicketSlaIntegrationTest.openConfigured;
import static cn.mop.persistence.PostgresReportCaptureIsolatedIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh explicit isolated tenants on the designated PG only; no migrations, legacy identities or customer data. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_DASHBOARD_ACTIONABLE_PG_TESTS", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresDashboardActionableTicketsIntegrationTest {
    static PostgresDashboardActionableTicketsReader actionable;
    static String breachedTicket;
    static String lifecycleTicket;

    @BeforeAll
    static void prepareActionable() throws Exception {
        PostgresDashboardPendingTicketsIntegrationTest.prepare();
        actionable = createReader(pool, platform);
        for (int i = 0; i < 105; i++) {
            String ticket = register(viewer, tenant);
            if (i == 0) {
                breachedTicket = ticket;
                openConfigured(ticket, "P1", Duration.ofMillis(1), Duration.ofMillis(1));
            } else {
                openConfigured(ticket, "P2", Duration.ofDays(1), Duration.ofDays(2));
            }
        }
        lifecycleTicket = register(viewer, tenant);
        var lifecycle = new PostgresTicketLifecycleRepository(pool, platform, mapper, true);
        var access = new TicketLifecycleRepository.Access(actor, tenant, who(actor, tenant).permissionVersion(),
                UUID.randomUUID().toString());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String opening = """
                {"enabled":true,"configuration_release_ref":"SYNTHETIC_ACTIONABLE_TEST_ONLY",
                 "service_tier":"STANDARD","priority":"P1","solution_min_chars":20,
                 "confirmation_timeout_hours":48,"reopen_window_hours":24,"manager_threshold":3}
                """;
        platform.idempotentAuthorized(actor, tenant, "POST /integration/actionable/lifecycle/" + lifecycleTicket,
                UUID.randomUUID().toString(), "a".repeat(64), access.permissionVersion(),
                () -> lifecycle.authorizeCurrentAccess(access, lifecycleTicket, false),
                () -> lifecycle.start(access, lifecycleTicket, opening, now.plusSeconds(600), now.plusSeconds(7200)));
        // Place five resolved registrations ahead of every actionable one. SQL must refill the homepage.
        for (int i = 0; i < 5; i++) {
            String ticket = register(viewer, tenant);
            activate(ticket, tenant);
            transition(ticket, WorkflowLifecycle.Action.ACCEPT, 1);
            transition(ticket, WorkflowLifecycle.Action.START, 2);
            transition(ticket, WorkflowLifecycle.Action.RESOLVE, 3);
        }
    }

    static PostgresDashboardActionableTicketsReader createReader(DataSource source, PostgresPlatformRepository repository) {
        return new PostgresDashboardActionableTicketsReader(source, repository,
                new PostgresTicketLifecycleRepository(source, repository, mapper, false), mapper);
    }

    @AfterAll
    static void closeActionable() {
        PostgresDashboardPendingTicketsIntegrationTest.finish();
    }

    @Test
    @Order(1)
    void latestFiveResolvedRegistrationsAreReplacedAndOldProjectionRemainsCompatible() throws Exception {
        var before = counts();
        var legacy = reader.read(who(actor, tenant));
        assertEquals(5, legacy.items().size());
        assertTrue(legacy.items().stream().allMatch(item -> "RESOLVED".equals(item.state())));
        var result = actionable.read(who(actor, tenant), new Page(1, 5));
        assertEquals(120, result.registrations());
        assertEquals(112, result.knownPending());
        assertEquals(7, result.excluded());
        assertEquals(1, result.unconfigured());
        assertNull(result.pendingTickets());
        assertEquals(5, result.items().size());
        assertTrue(result.items().stream().noneMatch(item -> List.of("RESOLVED", "CLOSED", "CANCELLED")
                .contains(item.ticket().state())));
        var lifecycle = result.items().get(0);
        assertEquals(lifecycleTicket, lifecycle.ticket().ticketId().toString());
        assertEquals("LIFECYCLE_V42", lifecycle.ticket().workflowModel());
        assertEquals("LIFECYCLE_SLA_DISABLED", lifecycle.sla().response().missingReason());
        assertNull(lifecycle.sla().overdue());
        assertEquals(before, counts());
    }

    @Test
    @Order(2)
    void fullPopulationSlaIncludesTheBreachBeyondOneHundredAndAllPagesAreReachable() {
        var first = actionable.read(who(actor, tenant), new Page(1, 100));
        var last = actionable.read(who(actor, tenant), new Page(2, 100));
        assertEquals(100, first.items().size());
        assertEquals(12, last.items().size());
        assertEquals(2, first.totalPages());
        assertEquals(1, first.knownOverdue());
        assertEquals(first.knownOverdue(), last.knownOverdue());
        assertEquals(7, first.unknownEvaluated());
        assertEquals(8, first.unknownSla());
        assertNull(first.overdueSla());
        assertFalse(first.items().stream().anyMatch(item -> breachedTicket.equals(item.ticket().ticketId().toString())));
        var breached = last.items().stream().filter(item -> breachedTicket.equals(item.ticket().ticketId().toString()))
                .findFirst().orElseThrow();
        assertEquals(true, breached.sla().overdue());
        assertEquals(0L, breached.sla().response().remainingMillis());
        var ids = new HashSet<UUID>();
        first.items().forEach(item -> assertTrue(ids.add(item.ticket().ticketId())));
        last.items().forEach(item -> assertTrue(ids.add(item.ticket().ticketId())));
        assertEquals(112, ids.size());
        assertEquals(List.of(), actionable.read(who(actor, tenant), new Page(3, 100)).items());
    }

    @Test
    @Order(3)
    void reporterTenantAndPermissionVersionConstrainBothSlaTotalsAndRows() {
        var other = actionable.read(who(otherViewer, tenant), new Page(1, 5));
        assertEquals("REPORTER_ONLY", other.scope());
        assertEquals(1L, other.pendingTickets());
        assertEquals(otherTicket, other.items().get(0).ticket().ticketId().toString());
        assertEquals(0, other.knownOverdue());
        assertEquals(1, other.unknownSla());
        assertEquals(1, actionable.read(who(actor, foreignTenant), new Page(1, 5)).registrations());
        assertEquals(401, assertThrows(DomainException.class, () -> actionable.read(
                new Access(actor, tenant, who(actor, tenant).permissionVersion() + 1), new Page(1, 5))).status);
        assertEquals(401, assertThrows(DomainException.class, () -> actionable.read(
                new Access(actor, UUID.randomUUID().toString(), 1), new Page(1, 5))).status);
    }

    @Test
    @Order(4)
    void concurrentRegistrationCannotMixCountsPageAndSlaSnapshots() {
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
        var isolatedPlatform = new PostgresPlatformRepository(wrapped, mapper);
        var original = createReader(wrapped, isolatedPlatform).read(who(actor, tenant), new Page(1, 5));
        assertTrue(injected.get());
        assertEquals(120, original.registrations());
        assertEquals(112, original.knownPending());
        assertEquals(1, original.knownOverdue());
        assertEquals(1, original.unconfigured());
        var updated = actionable.read(who(actor, tenant), new Page(1, 5));
        assertEquals(121, updated.registrations());
        assertEquals(112, updated.knownPending());
        assertEquals(2, updated.unconfigured());
    }
}
