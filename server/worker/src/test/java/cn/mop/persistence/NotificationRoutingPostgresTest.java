package cn.mop.persistence;

import cn.mop.domain.CustomerReport;
import cn.mop.domain.CustomerReportRegistration;
import cn.mop.domain.DomainException;
import cn.mop.domain.contact.CustomerContact.QuietWindow;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft.LevelPreference;
import cn.mop.domain.notification.OwnNotificationPreferenceRepository;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketSlaSnapshot;
import cn.mop.domain.ticket.TicketWorkflowRepository;
import cn.mop.persistence.NotificationControlStore.Access;
import cn.mop.persistence.NotificationControlStore.Kind;
import cn.mop.worker.notification.NotificationRoutingScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Real designated service, schema-only isolated database, synthetic sources; no provider or live database writes. */
@EnabledIfEnvironmentVariable(named = "MOP_NOTIFICATION_ROUTING_PG_TEST", matches = "isolated-database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationRoutingPostgresTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static HikariDataSource source;
    private static NotificationControlStore store;
    private static NotificationRoutingService routing;
    private static PostgresPlatformRepository platform;
    private static UUID tenant, foreignTenant, actor, receiver, draftId, requestId, bindingId, poolId;
    private static long draftVersion;
    private static NotificationRetainedSchemaVerification retainedSchema;

    @BeforeAll
    static void initializeIsolatedDatabase() throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_notification_test_[a-f0-9]{12}"));
        NotificationControlSchemaVerifier.resource();
        retainedSchema = NotificationRetainedSchemaVerification.inspectIfRequested(url);
        if (retainedSchema == null) {
            // Default fresh-database mode retains its original apply/repeat assertions in full.
            var flyway = Flyway.configure().dataSource(url, "mop_test_migrator", System.getenv("MOP_MIGRATION_PASSWORD"))
                    .locations("classpath:db/migration").schemas("public").defaultSchema("public").createSchemas(false)
                    .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).target("51").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();
            try (var connection = java.sql.DriverManager.getConnection(url, "mop_test_migrator", System.getenv("MOP_MIGRATION_PASSWORD"))) {
                NotificationControlSchemaVerifier.verify(connection);
            }
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(6);
        config.setConnectionTimeout(5000);
        source = new HikariDataSource(config);
        tenant = id("MOP_NOTIFICATION_TEST_TENANT");
        foreignTenant = id("MOP_NOTIFICATION_TEST_FOREIGN_TENANT");
        actor = id("MOP_NOTIFICATION_TEST_ACTOR");
        receiver = id("MOP_NOTIFICATION_TEST_RECEIVER");
        poolId = UUID.randomUUID();
        assertNotEquals(UUID.fromString("55000000-0000-4000-8000-000000000001"), tenant);
        if (retainedSchema != null) {
            verifyFreshSyntheticScope();
        } else {
            // Preserve the original fresh runner's fixture premise; retained reuse has stricter independent gates.
            try (var connection = source.getConnection();
                 var query = connection.prepareStatement("SELECT name FROM public.mop_tenant WHERE id=?")) {
                query.setObject(1, tenant);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("SYNTHETIC_NOTIFICATION_" + tenant, row.getString(1));
                }
            }
        }
        store = new NotificationControlStore(source, MAPPER);
        routing = new NotificationRoutingService(store, MAPPER);
        platform = new PostgresPlatformRepository(source, MAPPER);
        assertTrue(store.ready());
        seedSavedPreferenceAndBinding();
        createCommittedSource();
    }

    @AfterAll
    static void close() throws Exception {
        try {
            if (source != null) {
                source.close();
            }
        } finally {
            if (retainedSchema != null) {
                retainedSchema.finish(source == null || source.isClosed());
            }
        }
    }

    /** Retained reuse requires this run's new credential-free principals and empty tenant-local routing journal. */
    private static void verifyFreshSyntheticScope() throws Exception {
        assertNotEquals(tenant, foreignTenant);
        assertNotEquals(actor, receiver);
        try (var connection = source.getConnection()) {
            try (var query = connection.prepareStatement("SELECT current_user,session_user,current_database()")) {
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("mop_test_runtime", row.getString(1));
                    assertEquals("mop_test_runtime", row.getString(2));
                    assertTrue(row.getString(3).matches("mop_notification_test_[a-f0-9]{12}"));
                }
            }
            for (UUID scope : List.of(tenant, foreignTenant)) {
                assertFalse(scope.toString().startsWith("55000000-"));
                try (var query = connection.prepareStatement("SELECT name FROM public.mop_tenant WHERE id=?")) {
                    query.setObject(1, scope);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("SYNTHETIC_NOTIFICATION_" + scope, row.getString(1));
                    }
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM public.mop_notification_control WHERE tenant_id=?")) {
                    query.setObject(1, scope);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(0, row.getLong(1));
                    }
                }
            }
            for (UUID user : List.of(actor, receiver)) {
                try (var query = connection.prepareStatement("SELECT identity_source,subject,active,"
                        + "EXISTS(SELECT 1 FROM public.mop_local_account WHERE user_id=u.id),"
                        + "EXISTS(SELECT 1 FROM public.mop_external_identity_binding WHERE user_id=u.id),"
                        + "EXISTS(SELECT 1 FROM public.mop_membership WHERE user_id=u.id AND tenant_id NOT IN (?,?)) "
                        + "FROM public.mop_user u WHERE id=?")) {
                    query.setObject(1, tenant);
                    query.setObject(2, foreignTenant);
                    query.setObject(3, user);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("isolated_notification_routing", row.getString(1));
                        assertEquals("SYNTHETIC_NOTIFICATION_" + user, row.getString(2));
                        assertTrue(row.getBoolean(3));
                        assertFalse(row.getBoolean(4));
                        assertFalse(row.getBoolean(5));
                        assertFalse(row.getBoolean(6));
                    }
                }
            }
        }
    }

    private static UUID id(String environment) {
        return UUID.fromString(System.getenv(environment));
    }

    private static Access admin() {
        return store.workerAccess(tenant, actor);
    }

    private static Access own() {
        return store.workerAccess(tenant, receiver);
    }

    private static void seedSavedPreferenceAndBinding() throws Exception {
        var preferences = new PostgresOwnNotificationPreferenceRepository(source, platform);
        var access = new OwnNotificationPreferenceRepository.Access(receiver.toString(), tenant.toString(), 1, "synthetic-routing");
        var draft = new OwnNotificationPreferenceDraft(tenant, receiver, LevelPreference.ALL, List.of(Channel.EMAIL),
                new QuietWindow(false, null, null));
        var saved = (OwnNotificationPreferenceRepository.Snapshot) platform.idempotentAuthorized(receiver.toString(),
                tenant.toString(), "POST /isolated/preference", UUID.randomUUID().toString(), "a".repeat(64), 1,
                () -> preferences.authorizeCurrentAccess(access, null), () -> preferences.create(access, draft));
        draftId = UUID.fromString(saved.id());
        draftVersion = saved.version();
        bindingId = UUID.randomUUID();
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "INSERT INTO public.mop_email_binding(id,tenant_id,user_id,sender,email,active,updated_by) VALUES(?,?,?,'synthetic@example.invalid','recipient@example.invalid',true,?)")) {
            query.setObject(1, bindingId);
            query.setObject(2, tenant);
            query.setObject(3, receiver);
            query.setObject(4, actor);
            assertEquals(1, query.executeUpdate());
        }
    }

    private static void createCommittedSource() throws Exception {
        var report = new CustomerReport("SYNTHETIC_NOTIFICATION_ROUTING", "SYNTHETIC_NOTIFICATION_ROUTING_SOURCE",
                "其他", "individual", "SYNTHETIC_REPORTER", "13800138000", "normal", null);
        var registration = (CustomerReportRegistration) platform.idempotentAuthorized(receiver.toString(), tenant.toString(),
                "POST /isolated/report", UUID.randomUUID().toString(), "b".repeat(64), 1, () -> {},
                () -> platform.registerCustomerReport(tenant.toString(), receiver.toString(), report));
        var workflow = new PostgresTicketWorkflowRepository(source, platform, MAPPER);
        var access = new TicketWorkflowRepository.Access(tenant.toString(), actor.toString(), 1, "synthetic-routing");
        platform.idempotentAuthorized(actor.toString(), tenant.toString(), "POST /isolated/activate", UUID.randomUUID().toString(),
                "c".repeat(64), 1, () -> workflow.authorizeCommand(access, registration.ticketId(), false),
                () -> workflow.activate(access, registration.ticketId(), 0, "P2", Tier.STANDARD, "SYNTHETIC_ROUTING",
                        (context, priority, tier, start) -> TicketSlaSnapshot.freeze("P2", Tier.STANDARD, start,
                                Duration.ofMinutes(30), Duration.ofHours(4), null, 48L, null, List.of(), Map.of())));
        assertEquals(1, new PostgresTicketNotificationRepository(source, platform, MAPPER).relay(tenant.toString(), 100));
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT id FROM public.mop_notification_message WHERE tenant_id=? AND ticket_id=?::uuid AND recipient_id=?")) {
            query.setObject(1, tenant);
            query.setString(2, registration.ticketId());
            query.setObject(3, receiver);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                requestId = row.getObject(1, UUID.class);
            }
        }
    }

    private ObjectNode completePolicy() throws Exception {
        var policy = (ObjectNode) MAPPER.readTree("""
                {"published":true,"resource_pool_id":null,"matrix":{"P1":["EMAIL"],"P2":["EMAIL"],"P3":[],"P4":[]},
                 "aggregate_seconds":0,"precedence":"MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS","retry_budget":0,"retry_delay_seconds":60}
                """);
        policy.put("resource_pool_id", poolId.toString());
        return policy;
    }

    @Test
    @Order(1)
    void durableDiscoveryIsIdempotentAndMissingPolicyDoesNotBecomeAnEmptySuccessfulSend() {
        assertEquals(1, routing.discover(admin(), 100));
        assertEquals(0, routing.discover(admin(), 100));
        var request = routing.detail(admin(), Kind.REQUEST, requestId);
        assertEquals("WAITING_CONFIGURATION", request.state().path("status").asText());
        assertEquals(0, routing.plan(admin(), requestId, 0, UUID.randomUUID(), Instant.now()).version());
        assertTrue(store.page(admin(), Kind.ROUTE, 25, null, null).items().isEmpty());
        var foreign = store.workerAccess(foreignTenant, actor);
        assertEquals(404, assertThrows(DomainException.class, () -> routing.detail(foreign, Kind.REQUEST, requestId)).status);
        assertEquals(403, assertThrows(DomainException.class, () -> routing.discover(own(), 100)).status);
    }

    @Test
    @Order(2)
    void explicitPublicationAndVerifiedBindingProduceOneScopedDurableRoute() throws Exception {
        var body = completePolicy();
        UUID command = UUID.randomUUID();
        var policy = routing.savePolicy(admin(), -1, command, body);
        assertEquals(policy, routing.savePolicy(admin(), -1, command, body));
        var changed = body.deepCopy();
        changed.put("aggregate_seconds", 1);
        assertEquals(409, assertThrows(DomainException.class, () -> routing.savePolicy(admin(), -1, command, changed)).status);
        routing.publishPreference(own(), -1, UUID.randomUUID(), draftId, draftVersion, true);
        var binding = MAPPER.createObjectNode().put("receiver_id", receiver.toString()).put("channel", "EMAIL")
                .put("binding_id", bindingId.toString()).put("valid_until", Instant.now().plusSeconds(86400).toString()).put("active", true);
        routing.saveBinding(admin(), -1, UUID.randomUUID(), binding);
        var request = routing.plan(admin(), requestId, 0, UUID.randomUUID(), Instant.now());
        assertEquals("ROUTED", request.state().path("status").asText());
        var routes = store.page(admin(), Kind.ROUTE, 25, null, null).items();
        assertEquals(1, routes.size());
        assertEquals("QUEUED", routes.get(0).state().path("status").asText());
        assertFalse(routes.get(0).state().path("external_dispatch_enabled").asBoolean());
        assertEquals(receiver.toString(), routes.get(0).state().path("receiver_id").asText());
        var sourceDraft = new PostgresOwnNotificationPreferenceRepository(source, platform).get(
                new OwnNotificationPreferenceRepository.Access(receiver.toString(), tenant.toString(), 1, "check"), draftId.toString());
        assertEquals(draftVersion, sourceDraft.version());
        assertFalse(sourceDraft.draft().effective());
    }

    @Test
    @Order(3)
    void repeatedSchedulerAndPreferenceWithdrawalDoNotDuplicateOrKeepStaleQueuedRoutes() {
        for (int i = 0; i < 2; i++) {
            var scheduler = new NotificationRoutingScheduler(store, routing, actor, List.of(tenant), 25, Clock.systemUTC());
            var result = scheduler.tick(() -> true);
            assertTrue(result.ready());
            assertEquals(0, result.failures());
        }
        var route = store.page(admin(), Kind.ROUTE, 25, null, null).items().get(0);
        assertEquals(0, route.version());
        routing.publishPreference(own(), 0, UUID.randomUUID(), draftId, draftVersion, false);
        var scheduler = new NotificationRoutingScheduler(store, routing, actor, List.of(tenant), 25, Clock.systemUTC());
        assertEquals(0, scheduler.tick(() -> true).failures());
        var suspended = routing.detail(admin(), Kind.ROUTE, route.id());
        assertEquals("WAITING_CONFIGURATION", suspended.state().path("status").asText());
        assertTrue(suspended.state().path("not_before").isNull());
        routing.publishPreference(own(), 1, UUID.randomUUID(), draftId, draftVersion, true);
        assertEquals(0, scheduler.tick(() -> true).failures());
        assertEquals("QUEUED", routing.detail(admin(), Kind.ROUTE, route.id()).state().path("status").asText());
        assertEquals(1, store.page(admin(), Kind.ROUTE, 25, null, null).items().size());
    }

    @Test
    @Order(4)
    void concurrentPolicyWritesHaveOneWinnerAndOriginalCommandsRemainRecoverable() throws Exception {
        var original = routing.policy(admin());
        var gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int seconds : new int[]{1, 2}) {
                var body = completePolicy().put("aggregate_seconds", seconds);
                jobs.add(executor.submit(() -> {
                    gate.await();
                    try {
                        routing.savePolicy(admin(), original.version(), UUID.randomUUID(), body);
                        return true;
                    } catch (DomainException failure) {
                        assertEquals(409, failure.status);
                        return false;
                    }
                }));
            }
            gate.countDown();
            int winners = 0;
            for (var job : jobs) {
                if (job.get(20, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(original.version() + 1, routing.policy(admin()).version());
            assertEquals(original, store.command(admin(), original.commandId()).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Order(5)
    void revokedMembershipAndOldPermissionVersionCannotReadOrReplay() throws Exception {
        Access original = admin();
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=? AND user_id=?")) {
            query.setObject(1, tenant);
            query.setObject(2, actor);
            query.executeUpdate();
        }
        try {
            assertEquals(401, assertThrows(DomainException.class, () -> routing.policy(original)).status);
            assertEquals(403, assertThrows(DomainException.class, () -> store.workerAccess(tenant, actor)).status);
        } finally {
            try (var connection = source.getConnection(); var query = connection.prepareStatement(
                    "UPDATE public.mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id=? AND user_id=?")) {
                query.setObject(1, tenant);
                query.setObject(2, actor);
                query.executeUpdate();
            }
        }
        assertEquals(401, assertThrows(DomainException.class, () -> routing.policy(original)).status);
        assertNotNull(routing.policy(admin()));
    }

    @Test
    @Order(6)
    void runtimeCannotRewriteOrDeleteControlHistory() throws Exception {
        for (String statement : List.of("UPDATE public.mop_notification_control SET root_version=0 WHERE tenant_id=?",
                "DELETE FROM public.mop_notification_control WHERE tenant_id=?")) {
            try (var connection = source.getConnection(); var query = connection.prepareStatement(statement)) {
                query.setObject(1, tenant);
                assertThrows(SQLException.class, query::executeUpdate);
            }
        }
        assertTrue(store.ready());
    }
}
