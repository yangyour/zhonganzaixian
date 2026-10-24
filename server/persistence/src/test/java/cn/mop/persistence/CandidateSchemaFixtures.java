package cn.mop.persistence;

import cn.mop.domain.inspection.InspectionQuarterlyReport;
import cn.mop.domain.inspection.InspectionSourceManifest;
import cn.mop.domain.monitoring.MonitoringEnrollment;
import cn.mop.domain.monitoring.MonitoringObjectBinding;
import cn.mop.domain.monitoring.MonitoringObjectMapping;
import cn.mop.domain.reporting.ReportPublication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Minimal synthetic SQL fixtures only, invoked after the owned-database and empty-schema gates. No business acceptance claim. */
final class CandidateSchemaFixtures {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CandidateSchemaFixtures() { }

    private record Scope(UUID tenant, UUID actor, UUID asset) { }

    static void exercise(String stage, DataSource migrator, DataSource runtime, List<Map<String, Object>> evidence) throws Exception {
        Scope scope = seed(migrator, stage);
        switch (stage) {
            case "N01" -> notification(runtime, scope, evidence);
            case "P02" -> attachment(migrator, runtime, scope, evidence);
            case "R02" -> report(migrator, runtime, scope, evidence);
            case "M02" -> {
                enrollment(runtime, scope, evidence);
                mapping(runtime, scope, evidence);
            }
            default -> throw new IllegalArgumentException("Unsupported owned fixture stage");
        }
    }

    /** No real identities, bindings, accounts, external messages or device operations are created. */
    private static Scope seed(DataSource migrator, String stage) throws Exception {
        Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String label = "SYNTHETIC_CANDIDATE_" + stage + "_" + scope.tenant();
        try (var connection = migrator.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) "
                    + "VALUES(?::uuid,'candidate_schema_only',?,?,'padmin')", scope.actor(), label, label);
            execute(connection, "INSERT INTO public.mop_tenant(id,name) VALUES(?::uuid,?)", scope.tenant(), label);
            execute(connection, "INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(?::uuid,?::uuid,'padmin')",
                    scope.tenant(), scope.actor());
            execute(connection, "INSERT INTO public.mop_site(tenant_id,id,name) VALUES(?::uuid,'candidate-schema','SYNTHETIC_SCHEMA_SITE')",
                    scope.tenant());
            execute(connection, "INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,source,created_by,updated_by) "
                    + "VALUES(?::uuid,?::uuid,'SYNTHETIC_SCHEMA_ASSET','other','192.0.2.10','candidate-schema','manual',?::uuid,?::uuid)",
                    scope.asset(), scope.tenant(), scope.actor(), scope.actor());
            connection.commit();
        }
        return scope;
    }

    private static void notification(DataSource runtime, Scope scope, List<Map<String, Object>> evidence) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = runtime.getConnection()) {
            beginRuntime(connection);
            for (int version = 0; version <= 1; version++) {
                execute(connection, "INSERT INTO public.mop_notification_control"
                        + "(tenant_id,kind,object_id,version,expected_version,command_id,actor_id,permission_version,action,trace_id,state,command_sha256) "
                        + "VALUES(?::uuid,'POLICY',?::uuid,?,?,?::uuid,?::uuid,1,'SCHEMA_FIXTURE','candidate-schema',?::jsonb,?)",
                        scope.tenant(), id, version, version - 1, UUID.randomUUID(), scope.actor(),
                        "{\"schema_only\":true}", "a".repeat(64));
            }
            assertEquals(2, count(connection, "mop_notification_control", "object_id", scope, id, ""));
            var result = lockAndImmutable(connection, "mop_notification_control", "object_id", "root_version",
                    scope, id, " AND kind='POLICY' AND version=0", "23514");
            assertEquals(2, count(connection, "mop_notification_control", "object_id", scope, id, ""));
            result.put("first_revision_inserted", true);
            result.put("revision_count", 1);
            connection.commit();
            evidence.add(result);
        }
    }

    private static void attachment(DataSource migrator, DataSource runtime, Scope scope,
                                   List<Map<String, Object>> evidence) throws Exception {
        UUID file = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        String contentHash = CandidateSchemaChainPostgresTest.sha256("SYNTHETIC_SCHEMA_BYTES".getBytes(StandardCharsets.UTF_8));
        // Storage metadata is a marked fixture. No object was uploaded and no scanner verdict is invented.
        try (var connection = migrator.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO public.mop_file(id,tenant_id,created_by,purpose,original_name,media_type,size_bytes,sha256,"
                    + "object_key,operation_id,permission_version,upload_status) "
                    + "VALUES(?::uuid,?::uuid,?::uuid,'customer_report_photo','SYNTHETIC_SCHEMA_ONLY.png','image/png',22,?,?,?::uuid,1,'READY')",
                    file, scope.tenant(), scope.actor(), contentHash, "photos/" + scope.tenant() + "/" + file, operation);
            execute(connection, "INSERT INTO public.mop_operation(id,tenant_id,kind,status,step,result_ref,error,file_id) "
                    + "VALUES(?::uuid,?::uuid,'FILE_UPLOAD','SUCCEEDED','STORED',?,NULL,?::uuid)",
                    operation, scope.tenant(), "/files/" + file, file);
            connection.commit();
        }
        try (var connection = runtime.getConnection()) {
            beginRuntime(connection);
            execute(connection, "INSERT INTO public.mop_attachment_security_revision"
                    + "(tenant_id,file_id,version,action,state,actor_id,permission_version,scan_request_id,content_sha256,trace_id) "
                    + "VALUES(?::uuid,?::uuid,1,'REQUEST_SCAN','QUEUED',?::uuid,1,?::uuid,?,'candidate-schema')",
                    scope.tenant(), file, scope.actor(), UUID.randomUUID(), contentHash);
            lockRow(connection, "mop_file", "id", scope, file, "", "SHARE");
            lockRow(connection, "mop_file", "id", scope, file, "", "UPDATE");
            requireUpdateColumns(connection, "mop_attachment_security_revision", Set.of());
            rejectedUpdate(connection, "mop_attachment_security_revision", "file_id", "version", scope, file, "", "42501");
            assertEquals(1, count(connection, "mop_attachment_security_revision", "file_id", scope, file, ""));
            connection.commit();
        }
        try (var connection = migrator.getConnection()) {
            connection.setAutoCommit(false);
            rejectedUpdate(connection, "mop_attachment_security_revision", "file_id", "version", scope, file, "", "23514");
            assertEquals(1, count(connection, "mop_attachment_security_revision", "file_id", scope, file, ""));
            connection.commit();
        }
        evidence.add(new LinkedHashMap<>(Map.of("table", "mop_attachment_security_revision", "first_revision_inserted", true,
                "revision_count", 1, "nonempty_parent_share_lock", true, "nonempty_parent_update_lock", true,
                "runtime_update_denied_sql_state", "42501", "owner_immutable_update_sql_state", "23514",
                "external_scanner_validation", "NOT_RUN")));
    }

    private static void report(DataSource migrator, DataSource runtime, Scope scope,
                               List<Map<String, Object>> evidence) throws Exception {
        UUID collection = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        var manifest = new InspectionSourceManifest(scope.tenant(), List.of());
        byte[] sourceDocument = InspectionSourceDocumentCodec.encode(manifest);
        var draft = InspectionQuarterlyReport.generate(scope.tenant(), manifest, new InspectionQuarterlyReport.Period(2026, 4),
                InspectionQuarterlyReport.Configuration.pending(), InspectionQuarterlyReport.Completeness.COMPLETE_EXPLICIT_SELECTION);
        byte[] reportDocument = InspectionQuarterlyReportDocumentCodec.encode(collection.toString(), draft);
        String sourceHash = CandidateSchemaChainPostgresTest.sha256(sourceDocument);
        String reportHash = CandidateSchemaChainPostgresTest.sha256(reportDocument);
        try (var connection = migrator.getConnection()) {
            connection.setAutoCommit(false);
            Instant recorded = now(connection);
            execute(connection, "INSERT INTO public.mop_inspection_source_collection"
                    + "(id,tenant_id,created_by,label,recorded_at,source_count,document,source_manifest_sha256) "
                    + "VALUES(?::uuid,?::uuid,?::uuid,'SYNTHETIC_SCHEMA_EMPTY_SELECTION',?,0,?,?)",
                    collection, scope.tenant(), scope.actor(), recorded, sourceDocument, manifest.fingerprint());
            execute(connection, "INSERT INTO public.mop_inspection_quarterly_report"
                    + "(id,tenant_id,created_by,label,recorded_at,source_collection_id,source_document_sha256,source_manifest_sha256,"
                    + "year,quarter,report_sha256,document) VALUES(?::uuid,?::uuid,?::uuid,'SYNTHETIC_SCHEMA_DRAFT',?,?::uuid,?,?,2026,4,?,?)",
                    sourceId, scope.tenant(), scope.actor(), recorded, collection, sourceHash, manifest.fingerprint(),
                    draft.fingerprint(), reportDocument);
            connection.commit();
        }
        UUID id = UUID.randomUUID();
        try (var connection = runtime.getConnection()) {
            beginRuntime(connection);
            Instant captured = now(connection);
            var payload = JSON.createObjectNode();
            payload.put("format", "MOP_REPORT_PUBLICATION_V1").put("tenant_id", scope.tenant().toString())
                    .put("source_id", sourceId.toString()).put("source_document_sha256", reportHash);
            payload.set("output", JSON.readTree(reportDocument).get("output"));
            byte[] bytes = JSON.writeValueAsBytes(payload);
            var document = new ReportPublication.Document(scope.tenant(), id, id, null, 1,
                    ReportPublication.Kind.QUARTERLY, ReportPublication.Scope.ISOLATED_TEST,
                    CandidateSchemaChainPostgresTest.sha256(bytes), manifest.fingerprint(), null, null,
                    scope.actor(), captured, List.of("SYNTHETIC_SCHEMA_ONLY"));
            execute(connection, "INSERT INTO public.mop_report_publication_document"
                    + "(tenant_id,id,source_report_id,source_document_sha256,label,payload,created_by,permission_version,captured_at) "
                    + "VALUES(?::uuid,?::uuid,?::uuid,?,'SYNTHETIC_SCHEMA_PUBLICATION',?,?::uuid,1,?)",
                    scope.tenant(), id, sourceId, reportHash, bytes, scope.actor(), captured);
            execute(connection, "INSERT INTO public.mop_report_publication_revision"
                    + "(tenant_id,document_id,version,action,phase,state,actor_id,permission_version,recorded_at) "
                    + "VALUES(?::uuid,?::uuid,1,'CREATE','DRAFT',?::jsonb,?::uuid,1,?)",
                    scope.tenant(), id, JSON.writeValueAsString(ReportPublication.create(document)), scope.actor(), captured);
            assertEquals(1, count(connection, "mop_report_publication_revision", "document_id", scope, id, ""));
            var result = lockAndImmutable(connection, "mop_report_publication_document", "id", "id", scope, id, "", "P0001");
            requireUpdateColumns(connection, "mop_report_publication_revision", Set.of());
            result.put("first_revision_inserted", true);
            result.put("revision_count", 1);
            assertEquals(1, count(connection, "mop_report_publication_revision", "document_id", scope, id, ""));
            connection.commit();
            evidence.add(result);
        }
    }

    private static void enrollment(DataSource runtime, Scope scope, List<Map<String, Object>> evidence) throws Exception {
        UUID id = UUID.randomUUID();
        String configuration = JSON.writeValueAsString(Map.of("version", 1, "targets", List.of(Map.of(
                "id", UUID.randomUUID(), "tenant_id", scope.tenant(), "asset_id", scope.asset(), "enabled", false))));
        try (var connection = runtime.getConnection()) {
            beginRuntime(connection);
            Instant captured = now(connection);
            var document = new MonitoringEnrollment.Document(id, scope.tenant(), scope.actor(), captured, "a".repeat(64),
                    CandidateSchemaChainPostgresTest.sha256(configuration.getBytes(StandardCharsets.UTF_8)), configuration,
                    List.of(new MonitoringEnrollment.AssetSnapshot(scope.asset(), 1, "192.0.2.10")));
            execute(connection, "INSERT INTO public.mop_monitoring_enrollment"
                    + "(tenant_id,id,created_by,permission_version,document,created_at) VALUES(?::uuid,?::uuid,?::uuid,1,?::jsonb,?)",
                    scope.tenant(), id, scope.actor(), JSON.writeValueAsString(document), captured);
            execute(connection, "INSERT INTO public.mop_monitoring_enrollment_revision"
                    + "(tenant_id,id,version,action,phase,state,actor_id,permission_version,recorded_at) "
                    + "VALUES(?::uuid,?::uuid,1,'CREATE','REQUESTED',?::jsonb,?::uuid,1,?)",
                    scope.tenant(), id, JSON.writeValueAsString(MonitoringEnrollment.create(document)), scope.actor(), captured);
            var result = lockAndImmutable(connection, "mop_monitoring_enrollment", "id", "id", scope, id, "", "P0001");
            requireUpdateColumns(connection, "mop_monitoring_enrollment_revision", Set.of());
            assertEquals(1, count(connection, "mop_monitoring_enrollment_revision", "id", scope, id, ""));
            result.put("first_revision_inserted", true);
            result.put("revision_count", 1);
            result.put("targets_enabled", false);
            connection.commit();
            evidence.add(result);
        }
    }

    private static void mapping(DataSource runtime, Scope scope, List<Map<String, Object>> evidence) throws Exception {
        UUID id = UUID.randomUUID();
        var key = new MonitoringObjectMapping.Key(UUID.randomUUID(), MonitoringObjectMapping.ObjectType.HOST,
                "SYNTHETIC_SCHEMA_" + id);
        try (var connection = runtime.getConnection()) {
            beginRuntime(connection);
            Instant captured = now(connection);
            var document = new MonitoringObjectBinding.Document(id, scope.tenant(), key, "SYNTHETIC", scope.actor(), captured);
            execute(connection, "INSERT INTO public.mop_monitoring_object_binding"
                    + "(tenant_id,id,pool_id,object_type,external_id,provider,document,created_by,permission_version,created_at) "
                    + "VALUES(?::uuid,?::uuid,?::uuid,'HOST',?,'SYNTHETIC',?::jsonb,?::uuid,1,?)",
                    scope.tenant(), id, key.poolId(), key.externalId(), JSON.writeValueAsString(document), scope.actor(), captured);
            execute(connection, "INSERT INTO public.mop_monitoring_object_binding_revision"
                    + "(tenant_id,id,version,action,state,actor_id,permission_version,recorded_at) "
                    + "VALUES(?::uuid,?::uuid,1,'CREATE',?::jsonb,?::uuid,1,?)", scope.tenant(), id,
                    JSON.writeValueAsString(MonitoringObjectBinding.create(document, scope.asset(), 1, null)), scope.actor(), captured);
            var result = lockAndImmutable(connection, "mop_monitoring_object_binding", "id", "id", scope, id, "", "P0001");
            requireUpdateColumns(connection, "mop_monitoring_object_binding_revision", Set.of());
            assertEquals(1, count(connection, "mop_monitoring_object_binding_revision", "id", scope, id, ""));
            result.put("first_revision_inserted", true);
            result.put("revision_count", 1);
            result.put("mapping_activated", false);
            connection.commit();
            evidence.add(result);
        }
    }

    private static Map<String, Object> lockAndImmutable(Connection connection, String table, String key, String updateColumn,
                                                       Scope scope, UUID id, String extra, String state) throws Exception {
        requireUpdateColumns(connection, table, Set.of(updateColumn));
        String before = rowHash(connection, table, key, scope, id, extra);
        lockRow(connection, table, key, scope, id, extra, "SHARE");
        lockRow(connection, table, key, scope, id, extra, "UPDATE");
        rejectedUpdate(connection, table, key, updateColumn, scope, id, extra, state);
        assertEquals(before, rowHash(connection, table, key, scope, id, extra));
        return new LinkedHashMap<>(Map.of("table", table, "nonempty_share_lock", true, "nonempty_update_lock", true,
                "root_column_update_only", updateColumn, "table_update_granted", false,
                "immutable_update_sql_state", state, "row_unchanged_after_rejected_update", true));
    }

    private static void lockRow(Connection connection, String table, String key, Scope scope, UUID id,
                                String extra, String lock) throws Exception {
        assertFalse(connection.getAutoCommit());
        assertFalse(connection.isReadOnly());
        try (var statement = connection.prepareStatement("SELECT " + key + " FROM public." + table
                + " WHERE tenant_id=?::uuid AND " + key + "=?::uuid" + extra + " FOR " + lock)) {
            statement.setQueryTimeout(10);
            bind(statement, scope.tenant(), id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next(), "Row-lock check must lock an actual fixture row");
                assertEquals(id.toString(), rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    private static void rejectedUpdate(Connection connection, String table, String key, String column, Scope scope,
                                       UUID id, String extra, String expectedState) throws Exception {
        var savepoint = connection.setSavepoint();
        try (var statement = connection.prepareStatement("UPDATE public." + table + " SET " + column + "=" + column
                + " WHERE tenant_id=?::uuid AND " + key + "=?::uuid" + extra)) {
            statement.setQueryTimeout(10);
            bind(statement, scope.tenant(), id);
            SQLException rejected = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals(expectedState, rejected.getSQLState(), "Reject with the intended ACL or immutable-trigger boundary");
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static void requireUpdateColumns(Connection connection, String table, Set<String> allowed) throws Exception {
        try (var statement = connection.prepareStatement("SELECT has_table_privilege(current_user,?,'UPDATE'),"
                + "attname,has_column_privilege(current_user,attrelid,attnum,'UPDATE') FROM pg_attribute "
                + "WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped ORDER BY attnum")) {
            statement.setString(1, "public." + table);
            statement.setString(2, "public." + table);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    assertFalse(rows.getBoolean(1));
                    assertEquals(allowed.contains(rows.getString(2)), rows.getBoolean(3), "Unexpected mutable candidate column");
                }
                assertTrue(count > 0);
            }
        }
    }

    private static long count(Connection connection, String table, String key, Scope scope, UUID id, String extra) throws Exception {
        try (var statement = connection.prepareStatement("SELECT count(*) FROM public." + table
                + " WHERE tenant_id=?::uuid AND " + key + "=?::uuid" + extra)) {
            bind(statement, scope.tenant(), id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static String rowHash(Connection connection, String table, String key, Scope scope, UUID id, String extra) throws Exception {
        try (var statement = connection.prepareStatement("SELECT encode(sha256(convert_to(row_to_json(t)::text,'UTF8')),'hex') "
                + "FROM public." + table + " t WHERE tenant_id=?::uuid AND " + key + "=?::uuid" + extra)) {
            bind(statement, scope.tenant(), id);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                String digest = rows.getString(1);
                assertFalse(rows.next());
                return digest;
            }
        }
    }

    private static void beginRuntime(Connection connection) throws Exception {
        connection.setReadOnly(false);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_user='mop_test_runtime' AND session_user='mop_test_runtime'")) {
            assertTrue(rows.next());
            assertTrue(rows.getBoolean(1));
        }
    }

    private static Instant now(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT date_trunc('milliseconds',clock_timestamp())")) {
            assertTrue(rows.next());
            return rows.getObject(1, OffsetDateTime.class).toInstant();
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            bind(statement, values);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void bind(java.sql.PreparedStatement statement, Object... values) throws Exception {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof UUID id) {
                statement.setString(index + 1, id.toString());
            } else if (value instanceof Instant time) {
                statement.setObject(index + 1, time.atOffset(ZoneOffset.UTC));
            } else if (value instanceof byte[] bytes) {
                statement.setBytes(index + 1, bytes);
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }
}
