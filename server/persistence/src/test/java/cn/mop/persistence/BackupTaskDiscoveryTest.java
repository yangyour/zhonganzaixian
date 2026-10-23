package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupArchiveRepository.Phase;
import cn.mop.persistence.PostgresBackupTaskDiscovery.AssetScope;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Cursor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupTaskDiscoveryTest {
    final UUID tenant = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();
    final Access access = new Access(tenant, UUID.randomUUID(), 7);
    final AssetScope scope = new AssetScope(tenant, asset);
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void invalidBoundsAndCrossTenantCursorDoNotOpenConnection() {
        var db = new Database();
        var repository = repository(db);
        for (int limit : new int[]{0, -1, 51}) {
            assertEquals(400, assertThrows(DomainException.class,
                    () -> repository.discover(access, scope, null, limit)).status);
        }
        var other = new AssetScope(UUID.randomUUID(), asset);
        assertEquals(404, assertThrows(DomainException.class,
                () -> repository.discover(access, other, null, 10)).status);
        assertEquals(404, assertThrows(DomainException.class,
                () -> repository.discover(access, scope, new Cursor(other, now, UUID.randomUUID()), 10)).status);
        assertEquals(0, db.connections);
    }

    @Test
    void keysetPageHasStableSourceIdentityWithoutEncryptedContent() {
        var db = new Database();
        var first = row(Phase.STAGED, 0);
        db.rows.add(first);
        db.rows.add(row(Phase.STORE_STARTED, 1));
        var page = repository(db).discover(access, scope, null, 1);
        assertEquals(1, page.items().size());
        assertEquals(first.get("submission_id"), page.items().get(0).submissionId());
        assertEquals(first.get("version_id"), page.nextAfter().versionId());
        assertEquals(scope, page.nextAfter().scope());
        assertFalse(page.items().get(0).executionAuthorized());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertEquals(2, db.authorizations);
        assertEquals(1, db.commits);
        assertTrue(db.sql.stream().anyMatch(sql -> sql.contains("NOT EXISTS")
                && sql.contains("mop_backup_version_registration") && sql.contains("ORDER BY s.recorded_at,s.version_id LIMIT ?")));
        assertFalse(db.sql.stream().anyMatch(sql -> sql.contains("encoded_envelope")
                || sql.contains("key_reference") || sql.startsWith("INSERT") || sql.startsWith("UPDATE")));
        assertEquals(2, db.parameters.get(3));
    }

    @Test
    void nextPageBindsCursorAndOneExtraRowLimit() {
        var db = new Database();
        var cursor = new Cursor(scope, now, UUID.randomUUID());
        var page = repository(db).discover(access, scope, cursor, 10);
        assertNull(page.nextAfter());
        assertEquals(now.atOffset(ZoneOffset.UTC), db.parameters.get(3));
        assertEquals(cursor.versionId(), db.parameters.get(4));
        assertEquals(11, db.parameters.get(5));
    }

    @Test
    void unknownDispatchRemainsReconciliationAndVerifiedIsNotCompleted() {
        var db = new Database();
        db.rows.add(row(Phase.VERIFY_REQUIRED, 2));
        db.rows.add(row(Phase.VERIFIED, 3));
        var page = repository(db).discover(access, scope, null, 10);
        assertTrue(page.items().get(0).requiresReconciliation());
        assertFalse(page.items().get(1).requiresReconciliation());
        assertFalse(page.items().get(1).executionAuthorized());
        assertNull(page.nextAfter());
    }

    @Test
    void revocationAtReturnDoesNotReturnPartialPage() {
        var db = new Database();
        db.rows.add(row(Phase.STAGED, 0));
        var repository = new PostgresBackupTaskDiscovery(db.source(), (connection, identity, target) -> {
            if (++db.authorizations == 2) {
                throw new DomainException(403, "PRIVATE_DETAIL", "PRIVATE_DETAIL");
            }
        });
        var failure = assertThrows(DomainException.class, () -> repository.discover(access, scope, null, 10));
        assertEquals(403, failure.status);
        assertFalse(failure.getMessage().contains("PRIVATE_DETAIL"));
        assertEquals(0, db.commits);
        assertEquals(1, db.rollbacks);
    }

    @Test
    void hiddenAssetIs404EvenWithDiscoveryGrant() {
        var db = new Database();
        db.assetExists = false;
        assertEquals(404, assertThrows(DomainException.class,
                () -> repository(db).discover(access, scope, null, 10)).status);
        assertFalse(db.sql.stream().anyMatch(sql -> sql.contains("JOIN LATERAL")));
    }

    @Test
    void foreignOrMalformedStoredTaskFailsClosed() {
        for (String field : List.of("tenant_id", "phase", "dispatch_owner")) {
            var db = new Database();
            var row = row(Phase.STORE_STARTED, 1);
            row.put(field, field.equals("tenant_id") ? UUID.randomUUID() : "PRIVATE_DETAIL!");
            db.rows.add(row);
            var failure = assertThrows(DomainException.class,
                    () -> repository(db).discover(access, scope, null, 10));
            assertEquals(503, failure.status);
            assertFalse(failure.getMessage().contains("PRIVATE_DETAIL"));
            assertEquals(1, db.rollbacks);
        }
    }

    @Test
    void unavailableDatabaseDoesNotBecomeEmptySuccess() {
        DataSource source = proxy(DataSource.class, (p, method, arguments) -> {
            throw new SQLException("PRIVATE_DETAIL");
        });
        var repository = new PostgresBackupTaskDiscovery(source, (c, a, s) -> {});
        var failure = assertThrows(DomainException.class, () -> repository.discover(access, scope, null, 10));
        assertEquals(503, failure.status);
        assertNull(failure.getCause());
    }

    @Test
    void cursorRejectsUnsupportedTimestampPrecision() {
        assertThrows(DomainException.class, () -> new Cursor(scope, now.plusNanos(1), UUID.randomUUID()));
        assertThrows(DomainException.class, () -> new Cursor(scope, Instant.MIN, UUID.randomUUID()));
    }

    private PostgresBackupTaskDiscovery repository(Database db) {
        return new PostgresBackupTaskDiscovery(db.source(), (connection, identity, target) -> {
            assertFalse(connection.getAutoCommit());
            assertEquals(access, identity);
            assertEquals(scope, target);
            db.authorizations++;
        });
    }

    private Map<String, Object> row(Phase phase, long version) {
        var values = new HashMap<String, Object>();
        values.put("tenant_id", tenant);
        values.put("asset_id", asset);
        values.put("run_id", UUID.randomUUID());
        values.put("version_id", UUID.randomUUID());
        values.put("submission_id", UUID.randomUUID());
        values.put("attempt_number", 1);
        values.put("run_version", 1L);
        values.put("lease_request_id", UUID.randomUUID());
        values.put("lease_owner", "synthetic-original-device-lease");
        values.put("fencing_token", 1L);
        values.put("recorded_at", now.atOffset(ZoneOffset.UTC));
        values.put("phase", phase.name());
        values.put("event_version", version);
        values.put("dispatch_count", version == 0 ? 0L : 1L);
        values.put("dispatch_id", version == 0 ? null : UUID.randomUUID());
        values.put("dispatch_owner", version == 0 ? null : "synthetic-worker");
        return values;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    static class Database {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<String> sql = new ArrayList<>();
        final Map<Integer, Object> parameters = new HashMap<>();
        int connections;
        int commits;
        int rollbacks;
        int authorizations;
        boolean assetExists = true;

        DataSource source() {
            return proxy(DataSource.class, (p, method, args) -> {
                connections++;
                return proxy(Connection.class, (c, cm, ca) -> switch (cm.getName()) {
                    case "setAutoCommit", "setTransactionIsolation", "close" -> null;
                    case "getAutoCommit" -> false;
                    case "commit" -> { commits++; yield null; }
                    case "rollback" -> { rollbacks++; yield null; }
                    case "createStatement" -> proxy(Statement.class, (s, sm, sa) -> {
                        if (sm.getName().equals("execute")) {
                            sql.add((String) sa[0]);
                            return true;
                        }
                        return null;
                    });
                    case "prepareStatement" -> prepared((String) ca[0]);
                    default -> throw new AssertionError(cm);
                });
            });
        }

        PreparedStatement prepared(String query) {
            sql.add(query);
            return proxy(PreparedStatement.class, (p, method, args) -> {
                if (method.getName().equals("setObject") || method.getName().equals("setInt")) {
                    parameters.put((Integer) args[0], args[1]);
                }
                if (method.getName().equals("executeQuery")) {
                    var index = new AtomicInteger(-1);
                    return proxy(ResultSet.class, (r, rm, ra) -> {
                        if (rm.getName().equals("next")) {
                            return index.incrementAndGet() < (query.contains("JOIN LATERAL") ? rows.size() : assetExists ? 1 : 0);
                        }
                        if (rm.getName().startsWith("get")) {
                            return rows.get(index.get()).get(ra[0]);
                        }
                        return null;
                    });
                }
                return null;
            });
        }
    }
}
