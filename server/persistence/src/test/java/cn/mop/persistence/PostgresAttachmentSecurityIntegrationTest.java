package cn.mop.persistence;

import cn.mop.domain.AttachmentSecurityRepository;
import cn.mop.domain.AttachmentSecurityRepository.Access;
import cn.mop.domain.AttachmentSecurityRepository.Action;
import cn.mop.domain.AttachmentSecurityRepository.ScanObservation;
import cn.mop.domain.AttachmentSecurityRepository.Snapshot;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual designated PostgreSQL state/audit tests in a new namespace; storage and antivirus are NOT tested. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_ATTACHMENT_SECURITY_PG_TESTS", matches = "true")
class PostgresAttachmentSecurityIntegrationTest {
    private static HikariDataSource pool;
    private static ObjectMapper mapper;
    private static PostgresPlatformRepository platform;
    private static AttachmentSecurityRepository security;
    private static String schema;
    private static String tenant;
    private static String foreignTenant;
    private static String actor;
    private static String viewer;
    private static List<String> files;
    private static String viewerFile;

    @BeforeAll
    static void connect() throws Exception {
        schema = required("MOP_ATTACHMENT_SECURITY_SCHEMA");
        assertTrue(schema.matches("mop_security_test_[a-f0-9]{32}"));
        tenant = uuid("MOP_SECURITY_TEST_TENANT");
        foreignTenant = uuid("MOP_SECURITY_TEST_FOREIGN_TENANT");
        actor = uuid("MOP_SECURITY_TEST_ACTOR");
        viewer = uuid("MOP_SECURITY_TEST_VIEWER");
        viewerFile = uuid("MOP_SECURITY_TEST_VIEWER_FILE");
        files = List.of(required("MOP_SECURITY_TEST_FILES").split(","));
        assertEquals(12, files.size());
        assertNotEquals(tenant, foreignTenant);
        assertFalse(tenant.startsWith("55000000-"));
        assertFalse(foreignTenant.startsWith("55000000-"));
        var configuration = new HikariConfig();
        String url = required("SPRING_DATASOURCE_URL");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        assertEquals("mop_test_runtime", required("SPRING_DATASOURCE_USERNAME"));
        configuration.setJdbcUrl(url);
        configuration.setUsername(required("SPRING_DATASOURCE_USERNAME"));
        configuration.setPassword(required("SPRING_DATASOURCE_PASSWORD"));
        configuration.setMaximumPoolSize(4);
        configuration.setConnectionTimeout(5000);
        pool = new HikariDataSource(configuration);
        mapper = new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        platform = new PostgresPlatformRepository(pool, mapper);
        security = new PostgresAttachmentSecurityRepository(platform, schema, "CONFIGURED_NOT_VERIFIED", "b".repeat(64));
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
            "SELECT u.identity_source,t.name,NOT EXISTS(SELECT 1 FROM public.mop_local_account l WHERE l.user_id=u.id) "
                + "FROM public.mop_user u JOIN public.mop_membership m ON m.user_id=u.id JOIN public.mop_tenant t ON t.id=m.tenant_id "
                + "WHERE u.id IN (?::uuid,?::uuid) AND m.tenant_id IN (?::uuid,?::uuid) AND u.active AND m.active")) {
            statement.setString(1, actor); statement.setString(2, viewer);
            statement.setString(3, tenant); statement.setString(4, foreignTenant);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    assertEquals("isolated_attachment_security", rows.getString(1));
                    assertTrue(rows.getString(2).startsWith("SYNTHETIC_ATTACHMENT_SECURITY_"));
                    assertTrue(rows.getBoolean(3));
                    count++;
                }
                assertEquals(4, count);
            }
        }
    }

    @AfterAll
    static void close() {
        if (pool != null) { pool.close(); }
    }

    private static Access access(String actorId, String tenantId) {
        return new Access(tenantId, actorId, platform.membership(actorId, tenantId).orElseThrow().permissionVersion());
    }

    private Snapshot action(int file, Action action, long version) {
        return command(platform, security, access(actor, tenant), files.get(file), action, version, UUID.randomUUID().toString());
    }

    private static Snapshot command(PostgresPlatformRepository platform, AttachmentSecurityRepository security, Access access,
                                    String file, Action action, long version, String key) {
        Object result = platform.idempotentAuthorized(access.actorId(), access.tenantId(), "/security-test/" + file,
            key, action.name() + "-" + version, access.permissionVersion(), () -> security.current(access, file),
            () -> security.change(access, file, version, action, "isolated-security-trace"));
        return mapper.convertValue(result, Snapshot.class);
    }

    private static Instant databaseNow() throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT clock_timestamp()")) {
            rows.next(); return rows.getTimestamp(1).toInstant();
        }
    }

    private Snapshot clean(int file, Snapshot queued, long seconds) throws Exception {
        Instant now = databaseNow();
        return security.recordScan(access(actor, tenant), files.get(file), queued.version(),
            new ScanObservation(queued.scanRequestId(), queued.contentSha256(), "NO_THREAT_REPORTED",
                "SYNTHETIC_ENGINE", "SYNTHETIC_SIGNATURE", "b".repeat(64), now, now.plusSeconds(seconds)), "unit-injected-scan");
    }

    @Test
    void successfulStorageIsStillQuarantinedWithoutAnyScan() {
        Snapshot current = security.current(access(actor, tenant), files.get(0));
        assertEquals("QUARANTINED", current.state());
        assertEquals(0, current.version());
        assertFalse(current.contentAllowed());
        assertEquals("CONFIGURED_NOT_VERIFIED", current.scannerStatus());
        assertEquals(422, assertThrows(DomainException.class, () -> security.requireReleased(access(actor, tenant), files.get(0))).status);
    }

    @Test
    void scanCancellationInvalidatesLateResultsAndCas() throws Exception {
        Snapshot queued = action(1, Action.REQUEST_SCAN, 0);
        Snapshot cancelled = action(1, Action.CANCEL_SCAN, queued.version());
        assertEquals("CANCELLED", cancelled.state());
        assertEquals(409, assertThrows(DomainException.class, () -> clean(1, queued, 120)).status);
        assertFalse(security.current(access(actor, tenant), files.get(1)).contentAllowed());
    }

    @Test
    void cleanEvidenceRequiresExplicitReleaseAndRevocationBlocksOldGrants() throws Exception {
        Snapshot ready = clean(2, action(2, Action.REQUEST_SCAN, 0), 120);
        assertTrue(ready.releaseAllowed());
        assertFalse(ready.contentAllowed());
        Snapshot released = action(2, Action.RELEASE, ready.version());
        assertEquals(released.version(), security.requireReleased(access(actor, tenant), files.get(2)));
        Snapshot revoked = action(2, Action.QUARANTINE, released.version());
        assertEquals("QUARANTINED", revoked.state());
        assertEquals(422, assertThrows(DomainException.class, () -> security.requireReleased(access(actor, tenant), files.get(2))).status);
    }

    @Test
    void failedAndUnknownScannerObservationsCannotRelease() {
        for (int index = 3; index <= 4; index++) {
            Snapshot queued = action(index, Action.REQUEST_SCAN, 0);
            String file = files.get(index);
            Snapshot result = security.recordScan(access(actor, tenant), file, queued.version(),
                new ScanObservation(queued.scanRequestId(), queued.contentSha256(), index == 3 ? "THREAT_REPORTED" : "UNKNOWN",
                    null, null, null, null, null), "unit-failed-scan");
            assertFalse(result.releaseAllowed());
            assertFalse(result.contentAllowed());
            assertEquals(422, assertThrows(DomainException.class,
                () -> security.change(access(actor, tenant), file, result.version(), Action.RELEASE, "unit-release")).status);
        }
    }

    @Test
    void crossTenantAndForeignPrivateStagingRemain404() {
        assertEquals(404, assertThrows(DomainException.class,
            () -> security.current(access(actor, foreignTenant), files.get(0))).status);
        assertEquals(404, assertThrows(DomainException.class,
            () -> security.current(access(viewer, tenant), files.get(0))).status);
        assertEquals(404, assertThrows(DomainException.class,
            () -> security.current(access(actor, tenant), viewerFile)).status);
    }

    @Test
    void uploaderWithoutManagementCapabilityCannotPublishOrQuarantine() {
        Access current = access(viewer, tenant);
        Snapshot queued = command(platform, security, current, viewerFile, Action.REQUEST_SCAN, 0, UUID.randomUUID().toString());
        assertFalse(queued.canManageRelease());
        for (Action action : List.of(Action.RELEASE, Action.QUARANTINE)) {
            assertEquals(403, assertThrows(DomainException.class,
                () -> security.change(current, viewerFile, queued.version(), action, "unit-denied")).status);
        }
    }

    @Test
    void replayReturnsOriginalRevisionWithoutAppendingAgain() {
        Access current = access(actor, tenant); String key = UUID.randomUUID().toString();
        Snapshot first = command(platform, security, current, files.get(5), Action.REQUEST_SCAN, 0, key);
        Snapshot replay = command(platform, security, current, files.get(5), Action.REQUEST_SCAN, 0, key);
        assertEquals(first, replay);
        assertEquals(1, security.current(current, files.get(5)).version());
        assertEquals(409, assertThrows(DomainException.class,
            () -> command(platform, security, current, files.get(5), Action.CANCEL_SCAN, 1, key)).status);
    }

    @Test
    void concurrentCommandsAtOneVersionHaveOnlyOneWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try { action(6, Action.REQUEST_SCAN, 0); return 200; }
                    catch (DomainException result) { return result.status; }
                }));
            }
            start.countDown(); var results = new ArrayList<Integer>();
            for (var future : futures) { results.add(future.get(15, TimeUnit.SECONDS)); }
            results.sort(Integer::compare); assertEquals(List.of(200, 409), results);
            assertEquals(1, security.current(access(actor, tenant), files.get(6)).version());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void expiredEvidenceStopsContentWithoutDeletingHistory() throws Exception {
        Snapshot ready = clean(7, action(7, Action.REQUEST_SCAN, 0), 1);
        Snapshot released = action(7, Action.RELEASE, ready.version());
        assertTrue(released.contentAllowed());
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) { statement.execute("SELECT pg_sleep(1.2)"); }
        Snapshot expired = security.current(access(actor, tenant), files.get(7));
        assertEquals("RELEASED", expired.state());
        assertFalse(expired.contentAllowed());
        assertEquals(released.version(), expired.version());
    }

    @Test
    void scanReceiptCannotBeReboundToDifferentBytesOrRequest() {
        Snapshot queued = action(8, Action.REQUEST_SCAN, 0);
        for (ScanObservation invalid : List.of(
            new ScanObservation(queued.scanRequestId(), "f".repeat(64), "UNKNOWN", null, null, null, null, null),
            new ScanObservation(UUID.randomUUID().toString(), queued.contentSha256(), "UNKNOWN", null, null, null, null, null))) {
            assertEquals(422, assertThrows(DomainException.class,
                () -> security.recordScan(access(actor, tenant), files.get(8), queued.version(), invalid, "unit-invalid")).status);
        }
    }

    @Test
    void auditFailureRollsBackDecisionAndIdempotencyTogether() {
        DataSource failedAudit = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                try {
                    Object value = method.invoke(pool, args);
                    if (!(value instanceof Connection connection)) { return value; }
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, called, values) -> {
                        if (called.getName().equals("prepareStatement") && values[0] instanceof String sql && sql.startsWith("INSERT INTO public.mop_audit")) {
                            throw new SQLException("SYNTHETIC_AUDIT_FAILURE", "08006");
                        }
                        try { return called.invoke(connection, values); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        var failingPlatform = new PostgresPlatformRepository(failedAudit, mapper);
        var failingSecurity = new PostgresAttachmentSecurityRepository(failingPlatform, schema, "NOT_CONFIGURED");
        Access current = access(actor, tenant); String key = UUID.randomUUID().toString();
        assertThrows(DomainException.class,
            () -> command(failingPlatform, failingSecurity, current, files.get(9), Action.REQUEST_SCAN, 0, key));
        assertEquals(0, security.current(current, files.get(9)).version());
        assertEquals(1, command(platform, security, current, files.get(9), Action.REQUEST_SCAN, 0, key).version());
    }

    @Test
    void runtimeCannotRewriteOrDeleteSecurityHistory() throws Exception {
        action(10, Action.REQUEST_SCAN, 0);
        for (String query : List.of("DELETE FROM " + schema + ".mop_attachment_security_revision",
            "UPDATE " + schema + ".mop_attachment_security_revision SET state='RELEASED'")) {
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate(query)).getSQLState());
            }
        }
    }

    @Test
    void changedPermissionVersionCannotReadOrWrite() {
        Access current = access(actor, tenant);
        Access stale = new Access(tenant, actor, current.permissionVersion() + 1);
        assertEquals(401, assertThrows(DomainException.class, () -> security.current(stale, files.get(11))).status);
        assertEquals(401, assertThrows(DomainException.class,
            () -> security.change(stale, files.get(11), 0, Action.REQUEST_SCAN, "unit-stale")).status);
    }

    @Test
    void absentScannerPolicyNeverAllowsPublication() {
        var unavailable = new PostgresAttachmentSecurityRepository(platform, schema, "NOT_CONFIGURED");
        assertFalse(unavailable.current(access(actor, tenant), files.get(0)).contentAllowed());
        assertEquals("NOT_CONFIGURED", unavailable.current(access(actor, tenant), files.get(0)).scannerStatus());
    }

    private static String required(String key) {
        String value = System.getenv(key); assertNotNull(value, key); return value;
    }
    private static String uuid(String key) {
        String value = required(key); assertEquals(UUID.fromString(value).toString(), value); return value;
    }
}
