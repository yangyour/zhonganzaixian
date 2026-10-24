package cn.mop.persistence;

import static org.junit.jupiter.api.Assertions.*;

import cn.mop.adapters.reports.QuarterlyReportPdfPreview;
import cn.mop.adapters.reports.ReportPublicationRenderer;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.InspectionQuarterlyReport;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository;
import cn.mop.domain.inspection.InspectionSourceSnapshot;
import cn.mop.domain.inspection.InspectionTemplate;
import cn.mop.domain.inspection.InspectionTemplateRepository;
import cn.mop.domain.inspection.InspectionWorksheet;
import cn.mop.domain.inspection.InspectionWorksheetRepository;
import cn.mop.domain.reporting.ReportPublication;
import cn.mop.domain.reporting.ReportPublicationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real retained-V53 transactions and actual FOP output; layer B is explicitly not capture completeness acceptance. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_REPORT_PUBLICATION_BUSINESS_PG_TESTS", matches = "true")
class PostgresReportPublicationBusinessIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String PREFIX = "MOP_REPORT_PUBLICATION_TEST_";
    private static final String HISTORY_HASH_SQL =
        "SELECT encode(sha256(convert_to(" +
        "COALESCE(jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text,'[]'),'UTF8')),'hex') " +
        "FROM public.flyway_schema_history h";
    /** Every identity and tenant is newly generated; no legacy or actual customer subject is reused. */
    private final UUID tenant = UUID.randomUUID();
    private final UUID foreignTenant = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();
    private final UUID publisher = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID excluded = UUID.randomUUID();
    private final UUID foreignActor = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private final AtomicInteger renderCalls = new AtomicInteger();
    /** Restricted runtime pool exercises the actual publication and source repositories. */
    private HikariDataSource runtime;
    /** Existing migrator identity seeds only this run's new namespace and performs read-only schema checks. */
    private HikariDataSource migrator;
    private PostgresPlatformRepository platform;
    private PostgresInspectionQuarterlyReportRepository quarters;
    private PostgresInspectionWorksheetRepository worksheets;
    private Settings settings;

    /** Only supplied environment references are read; no credentials are included in evidence or toString. */
    private record Settings(
        String url,
        String database,
        String marker,
        String runtimePassword,
        String migratorPassword,
        String historyHash,
        int checksum,
        Path evidencePath,
        boolean pdfRequired,
        String font,
        String fontHash
    ) {
        static Settings load() throws Exception {
            String database = required("DATABASE");
            assertTrue(database.matches("mop_report_test_[a-f0-9]{12}"));
            String url = required("JDBC_URL");
            assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/" + database));
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            assertTrue(port > 0 && port <= 65535);
            String marker = required("OWNER_MARKER");
            assertTrue(marker.matches("MOP_CANDIDATE_CHAIN:[a-f0-9]{32}:R02:[a-f0-9]{32}"));
            String history = required("HISTORY_SHA256");
            assertTrue(history.matches("[a-f0-9]{64}"));
            Path root = Path.of(required("REPOSITORY_ROOT")).toRealPath();
            assertTrue(Files.isRegularFile(root.resolve("pom.xml")));
            Path local = root.resolve(".local");
            assertEquals(local, local.toRealPath());
            Path output = Path.of(required("EVIDENCE_PATH")).toAbsolutePath().normalize();
            assertTrue(output.startsWith(local));
            assertEquals("r02-business-evidence.json", output.getFileName().toString());
            assertTrue(output.getParent().toRealPath().startsWith(local));
            assertFalse(Files.exists(output));
            String mode = required("PDF_MODE");
            assertTrue(List.of("REQUIRED", "HTML_ONLY").contains(mode));
            String font = System.getenv(PREFIX + "FONT_PATH");
            String fontHash = System.getenv(PREFIX + "FONT_SHA256");
            if (mode.equals("REQUIRED")) {
                assertNotNull(font);
                assertNotNull(fontHash);
                assertTrue(fontHash.matches("[a-f0-9]{64}"));
                Path path = Path.of(font);
                assertTrue(path.isAbsolute() && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
                assertTrue(Files.size(path) <= QuarterlyReportPdfPreview.MAX_FONT_BYTES);
                assertEquals(fontHash, ReportPublicationRenderer.sha(Files.readAllBytes(path)));
            }
            return new Settings(
                url,
                database,
                marker,
                required("RUNTIME_PASSWORD"),
                required("MIGRATOR_PASSWORD"),
                history,
                Integer.parseInt(required("V53_CHECKSUM")),
                output,
                mode.equals("REQUIRED"),
                font,
                fontHash
            );
        }

        @Override
        public String toString() {
            return "ReportPublicationBusinessSettings[credentials=REDACTED]";
        }
    }

    /** A: real source capture remains gated; B: separately seeded isolated workflow exercises durable publication. */
    @Test
    void verifiesCapturedSourceAndSeparatelyLabelledIsolatedWorkflow() throws Exception {
        settings = Settings.load();
        evidence.put("status", "RUNNING");
        evidence.put("database", settings.database());
        evidence.put("owner_marker", settings.marker());
        evidence.put("schema_mode", "PREAPPLIED_V53");
        evidence.put("schema_migrations", 0);
        evidence.put("external_dispatch_enabled", false);
        evidence.put("customer_template_confirmation", null);
        evidence.put("formal_fee_configuration", null);
        evidence.put("formal_contract_acceptance", "NOT_RUN");
        evidence.put("test_tenants", List.of(tenant.toString(), foreignTenant.toString()));
        evidence.put(
            "test_actors",
            List.of(creator, reviewer, publisher, recipient, excluded, foreignActor)
                .stream()
                .map(UUID::toString)
                .toList()
        );
        evidence.put("identity_source", "r02_business_validation");
        evidence.put(
            "test_actor_memberships",
            List.of(
                plannedMember(creator, tenant, "padmin"),
                plannedMember(reviewer, tenant, "padmin"),
                plannedMember(publisher, tenant, "padmin"),
                plannedMember(recipient, tenant, "viewer"),
                plannedMember(excluded, tenant, "viewer"),
                plannedMember(foreignActor, foreignTenant, "padmin")
            )
        );
        evidence.put("seed_committed", false);
        evidence.put("pools_closed", false);
        evidence.put("identity_cleanup_owner", "ROOT_RUNNER_AFTER_JVM_EXIT_AND_ZERO_CONNECTIONS");
        evidence.put("pdf_mode", settings.pdfRequired() ? "REQUIRED" : "HTML_ONLY");
        evidence.put("checks", checks);
        // Persist the exact scope before connecting or inserting any identity. A later interrupted run is recoverable.
        checkpoint();
        Throwable failure = null;
        try {
            migrator = pool("mop_test_migrator", settings.migratorPassword());
            runtime = pool("mop_test_runtime", settings.runtimePassword());
            evidence.put("history_before", verifySchema());
            seedOwnNamespace();
            evidence.put("seed_committed", true);
            checkpoint();
            platform = new PostgresPlatformRepository(runtime, JSON);
            quarters = new PostgresInspectionQuarterlyReportRepository(runtime, platform);
            worksheets = new PostgresInspectionWorksheetRepository(runtime, platform);
            var source = captureSource();
            var unavailableRepository = repository(false, policy("SYNTHETIC_R02_POLICY_V1"));
            String prepareKey = UUID.randomUUID().toString();
            var initial = prepare(unavailableRepository, source, prepareKey);
            byte[] frozen = unavailableRepository
                .get(access(creator, tenant), initial.document().id().toString())
                .payload();
            assertEquals(source.documentSha256(), JSON.readTree(frozen).path("source_document_sha256").asText());
            assertTrue(initial.document().incompleteReasons().contains("SELF_REPORTED_SOURCES_UNVERIFIED"));
            assertTrue(initial.document().incompleteReasons().contains("TEMPLATE_APPROVAL_UNVERIFIED"));
            assertEquals(ReportPublication.Scope.CUSTOMER_DELIVERY, initial.document().scope());
            assertEquals(normalized(initial), normalized(prepare(unavailableRepository, source, prepareKey)));
            check("A_REAL_CAPTURE_AND_PREPARE", "PASSED", "Source reasons retained; not approved or published");

            String id = initial.document().id().toString();
            String renderKey = UUID.randomUUID().toString();
            var failed = command(unavailableRepository, creator, id, initial.version(), "RENDER", null, renderKey);
            assertEquals(ReportPublication.Phase.FAILED, failed.phase());
            assertEquals("FONT_UNAVAILABLE", failed.reason());
            byte[] failureHtml = unavailableRepository
                .content(access(creator, tenant), id, ReportPublication.Format.HTML)
                .bytes();
            save("synthetic-render-failure.html", failureHtml);
            assertTrue(new String(failureHtml, StandardCharsets.UTF_8).contains("&lt;script&gt;"));
            var counts = counts();
            int calls = renderCalls.get();
            assertEquals(
                normalized(failed),
                normalized(command(unavailableRepository, creator, id, initial.version(), "RENDER", null, renderKey))
            );
            assertEquals(calls, renderCalls.get());
            assertEquals(counts, counts());
            assertDomain(409, "IDEMPOTENCY_CONFLICT", () ->
                command(unavailableRepository, creator, id, failed.version(), "RENDER", null, renderKey)
            );
            assertDomain(409, "VERSION_CONFLICT", () ->
                command(
                    unavailableRepository,
                    creator,
                    id,
                    initial.version(),
                    "RENDER",
                    null,
                    UUID.randomUUID().toString()
                )
            );
            assertEquals(counts, counts());
            check("A_HTML_FAILURE_AND_IDEMPOTENT_REPLAY", "PASSED", "Real HTML stored; no replacement PDF");

            var repository = repository(settings.pdfRequired(), policy("SYNTHETIC_R02_POLICY_V1"));
            var rendered = command(
                repository,
                creator,
                id,
                failed.version(),
                "RENDER",
                null,
                UUID.randomUUID().toString()
            );
            assertArrayEquals(frozen, repository.get(access(creator, tenant), id).payload());
            assertArrayEquals(
                failureHtml,
                repository.content(access(creator, tenant), id, ReportPublication.Format.HTML).bytes()
            );
            assertDomain(404, "OBJECT_NOT_FOUND", () ->
                repository(false, policy("SYNTHETIC_R02_POLICY_V1")).get(access(recipient, tenant), id)
            );
            assertDomain(404, "OBJECT_NOT_FOUND", () ->
                repository(false, policy("SYNTHETIC_R02_POLICY_V1")).get(access(foreignActor, foreignTenant), id)
            );
            if (settings.pdfRequired()) {
                assertEquals(ReportPublication.Phase.REVIEW_REQUIRED, rendered.phase());
                saveOutputs(repository, rendered, "synthetic-captured-quarter");
                var approvedSourceRepository = repository;
                assertDomain(422, "REPORT_INCOMPLETE", () ->
                    command(
                        approvedSourceRepository,
                        reviewer,
                        id,
                        rendered.version(),
                        "APPROVE",
                        null,
                        UUID.randomUUID().toString()
                    )
                );
                assertDomain(409, "REPORT_STATE_CONFLICT", () ->
                    command(
                        approvedSourceRepository,
                        publisher,
                        id,
                        rendered.version(),
                        "PUBLISH",
                        null,
                        UUID.randomUUID().toString()
                    )
                );
                var returned = command(
                    repository,
                    reviewer,
                    id,
                    rendered.version(),
                    "RETURN",
                    "SYNTHETIC: source verification still missing",
                    UUID.randomUUID().toString()
                );
                assertEquals(ReportPublication.Phase.RETURNED, returned.phase());
                assertDomain(409, "REPORT_STATE_CONFLICT", () ->
                    command(
                        approvedSourceRepository,
                        creator,
                        id,
                        returned.version(),
                        "RENDER",
                        null,
                        UUID.randomUUID().toString()
                    )
                );
                check(
                    "A_REAL_PDF_RETURN_AND_APPROVAL_GATE",
                    "PASSED",
                    "Returned output remains frozen and source approval remains blocked"
                );
                revisionWorkflow(repository, source, returned, frozen);
                isolatedWorkflow(repository, source, frozen);
            } else {
                assertEquals(ReportPublication.Phase.FAILED, rendered.phase());
                assertNull(rendered.pdf());
                save(
                    "synthetic-captured-quarter.html",
                    repository.content(access(creator, tenant), id, ReportPublication.Format.HTML).bytes()
                );
                check("A_RENDER_RETRY", "PASSED", "HTML_ONLY mode remains explicit FONT_UNAVAILABLE");
                check("PDF_RENDERING", "PDF_NOT_RUN", "No font/PDF substitute accepted");
                check("B_ISOLATED_PUBLICATION_WORKFLOW", "NOT_RUN", "Requires actual dual HTML/PDF output");
            }
            evidence.put("captured_source_publication", "BLOCKED_BY_UNVERIFIED_SOURCE_AND_TEMPLATE");
            evidence.put("status", "PASSED_WITH_EXPLICIT_SCOPE_LIMITS");
        } catch (Throwable problem) {
            failure = problem;
            evidence.put("status", "FAILED");
            evidence.put("failure_type", problem.getClass().getSimpleName());
            if (problem instanceof DomainException domain) {
                evidence.put("failure_code", domain.code);
                evidence.put("failure_status", domain.status);
            }
            for (var frame : problem.getStackTrace()) {
                if (frame.getClassName().equals(getClass().getName())) {
                    evidence.put("failure_test_method", frame.getMethodName());
                    evidence.put("failure_test_line", frame.getLineNumber());
                    break;
                }
            }
        } finally {
            try {
                if (migrator != null) {
                    evidence.put("history_after", verifySchema());
                    assertEquals(evidence.get("history_before"), evidence.get("history_after"));
                }
            } catch (Throwable problem) {
                failure = problem;
                evidence.put("status", "FAILED");
                evidence.put("history_failure_type", problem.getClass().getSimpleName());
            }
            boolean closeSucceeded = true;
            try {
                if (runtime != null) {
                    runtime.close();
                }
            } catch (Throwable problem) {
                closeSucceeded = false;
                failure = problem;
                evidence.put("status", "FAILED");
                evidence.put("runtime_close_failure_type", problem.getClass().getSimpleName());
            }
            try {
                if (migrator != null) {
                    migrator.close();
                }
            } catch (Throwable problem) {
                closeSucceeded = false;
                failure = problem;
                evidence.put("status", "FAILED");
                evidence.put("migrator_close_failure_type", problem.getClass().getSimpleName());
            }
            evidence.put(
                "pools_closed",
                closeSucceeded && (runtime == null || runtime.isClosed()) && (migrator == null || migrator.isClosed())
            );
            evidence.put("database_retained", true);
            try {
                checkpoint();
            } catch (Throwable problem) {
                failure = problem;
                // The earlier fsynced manifest still identifies the exact run; do not expose driver/private context.
            }
        }
        if (failure != null) {
            throw new AssertionError(
                "SYNTHETIC R02 validation failed (" + failure.getClass().getSimpleName() + "); inspect scoped evidence"
            );
        }
    }

    /** Positive phases are isolated state-machine fixtures, never a replacement for real source approval. */
    private void isolatedWorkflow(
        PostgresReportPublicationRepository repository,
        InspectionQuarterlyReportRepository.Summary source,
        byte[] frozen
    ) throws Exception {
        var draft = isolatedDraft(source, frozen);
        String id = draft.document().id().toString();
        var rendered = command(repository, creator, id, 1, "RENDER", null, UUID.randomUUID().toString());
        assertEquals(ReportPublication.Phase.REVIEW_REQUIRED, rendered.phase());
        assertDomain(403, "ACTION_FORBIDDEN", () ->
            command(repository, creator, id, rendered.version(), "APPROVE", null, UUID.randomUUID().toString())
        );
        var approved = command(
            repository,
            reviewer,
            id,
            rendered.version(),
            "APPROVE",
            null,
            UUID.randomUUID().toString()
        );
        var changedPolicy = repository(true, policy("SYNTHETIC_R02_POLICY_V2"));
        assertDomain(409, "REPORT_POLICY_CHANGED", () ->
            command(changedPolicy, publisher, id, approved.version(), "PUBLISH", null, UUID.randomUUID().toString())
        );
        setMemberActive(recipient, false);
        var before = counts();
        assertDomain(404, "OBJECT_NOT_FOUND", () ->
            command(repository, publisher, id, approved.version(), "PUBLISH", null, UUID.randomUUID().toString())
        );
        assertEquals(before, counts());
        assertEquals(ReportPublication.Phase.APPROVED, repository.get(access(creator, tenant), id).state().phase());
        setMemberActive(recipient, true);
        String publishKey = UUID.randomUUID().toString();
        var published = command(repository, publisher, id, approved.version(), "PUBLISH", null, publishKey);
        assertEquals(ReportPublication.Phase.PUBLISHED, published.phase());
        assertEquals(List.of(recipient), published.recipients());
        before = counts();
        assertEquals(
            normalized(published),
            normalized(command(repository, publisher, id, approved.version(), "PUBLISH", null, publishKey))
        );
        assertEquals(before, counts());
        assertFalse(published.customerAcceptanceConfirmed());
        assertEquals(published, repository.get(access(recipient, tenant), id).state());
        assertTrue(
            repository
                .list(access(recipient, tenant), 21, null, null)
                .stream()
                .anyMatch(row -> row.state().document().id().toString().equals(id))
        );
        assertTrue(
            repository
                .list(access(excluded, tenant), 21, null, null)
                .stream()
                .noneMatch(row -> row.state().document().id().toString().equals(id))
        );
        assertDomain(404, "OBJECT_NOT_FOUND", () -> repository.get(access(excluded, tenant), id));
        assertDomain(404, "OBJECT_NOT_FOUND", () -> repository.get(access(foreignActor, foreignTenant), id));
        String readKey = UUID.randomUUID().toString();
        var first = read(repository, id, published.version(), readKey);
        assertEquals(normalized(first), normalized(read(repository, id, published.version(), readKey)));
        assertEquals(first, read(repository, id, published.version(), UUID.randomUUID().toString()));
        assertEquals(
            1,
            scalar(
                "SELECT count(*) FROM public.mop_report_publication_read WHERE tenant_id=? AND document_id=?",
                tenant,
                UUID.fromString(id)
            )
        );
        assertEquals(first.documentSha256(), published.document().documentSha256());
        saveOutputs(repository, published, "synthetic-isolated-publication");
        var content = repository.content(access(recipient, tenant), id, ReportPublication.Format.PDF);
        assertEquals(published.pdf().contentSha256(), content.sha256());
        String resendKey = UUID.randomUUID().toString();
        command(repository, publisher, id, published.version(), "RESEND", null, resendKey);
        command(repository, publisher, id, published.version(), "RESEND", null, resendKey);
        assertEquals(
            2,
            scalar(
                "SELECT count(*) FROM public.mop_report_publication_delivery_intent " +
                    "WHERE tenant_id=? AND document_id=? AND NOT external_dispatch_enabled",
                tenant,
                UUID.fromString(id)
            )
        );
        assertEquals(
            0,
            scalar(
                "SELECT count(*) FROM public.mop_report_publication_delivery_intent WHERE tenant_id=? AND external_dispatch_enabled",
                tenant
            )
        );
        assertArrayEquals(
            content.bytes(),
            repository.content(access(recipient, tenant), id, ReportPublication.Format.PDF).bytes()
        );
        var oldAccess = access(publisher, tenant);
        setMemberActive(publisher, false);
        before = counts();
        assertDomain(401, "SESSION_EXPIRED", () ->
            protectedWrite(repository, oldAccess, id, published.version(), "RESEND", null, resendKey)
        );
        assertEquals(before, counts());
        check(
            "B_ISOLATED_PUBLICATION_WORKFLOW",
            "PASSED",
            "Synthetic ISOLATED_TEST initial state; not capture completeness or customer approval"
        );
        evidence.put("isolated_publication_id", id);
    }

    private HikariDataSource pool(String role, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(settings.url());
        config.setUsername(role);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setPoolName("synthetic-r02-" + role);
        return new HikariDataSource(config);
    }

    /** Current/session role and exact retained database marker are checked before any fixture write. */
    private String verifySchema() throws Exception {
        ReportPublicationSchemaVerifier.resource();
        try (var connection = migrator.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL timezone='UTC'");
            }
            try (
                var query = connection.prepareStatement(
                    "SELECT current_database(),current_user,session_user," +
                        "pg_get_userbyid(datdba),shobj_description(oid,'pg_database') " +
                        "FROM pg_database WHERE datname=current_database()"
                );
                var row = query.executeQuery()
            ) {
                assertTrue(row.next());
                assertEquals(settings.database(), row.getString(1));
                assertEquals("mop_test_migrator", row.getString(2));
                assertEquals("mop_test_migrator", row.getString(3));
                assertEquals("mop_test_migrator", row.getString(4));
                assertEquals(settings.marker(), row.getString(5));
            }
            int count = 0;
            try (
                var query = connection.prepareStatement(
                    "SELECT version,checksum,success " + "FROM public.flyway_schema_history ORDER BY installed_rank"
                );
                var rows = query.executeQuery()
            ) {
                while (rows.next()) {
                    assertEquals(Integer.toString(++count), rows.getString(1));
                    assertTrue(rows.getBoolean(3));
                    if (count == 53) {
                        assertEquals(settings.checksum(), rows.getInt(2));
                    }
                }
            }
            assertEquals(53, count);
            ReportPublicationSchemaVerifier.verify(connection);
            try (var query = connection.prepareStatement(HISTORY_HASH_SQL); var row = query.executeQuery()) {
                assertTrue(row.next());
                String hash = row.getString(1);
                assertEquals(settings.historyHash(), hash);
                connection.commit();
                return hash;
            }
        }
    }

    /** The retained schema's original records remain untouched; only new non-login principals are added. */
    private void seedOwnNamespace() throws Exception {
        assertFalse(tenant.toString().startsWith("55000000-"));
        assertFalse(foreignTenant.toString().startsWith("55000000-"));
        try (var connection = migrator.getConnection()) {
            connection.setAutoCommit(false);
            for (UUID id : List.of(creator, reviewer, publisher, recipient, excluded, foreignActor)) {
                String role = List.of(recipient, excluded).contains(id) ? "viewer" : "padmin";
                execute(
                    connection,
                    "INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES(?,'r02_business_validation',?,?,?)",
                    id,
                    "SYNTHETIC_R02_" + id,
                    "SYNTHETIC_R02_" + id,
                    role
                );
            }
            for (UUID id : List.of(tenant, foreignTenant)) {
                execute(connection, "INSERT INTO public.mop_tenant(id,name) VALUES(?,?)", id, "SYNTHETIC_R02_" + id);
            }
            for (UUID id : List.of(creator, reviewer, publisher, recipient, excluded)) {
                execute(
                    connection,
                    "INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(?,?,?)",
                    tenant,
                    id,
                    List.of(recipient, excluded).contains(id) ? "viewer" : "padmin"
                );
            }
            execute(
                connection,
                "INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(?,?,'padmin')",
                foreignTenant,
                foreignActor
            );
            execute(
                connection,
                "INSERT INTO public.mop_site(tenant_id,id,name) VALUES(?,'r02-business','SYNTHETIC_R02_SITE')",
                tenant
            );
            execute(
                connection,
                "INSERT INTO public.mop_asset" +
                    "(id,tenant_id,name,type,management_ip,site_id,source,created_by,updated_by) " +
                    "VALUES(?,?,'SYNTHETIC_R02_中文设备','other','192.0.2.53','r02-business','manual',?,?)",
                asset,
                tenant,
                creator,
                creator
            );
            connection.commit();
        }
        try (
            var connection = runtime.getConnection();
            var query = connection.prepareStatement("SELECT current_database(),current_user,session_user");
            var row = query.executeQuery()
        ) {
            assertTrue(row.next());
            assertEquals(settings.database(), row.getString(1));
            assertEquals("mop_test_runtime", row.getString(2));
            assertEquals("mop_test_runtime", row.getString(3));
        }
    }

    private InspectionQuarterlyReportRepository.Summary captureSource() throws Exception {
        return captureSource(2026, 4);
    }

    private InspectionQuarterlyReportRepository.Summary captureSource(int year, int quarter) throws Exception {
        var access = access(creator, tenant);
        var templates = new PostgresInspectionTemplateRepository(runtime, platform);
        var templateAccess = new InspectionTemplateRepository.Access(
            access.actorId(),
            access.tenantId(),
            access.permissionVersion(),
            access.traceId()
        );
        var definition = new InspectionTemplate(
            "r02_" + UUID.randomUUID().toString().replace("-", ""),
            "SYNTHETIC_R02_TEMPLATE",
            List.of(new InspectionTemplate.Item("backup", "SYNTHETIC 配置备份", "SYNTHETIC 手工来源", null))
        );
        var template = (InspectionTemplateRepository.Entry) platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/template",
            UUID.randomUUID().toString(),
            definition.fingerprint(),
            access.permissionVersion(),
            () -> templates.authorizeWrite(templateAccess, null),
            () -> templates.create(templateAccess, 0, definition)
        );
        var worksheetAccess = new InspectionWorksheetRepository.Access(
            access.actorId(),
            access.tenantId(),
            access.permissionVersion(),
            access.traceId()
        );
        var content = new InspectionWorksheetRepository.Content(
            Map.of(
                "backup",
                new InspectionWorksheet.Observation(
                    InspectionWorksheet.Result.ATTENTION,
                    "SYNTHETIC 中文来源 <script>not executable</script>",
                    List.of()
                )
            ),
            java.time.LocalDate.of(year, (quarter - 1) * 3 + 1, 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        );
        var worksheet = (InspectionWorksheetRepository.Entry) platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/worksheet",
            UUID.randomUUID().toString(),
            "a".repeat(64),
            access.permissionVersion(),
            () -> worksheets.authorizeWrite(worksheetAccess, null),
            () ->
                worksheets.create(
                    worksheetAccess,
                    0,
                    new InspectionWorksheetRepository.Creation(
                        asset.toString(),
                        template.id(),
                        template.version(),
                        content
                    )
                )
        );
        var quarterAccess = new InspectionQuarterlyReportRepository.Access(
            access.actorId(),
            access.tenantId(),
            access.permissionVersion(),
            access.traceId()
        );
        var input = new InspectionQuarterlyReportRepository.CaptureCreation(
            "SYNTHETIC_R02_CAPTURE",
            List.of(
                new InspectionSourceCollectionRepository.Source(
                    worksheet.id(),
                    worksheet.version(),
                    InspectionSourceSnapshot.fromEntry(worksheet).fingerprint()
                )
            ),
            new InspectionQuarterlyReport.Period(year, quarter),
            new InspectionQuarterlyReport.Configuration(
                "SYNTHETIC_R02_CONFIG_V1",
                "UTC",
                InspectionQuarterlyReport.DuplicateRule.REQUIRE_SINGLE_SOURCE,
                InspectionQuarterlyReport.ArchivedRule.INCLUDE,
                "SYNTHETIC_R02_METRIC_V1",
                "SYNTHETIC_R02_UNAPPROVED_TEMPLATE"
            )
        );
        var captured = (InspectionQuarterlyReportRepository.Summary) platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/capture",
            UUID.randomUUID().toString(),
            "b".repeat(64),
            access.permissionVersion(),
            () -> quarters.authorizeCapture(quarterAccess),
            () -> quarters.capture(quarterAccess, 0, input)
        );
        var frozen = quarters.get(quarterAccess, captured.id());
        var changed = new InspectionWorksheetRepository.Content(
            Map.of(
                "backup",
                new InspectionWorksheet.Observation(
                    InspectionWorksheet.Result.REQUIRES_ACTION,
                    "SYNTHETIC later revision must not alter frozen report",
                    List.of()
                )
            ),
            content.reportedPerformedAt()
        );
        platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "PATCH /integration/r02/worksheet",
            UUID.randomUUID().toString(),
            "c".repeat(64),
            access.permissionVersion(),
            () -> worksheets.authorizeWrite(worksheetAccess, worksheet.id()),
            () -> worksheets.update(worksheetAccess, worksheet.id(), worksheet.version(), changed)
        );
        assertEquals(frozen, quarters.get(quarterAccess, captured.id()));
        evidence.putIfAbsent("captured_source_id", captured.id());
        evidence.putIfAbsent("captured_source_sha256", captured.documentSha256());
        return captured;
    }

    /** Real row locks arbitrate two different commands; no database or provider substitute is involved. */
    private void revisionWorkflow(
        PostgresReportPublicationRepository repository,
        InspectionQuarterlyReportRepository.Summary originalSource,
        ReportPublication.State parent,
        byte[] originalPayload
    ) throws Exception {
        String parentId = parent.document().id().toString();
        var replacementSource = captureSource();
        var wrongPeriod = captureSource(2026, 3);
        var before = counts();
        var absentPolicy = new ReportPublication.Policy(
            "SYNTHETIC_NO_REVISION_POLICY",
            List.of(reviewer),
            List.of(publisher),
            List.of(recipient),
            false,
            null,
            null,
            null,
            null
        );
        assertDomain(422, "CONFIG_PENDING", () ->
            revise(
                repository(true, absentPolicy),
                creator,
                tenant,
                parentId,
                parent.version(),
                replacementSource,
                "SYNTHETIC correction",
                UUID.randomUUID().toString()
            )
        );
        assertDomain(409, "VERSION_CONFLICT", () ->
            revise(
                repository,
                creator,
                tenant,
                parentId,
                parent.version() - 1,
                replacementSource,
                "SYNTHETIC correction",
                UUID.randomUUID().toString()
            )
        );
        assertDomain(422, "REPORT_REVISION_PERIOD_MISMATCH", () ->
            revise(
                repository,
                creator,
                tenant,
                parentId,
                parent.version(),
                wrongPeriod,
                "SYNTHETIC wrong quarter",
                UUID.randomUUID().toString()
            )
        );
        assertDomain(404, "OBJECT_NOT_FOUND", () ->
            revise(
                repository,
                foreignActor,
                foreignTenant,
                parentId,
                parent.version(),
                replacementSource,
                "SYNTHETIC foreign scope",
                UUID.randomUUID().toString()
            )
        );
        assertEquals(before, counts());

        record Attempt(String key, ReportPublication.State state, String rejectedCode) {}
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<Attempt> outcomes = new ArrayList<>();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Attempt>>();
            for (int attempt = 0; attempt < 2; attempt++) {
                String key = UUID.randomUUID().toString();
                futures.add(
                    executor.submit(() -> {
                        assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                        try {
                            return new Attempt(
                                key,
                                revise(
                                    repository,
                                    creator,
                                    tenant,
                                    parentId,
                                    parent.version(),
                                    replacementSource,
                                    "SYNTHETIC correction",
                                    key
                                ),
                                null
                            );
                        } catch (DomainException rejected) {
                            assertEquals(409, rejected.status);
                            return new Attempt(key, null, rejected.code);
                        }
                    })
                );
            }
            start.countDown();
            for (var future : futures) {
                outcomes.add(future.get(45, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(45, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(
            1,
            outcomes
                .stream()
                .filter(result -> result.state() != null)
                .count()
        );
        assertEquals(
            List.of("REPORT_REVISION_EXISTS"),
            outcomes
                .stream()
                .filter(result -> result.state() == null)
                .map(Attempt::rejectedCode)
                .toList()
        );
        var winner = outcomes
            .stream()
            .filter(result -> result.state() != null)
            .findFirst()
            .orElseThrow();
        var child = winner.state();
        assertEquals(1, child.version());
        assertEquals(2, child.document().revision());
        assertEquals(parent.document().id(), child.document().parentId());
        assertEquals(parent.document().familyId(), child.document().familyId());
        assertEquals(ReportPublication.Phase.DRAFT, child.phase());
        assertEquals(ReportPublication.Scope.CUSTOMER_DELIVERY, child.document().scope());
        assertTrue(child.document().incompleteReasons().contains("SELF_REPORTED_SOURCES_UNVERIFIED"));
        assertTrue(child.document().incompleteReasons().contains("TEMPLATE_APPROVAL_UNVERIFIED"));
        assertNotEquals(parent.document().documentSha256(), child.document().documentSha256());
        var after = counts();
        assertEquals(
            normalized(child),
            normalized(
                revise(
                    repository,
                    creator,
                    tenant,
                    parentId,
                    parent.version(),
                    replacementSource,
                    "SYNTHETIC correction",
                    winner.key()
                )
            )
        );
        assertDomain(409, "IDEMPOTENCY_CONFLICT", () ->
            revise(
                repository,
                creator,
                tenant,
                parentId,
                parent.version(),
                originalSource,
                "SYNTHETIC correction",
                winner.key()
            )
        );
        assertEquals(after, counts());
        assertEquals(normalized(parent), normalized(repository.get(access(creator, tenant), parentId).state()));
        assertArrayEquals(originalPayload, repository.get(access(creator, tenant), parentId).payload());
        assertDomain(404, "OBJECT_NOT_FOUND", () ->
            repository.get(access(recipient, tenant), child.document().id().toString())
        );

        String childId = child.document().id().toString();
        var rendered = command(
            repository,
            creator,
            childId,
            child.version(),
            "RENDER",
            null,
            UUID.randomUUID().toString()
        );
        assertEquals(ReportPublication.Phase.REVIEW_REQUIRED, rendered.phase());
        assertDomain(422, "REPORT_INCOMPLETE", () ->
            command(repository, reviewer, childId, rendered.version(), "APPROVE", null, UUID.randomUUID().toString())
        );
        var returned = command(
            repository,
            reviewer,
            childId,
            rendered.version(),
            "RETURN",
            "SYNTHETIC source still unverified",
            UUID.randomUUID().toString()
        );
        var third = revise(
            repository,
            creator,
            tenant,
            childId,
            returned.version(),
            replacementSource,
            "SYNTHETIC second correction",
            UUID.randomUUID().toString()
        );
        assertEquals(3, third.document().revision());
        assertEquals(child.document().id(), third.document().parentId());
        assertEquals(parent.document().familyId(), third.document().familyId());
        assertEquals(normalized(parent), normalized(repository.get(access(creator, tenant), parentId).state()));
        check(
            "A_REVISION_CAPTURE_CONCURRENCY_AND_RECOVERY",
            "PASSED",
            "One child per locked parent; original-command recovery, same-period, scope, source gates and immutable parent retained"
        );
        evidence.put("revision_documents", List.of(parentId, childId, third.document().id().toString()));
    }

    private ReportPublication.State revise(
        PostgresReportPublicationRepository repository,
        UUID actor,
        UUID scope,
        String parent,
        long version,
        InspectionQuarterlyReportRepository.Summary source,
        String reason,
        String key
    ) {
        var access = access(actor, scope);
        String body = version + "|" + source.id() + "|" + source.documentSha256() + "|" + reason;
        Object result = platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/" + parent + "/revisions",
            key,
            ReportPublicationRenderer.sha(body.getBytes(StandardCharsets.UTF_8)),
            access.permissionVersion(),
            () -> repository.authorize(access, parent, true),
            () -> repository.revise(access, parent, version, source.id(), source.documentSha256(), reason).state()
        );
        return JSON.convertValue(result, ReportPublication.State.class);
    }

    private ReportPublication.Policy policy(String reference) {
        return new ReportPublication.Policy(
            reference,
            List.of(creator, reviewer),
            List.of(publisher),
            List.of(recipient),
            false,
            ReportPublication.Regeneration.NEW_REVISION,
            null,
            null,
            null
        );
    }

    private PostgresReportPublicationRepository repository(boolean enablePdf, ReportPublication.Policy policy) {
        var pdf = new QuarterlyReportPdfPreview(
            new QuarterlyReportPdfPreview.Settings(enablePdf, settings.font(), settings.fontHash(), null, null)
        );
        var renderer = new ReportPublicationRenderer(pdf, JSON);
        return new PostgresReportPublicationRepository(
            platform,
            JSON,
            (document, payload, at) -> {
                renderCalls.incrementAndGet();
                var output = renderer.render(document, payload, at);
                return new ReportPublicationRepository.Rendered(
                    output.html(),
                    output.htmlBytes(),
                    output.pdf(),
                    output.pdfBytes(),
                    output.failure()
                );
            },
            scope ->
                scope.equals(tenant.toString())
                    ? policy
                    : new ReportPublication.Policy(null, null, null, null, null, null, null, null, null),
            ReportPublicationRenderer.TEMPLATE
        );
    }

    private ReportPublicationRepository.Access access(UUID actor, UUID scope) {
        long epoch = platform.membership(actor.toString(), scope.toString()).orElseThrow().permissionVersion();
        return new ReportPublicationRepository.Access(
            actor.toString(),
            scope.toString(),
            epoch,
            "synthetic-r02-" + UUID.randomUUID()
        );
    }

    private ReportPublication.State prepare(
        PostgresReportPublicationRepository repository,
        InspectionQuarterlyReportRepository.Summary source,
        String key
    ) {
        var access = access(creator, tenant);
        Object value = platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/prepare",
            key,
            source.documentSha256(),
            access.permissionVersion(),
            () -> repository.authorize(access, null, true),
            () -> repository.prepare(access, source.id(), source.documentSha256()).state()
        );
        return JSON.convertValue(value, ReportPublication.State.class);
    }

    private ReportPublication.State command(
        PostgresReportPublicationRepository repository,
        UUID actor,
        String id,
        long expected,
        String action,
        String reason,
        String key
    ) {
        return JSON.convertValue(
            protectedWrite(repository, access(actor, tenant), id, expected, action, reason, key),
            ReportPublication.State.class
        );
    }

    private Object protectedWrite(
        PostgresReportPublicationRepository repository,
        ReportPublicationRepository.Access access,
        String id,
        long expected,
        String action,
        String reason,
        String key
    ) {
        String body = action + "|" + expected + "|" + reason;
        return platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/" + id + "/actions",
            key,
            ReportPublicationRenderer.sha(body.getBytes(StandardCharsets.UTF_8)),
            access.permissionVersion(),
            () -> repository.authorize(access, id, true),
            () -> repository.command(access, id, expected, action, reason)
        );
    }

    private ReportPublication.ReadReceipt read(
        PostgresReportPublicationRepository repository,
        String id,
        long version,
        String key
    ) {
        var access = access(recipient, tenant);
        Object result = platform.idempotentAuthorized(
            access.actorId(),
            access.tenantId(),
            "POST /integration/r02/" + id + "/read",
            key,
            ReportPublicationRenderer.sha(Long.toString(version).getBytes(StandardCharsets.UTF_8)),
            access.permissionVersion(),
            () -> repository.authorize(access, id, false),
            () -> repository.read(access, id, version)
        );
        return JSON.convertValue(result, ReportPublication.ReadReceipt.class);
    }

    /** Only this SQL bootstrap bypasses prepare's deliberate incompleteness gate; evidence labels it separately. */
    private ReportPublication.State isolatedDraft(InspectionQuarterlyReportRepository.Summary source, byte[] captured)
        throws Exception {
        UUID id = UUID.randomUUID();
        var payload = (ObjectNode) JSON.readTree(captured);
        payload.put("label", "SYNTHETIC_ISOLATED_TEST_WORKFLOW_ONLY");
        byte[] bytes = JSON.writeValueAsBytes(payload);
        var access = access(creator, tenant);
        try (var connection = runtime.getConnection()) {
            connection.setAutoCommit(false);
            Instant at;
            try (
                var query = connection.prepareStatement("SELECT date_trunc('milliseconds',clock_timestamp())");
                var row = query.executeQuery()
            ) {
                assertTrue(row.next());
                at = row.getObject(1, OffsetDateTime.class).toInstant();
            }
            var document = new ReportPublication.Document(
                tenant,
                id,
                id,
                null,
                1,
                ReportPublication.Kind.QUARTERLY,
                ReportPublication.Scope.ISOLATED_TEST,
                ReportPublicationRenderer.sha(bytes),
                source.sourceManifestSha256(),
                "SYNTHETIC_R02_METRIC_V1",
                ReportPublicationRenderer.TEMPLATE,
                creator,
                at,
                List.of()
            );
            var state = ReportPublication.create(document);
            execute(
                connection,
                "INSERT INTO public.mop_report_publication_document" +
                    "(tenant_id,id,source_report_id,source_document_sha256,label,payload,created_by,permission_version,captured_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                tenant,
                id,
                UUID.fromString(source.id()),
                source.documentSha256(),
                "SYNTHETIC_ISOLATED_TEST_WORKFLOW_ONLY",
                bytes,
                creator,
                access.permissionVersion(),
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
            );
            execute(
                connection,
                "INSERT INTO public.mop_report_publication_revision" +
                    "(tenant_id,document_id,version,action,phase,state,actor_id,permission_version,recorded_at) " +
                    "VALUES(?,?,1,'CREATE','DRAFT',?::jsonb,?,?,?)",
                tenant,
                id,
                JSON.writeValueAsString(state),
                creator,
                access.permissionVersion(),
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
            );
            connection.commit();
            evidence.put(
                "isolated_fixture_scope",
                "ISOLATED_TEST; initial state injected only for repository workflow validation; source output unchanged"
            );
            return state;
        }
    }

    private void saveOutputs(PostgresReportPublicationRepository repository, ReportPublication.State state, String name)
        throws Exception {
        String id = state.document().id().toString();
        var html = repository.content(access(creator, tenant), id, ReportPublication.Format.HTML);
        var pdf = repository.content(access(creator, tenant), id, ReportPublication.Format.PDF);
        assertEquals(state.html().contentSha256(), html.sha256());
        assertEquals(html.sha256(), state.pdf().htmlSha256());
        assertEquals(state.html().fontSha256(), state.pdf().fontSha256());
        assertEquals(ReportPublicationRenderer.sha(pdf.bytes()), state.pdf().contentSha256());
        assertEquals(state.html().bytes(), html.bytes().length);
        assertEquals(state.pdf().bytes(), pdf.bytes().length);
        assertTrue(state.pdf().pages() >= 1);
        String raw = new String(pdf.bytes(), StandardCharsets.ISO_8859_1);
        assertTrue(raw.startsWith("%PDF-") && raw.stripTrailing().endsWith("%%EOF"));
        assertTrue(raw.contains("/FontFile2") || raw.contains("/FontFile3"), "Actual configured font must be embedded");
        save(name + ".html", html.bytes());
        save(name + ".pdf", pdf.bytes());
        evidence.put(
            name,
            Map.of(
                "document_sha256",
                state.document().documentSha256(),
                "html_sha256",
                html.sha256(),
                "pdf_sha256",
                pdf.sha256(),
                "font_identity_sha256",
                state.pdf().fontSha256(),
                "pages",
                state.pdf().pages(),
                "renderer",
                state.pdf().rendererVersion(),
                "visual_review",
                "NOT_RUN"
            )
        );
    }

    private void save(String filename, byte[] bytes) throws Exception {
        Path destination = settings.evidencePath().getParent().resolve(filename);
        assertFalse(Files.exists(destination));
        Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertArrayEquals(bytes, Files.readAllBytes(destination));
    }

    private void setMemberActive(UUID actor, boolean active) throws Exception {
        assertTrue(List.of(publisher, recipient).contains(actor));
        try (var connection = migrator.getConnection()) {
            execute(
                connection,
                "UPDATE public.mop_membership SET active=?,permission_version=permission_version+1 " +
                    "WHERE tenant_id=? AND user_id=?",
                active,
                tenant,
                actor
            );
        }
    }

    private Map<String, Long> counts() throws Exception {
        var result = new LinkedHashMap<String, Long>();
        for (String table : List.of(
            "mop_report_publication_document",
            "mop_report_publication_revision",
            "mop_report_publication_artifact",
            "mop_report_publication_read",
            "mop_report_publication_delivery_intent",
            "mop_audit",
            "mop_idempotency"
        )) {
            result.put(table, scalar("SELECT count(*) FROM public." + table + " WHERE tenant_id=?", tenant));
        }
        return result;
    }

    private long scalar(String sql, Object... values) throws Exception {
        try (var connection = runtime.getConnection(); var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(10);
            for (int index = 0; index < values.length; index++) {
                query.setObject(index + 1, values[index]);
            }
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(10);
            for (int index = 0; index < values.length; index++) {
                query.setObject(index + 1, values[index]);
            }
            assertEquals(1, query.executeUpdate());
        }
    }

    private static JsonNode normalized(Object value) throws Exception {
        return JSON.readTree(JSON.writeValueAsBytes(value));
    }

    private static void assertDomain(int status, String code, Runnable action) {
        var error = assertThrows(DomainException.class, action::run);
        assertEquals(status, error.status);
        assertEquals(code, error.code);
    }

    /** Persist individual evidence layers without upgrading fixture coverage into customer acceptance. */
    private void check(String id, String status, String limitation) throws Exception {
        checks.add(Map.of("id", id, "status", status, "scope", limitation));
        checkpoint();
    }

    private static Map<String, String> plannedMember(UUID actor, UUID scope, String role) {
        return Map.of(
            "actor_id",
            actor.toString(),
            "tenant_id",
            scope.toString(),
            "role",
            role,
            "subject",
            "SYNTHETIC_R02_" + actor
        );
    }

    /** Atomic, fsynced scope/evidence checkpoint; only files inside the prevalidated private run directory are replaced. */
    private void checkpoint() throws Exception {
        Path target = settings.evidencePath();
        assertFalse(Files.isSymbolicLink(target));
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence);
        Path temporary = Files.createTempFile(target.getParent(), ".r02-business-", ".tmp");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var channel = FileChannel.open(target, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        assertArrayEquals(bytes, Files.readAllBytes(target));
    }

    private static String required(String key) {
        String value = System.getenv(PREFIX + key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required synthetic R02 setting missing: " + key);
        }
        return value;
    }
}
