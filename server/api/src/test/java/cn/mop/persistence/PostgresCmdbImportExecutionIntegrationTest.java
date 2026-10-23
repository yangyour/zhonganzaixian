package cn.mop.persistence;

import cn.mop.adapters.cmdb.CmdbImportPlanner;
import cn.mop.adapters.cmdb.CmdbImportPlannerTest;
import cn.mop.api.RuntimeConfigurationCatalog;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.CmdbImportExecutionRepository;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Designated server only, fresh invisible tenant in an outer rollback transaction.
 * Commands run with the real restricted runtime role and real SQL constraints; each uses a savepoint
 * in place of a physical commit so no test identity, configuration, asset or audit survives.
 * This tests PostgreSQL transaction behavior, not committed crash recovery, concurrent sessions or storage parsing.
 */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_CMDB_ROLLBACK_TESTS", matches = "true")
class PostgresCmdbImportExecutionIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Connection outer;
    private String tenant, actor, reviewer;
    private PostgresPlatformRepository platform;
    private PostgresCmdbImportRepository imports;
    private final Map<String, String> hashes = new HashMap<>();
    private final Map<String, Object> values = new HashMap<>();
    private int commits, rollbacks;

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv("MOP_CMDB_TEST_JDBC");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"));
        outer = DriverManager.getConnection(url, "postgres", System.getenv("MOP_CMDB_TEST_ADMIN_PASSWORD"));
        outer.setAutoCommit(false);
        tenant = uuid();
        actor = uuid();
        reviewer = uuid();
        sql("INSERT INTO public.mop_tenant(id,name) VALUES (?::uuid,?)", tenant, "SYNTHETIC-rollback-cmdb-" + tenant);
        for (String user : List.of(actor, reviewer)) {
            sql("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES (?::uuid,'cmdb_rollback_test',?,'SYNTHETIC rollback identity','padmin')", user, user);
            sql("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES (?::uuid,?::uuid,'padmin')", tenant, user);
        }
        sql("INSERT INTO public.mop_site(tenant_id,id,name) VALUES (?::uuid,'unit-site','SYNTHETIC rollback site')", tenant);
        var catalog = new RuntimeConfigurationCatalog(mapper);
        values.put("asset.import_steps", mapper.convertValue(CmdbImportPlannerTest.policy(), Map.class));
        values.put("asset.ip_scope", "TENANT");
        values.put("tenant.contract_device_quota", 10);
        for (String key : PostgresCmdbImportRepository.CONFIGURATION_KEYS) {
            hashes.put(key, catalog.hash(key));
            publish(key, values.get(key), 1);
        }
        sql("SET LOCAL ROLE mop_test_runtime");
        DataSource source = rollbackDataSource();
        platform = new PostgresPlatformRepository(source, mapper);
        var staging = new PostgresAssetImportRepository(source, mapper, platform);
        imports = new PostgresCmdbImportRepository(platform, staging,
                new PostgresManualAssetMetadataRepository(source, platform, mapper), new CmdbImportPlanner(mapper), mapper, hashes);
    }

    @AfterEach
    void clean() throws Exception {
        if (outer != null) {
            try {
                outer.rollback();
                outer.setAutoCommit(true);
                try (var s = outer.prepareStatement("SELECT count(*) FROM public.mop_tenant WHERE id=?::uuid")) {
                    s.setString(1, tenant);
                    try (var r = s.executeQuery()) {
                        r.next();
                        assertEquals(0, r.getLong(1), "Fresh rollback tenant must not survive");
                    }
                }
            } finally {
                outer.close();
            }
        }
    }

    @Test
    void realRuntimeWritesDraftsMetadataAndReceiptAndReplaysWithoutDuplicates() throws Exception {
        var source = source(Map.of(3, "SYNTHETIC brand", 17, "2026-01-01"), 2);
        var receipt = run(source, "success", imports);
        assertEquals("IMPORTED_DRAFTS", receipt.get("status"));
        assertEquals(2, receipt.get("imported_count"));
        assertEquals(2, count("mop_asset"));
        assertEquals(4, count("mop_asset_manual_revision"));
        assertEquals(1, count("mop_idempotency"));
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(receipt)),
                mapper.readTree(mapper.writeValueAsBytes(run(source, "success", imports))));
        assertEquals(2, count("mop_asset"));
        assertEquals("CMDB_SOURCE_ALREADY_IMPORTED", assertThrows(DomainException.class, () -> run(source, "different", imports)).code);
        try (var s = outer.prepareStatement("SELECT status,lifecycle_status,availability,sampled_at FROM public.mop_asset WHERE tenant_id=?::uuid")) {
            s.setString(1, tenant);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    assertEquals("unknown", r.getString(1));
                    assertEquals("DRAFT", r.getString(2));
                    assertEquals("UNKNOWN", r.getString(3));
                    assertNull(r.getObject(4));
                }
            }
        }
    }

    @Test
    void databaseFailureAfterFirstAssetRollsBackAssetsMetadataAuditAndReceipt() throws Exception {
        var source = source(Map.of(), 2);
        var realMetadata = new PostgresManualAssetMetadataRepository(rollbackDataSource(), platform, mapper);
        var failingMetadata = org.mockito.Mockito.spy(realMetadata);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(call -> {
            if (calls.incrementAndGet() == 2) {
                try (var statement = platform.idempotentCommandConnection().createStatement()) {
                    statement.execute("SELECT 1 / 0");
                } catch (SQLException failure) {
                    throw new DomainException(503, "INJECTED_DATABASE_FAILURE", "Controlled rollback test");
                }
            }
            return call.callRealMethod();
        }).when(failingMetadata).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        var failing = new PostgresCmdbImportRepository(platform,
                new PostgresAssetImportRepository(rollbackDataSource(), mapper, platform), failingMetadata,
                new CmdbImportPlanner(mapper), mapper, hashes);
        assertEquals(503, assertThrows(DomainException.class, () -> run(source, "rollback", failing)).status);
        assertEquals(0, count("mop_asset"));
        assertEquals(0, count("mop_asset_manual_revision"));
        assertEquals(0, count("mop_audit"));
        assertEquals(0, count("mop_idempotency"));
        assertEquals(1, rollbacks);
        assertEquals(2, run(source, "retry-after-rollback", imports).get("imported_count"));
    }

    @Test
    void crossTenantReferencesAndForeignCreatorAre404AndCannotWrite() throws Exception {
        var source = source(Map.of(), 1);
        var denied = new AssetImportSourceRepository.Access(reviewer, tenant, 1, "cmdb-rollback");
        assertEquals(404, assertThrows(DomainException.class, () -> protectedCommand(denied, source, "foreign", imports)).status);
        sql("RESET ROLE");
        var p = CmdbImportPlannerTest.policy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) p.get("site_mapping")).put("lab", "invisible-site");
        publish("asset.import_steps", mapper.convertValue(p, Map.class), 2);
        sql("SET LOCAL ROLE mop_test_runtime");
        assertEquals(404, assertThrows(DomainException.class, () -> run(source, "reference", imports)).status);
        assertEquals(0, count("mop_asset"));
    }

    @Test
    void quotaDuplicatesAndStaleSourceRejectBeforeWrites() throws Exception {
        var source = source(Map.of(), 2);
        var stale = new Source(source.id(), new CmdbImportExecutionRepository.Command(2, source.command().parseHash()));
        assertEquals(409, assertThrows(DomainException.class, () -> run(stale, "stale", imports)).status);
        sql("RESET ROLE");
        publish("tenant.contract_device_quota", 1, 2);
        sql("SET LOCAL ROLE mop_test_runtime");
        assertEquals("ASSET_QUOTA_EXCEEDED", assertThrows(DomainException.class, () -> run(source, "quota", imports)).code);
        sql("RESET ROLE");
        publish("tenant.contract_device_quota", 10, 3);
        sql("SET LOCAL ROLE mop_test_runtime");
        var duplicate = source(Map.of(2, "192.0.2.55"), 2);
        assertEquals("CMDB_ASSET_CONFLICT", assertThrows(DomainException.class, () -> run(duplicate, "duplicate", imports)).code);
        assertEquals(0, count("mop_asset"));
        assertEquals(0, count("mop_idempotency"));
    }

    @Test
    void revokedPermissionBlocksReceiptReplay() throws Exception {
        var source = source(Map.of(), 1);
        run(source, "replay", imports);
        sql("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=?::uuid AND user_id=?::uuid", tenant, actor);
        assertEquals(401, assertThrows(DomainException.class, () -> run(source, "replay", imports)).status);
        assertEquals(1, count("mop_asset"));
    }

    @Test
    void manualCreationSharesThePublishedQuotaWithImports() throws Exception {
        var source = source(Map.of(), 2);
        sql("RESET ROLE");
        publish("tenant.contract_device_quota", 2, 2);
        sql("SET LOCAL ROLE mop_test_runtime");
        run(source, "fill-quota", imports);
        assertEquals("ASSET_QUOTA_EXCEEDED", assertThrows(DomainException.class, () ->
                platform.idempotent(actor, tenant, "/manual-quota-test", "manual", "a".repeat(64), 1,
                        () -> platform.insert(tenant, actor, "SYNTHETIC extra", "switch", "192.0.2.99", "unit-site"))).code);
        assertEquals(2, count("mop_asset"));
        assertEquals(1, count("mop_idempotency"));
        sql("RESET ROLE");
        publish("tenant.contract_device_quota", 3, 3);
        sql("SET LOCAL ROLE mop_test_runtime");
        assertNotNull(platform.idempotent(actor, tenant, "/manual-quota-test", "manual", "a".repeat(64), 1,
                () -> platform.insert(tenant, actor, "SYNTHETIC extra", "switch", "192.0.2.99", "unit-site")));
        assertEquals(3, count("mop_asset"));
    }

    record Source(String id, CmdbImportExecutionRepository.Command command) {}

    private Source source(Map<Integer, String> overrides, int count) throws Exception {
        sql("RESET ROLE");
        String id = uuid(), store = uuid(), parse = uuid(), artifactId = uuid();
        byte[] raw = ("SYNTHETIC isolated parsed-source seed " + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = PostgresAssetImportRepository.sha(raw);
        var artifact = CmdbImportPlannerTest.artifact(overrides, count);
        var selection = new ParseSelection(ImportFormat.CSV, "UTF-8", ",", null, 1);
        var binding = Map.of("tenant_id", tenant, "source_id", id, "source_sha256", sha,
                "selection", selection, "schema_version", AssetImportSchema.VERSION, "artifact", artifact);
        String hash = PostgresAssetImportRepository.canonicalHash(mapper.valueToTree(binding));
        sql("INSERT INTO public.mop_asset_import_source(id,tenant_id,created_by,original_name,declared_format,size_bytes,sha256,object_key,permission_version,store_operation_id,storage_status) VALUES (?::uuid,?::uuid,?::uuid,'SYNTHETIC.csv','CSV',?,?,?,1,?::uuid,'READY')", id, tenant, actor, raw.length, sha, "asset-import-sources/" + tenant + "/" + id, store);
        for (var op : Map.of(store, "STORE_RAW", parse, "PARSE").entrySet()) {
            sql("INSERT INTO public.mop_asset_import_operation(id,tenant_id,source_id,created_by,permission_version,kind) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1,?)", op.getKey(), tenant, id, actor, op.getValue());
        }
        sql("INSERT INTO public.mop_asset_import_artifact(id,tenant_id,source_id,operation_id,parse_hash,source_sha256,selection_json,artifact_json,schema_version) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::jsonb,?::jsonb,?)", artifactId, tenant, id, parse, hash, sha, mapper.writeValueAsString(selection), mapper.writeValueAsString(artifact), AssetImportSchema.VERSION);
        sql("UPDATE public.mop_asset_import_source SET parse_operation_id=?::uuid,latest_artifact_id=?::uuid,parse_status='PARSED' WHERE id=?::uuid", parse, artifactId, id);
        sql("SET LOCAL ROLE mop_test_runtime");
        return new Source(id, new CmdbImportExecutionRepository.Command(1, hash));
    }

    private void publish(String key, Object value, long version) throws Exception {
        String revision = uuid();
        sql("INSERT INTO public.mop_configuration_revision(id,tenant_id,configuration_key,proposal,workflow_status,created_by,reviewed_by,reviewed_at,review_comment) VALUES (?::uuid,?::uuid,?,?::jsonb,'APPROVED',?::uuid,?::uuid,clock_timestamp(),'SYNTHETIC rollback test only')", revision, tenant, key, mapper.writeValueAsString(Map.of("proposed_value", value)), actor, reviewer);
        sql("INSERT INTO public.mop_runtime_configuration_release(id,tenant_id,configuration_key,version,action,revision_id,revision_version,value_json,definition_hash,effective_at,published_by,published_permission_version,published_at,reason) VALUES (?::uuid,?::uuid,?,?,'PUBLISH',?::uuid,1,?::jsonb,?,statement_timestamp(),?::uuid,1,statement_timestamp(),'SYNTHETIC rollback only')", uuid(), tenant, key, version, revision, mapper.writeValueAsString(value), hashes.get(key), actor);
    }

    private Map<String, Object> run(Source source, String key, CmdbImportExecutionRepository repository) {
        return protectedCommand(new AssetImportSourceRepository.Access(actor, tenant, 1, "cmdb-rollback"), source, key, repository);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> protectedCommand(AssetImportSourceRepository.Access access, Source source, String key, CmdbImportExecutionRepository repository) {
        Object result = platform.idempotentAuthorized(access.actorId(), tenant, "/cmdb-imports/" + source.id(), key,
                "a".repeat(64), access.permissionVersion(), () -> repository.authorize(access, source.id()),
                () -> repository.execute(access, source.id(), source.command()));
        return mapper.convertValue(result, Map.class);
    }

    private long count(String table) throws SQLException {
        assertTrue(Set.of("mop_asset", "mop_asset_manual_revision", "mop_idempotency", "mop_audit").contains(table));
        try (var s = outer.prepareStatement("SELECT count(*) FROM public." + table + " WHERE tenant_id=?::uuid")) {
            s.setString(1, tenant);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private DataSource rollbackDataSource() {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p, m, a) -> {
            if (!m.getName().equals("getConnection")) {
                throw new UnsupportedOperationException(m.getName());
            }
            Savepoint[] point = new Savepoint[1];
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setAutoCommit":
                        assertEquals(false, args[0]);
                        point[0] = outer.setSavepoint();
                        return null;
                    case "commit":
                        sql("SET CONSTRAINTS ALL IMMEDIATE");
                        sql("SET CONSTRAINTS ALL DEFERRED");
                        outer.releaseSavepoint(point[0]);
                        commits++;
                        return null;
                    case "rollback":
                        outer.rollback(point[0]);
                        outer.releaseSavepoint(point[0]);
                        rollbacks++;
                        return null;
                    case "close":
                        return null;
                    default:
                        try {
                            return method.invoke(outer, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                }
            });
        });
    }

    private void sql(String sql, Object... args) throws SQLException {
        try (var s = outer.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }
            s.execute();
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
