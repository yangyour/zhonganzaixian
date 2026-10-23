package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.BackupTaskGrant.Operation;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static cn.mop.persistence.BackupTaskDiscoveryTest.proxy;
import static org.junit.jupiter.api.Assertions.*;

class BackupTaskAuthorizationTest {
    final UUID tenant = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();
    final Access access = new Access(tenant, UUID.randomUUID(), 7);
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    final String binding = "backup-task-binding:synthetic-unit-test";
    final AtomicInteger queries = new AtomicInteger();
    final AtomicReference<List<BackupTaskGrant>> grants = new AtomicReference<>(List.of(grant(Set.of(Operation.DISCOVER))));

    @Test
    void exactGrantAndActivePermissionVersionAreBothRequired() throws Exception {
        authorization().require(connection(true, 7, false), access, tenant, asset, Operation.DISCOVER);
        assertEquals(1, queries.get());
        assertEquals(403, assertThrows(DomainException.class,
                () -> authorization().require(connection(false, 7, false), access, tenant, asset, Operation.DISCOVER)).status);
        assertEquals(403, assertThrows(DomainException.class,
                () -> authorization().require(connection(true, 8, false), access, tenant, asset, Operation.DISCOVER)).status);
    }

    @Test
    void grantDoesNotImplyOtherOperationsOrAssets() {
        for (Operation operation : List.of(Operation.ARCHIVE_START, Operation.ARCHIVE_RECONCILE, Operation.VERSION_REGISTER)) {
            assertEquals(403, assertThrows(DomainException.class,
                    () -> authorization().require(connection(true, 7, false), access, tenant, asset, operation)).status);
        }
        assertEquals(403, assertThrows(DomainException.class,
                () -> authorization().require(connection(true, 7, false), access, tenant, UUID.randomUUID(), Operation.DISCOVER)).status);
        assertEquals(0, queries.get());
    }

    @Test
    void crossTenantFailsBeforeDatabaseEvenWithMatchingRole() {
        assertEquals(404, assertThrows(DomainException.class,
                () -> authorization().require(connection(true, 7, false), access, UUID.randomUUID(), asset, Operation.DISCOVER)).status);
        assertEquals(0, queries.get());
    }

    @Test
    void grantRevocationIsRecheckedAfterIdentityLock() {
        Connection connection = connection(true, 7, false);
        var calls = new AtomicInteger();
        var authorization = new PostgresBackupTaskAuthorization(binding,
                () -> calls.incrementAndGet() == 1 ? grants.get() : List.of(), Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(403, assertThrows(DomainException.class,
                () -> authorization.require(connection, access, tenant, asset, Operation.DISCOVER)).status);
        assertEquals(2, calls.get());
    }

    @Test
    void expiredBindingAndChangedActorOrPermissionAreDenied() {
        var original = grants.get().get(0);
        var variants = List.of(
                new BackupTaskGrant(binding, tenant, asset, access.actorId(), 7, now, original.operations()),
                new BackupTaskGrant(binding, tenant, asset, UUID.randomUUID(), 7, now.plusSeconds(1), original.operations()),
                new BackupTaskGrant(binding, tenant, asset, access.actorId(), 8, now.plusSeconds(1), original.operations()),
                new BackupTaskGrant("backup-task-binding:other", tenant, asset, access.actorId(), 7, now.plusSeconds(1), original.operations()));
        for (var grant : variants) {
            grants.set(List.of(grant));
            assertThrows(DomainException.class,
                    () -> authorization().require(connection(true, 7, false), access, tenant, asset, Operation.DISCOVER));
        }
        assertEquals(0, queries.get());
    }

    @Test
    void unavailableGrantSourceFailsClosedAndSanitizesDetails() {
        var authorization = new PostgresBackupTaskAuthorization(binding, () -> {
            throw new IllegalStateException("PRIVATE_DETAIL");
        }, Clock.fixed(now, ZoneOffset.UTC));
        var failure = assertThrows(DomainException.class,
                () -> authorization.require(connection(true, 7, false), access, tenant, asset, Operation.DISCOVER));
        assertEquals(503, failure.status);
        assertFalse(failure.getMessage().contains("PRIVATE_DETAIL"));
        assertNull(failure.getCause());
    }

    @Test
    void autoCommitCannotReleaseRevocationLocksEarly() {
        assertEquals(503, assertThrows(DomainException.class,
                () -> authorization().require(connection(true, 7, true), access, tenant, asset, Operation.DISCOVER)).status);
        assertEquals(0, queries.get());
    }

    @Test
    void workerAuthorityNeverPermitsStageOrConfirmNoDispatch() {
        grants.set(List.of(grant(Set.of(Operation.values()))));
        Scope scope = new Scope(tenant, asset, UUID.randomUUID(), 1, UUID.randomUUID());
        for (var action : List.of(PostgresBackupArchiveRepository.Action.STAGE,
                PostgresBackupArchiveRepository.Action.CONFIRM_NO_DISPATCH)) {
            assertEquals(403, assertThrows(DomainException.class,
                    () -> authorization().archiveAuthorizer().authorize(connection(true, 7, false), access, scope, action)).status);
        }
    }

    @Test
    void explicitArchiveAndVersionGrantsMapToSeparateOperations() throws Exception {
        grants.set(List.of(grant(Set.of(Operation.ARCHIVE_READ, Operation.VERSION_READ))));
        var scope = new Scope(tenant, asset, UUID.randomUUID(), 1, UUID.randomUUID());
        var connection = connection(true, 7, false);
        authorization().archiveAuthorizer().authorize(connection, access, scope, PostgresBackupArchiveRepository.Action.READ);
        var versionAccess = new PostgresBackupVersionRepository.Access(tenant, access.actorId(), 7);
        authorization().versionAuthorizer().authorize(connection, versionAccess, scope, PostgresBackupVersionRepository.Operation.READ);
        assertThrows(DomainException.class, () -> authorization().versionAuthorizer()
                .authorize(connection, versionAccess, scope, PostgresBackupVersionRepository.Operation.REGISTER));
    }

    @Test
    void grantsDefensivelyCopyOperationsAndRejectUnknownBindings() {
        var operations = new HashSet<>(Set.of(Operation.DISCOVER));
        var grant = grant(operations);
        operations.add(Operation.VERSION_REGISTER);
        assertFalse(grant.operations().contains(Operation.VERSION_REGISTER));
        assertThrows(DomainException.class,
                () -> new PostgresBackupTaskAuthorization("https://private-endpoint", grants::get, Clock.systemUTC()));
    }

    BackupTaskGrant grant(Set<Operation> operations) {
        return new BackupTaskGrant(binding, tenant, asset, access.actorId(), 7, now.plusSeconds(60), operations);
    }

    PostgresBackupTaskAuthorization authorization() {
        return new PostgresBackupTaskAuthorization(binding, grants::get, Clock.fixed(now, ZoneOffset.UTC));
    }

    Connection connection(boolean active, long version, boolean autoCommit) {
        return proxy(Connection.class, (c, cm, ca) -> switch (cm.getName()) {
            case "getAutoCommit" -> autoCommit;
            case "prepareStatement" -> {
                assertTrue(((String) ca[0]).contains("FOR SHARE OF m,u"));
                assertTrue(((String) ca[0]).contains("m.role_code IN ('padmin','msp')"));
                queries.incrementAndGet();
                yield proxy(PreparedStatement.class, (q, qm, qa) -> {
                    if (qm.getName().equals("executeQuery")) {
                        var cursor = new AtomicInteger();
                        return proxy(ResultSet.class, (r, rm, ra) -> switch (rm.getName()) {
                            case "next" -> active && cursor.incrementAndGet() == 1;
                            case "getLong" -> version;
                            default -> null;
                        });
                    }
                    return null;
                });
            }
            default -> throw new AssertionError(cm);
        });
    }
}
