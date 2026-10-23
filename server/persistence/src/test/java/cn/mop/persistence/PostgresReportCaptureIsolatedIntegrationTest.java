package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.Access;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.CaptureCreation;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.Summary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** Real designated PG only, with explicit fresh isolated tenants and a non-login test principal. No DDL. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_REPORT_CAPTURE_PG_TESTS", matches = "true")
class PostgresReportCaptureIsolatedIntegrationTest {
    static HikariDataSource pool;
    static ObjectMapper mapper;
    static PostgresPlatformRepository platform;
    static PostgresInspectionQuarterlyReportRepository reports;
    static PostgresInspectionWorksheetRepository worksheets;
    static PostgresInspectionTemplateRepository templates;
    static String tenant;
    static String foreignTenant;
    static String actor;
    static String asset;
    static String foreignAsset;

    @BeforeAll
    static void connect() throws Exception {
        tenant = uuidEnv("MOP_REPORT_CAPTURE_TENANT");
        foreignTenant = uuidEnv("MOP_REPORT_CAPTURE_FOREIGN_TENANT");
        actor = uuidEnv("MOP_REPORT_CAPTURE_ACTOR");
        asset = uuidEnv("MOP_REPORT_CAPTURE_ASSET");
        foreignAsset = uuidEnv("MOP_REPORT_CAPTURE_FOREIGN_ASSET");
        assertNotEquals(tenant, foreignTenant);
        for (String id : List.of(tenant, foreignTenant)) {
            assertFalse(id.startsWith("55000000-"), "Legacy/actual tenant is forbidden");
        }
        var config = new HikariConfig();
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        assertEquals("mop_test_runtime", System.getenv("SPRING_DATASOURCE_USERNAME"));
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));
        config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(5000);
        pool = new HikariDataSource(config);
        try (var connection = pool.getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement("SELECT name FROM public.mop_tenant WHERE id IN (?::uuid,?::uuid)")) {
                statement.setString(1, tenant);
                statement.setString(2, foreignTenant);
                int count = 0;
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        assertTrue(rows.getString(1).startsWith("SYNTHETIC_REPORT_CAPTURE_"));
                        count++;
                    }
                }
                assertEquals(2, count);
            }
            try (var statement = connection.prepareStatement("SELECT identity_source, "
                    + "EXISTS(SELECT 1 FROM public.mop_local_account WHERE user_id=u.id), "
                    + "EXISTS(SELECT 1 FROM public.mop_external_identity_binding WHERE user_id=u.id), "
                    + "EXISTS(SELECT 1 FROM public.mop_membership WHERE user_id=u.id AND tenant_id NOT IN (?::uuid,?::uuid)) "
                    + "FROM public.mop_user u WHERE id=?::uuid AND active")) {
                statement.setString(1, tenant);
                statement.setString(2, foreignTenant);
                statement.setString(3, actor);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("isolated_report_capture", rows.getString(1));
                    assertFalse(rows.getBoolean(2));
                    assertFalse(rows.getBoolean(3));
                    assertFalse(rows.getBoolean(4));
                }
            }
        }
        mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        platform = new PostgresPlatformRepository(pool, mapper);
        reports = new PostgresInspectionQuarterlyReportRepository(pool, platform);
        worksheets = new PostgresInspectionWorksheetRepository(pool, platform);
        templates = new PostgresInspectionTemplateRepository(pool, platform);
        assertEquals(List.of(), reports.readinessProblems());
        assertEquals(List.of(), worksheets.readinessProblems());
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    static String uuidEnv(String key) {
        String value = System.getenv(key);
        assertNotNull(value, key);
        assertEquals(value, UUID.fromString(value).toString());
        return value;
    }

    static Access access(String scope) {
        return new Access(actor, scope, platform.membership(actor, scope).orElseThrow().permissionVersion(),
                UUID.randomUUID().toString());
    }

    static InspectionWorksheetRepository.Entry source(String scope, String assetId) {
        var access = access(scope);
        var templateAccess = new InspectionTemplateRepository.Access(actor, scope, access.permissionVersion(), access.traceId());
        var definition = new InspectionTemplate("isolated_report", "SYNTHETIC_REPORT_CAPTURE_" + UUID.randomUUID(),
                List.of(new InspectionTemplate.Item("backup", "配置备份", "人工记录测试", null)));
        var template = (InspectionTemplateRepository.Entry) platform.idempotentAuthorized(actor, scope,
                "POST /integration/report-capture/template", UUID.randomUUID().toString(), definition.fingerprint(),
                access.permissionVersion(), () -> templates.authorizeWrite(templateAccess, null),
                () -> templates.create(templateAccess, 0, definition));
        var worksheetAccess = new InspectionWorksheetRepository.Access(actor, scope, access.permissionVersion(), access.traceId());
        return (InspectionWorksheetRepository.Entry) platform.idempotentAuthorized(actor, scope,
                "POST /integration/report-capture/worksheet", UUID.randomUUID().toString(), "a".repeat(64),
                access.permissionVersion(), () -> worksheets.authorizeWrite(worksheetAccess, null),
                () -> worksheets.create(worksheetAccess, 0, new InspectionWorksheetRepository.Creation(assetId,
                        template.id(), template.version(), content("SYNTHETIC initial"))));
    }

    static InspectionWorksheetRepository.Content content(String note) {
        return new InspectionWorksheetRepository.Content(Map.of("backup", new InspectionWorksheet.Observation(
                InspectionWorksheet.Result.ATTENTION, note, List.of())), Instant.parse("2026-10-01T00:00:00Z"));
    }

    static CaptureCreation input(InspectionWorksheetRepository.Entry source) {
        return new CaptureCreation("SYNTHETIC_REPORT_CAPTURE_DRAFT", List.of(
                new InspectionSourceCollectionRepository.Source(source.id(), source.version(),
                        InspectionSourceSnapshot.fromEntry(source).fingerprint())),
                new InspectionQuarterlyReport.Period(2026, 4), new InspectionQuarterlyReport.Configuration(
                        "SYNTHETIC_V1", "UTC", InspectionQuarterlyReport.DuplicateRule.REQUIRE_SINGLE_SOURCE,
                        InspectionQuarterlyReport.ArchivedRule.INCLUDE, "SYNTHETIC_V1", "SYNTHETIC_V1"));
    }

    static Object command(Access access, String key, Supplier<Object> action) {
        return platform.idempotentAuthorized(actor, access.tenantId(), "POST /integration/report-capture", key,
                "b".repeat(64), access.permissionVersion(), () -> reports.authorizeCapture(access), action);
    }

    static Summary capture(CaptureCreation input) {
        var access = access(tenant);
        return (Summary) command(access, UUID.randomUUID().toString(), () -> reports.capture(access, 0, input));
    }

    static List<Long> counts() throws Exception {
        var result = new ArrayList<Long>();
        try (var connection = pool.getConnection()) {
            connection.setReadOnly(true);
            for (String table : List.of("mop_inspection_source_collection", "mop_inspection_source_collection_item",
                    "mop_inspection_quarterly_report", "mop_audit", "mop_idempotency")) {
                try (var statement = connection.prepareStatement("SELECT count(*) FROM public." + table + " WHERE tenant_id=?::uuid")) {
                    statement.setString(1, tenant);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        result.add(rows.getLong(1));
                    }
                }
            }
        }
        return result;
    }

    @Test
    void commitsWholeChainAndFreshRepositoryVerifiesFrozenDocument() throws Exception {
        var input = input(source(tenant, asset));
        var before = counts();
        Summary saved = capture(input);
        assertEquals(List.of(before.get(0) + 1, before.get(1) + 1, before.get(2) + 1,
                before.get(3) + 2, before.get(4) + 1), counts());
        var stored = reports.get(access(tenant), saved.id());
        var fresh = new PostgresInspectionQuarterlyReportRepository(pool, new PostgresPlatformRepository(pool, mapper));
        assertEquals(stored, fresh.get(access(tenant), saved.id()));
        var document = mapper.readTree(stored.frozenDocument());
        assertEquals(1, document.path("output").path("summary").path("selectedAssets").asInt());
        assertFalse(document.path("output").path("report_published").asBoolean());
        var collections = new PostgresInspectionSourceCollectionRepository(pool, platform);
        var access = access(tenant);
        var collection = collections.get(new InspectionSourceCollectionRepository.Access(actor, tenant,
                access.permissionVersion(), access.traceId()), saved.sourceCollectionId());
        assertEquals(saved.sourceManifestSha256(), collection.manifest().fingerprint());
    }

    @Test
    void mismatchedSourceHashLeavesNoCollectionReportAuditOrReceipt() throws Exception {
        var source = source(tenant, asset);
        var input = input(source);
        var bad = new CaptureCreation(input.label(), List.of(new InspectionSourceCollectionRepository.Source(
                source.id(), source.version(), "0".repeat(64))), input.period(), input.configuration());
        var before = counts();
        assertEquals(409, assertThrows(DomainException.class, () -> capture(bad)).status);
        assertEquals(before, counts());
    }

    @Test
    void failureAfterBothInsertsRollsBackWholeChain() throws Exception {
        var input = input(source(tenant, asset));
        var before = counts();
        var access = access(tenant);
        assertThrows(IllegalStateException.class, () -> command(access, UUID.randomUUID().toString(), () -> {
            reports.capture(access, 0, input);
            throw new IllegalStateException("SYNTHETIC downstream failure");
        }));
        assertEquals(before, counts());
    }

    @Test
    void foreignSourceIs404WithoutPartialCapture() throws Exception {
        var input = input(source(foreignTenant, foreignAsset));
        var before = counts();
        assertEquals(404, assertThrows(DomainException.class, () -> capture(input)).status);
        assertEquals(before, counts());
    }

    @Test
    void stalePermissionCannotReplayOriginalReceipt() throws Exception {
        var input = input(source(tenant, asset));
        var access = access(tenant);
        String key = UUID.randomUUID().toString();
        command(access, key, () -> reports.capture(access, 0, input));
        var before = counts();
        var stale = new Access(actor, tenant, access.permissionVersion() + 1, access.traceId());
        assertEquals(401, assertThrows(DomainException.class, () -> command(stale, key, () -> {
            throw new AssertionError("must reject before replay");
        })).status);
        assertEquals(before, counts());
    }

    @Test
    void laterWorksheetRevisionCannotChangeFrozenReport() {
        var source = source(tenant, asset);
        var saved = capture(input(source));
        var stored = reports.get(access(tenant), saved.id());
        var access = access(tenant);
        var worksheetAccess = new InspectionWorksheetRepository.Access(actor, tenant,
                access.permissionVersion(), access.traceId());
        platform.idempotentAuthorized(actor, tenant, "PATCH /integration/report-capture/worksheet",
                UUID.randomUUID().toString(), "c".repeat(64), access.permissionVersion(),
                () -> worksheets.authorizeWrite(worksheetAccess, source.id()),
                () -> worksheets.update(worksheetAccess, source.id(), source.version(), content("SYNTHETIC changed")));
        assertEquals(stored, reports.get(access(tenant), saved.id()));
    }

    @Test
    void sameKeyConcurrentRequestsCommitOnlyOneCompleteChain() throws Exception {
        var input = input(source(tenant, asset));
        var before = counts();
        var access = access(tenant);
        String key = UUID.randomUUID().toString();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> task = () -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return mapper.writeValueAsString(command(access, key, () -> reports.capture(access, 0, input)));
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertEquals(mapper.readTree(first.get(30, TimeUnit.SECONDS)), mapper.readTree(second.get(30, TimeUnit.SECONDS)));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(List.of(before.get(0) + 1, before.get(1) + 1, before.get(2) + 1,
                before.get(3) + 2, before.get(4) + 1), counts());
    }
}
