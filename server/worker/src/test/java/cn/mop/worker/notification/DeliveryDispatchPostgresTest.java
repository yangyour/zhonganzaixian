package cn.mop.worker.notification;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import cn.mop.persistence.PostgresDeliveryReceiptRepository;
import cn.mop.persistence.PostgresDeliveryReceiptRepository.Access;
import cn.mop.worker.notification.DeliveryDispatch.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated PostgreSQL, separately provisioned synthetic tenant, simulated provider only. */
@EnabledIfEnvironmentVariable(named = "MOP_NOTIFICATION_DISPATCH_TEST_ENVIRONMENT", matches = "designated-isolated")
class DeliveryDispatchPostgresTest {
    private static HikariDataSource source;
    private static UUID tenant;
    private static UUID actor;
    private static String namespace;
    private static long otherTenantRows;
    private final AtomicBoolean loseCommitReply = new AtomicBoolean();
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final ConcurrentHashMap<UUID, DeliveryReceiptState.Receipt> trusted = new ConcurrentHashMap<>();
    private final Instant at = Instant.now().minusSeconds(60);

    @BeforeAll
    static void connectToExplicitIsolatedNamespace() throws Exception {
        tenant = UUID.fromString(System.getenv("MOP_NOTIFICATION_TEST_TENANT"));
        actor = UUID.fromString(System.getenv("MOP_NOTIFICATION_TEST_ACTOR"));
        namespace = System.getenv("MOP_NOTIFICATION_TEST_NAMESPACE");
        assertNotEquals("55000000-0000-4000-8000-000000000001", tenant.toString());
        assertTrue(namespace.matches("SYNTHETIC_NOTIFICATION_DISPATCH_[a-f0-9]{12}"));
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"));
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5000);
        source = new HikariDataSource(config);
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT name,current_database(),current_user FROM public.mop_tenant WHERE id=?")) {
            query.setObject(1, tenant);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(namespace, row.getString(1));
                assertEquals("mop_test", row.getString(2));
                assertEquals("mop_test_runtime", row.getString(3));
            }
        }
        otherTenantRows = otherRows();
    }

    @AfterAll
    static void verifyIsolationAndClose() throws Exception {
        if (source != null) {
            try {
                assertEquals(otherTenantRows, otherRows());
            } finally {
                source.close();
            }
        }
    }

    private static long otherRows() throws SQLException {
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT count(*) FROM public.mop_delivery_receipt_journal WHERE tenant_id<>?")) {
            query.setObject(1, tenant);
            try (var row = query.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private PostgresDeliveryReceiptRepository repository(boolean injectLostReply) {
        DataSource dataSource = injectLostReply ? tracked() : source;
        return new PostgresDeliveryReceiptRepository(dataSource, (connection, access, scope, operation) -> {
            // Synthetic test authority only, scoped to this separately verified namespace; not a production grant.
            if (connection.getAutoCommit() || revoked.get() || !access.tenantId().equals(tenant)
                    || !access.actorId().equals(actor) || access.permissionVersion() != 1) {
                throw new DomainException(403, "TEST_AUTHORITY_DENIED", "Synthetic namespace authority denied");
            }
            // Namespace identity is read-only: runtime has no UPDATE grant on the tenant catalog.
            try (var query = connection.prepareStatement("SELECT name FROM public.mop_tenant WHERE id=?")) {
                query.setObject(1, tenant);
                try (var row = query.executeQuery()) {
                    if (!row.next() || !namespace.equals(row.getString(1))) {
                        throw new DomainException(404, "NOT_FOUND", "Synthetic namespace missing");
                    }
                }
            }
        }, (connection, access, scope, command) -> {
            if (command.transition() instanceof ReceiptRecorded receipt
                    && !receipt.receipt().equals(trusted.get(command.commandId()))) {
                throw new IllegalStateException("Synthetic provider observation was not registered");
            }
        });
    }

    private DataSource tracked() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(source, args);
                        if (!(result instanceof Connection connection)) {
                            return result;
                        }
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                (connectionProxy, call, parameters) -> {
                                    try {
                                        Object answer = call.invoke(connection, parameters);
                                        if (call.getName().equals("commit") && loseCommitReply.compareAndSet(true, false)) {
                                            throw new SQLException("Synthetic lost commit response", "08006");
                                        }
                                        return answer;
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private Original queued() {
        var access = new Access(tenant, actor, 1);
        var scope = new Scope(tenant, UUID.randomUUID(), new Deduplication(UUID.randomUUID(), UUID.randomUUID(),
                Channel.WEBHOOK, UUID.randomUUID(), "SYNTHETIC_TEST"));
        repository(false).queue(access, scope, UUID.randomUUID(), at, "synthetic-pg-queue");
        return new Original(access, scope, new Command(0, UUID.randomUUID(), actor, 1,
                "synthetic-pg-start", new Started(UUID.randomUUID(), at.plusSeconds(1))));
    }

    private class Gateway implements Provider {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger lookups = new AtomicInteger();
        Optional<Observation> answer = Optional.empty();
        boolean loseSubmitReply;

        @Override
        public Optional<Observation> submit(Scope scope, UUID request) {
            submits.incrementAndGet();
            if (loseSubmitReply) {
                throw new IllegalStateException("Synthetic provider response lost");
            }
            return answer;
        }

        @Override
        public Optional<Observation> lookup(Scope scope, UUID request) {
            lookups.incrementAndGet();
            return answer;
        }
    }

    private DeliveryDispatch consumer(PostgresDeliveryReceiptRepository repository, Gateway gateway) {
        return new DeliveryDispatch(new Binding(true, "SYNTHETIC_TEST_ONLY"), (binding, access, scope, step) -> {
            if (revoked.get() || !scope.tenantId().equals(tenant)) {
                throw new IllegalStateException("Synthetic authority revoked");
            }
        }, gateway, repository);
    }

    private Observation observation(Original original, ReceiptStatus status, int seconds) {
        var receipt = new DeliveryReceiptState.Receipt(tenant, original.scope().deliveryId(), original.requestId(),
                UUID.randomUUID(), status, at.plusSeconds(seconds));
        var observation = new Observation(UUID.randomUUID(), receipt, at.plusSeconds(seconds + 1));
        trusted.put(observation.commandId(), receipt);
        return observation;
    }

    @Test
    void concurrentConsumersSubmitOnlyForTheFirstAcknowledgedStart() throws Exception {
        var original = queued();
        var gateway = new Gateway();
        var gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(6);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 6; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                    return consumer(repository(false), gateway).once(original);
                }));
            }
            gate.countDown();
            for (var result : results) {
                assertEquals(Outcome.RECONCILIATION_REQUIRED, result.get(15, TimeUnit.SECONDS).outcome());
            }
            assertEquals(1, gateway.submits.get());
            assertEquals(1, repository(false).current(original.access(), original.scope()).orElseThrow()
                    .journal().state().attempts().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void actualStartCommitResponseLossIsRecoveredByOriginalCommandWithoutSend() {
        var original = queued();
        var gateway = new Gateway();
        var faulty = repository(true);
        // Arm only after the read, immediately before START; reads also commit their transactions.
        var consumer = new DeliveryDispatch(new Binding(true, "SYNTHETIC_TEST_ONLY"),
                (binding, access, scope, step) -> {
                    if (step == Step.START) {
                        loseCommitReply.set(true);
                    }
                }, gateway, faulty);
        assertEquals(Outcome.RECONCILIATION_REQUIRED, consumer.once(original).outcome());
        assertEquals(0, gateway.submits.get());
        var recovered = repository(false).recover(original.access(), original.scope(), original.start().commandId()).orElseThrow();
        assertEquals(original.start(), recovered.original());
        assertEquals(1, recovered.appliedVersion());
        assertEquals(Status.UNKNOWN, recovered.current().state().status());
        assertFalse(consumer(repository(false), gateway).once(original).submitInvoked());
        assertTrue(consumer(repository(false), gateway).reconcile(original).lookupInvoked());
    }

    @Test
    void responseLossThenTrustedLookupRecordsCurrentDeliveryAndOriginalStart() {
        var original = queued();
        var gateway = new Gateway();
        gateway.loseSubmitReply = true;
        consumer(repository(false), gateway).once(original);
        gateway.answer = Optional.of(observation(original, ReceiptStatus.DELIVERED, 3));
        assertEquals(Outcome.DELIVERED_RECORDED, consumer(repository(false), gateway).reconcile(original).outcome());
        var recovered = repository(false).recover(original.access(), original.scope(), original.start().commandId()).orElseThrow();
        assertEquals(Status.DELIVERED, recovered.current().state().status());
        assertEquals(1, recovered.appliedVersion());
        assertEquals(2, recovered.current().state().version());
        assertEquals(1, gateway.submits.get());
    }

    @Test
    void duplicateAcceptedObservationReplaysOriginalCommandWithoutNewJournalRow() {
        var original = queued();
        var gateway = new Gateway();
        gateway.answer = Optional.of(observation(original, ReceiptStatus.ACCEPTED, 3));
        assertEquals(Outcome.ACCEPTED, consumer(repository(false), gateway).once(original).outcome());
        assertEquals(Outcome.ACCEPTED, consumer(repository(false), gateway).reconcile(original).outcome());
        assertEquals(2, repository(false).current(original.access(), original.scope()).orElseThrow().journal().state().version());
        assertEquals(1, gateway.submits.get());
    }

    @Test
    void untrustedObservationCannotAdvanceJournalAndRevocationBlocksRecovery() {
        var original = queued();
        var gateway = new Gateway();
        var observation = observation(original, ReceiptStatus.DELIVERED, 3);
        trusted.clear();
        gateway.answer = Optional.of(observation);
        assertEquals(Outcome.RECONCILIATION_REQUIRED, consumer(repository(false), gateway).once(original).outcome());
        assertEquals(Status.UNKNOWN, repository(false).current(original.access(), original.scope()).orElseThrow().journal().state().status());
        revoked.set(true);
        assertEquals(403, assertThrows(DomainException.class, () -> repository(false).recover(
                original.access(), original.scope(), original.start().commandId())).status);
        assertEquals(403, assertThrows(DomainException.class, () -> consumer(repository(false), gateway).reconcile(original)).status);
    }

    @Test
    void failedDeliveryCannotAcquireAnotherAutomaticAttempt() {
        var original = queued();
        var gateway = new Gateway();
        gateway.answer = Optional.of(observation(original, ReceiptStatus.FAILED, 3));
        assertEquals(Outcome.MANUAL_REVIEW, consumer(repository(false), gateway).once(original).outcome());
        assertEquals(Outcome.MANUAL_REVIEW, consumer(repository(false), gateway).once(original).outcome());
        assertEquals(1, gateway.submits.get());
    }
}
