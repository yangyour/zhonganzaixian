package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringObjectBinding;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import cn.mop.domain.monitoring.MonitoringObjectMapping;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real retained designated V55 PostgreSQL; the source protocol is explicitly synthetic and no devices are contacted. */
@EnabledIfEnvironmentVariable(named = "MOP_MAPPED_PROBLEM_PG_TEST", matches = "retained-v55|active-isolated-tenants")
class MappedProblemIntakePostgresTest {

    static final String TENANT = System.getenv("MOP_MAPPED_PROBLEM_TENANT");
    static final String FOREIGN = System.getenv("MOP_MAPPED_PROBLEM_FOREIGN_TENANT");
    static final String ACTOR = System.getenv("MOP_MAPPED_PROBLEM_ACTOR");
    static final UUID ASSET = UUID.fromString(
        System.getenv().getOrDefault("MOP_MAPPED_PROBLEM_ASSET", "00000000-0000-4000-8000-000000000001")
    );
    static final String HASH = "a".repeat(64);
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static HikariDataSource pool;
    static PostgresPlatformRepository platform;
    static PostgresMonitoringEventCenterRepository events;
    final UUID sourcePool = UUID.randomUUID();
    final MonitoringObjectMapping.Key key = new MonitoringObjectMapping.Key(
        sourcePool,
        MonitoringObjectMapping.ObjectType.HOST,
        "23"
    );
    final Instant occurred = Instant.now()
        .minusSeconds(300)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        .plusNanos(123456789);
    final AtomicInteger sourceReads = new AtomicInteger();
    volatile int severity = 4;
    volatile String recovery = "0";
    volatile RawMonitoringEvent.Phase phase = RawMonitoringEvent.Phase.PROBLEM;
    UUID mappingId;
    PostgresMonitoringObjectBindingRepository repository;

    @BeforeAll
    static void connect() throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        boolean active = "active-isolated-tenants".equals(System.getenv("MOP_MAPPED_PROBLEM_PG_TEST"));
        if (active) {
            assertNotNull(TENANT);
            assertNotEquals("55000000-0000-4000-8000-000000000001", TENANT);
            assertNotEquals("55000000-0000-4000-8000-000000000002", TENANT);
            assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"));
        } else {
            MonitoringSimulationGuard.requireTestConfiguration(url, TENANT);
        }
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(10);
        pool = new HikariDataSource(config);
        try (
            Connection c = pool.getConnection();
            var s = c.createStatement();
            var r = s.executeQuery(
                "SELECT current_database(),current_user,session_user," +
                    "(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database())"
            )
        ) {
            assertTrue(r.next());
            assertEquals(System.getenv("MOP_MAPPED_PROBLEM_DATABASE"), r.getString(1));
            assertEquals("mop_test_runtime", r.getString(2));
            assertEquals("mop_test_runtime", r.getString(3));
            if (!active) {
                assertEquals(System.getenv("MOP_MAPPED_PROBLEM_OWNER_MARKER"), r.getString(4));
            }
        }
        if (active) {
            try (
                var c = pool.getConnection();
                var s = c.prepareStatement(
                    "SELECT u.identity_source,u.subject,t.name " +
                        "FROM public.mop_user u JOIN public.mop_membership m ON m.user_id=u.id " +
                        "JOIN public.mop_tenant t ON t.id=m.tenant_id WHERE u.id=?::uuid AND t.id=?::uuid"
                )
            ) {
                s.setString(1, ACTOR);
                s.setString(2, TENANT);
                try (var r = s.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals("isolated_mapped_problem", r.getString(1));
                    assertEquals("SYNTHETIC_MAPPED_PROBLEM_" + ACTOR, r.getString(2));
                    assertEquals("SYNTHETIC_MAPPED_PROBLEM_" + TENANT, r.getString(3));
                    assertFalse(r.next());
                }
            }
        }
        platform = new PostgresPlatformRepository(pool, JSON);
        events = new PostgresMonitoringEventCenterRepository(pool, platform);
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    Access access(String tenant) {
        return new Access(
            tenant,
            ACTOR,
            platform.membership(ACTOR, tenant).orElseThrow().permissionVersion(),
            "mapped-problem-pg"
        );
    }

    PostgresMonitoringObjectBindingRepository assembly() {
        var policy = new Policy(
            "ISOLATED_TEST:" + HASH,
            Set.of(sourcePool),
            Set.of(UUID.fromString(ACTOR)),
            true,
            3600
        );
        Source source = new Source() {
            public String provider(UUID pool) {
                return "ZABBIX_7";
            }

            public String definitionSha256(UUID pool) {
                return HASH;
            }

            public SourceMetadata verify(MonitoringObjectMapping.Key sourceKey) {
                return new SourceMetadata(
                    sourceKey,
                    "ZABBIX_7",
                    "23",
                    sourcePool,
                    UUID.fromString(TENANT),
                    ASSET,
                    HASH,
                    MonitoringObjectBinding.metadataHash(
                        sourceKey,
                        "ZABBIX_7",
                        "23",
                        sourcePool,
                        UUID.fromString(TENANT),
                        ASSET
                    )
                );
            }

            public FetchedEvent readEvent(MonitoringObjectMapping.Key sourceKey, String event) {
                sourceReads.incrementAndGet();
                try {
                    var body = new TreeMap<String, Object>();
                    body.putAll(
                        Map.of(
                            "provider",
                            "ZABBIX_7",
                            "source_event_id",
                            event,
                            "trigger_id",
                            "93",
                            "clock",
                            occurred.getEpochSecond(),
                            "nanoseconds",
                            occurred.getNano(),
                            "value",
                            phase == RawMonitoringEvent.Phase.PROBLEM ? 1 : 0,
                            "severity",
                            severity,
                            "recovery_event_id",
                            recovery,
                            "host_ids",
                            List.of("23")
                        )
                    );
                    return new FetchedEvent(event, phase, occurred, "23", JSON.writeValueAsBytes(body));
                } catch (Exception failure) {
                    throw new IllegalStateException("Synthetic source encoding failed");
                }
            }
        };
        return new PostgresMonitoringObjectBindingRepository(platform, JSON, tenant -> policy, source, events);
    }

    @BeforeEach
    void publishOwnMapping() {
        repository = assembly();
        Access access = access(TENANT);
        Receipt created = command(access, () ->
            repository.create(access, ASSET, 1, key, "ISOLATED_TEST source mapping")
        );
        mappingId = created.id();
        var view = repository.get(access, mappingId);
        Receipt activated = command(access, () ->
            repository.change(
                access,
                mappingId,
                Action.ACTIVATE,
                1,
                null,
                null,
                view.policyReference(),
                HASH,
                "ISOLATED_TEST explicit policy"
            )
        );
        assertEquals(2, activated.acceptedVersion());
    }

    @SuppressWarnings("unchecked")
    <T> T command(Access access, java.util.function.Supplier<T> action) {
        return (T) platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "/isolated/mapped-problem/" + UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "b".repeat(64),
            access.permissionVersion(),
            () -> repository.authorize(access, mappingId, true),
            action::get
        );
    }

    ProblemIntakeReceipt intake(String sourceEvent) {
        Access access = access(TENANT);
        return command(access, () -> repository.intakeProblem(access, mappingId, 2, sourceEvent, HASH));
    }

    long count(String table, String field, String id) throws Exception {
        assertTrue(
            Set.of(
                "mop_raw_monitoring_event",
                "mop_monitoring_event_member",
                "mop_monitoring_quarantine",
                "mop_monitoring_event"
            ).contains(table)
        );
        assertTrue(Set.of("source_event_id", "raw_event_id", "id").contains(field));
        try (
            var c = pool.getConnection();
            var s = c.prepareStatement(
                "SELECT count(*) FROM public." +
                    table +
                    " WHERE " +
                    (table.equals("mop_monitoring_quarantine") ? "claimed_tenant_id" : "tenant_id") +
                    "=?::uuid AND " +
                    field +
                    "::text=?"
            )
        ) {
            s.setString(1, TENANT);
            s.setString(2, id);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    @Test
    void commitsRawMemberEventTicketAndSurvivesNewAssemblyReplay() throws Exception {
        var first = intake("1001");
        assertTrue(first.businessProcessed());
        assertNotNull(first.event().ticketId());
        assertEquals(1, count("mop_monitoring_event_member", "raw_event_id", first.capture().recordId().toString()));
        assertEquals(1, first.event().occurrenceCount());
        var detail = events.eventDetail(
            new cn.mop.domain.monitoring.MonitoringEventCenterRepository.Access(
                TENANT,
                ACTOR,
                access(TENANT).permissionVersion(),
                "pg-read"
            ),
            first.event().id()
        );
        assertEquals(first.capture().recordId().toString(), detail.members().get(0).rawEventId());
        repository = assembly();
        var replay = intake("1001");
        assertEquals("RAW_ALREADY_PRESENT", replay.capture().outcome());
        assertEquals(first.capture().recordId(), replay.capture().recordId());
        assertEquals(first.event().id(), replay.event().id());
        assertEquals(first.event().ticketId(), replay.event().ticketId());
        assertEquals(first.event().version(), replay.event().version());
    }

    @Test
    void concurrentRedeliveryRetriesOriginalCommandsWithoutDuplicateEffects() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        var commands = new ArrayList<java.util.concurrent.Callable<ProblemIntakeReceipt>>();
        Access access = access(TENANT);
        for (int i = 0; i < 6; i++) {
            String originalKey = UUID.randomUUID().toString();
            commands.add(
                () ->
                    (ProblemIntakeReceipt) platform.idempotentAuthorized(
                        ACTOR,
                        TENANT,
                        "/isolated/mapped-problem/" + mappingId + "/problem-intakes",
                        originalKey,
                        "b".repeat(64),
                        access.permissionVersion(),
                        () -> repository.authorize(access, mappingId, true),
                        () -> repository.intakeProblem(access, mappingId, 2, "1002", HASH)
                    )
            );
        }
        int retries = 0;
        try {
            var results = executor.invokeAll(commands);
            ProblemIntakeReceipt first = null;
            for (int i = 0; i < results.size(); i++) {
                ProblemIntakeReceipt receipt;
                try {
                    receipt = results.get(i).get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException failed) {
                    assertInstanceOf(DomainException.class, failed.getCause());
                    var failure = (DomainException) failed.getCause();
                    assertEquals(503, failure.status);
                    assertNotNull(failure.getCause());
                    assertTrue(
                        failure.getCause().getMessage().endsWith("sql_state=57014"),
                        "Only bounded lock waits may be retried"
                    );
                    receipt = commands.get(i).call();
                    retries++;
                }
                if (first == null) {
                    first = receipt;
                }
                assertEquals(first.capture().recordId(), receipt.capture().recordId());
                assertEquals(first.event().id(), receipt.event().id());
                assertEquals(first.event().ticketId(), receipt.event().ticketId());
            }
            assertNotNull(first);
            assertEquals(
                1,
                count("mop_monitoring_event_member", "raw_event_id", first.capture().recordId().toString())
            );
            System.out.println("MAPPED_PROBLEM_CONTROLLED_LOCK_RETRIES=" + retries);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void conflictIsDurablyHeldWithoutChangingBusinessEvent() throws Exception {
        var first = intake("1003");
        recovery = "999";
        var held = intake("1003");
        assertFalse(held.businessProcessed());
        assertEquals("CONFLICT_HELD", held.capture().outcome());
        assertEquals(1, count("mop_monitoring_quarantine", "source_event_id", "1003"));
        assertEquals(1, count("mop_monitoring_event_member", "raw_event_id", first.capture().recordId().toString()));
    }

    @Test
    void outerFailureRollsBackRawBusinessAndAuditBeforeRetry() throws Exception {
        Access access = access(TENANT);
        var createdId = new java.util.concurrent.atomic.AtomicReference<String>();
        assertThrows(IllegalStateException.class, () ->
            command(access, () -> {
                var receipt = repository.intakeProblem(access, mappingId, 2, "1004", HASH);
                createdId.set(receipt.event().id());
                throw new IllegalStateException("ISOLATED_TEST crash before commit");
            })
        );
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "1004"));
        assertEquals(0, count("mop_monitoring_event", "id", createdId.get()));
        assertTrue(intake("1004").businessProcessed());
    }

    @Test
    void recoveryAndUnknownSeverityNeverCreateBusinessState() throws Exception {
        phase = RawMonitoringEvent.Phase.RECOVERED;
        assertEquals("RECOVERY_REQUIRES_OBSERVATION", assertThrows(DomainException.class, () -> intake("1005")).code);
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "1005"));
        phase = RawMonitoringEvent.Phase.PROBLEM;
        severity = 0;
        assertEquals("SOURCE_SEVERITY_UNCLASSIFIED", assertThrows(DomainException.class, () -> intake("1006")).code);
        assertEquals(0, count("mop_raw_monitoring_event", "source_event_id", "1006"));
    }

    @Test
    void staleMappingAndForeignTenantFailBeforeAnySourceRead() {
        Access access = access(TENANT);
        assertEquals(
            409,
            assertThrows(DomainException.class, () ->
                command(access, () -> repository.intakeProblem(access, mappingId, 1, "1007", HASH))
            ).status
        );
        Access foreign = access(FOREIGN);
        assertEquals(
            404,
            assertThrows(DomainException.class, () ->
                command(foreign, () -> repository.intakeProblem(foreign, mappingId, 2, "1007", HASH))
            ).status
        );
        assertEquals(0, sourceReads.get());
    }
}
