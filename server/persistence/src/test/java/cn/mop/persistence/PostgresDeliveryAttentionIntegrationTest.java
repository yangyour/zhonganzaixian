package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryJournalReader;
import cn.mop.domain.notification.DeliveryJournalReader.AttentionStatus;
import cn.mop.domain.notification.DeliveryReceiptState.*;
import cn.mop.persistence.DeliveryReceiptJournal.Command;
import cn.mop.persistence.DeliveryReceiptSnapshotCodec.Scope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual designated PG transactions in fresh runner-owned namespaces; declared receipts are synthetic and never sent. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_DELIVERY_ATTENTION_PG_TESTS", matches = "true")
class PostgresDeliveryAttentionIntegrationTest {

    private static HikariDataSource source;
    private static UUID tenant, foreignTenant, actor;
    private static String otherRows;
    private final UUID pool = UUID.randomUUID();
    private final Instant at = Instant.now().minusSeconds(30);
    private final Set<UUID> allowedCommands = new HashSet<>();

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
        config.setMaximumPoolSize(2);
        source = new HikariDataSource(config);
        try (
            var connection = source.getConnection();
            var statement = connection.prepareStatement(
                "SELECT t.name,u.identity_source,u.subject,current_database(),current_user " +
                    "FROM public.mop_tenant t JOIN public.mop_membership m ON m.tenant_id=t.id " +
                    "JOIN public.mop_user u ON u.id=m.user_id WHERE t.id=? AND u.id=?"
            )
        ) {
            statement.setObject(1, tenant);
            statement.setObject(2, actor);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("SYNTHETIC_REPORT_CAPTURE_" + actor + "_0", row.getString(1));
                assertEquals("isolated_report_capture", row.getString(2));
                assertEquals("SYNTHETIC_REPORT_CAPTURE_" + actor, row.getString(3));
                assertEquals("mop_test", row.getString(4));
                assertEquals("mop_test_runtime", row.getString(5));
            }
        }
        otherRows = otherFingerprint();
    }

    @AfterAll
    static void close() throws Exception {
        if (source != null) {
            try {
                assertEquals(otherRows, otherFingerprint());
            } finally {
                source.close();
            }
        }
    }

    private static String otherFingerprint() throws Exception {
        try (
            var connection = source.getConnection();
            var statement = connection.prepareStatement(
                "SELECT count(*)::text||':'||md5(COALESCE(string_agg(md5(to_jsonb(j)::text),'' ORDER BY tenant_id,delivery_id,version),'')) " +
                    "FROM public.mop_delivery_receipt_journal j WHERE tenant_id<>?"
            )
        ) {
            statement.setObject(1, tenant);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private DeliveryJournalReader.Access readAccess(UUID selectedTenant) {
        return new DeliveryJournalReader.Access(selectedTenant, actor, 1);
    }

    private PostgresDeliveryJournalReader reader() {
        return new PostgresDeliveryJournalReader(source, selectedTenant -> Set.of(pool));
    }

    private PostgresDeliveryReceiptRepository writer() {
        return new PostgresDeliveryReceiptRepository(
            source,
            (connection, access, scope, operation) -> {
                assertEquals(tenant, access.tenantId());
                assertEquals(actor, access.actorId());
                assertEquals(pool, scope.key().resourcePoolId());
                PostgresAuthorization.require(
                    connection,
                    actor.toString(),
                    tenant.toString(),
                    1,
                    "audit:read"
                );
            },
            (connection, access, scope, command) -> {
                assertTrue(
                    allowedCommands.contains(command.commandId()),
                    "Only this test's explicit synthetic transitions are permitted"
                );
            }
        );
    }

    private PostgresDeliveryReceiptRepository.Access writeAccess() {
        return new PostgresDeliveryReceiptRepository.Access(tenant, actor, 1);
    }

    private Scope queue() {
        var scope = new Scope(
            tenant,
            UUID.randomUUID(),
            new Deduplication(pool, UUID.randomUUID(), Channel.EMAIL, actor, "SYNTHETIC_ATTENTION")
        );
        writer().queue(writeAccess(), scope, UUID.randomUUID(), at, "attention-pg-queue");
        return scope;
    }

    private void append(Scope scope, long version, Transition transition) {
        UUID command = UUID.randomUUID();
        allowedCommands.add(command);
        writer().append(
            writeAccess(),
            scope,
            new Command(version, command, actor, 1, "attention-pg-transition", transition)
        );
    }

    private UUID start(Scope scope) {
        UUID request = UUID.randomUUID();
        append(scope, 0, new Started(request, at.plusSeconds(1)));
        return request;
    }

    private Scope failed(boolean dead) {
        var scope = queue();
        UUID request = start(scope);
        append(
            scope,
            1,
            new ReceiptRecorded(
                new Receipt(
                    tenant,
                    scope.deliveryId(),
                    request,
                    UUID.randomUUID(),
                    ReceiptStatus.FAILED,
                    at.plusSeconds(2)
                ),
                at.plusSeconds(3)
            )
        );
        if (dead) {
            append(scope, 2, new DeadLettered(at.plusSeconds(4)));
        }
        return scope;
    }

    @Test
    void completeStatusPopulationFindsOlderFailuresBeyondTheFirstUnfilteredPage() {
        var unknown = queue();
        start(unknown);
        var failure = failed(false);
        var dead = failed(true);
        for (int index = 0; index < 12; index++) {
            queue();
        }
        var reader = reader();
        assertTrue(
            reader
                .list(readAccess(tenant), pool, null, 2)
                .items()
                .stream()
                .allMatch(row -> row.recordedStatus().equals("QUEUED"))
        );
        var first = reader.attention(readAccess(tenant), pool, AttentionStatus.ATTENTION, null, 2);
        assertEquals(15, first.scannedRoots());
        assertEquals(3, first.page().totalCount());
        assertEquals(2, first.page().items().size());
        assertNotNull(first.page().next());
        var second = reader.attention(
            readAccess(tenant),
            pool,
            AttentionStatus.ATTENTION,
            first.page().next(),
            2
        );
        assertEquals(3, second.page().totalCount());
        assertEquals(1, second.page().items().size());
        var actual = new HashSet<UUID>();
        first
            .page()
            .items()
            .forEach(row -> assertTrue(actual.add(row.deliveryId())));
        second
            .page()
            .items()
            .forEach(row -> assertTrue(actual.add(row.deliveryId())));
        assertEquals(Set.of(unknown.deliveryId(), failure.deliveryId(), dead.deliveryId()), actual);
        assertEquals(
            1,
            reader.attention(readAccess(tenant), pool, AttentionStatus.FAILED, null, 10).page().totalCount()
        );
        assertEquals(
            1,
            reader
                .attention(readAccess(tenant), pool, AttentionStatus.DEAD_LETTER, null, 10)
                .page()
                .totalCount()
        );
    }

    @Test
    void lateDeliveredDeclarationRemovesDeadLetterFromCurrentAttentionWithoutRewritingHistory() {
        var scope = failed(true);
        var before = reader().detail(readAccess(tenant), scope.deliveryId(), null, 100);
        assertEquals("DEAD_LETTER", before.entry().recordedStatus());
        append(
            scope,
            3,
            new ReceiptRecorded(
                new Receipt(
                    tenant,
                    scope.deliveryId(),
                    before.entry().lastRequestId(),
                    UUID.randomUUID(),
                    ReceiptStatus.DELIVERED,
                    at.plusSeconds(5)
                ),
                at.plusSeconds(6)
            )
        );
        assertEquals(
            0,
            reader()
                .attention(readAccess(tenant), pool, AttentionStatus.ATTENTION, null, 10)
                .page()
                .totalCount()
        );
        var after = reader().detail(readAccess(tenant), scope.deliveryId(), null, 100);
        assertEquals("DELIVERED", after.entry().recordedStatus());
        assertEquals(before.history(), after.history().subList(1, after.history().size()));
    }

    @Test
    void foreignPoolAndCurrentMembershipRemainRequiredForEveryRead() {
        var scope = failed(false);
        assertEquals(
            404,
            assertThrows(DomainException.class, () ->
                reader().attention(readAccess(tenant), UUID.randomUUID(), AttentionStatus.ATTENTION, null, 10)
            ).status
        );
        assertEquals(
            404,
            assertThrows(DomainException.class, () ->
                reader().detail(readAccess(foreignTenant), scope.deliveryId(), null, 100)
            ).status
        );
        assertThrows(DomainException.class, () ->
            reader().attention(
                new DeliveryJournalReader.Access(tenant, UUID.randomUUID(), 1),
                pool,
                AttentionStatus.ATTENTION,
                null,
                10
            )
        );
    }
}
