package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.domain.monitoring.RawMonitoringEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real PG17 source/clone guards and synthetic raw storage only; never repairs or writes to either older database. */
@EnabledIfEnvironmentVariable(named = "MOP_SCHEMA_TEXT_PG_TEST", matches = "owned-clone")
class PostgresSchemaTextRoundTripIntegrationTest {

    static HikariDataSource target;
    static HikariDataSource active;
    static HikariDataSource previous;
    static UUID tenant;
    static UUID asset;
    final UUID pool = UUID.randomUUID();
    final Instant occurred = Instant.parse("2026-10-24T09:00:00.123456789Z");

    static HikariDataSource source(String url, boolean readOnly) {
        assertNotNull(url);
        String pattern = readOnly ? "mop_test|mop_monitoring_test_[a-f0-9]{12}" : "mop_monitoring_test_[a-f0-9]{12}";
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/(" + pattern + ")"));
        String application = System.getenv("MOP_SCHEMA_TEXT_APPLICATION");
        assertNotNull(application);
        assertTrue(application.matches("mop-schema-text-[a-f0-9]{32}"));
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("mop_test_runtime");
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setReadOnly(readOnly);
        config.setAutoCommit(!readOnly);
        config.setMaximumPoolSize(2);
        config.addDataSourceProperty("ApplicationName", application);
        return new HikariDataSource(config);
    }

    @BeforeAll
    static void connect() throws Exception {
        tenant = UUID.fromString(System.getenv("MOP_SCHEMA_TEXT_TENANT"));
        asset = UUID.fromString(System.getenv("MOP_SCHEMA_TEXT_ASSET"));
        assertNotEquals("55000000-0000-4000-8000-000000000001", tenant.toString());
        assertNotEquals("55000000-0000-4000-8000-000000000002", tenant.toString());
        target = source(System.getenv("SPRING_DATASOURCE_URL"), false);
        active = source(System.getenv("MOP_SCHEMA_TEXT_ACTIVE_URL"), true);
        previous = source(System.getenv("MOP_SCHEMA_TEXT_PREVIOUS_URL"), true);
        try (
            var connection = target.getConnection();
            var statement = connection.prepareStatement(
                "SELECT current_database(),current_user,session_user," +
                    "(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database())," +
                    "t.name,a.source FROM public.mop_tenant t JOIN public.mop_asset a ON a.tenant_id=t.id " +
                    "WHERE t.id=? AND a.id=?"
            )
        ) {
            statement.setObject(1, tenant);
            statement.setObject(2, asset);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(System.getenv("MOP_SCHEMA_TEXT_DATABASE"), rows.getString(1));
                assertEquals("mop_test_runtime", rows.getString(2));
                assertEquals("mop_test_runtime", rows.getString(3));
                assertEquals(System.getenv("MOP_SCHEMA_TEXT_OWNER_MARKER"), rows.getString(4));
                assertEquals("SYNTHETIC_SCHEMA_TEXT_" + tenant, rows.getString(5));
                assertEquals("schema-text-test", rows.getString(6));
                assertFalse(rows.next());
            }
        }
    }

    @AfterAll
    static void close() {
        for (var source : new HikariDataSource[] { target, active, previous }) {
            if (source != null) {
                source.close();
            }
        }
    }

    @Test
    void activeFrozenSchemaRemainsAcceptedThroughReadOnlyConnections() throws Exception {
        try (var connection = active.getConnection()) {
            assertTrue(connection.isReadOnly());
            try (
                var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT current_database(),current_setting('transaction_read_only')")
            ) {
                assertTrue(row.next());
                assertEquals("mop_test", row.getString(1));
                assertEquals("on", row.getString(2));
            }
            RawMonitoringEventSchemaVerifier.resource();
            MonitoringQuarantineSchemaVerifier.resource();
            RawMonitoringEventSchemaVerifier.verify(connection);
            MonitoringQuarantineSchemaVerifier.verify(connection);
        }
    }

    @Test
    void olderDamagedCloneIsStillRejectedWithoutRepair() throws Exception {
        try (var connection = previous.getConnection()) {
            assertTrue(connection.isReadOnly());
            assertThrows(IllegalStateException.class, () -> RawMonitoringEventSchemaVerifier.verify(connection));
            assertThrows(IllegalStateException.class, () -> MonitoringQuarantineSchemaVerifier.verify(connection));
        }
    }

    @Test
    void losslessRestorationPassesFullRawAndQuarantineGuards() throws Exception {
        try (var connection = target.getConnection()) {
            connection.setReadOnly(true);
            RawMonitoringEventSchemaVerifier.verify(connection);
            MonitoringQuarantineSchemaVerifier.verify(connection);
        }
    }

    RawMonitoringEvent event(String id, byte[] payload) {
        return new RawMonitoringEvent(
            new RawMonitoringEvent.Key(pool, id, RawMonitoringEvent.Phase.PROBLEM),
            new RawMonitoringEvent.Binding(tenant, asset),
            occurred,
            occurred.plusSeconds(1),
            payload
        );
    }

    @Test
    void preciseRawBytesAndNanosSurviveStorageAndRedelivery() throws Exception {
        var repository = new PostgresRawMonitoringEventRepository(target);
        byte[] payload = "{\"source\":\"SYNTHETIC\",\"text\":\"\r\u2028\u2029\"}".getBytes(
            java.nio.charset.StandardCharsets.UTF_8
        );
        var input = event("exact-" + UUID.randomUUID(), payload);
        var first = repository.store(input);
        var replay = repository.store(
            new RawMonitoringEvent(
                input.key(),
                input.binding(),
                input.occurredAt(),
                input.receivedAt().plusSeconds(60),
                input.payload()
            )
        );
        assertEquals(PostgresRawMonitoringEventRepository.Outcome.STORED, first.outcome());
        assertEquals(PostgresRawMonitoringEventRepository.Outcome.SAME_CONTENT, replay.outcome());
        assertEquals(first.storedId(), replay.storedId());
        assertEquals(first.firstReceivedAt(), replay.firstReceivedAt());
        try (
            var connection = target.getConnection();
            var query = connection.prepareStatement(
                "SELECT payload,occurred_epoch_second,occurred_nano FROM public.mop_raw_monitoring_event WHERE id=?"
            )
        ) {
            query.setObject(1, first.storedId());
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertArrayEquals(payload, rows.getBytes(1));
                assertEquals(occurred.getEpochSecond(), rows.getLong(2));
                assertEquals(occurred.getNano(), rows.getInt(3));
            }
        }
    }

    @Test
    void changedPayloadIsHeldWithoutOverwritingOriginalSource() {
        var coordinator = new PostgresMonitoringEventStorageCoordinator(target);
        String id = "conflict-" + UUID.randomUUID();
        var first = coordinator.store(event(id, new byte[] { 1, 2, 3 }));
        var held = coordinator.store(event(id, new byte[] { 4, 5, 6 }));
        assertInstanceOf(PostgresMonitoringEventStorageCoordinator.RawResult.class, first);
        assertInstanceOf(PostgresMonitoringEventStorageCoordinator.HeldResult.class, held);
    }

    void insert(Connection connection, String id) throws SQLException {
        try (
            var statement = connection.prepareStatement(
                "INSERT INTO public.mop_raw_monitoring_event " +
                    "(id,pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano," +
                    "received_epoch_second,received_nano,payload) VALUES(?,?,?,'PROBLEM',?,?,0,1,0,2,?)"
            )
        ) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, pool);
            statement.setString(3, id);
            statement.setObject(4, tenant);
            statement.setObject(5, asset);
            statement.setBytes(6, new byte[] { 1 });
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test
    void unicodeWhitespaceBoundariesRemainEnforcedByTheDatabase() throws Exception {
        for (String invalid : List.of(" leading", "\rleading", "\u2028leading", "trailing\u2029", "trailing\u001c")) {
            try (var connection = target.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    assertEquals(
                        "23514",
                        assertThrows(SQLException.class, () -> insert(connection, invalid)).getSQLState()
                    );
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    void supplementaryCharactersUseTheSameUtf16LengthLimitAndInternalSeparatorsStayExact() throws Exception {
        String limit = "\uD800\uDC00".repeat(128);
        try (var connection = target.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insert(connection, limit);
                insert(connection, "inside\u2028separator");
            } finally {
                connection.rollback();
            }
        }
        try (var connection = target.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () -> insert(connection, limit + "x")).getSQLState()
                );
            } finally {
                connection.rollback();
            }
        }
    }
}
