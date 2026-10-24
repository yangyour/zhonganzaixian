package cn.mop.persistence;

import static cn.mop.persistence.NotificationControlStore.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.NotificationControlStore.Access;
import cn.mop.persistence.NotificationControlStore.Entry;
import cn.mop.persistence.NotificationControlStore.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual designated V55 transactions; all sources/receipts are explicitly synthetic and no provider is invoked. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_NOTIFICATION_CANCELLATION_PG_TESTS", matches = "true")
class PostgresNotificationCancellationIntegrationTest {

    private static HikariDataSource source;
    private static UUID tenant, foreignTenant, actor;
    private static String otherJournalFingerprint;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static NotificationControlStore store;
    private static PostgresPlatformRepository platform;
    private static NotificationRequestCancellationService cancellations;

    @BeforeAll
    static void connect() throws Exception {
        tenant = UUID.fromString(System.getenv("MOP_REPORT_CAPTURE_TENANT"));
        foreignTenant = UUID.fromString(System.getenv("MOP_REPORT_CAPTURE_FOREIGN_TENANT"));
        actor = UUID.fromString(System.getenv("MOP_REPORT_CAPTURE_ACTOR"));
        assertNotEquals("55000000-0000-4000-8000-000000000001", tenant.toString());
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"));
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(8);
        source = new HikariDataSource(config);
        try (
            var connection = source.getConnection();
            var query = connection.prepareStatement(
                "SELECT t.name,u.identity_source,u.subject,current_user FROM public.mop_tenant t " +
                    "JOIN public.mop_membership m ON m.tenant_id=t.id JOIN public.mop_user u ON u.id=m.user_id " +
                    "WHERE t.id=? AND u.id=?"
            )
        ) {
            query.setObject(1, tenant);
            query.setObject(2, actor);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("SYNTHETIC_REPORT_CAPTURE_" + actor + "_0", row.getString(1));
                assertEquals("isolated_report_capture", row.getString(2));
                assertEquals("SYNTHETIC_REPORT_CAPTURE_" + actor, row.getString(3));
                assertEquals("mop_test_runtime", row.getString(4));
            }
        }
        store = new NotificationControlStore(source, JSON);
        assertTrue(
            store.ready(),
            "Existing runtime must satisfy V50/V51, audit ACL and cancellation isolation checks"
        );
        platform = new PostgresPlatformRepository(source, JSON);
        cancellations = new NotificationRequestCancellationService(store, platform);
        otherJournalFingerprint = otherJournalFingerprint();
    }

    @AfterAll
    static void close() throws Exception {
        if (source != null) {
            try {
                assertEquals(otherJournalFingerprint, otherJournalFingerprint());
            } finally {
                source.close();
            }
        }
    }

    private static String otherJournalFingerprint() throws Exception {
        try (
            var connection = source.getConnection();
            var query = connection.prepareStatement(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(md5(to_jsonb(j)::text),'' ORDER BY tenant_id,delivery_id,version),'')) " +
                    "FROM public.mop_delivery_receipt_journal j WHERE tenant_id<>?"
            )
        ) {
            query.setObject(1, tenant);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private Access access() {
        return new Access(tenant, actor, 1, "synthetic-cancel-pg");
    }

    private record Request(UUID id, UUID intent, UUID pool, List<UUID> routes) {}

    private Request request(int routeCount) {
        UUID id = UUID.randomUUID(),
            intent = UUID.randomUUID(),
            pool = UUID.randomUUID();
        var state = JSON.createObjectNode();
        state.put("message_id", id.toString());
        state.put("intent_id", intent.toString());
        state.put("receiver_id", actor.toString());
        state.put("phase", "OPENED");
        state.put("status", routeCount == 0 ? "WAITING_CONFIGURATION" : "ROUTED");
        state.put("reason", "SYNTHETIC_CANCELLATION_INPUT");
        state.put("request_sha256", hash(id.toString()));
        var routes = new ArrayList<UUID>();
        store.transaction(access(), "configurations:write", connection -> {
            store.append(connection, access(), Kind.REQUEST, id, -1, UUID.randomUUID(), "DISCOVER", state);
            for (int index = 0; index < routeCount; index++) {
                Channel channel = index == 0 ? Channel.EMAIL : Channel.WECHAT_WORK;
                UUID routeId = identity("notification-route|" + tenant + "|" + id + "|" + channel);
                ObjectNode route = state.deepCopy();
                route.put("channel", channel.name());
                route.put("resource_pool_id", pool.toString());
                route.put("attempt", 0);
                route.put("status", "QUEUED");
                store.append(
                    connection,
                    access(),
                    Kind.ROUTE,
                    routeId,
                    -1,
                    UUID.randomUUID(),
                    "ROUTE",
                    route
                );
                routes.add(routeId);
            }
            return null;
        });
        return new Request(id, intent, pool, List.copyOf(routes));
    }

    private Scope scope(Request request) {
        return new Scope(
            tenant,
            UUID.randomUUID(),
            new Deduplication(request.pool(), request.intent(), Channel.EMAIL, actor, "OPENED")
        );
    }

    private PostgresDeliveryReceiptRepository writer() {
        return new PostgresDeliveryReceiptRepository(
            source,
            (connection, access, scope, operation) ->
                PostgresAuthorization.require(
                    connection,
                    actor.toString(),
                    tenant.toString(),
                    1,
                    "configurations:write"
                ),
            (connection, access, scope, command) -> {
                throw new AssertionError("No provider transition is part of cancellation validation");
            }
        );
    }

    private void queue(Scope scope) {
        writer().queue(
            new PostgresDeliveryReceiptRepository.Access(tenant, actor, 1),
            scope,
            UUID.randomUUID(),
            Instant.now().minusSeconds(1),
            "synthetic-cancel-queue"
        );
    }

    private Entry current(Request request) {
        return store.transaction(access(), "ticket_notes:read_internal", connection ->
            store.current(connection, tenant, Kind.REQUEST, request.id(), false).orElseThrow()
        );
    }

    private long auditCount(Request request) {
        return platform
            .audits(tenant.toString())
            .stream()
            .filter(
                row ->
                    request.id().toString().equals(row.get("resource_id")) &&
                    "notification.request.cancel".equals(row.get("action"))
            )
            .count();
    }

    private String outcome(Callable<?> work) throws Exception {
        try {
            work.call();
            return "COMMITTED";
        } catch (DomainException rejected) {
            assertEquals(409, rejected.status);
            return "CONFLICT";
        }
    }

    private List<String> race(Callable<?> one, Callable<?> two) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return outcome(one);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return outcome(two);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelsRoutesAndRequestWithOneAuditAndExactOriginalReplay() {
        var request = request(2);
        UUID command = UUID.randomUUID();
        var result = cancellations.cancel(access(), request.id(), 0, command, "SYNTHETIC stop planning");
        assertEquals(1, result.version());
        assertEquals("CANCELLED", result.state().path("status").asText());
        assertEquals(2, result.state().path("cancelled_route_count").asInt());
        assertEquals(1, auditCount(request));
        assertEquals(
            result,
            cancellations.cancel(access(), request.id(), 0, command, "SYNTHETIC stop planning")
        );
        assertEquals(1, auditCount(request));
        assertThrows(DomainException.class, () ->
            cancellations.cancel(access(), request.id(), 0, command, "different reason")
        );
        for (UUID route : request.routes()) {
            var row = store.transaction(access(), "ticket_notes:read_internal", connection ->
                store.current(connection, tenant, Kind.ROUTE, route, false).orElseThrow()
            );
            assertEquals(1, row.version());
            assertEquals("CANCELLED", row.state().path("status").asText());
        }
        var planned = new NotificationRoutingService(store, JSON).plan(
            access(),
            request.id(),
            1,
            UUID.randomUUID(),
            Instant.now()
        );
        assertEquals(result, planned);
        assertEquals(1, auditCount(request));
    }

    @Test
    void anExistingDeliveryRootBlocksCancellationWithoutPartialRouteWrites() {
        var request = request(2);
        queue(scope(request));
        var rejected = assertThrows(DomainException.class, () ->
            cancellations.cancel(access(), request.id(), 0, UUID.randomUUID(), "SYNTHETIC blocked")
        );
        assertEquals("NOTIFICATION_CANCELLATION_BLOCKED", rejected.code);
        assertEquals(0, current(request).version());
        assertEquals(0, auditCount(request));
        for (UUID route : request.routes()) {
            assertEquals(
                0,
                store
                    .transaction(access(), "ticket_notes:read_internal", connection ->
                        store.current(connection, tenant, Kind.ROUTE, route, false).orElseThrow()
                    )
                    .version()
            );
        }
    }

    @Test
    void aCancelledSourceCannotCreateANewDeliveryRoot() {
        var request = request(0);
        cancellations.cancel(access(), request.id(), 0, UUID.randomUUID(), "SYNTHETIC stop");
        assertEquals(409, assertThrows(DomainException.class, () -> queue(scope(request))).status);
    }

    @Test
    void simultaneousQueueAndCancellationCannotBothCommit() throws Exception {
        var request = request(1);
        var scope = scope(request);
        var outcomes = race(
            () -> cancellations.cancel(access(), request.id(), 0, UUID.randomUUID(), "SYNTHETIC race"),
            () -> {
                queue(scope);
                return null;
            }
        );
        assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count());
        assertEquals(
            "CANCELLED".equals(current(request).state().path("status").asText()) ? 1 : 0,
            auditCount(request)
        );
    }

    @Test
    void twoDifferentCancellationCommandsHaveOnlyOneWinnerAndAudit() throws Exception {
        var request = request(1);
        var outcomes = race(
            () -> cancellations.cancel(access(), request.id(), 0, UUID.randomUUID(), "SYNTHETIC one"),
            () -> cancellations.cancel(access(), request.id(), 0, UUID.randomUUID(), "SYNTHETIC two")
        );
        assertEquals(1, outcomes.stream().filter("COMMITTED"::equals).count());
        assertEquals(1, auditCount(request));
    }

    @Test
    void twoIdenticalCommandsConvergeToTheOriginalReceipt() throws Exception {
        var request = request(1);
        UUID command = UUID.randomUUID();
        var outcomes = race(
            () -> cancellations.cancel(access(), request.id(), 0, command, "SYNTHETIC same"),
            () -> cancellations.cancel(access(), request.id(), 0, command, "SYNTHETIC same")
        );
        assertEquals(List.of("COMMITTED", "COMMITTED"), outcomes);
        assertEquals(1, auditCount(request));
    }

    @Test
    void anAuditInsertFailureRollsBackEveryCancellationRevision() {
        var request = request(2);
        DataSource broken = (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] { DataSource.class },
            (proxy, method, arguments) -> {
                try {
                    Object result = method.invoke(source, arguments);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] { Connection.class },
                        (self, call, parameters) -> {
                            if (
                                call.getName().equals("prepareStatement") &&
                                parameters[0].toString().startsWith("INSERT INTO public.mop_audit(")
                            ) {
                                throw new SQLException("Synthetic audit insertion failure", "08006");
                            }
                            try {
                                return call.invoke(connection, parameters);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        }
                    );
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        );
        var service = new NotificationRequestCancellationService(
            new NotificationControlStore(broken, JSON),
            platform
        );
        assertThrows(DomainException.class, () ->
            service.cancel(access(), request.id(), 0, UUID.randomUUID(), "SYNTHETIC rollback")
        );
        assertEquals(0, current(request).version());
        assertEquals(0, auditCount(request));
        for (UUID route : request.routes()) {
            assertEquals(
                0,
                store
                    .transaction(access(), "ticket_notes:read_internal", connection ->
                        store.current(connection, tenant, Kind.ROUTE, route, false).orElseThrow()
                    )
                    .version()
            );
        }
    }

    @Test
    void foreignObjectAndStaleAuthorizationCannotRecoverOrCancel() {
        var request = request(0);
        assertEquals(
            404,
            assertThrows(DomainException.class, () ->
                cancellations.cancel(
                    new Access(foreignTenant, actor, 1, "synthetic-cancel-foreign"),
                    request.id(),
                    0,
                    UUID.randomUUID(),
                    "SYNTHETIC foreign"
                )
            ).status
        );
        assertThrows(DomainException.class, () ->
            cancellations.cancel(
                new Access(tenant, actor, 2, "synthetic-cancel-stale"),
                request.id(),
                0,
                UUID.randomUUID(),
                "SYNTHETIC stale"
            )
        );
        assertEquals(0, current(request).version());
        assertEquals(0, auditCount(request));
    }
}
