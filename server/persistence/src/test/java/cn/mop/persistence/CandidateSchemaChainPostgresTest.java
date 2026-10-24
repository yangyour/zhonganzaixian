package cn.mop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit owned-database schema experiment. Never connects to, migrates or seeds the active mop_test database. */
@EnabledIfEnvironmentVariable(named = "MOP_RUN_CANDIDATE_SCHEMA_CHAIN_PG_TESTS", matches = "true")
class CandidateSchemaChainPostgresTest {
    private static final Map<String, List<Integer>> STAGES = Map.of(
            "N01", List.of(51), "P02", List.of(52), "R02", List.of(53), "M02", List.of(54, 55));
    private static final Map<String, String> PREFIXES = Map.of(
            "N01", "notification", "P02", "attachment", "R02", "report", "M02", "monitoring");
    private static final Map<Integer, String> SCRIPTS = Map.of(
            51, "V51__notification_routing_control.sql", 52, "V52__attachment_security_journal.sql",
            53, "V53__report_publication_journal.sql", 54, "V54__monitoring_enrollment_journal.sql",
            55, "V55__monitoring_object_mapping_journal.sql");

    /** Passwords are intentionally excluded from this value's string representation and every report. */
    private record Settings(String stage, List<Integer> versions, String database, String url, String marker,
                            String migratorPassword, String runtimePassword, Path root, Path evidence) {
        static Settings load() throws Exception {
            String stage = required("MOP_CANDIDATE_STAGE");
            if (!STAGES.containsKey(stage)) {
                throw new IllegalArgumentException("Unsupported candidate stage");
            }
            var versions = STAGES.get(stage);
            String exactVersions = String.join(",", versions.stream().map(Object::toString).toList());
            if (!exactVersions.equals(required("MOP_CANDIDATE_VERSIONS"))) {
                throw new IllegalArgumentException("Candidate stage versions do not match");
            }
            String database = required("MOP_CANDIDATE_DATABASE");
            if (!database.matches("mop_" + PREFIXES.get(stage) + "_test_[a-f0-9]{12}")) {
                throw new IllegalArgumentException("Candidate database namespace rejected");
            }
            String url = required("MOP_CANDIDATE_JDBC_URL");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/" + database)) {
                throw new IllegalArgumentException("Candidate JDBC destination rejected");
            }
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Candidate tunnel port rejected");
            }
            String marker = required("MOP_CANDIDATE_OWNER_MARKER");
            if (!marker.matches("MOP_CANDIDATE_CHAIN:[a-f0-9]{32}:" + stage + ":[a-f0-9]{32}")) {
                throw new IllegalArgumentException("Candidate ownership marker rejected");
            }
            Path root = Path.of(required("MOP_CANDIDATE_REPOSITORY_ROOT")).toRealPath();
            if (!Files.isRegularFile(root.resolve("pom.xml"))
                    || !Files.isDirectory(root.resolve("server/persistence/src/main/resources/db/migration"))) {
                throw new IllegalArgumentException("Candidate repository root rejected");
            }
            Path local = root.resolve(".local");
            Files.createDirectories(local);
            if (!local.toRealPath().equals(local)) {
                throw new IllegalArgumentException("Candidate local evidence directory must not redirect");
            }
            Path evidence = Path.of(required("MOP_CANDIDATE_EVIDENCE_PATH")).toAbsolutePath().normalize();
            if (!evidence.startsWith(local) || !"java-evidence.json".equals(evidence.getFileName().toString())
                    || Files.exists(evidence)) {
                throw new IllegalArgumentException("Candidate evidence destination rejected");
            }
            Files.createDirectories(evidence.getParent());
            if (!evidence.getParent().toRealPath().startsWith(local)) {
                throw new IllegalArgumentException("Candidate evidence parent redirects outside the workspace");
            }
            return new Settings(stage, versions, database, url, marker, required("MOP_CANDIDATE_MIGRATOR_PASSWORD"),
                    required("MOP_CANDIDATE_RUNTIME_PASSWORD"), root, evidence);
        }

        @Override
        public String toString() {
            return "CandidateSettings[stage=" + stage + ", credentials=REDACTED]";
        }
    }

    @Test
    void appliesOnlyTheOwnedStageAndExercisesRealRowLocksAndImmutableGuards() throws Exception {
        Settings settings = Settings.load();
        var report = new LinkedHashMap<String, Object>();
        var migrations = new ArrayList<Map<String, Object>>();
        var checks = new ArrayList<Map<String, Object>>();
        var schemaValidation = new LinkedHashMap<String, Object>();
        schemaValidation.put("status", "NOT_RUN");
        schemaValidation.put("checks", checks);
        report.put("schema_version", 1);
        report.put("stage", settings.stage());
        report.put("database", settings.database());
        report.put("owner_marker", settings.marker());
        report.put("status", "RUNNING");
        report.put("pools_closed", false);
        report.put("migrations", migrations);
        report.put("schema_validation", schemaValidation);
        report.put("business_validation", Map.of("status", "NOT_RUN", "test_count", 0,
                "limitations", List.of("SCHEMA_ACL_ROW_LOCK_AND_INITIAL_REVISION_ONLY",
                        "NO_HTTP_RENDERING_SCANNER_DELIVERY_OR_MONITORING_COLLECTION_ACCEPTANCE")));
        HikariDataSource migrator = null;
        HikariDataSource runtime = null;
        String phase = "owned_identity";
        Throwable failure = null;
        try {
            quietFlyway();
            migrator = pool(settings, "mop_test_migrator", settings.migratorPassword());
            runtime = pool(settings, "mop_test_runtime", settings.runtimePassword());
            try (var connection = migrator.getConnection()) {
                identity(connection, settings, "mop_test_migrator");
                requireEmptyBusinessSchema(connection);
                requireHistory(connection, settings.versions().get(0) - 1);
            }
            try (var connection = runtime.getConnection()) {
                identity(connection, settings, "mop_test_runtime");
            }
            for (int version : settings.versions()) {
                phase = "migration_" + version;
                byte[] resource = resource(settings, version);
                var migration = new LinkedHashMap<String, Object>();
                migration.put("version", Integer.toString(version));
                migration.put("script", SCRIPTS.get(version));
                migration.put("sha256", sha256(resource));
                migration.put("apply_executed", 0);
                migration.put("repeat_executed", null);
                migration.put("history_status", "NOT_PROBED");
                migrations.add(migration);
                var flyway = Flyway.configure().dataSource(migrator).locations("classpath:db/migration")
                        .schemas("public").defaultSchema("public").createSchemas(false).cleanDisabled(true)
                        .baselineOnMigrate(false).outOfOrder(false).mixed(false).executeInTransaction(true).group(true)
                        .target(Integer.toString(version)).validateOnMigrate(true).validateMigrationNaming(true)
                        .failOnMissingLocations(true).skipDefaultCallbacks(true).ignoreMigrationPatterns("*:pending").load();
                flyway.validate();
                assertEquals(version - 1, flyway.info().applied().length, "Exact prior history required");
                assertTrue(Arrays.stream(flyway.info().applied()).allMatch(MigrationInfo::isChecksumMatching),
                        "Cloned history checksums must match source resources");
                int applied;
                try {
                    applied = flyway.migrate().migrationsExecuted;
                    migration.put("apply_executed", applied);
                } finally {
                    // A successful immutable history row freezes these bytes even when a later assertion fails.
                    captureAppliedHistory(migrator, version, migration);
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(settings.evidence().toFile(), report);
                }
                assertEquals(1, applied, "Only this explicit candidate version may execute");
                int repeated = flyway.migrate().migrationsExecuted;
                migration.put("repeat_executed", repeated);
                assertEquals(0, repeated, "Repeat must execute no migration");
                flyway.validate();
                MigrationInfo resolved = Arrays.stream(flyway.info().all())
                        .filter(info -> info.getVersion() != null && Integer.toString(version).equals(info.getVersion().toString()))
                        .findFirst().orElseThrow();
                assertTrue(resolved.isApplied() && resolved.isChecksumMatching());
                try (var connection = migrator.getConnection()) {
                    identity(connection, settings, "mop_test_migrator");
                    requireHistory(connection, version);
                    try (var statement = connection.prepareStatement(
                            "SELECT script,checksum,success FROM public.flyway_schema_history WHERE version=?")) {
                        statement.setString(1, Integer.toString(version));
                        try (var rows = statement.executeQuery()) {
                            assertTrue(rows.next());
                            assertEquals(SCRIPTS.get(version), rows.getString(1));
                            assertEquals(resolved.getChecksum(), rows.getInt(2));
                            assertTrue(rows.getBoolean(3));
                            assertFalse(rows.next());
                        }
                    }
                }
                try (var connection = runtime.getConnection()) {
                    identity(connection, settings, "mop_test_runtime");
                    verify(connection, version);
                }
                migration.put("schema_verifier", "PASSED");
                schemaValidation.put("status", "IN_PROGRESS");
            }
            phase = "nonempty_lock_and_revision_fixtures";
            CandidateSchemaFixtures.exercise(settings.stage(), migrator, runtime, checks);
            schemaValidation.put("status", "PASSED");
            report.put("status", "PASSED");
        } catch (Throwable problem) {
            failure = problem;
            report.put("status", "FAILED");
            report.put("failed_phase", phase);
            report.put("failure_type", problem.getClass().getSimpleName());
            report.put("sql_state", sqlState(problem));
            schemaValidation.put("status", "FAILED");
        } finally {
            if (runtime != null) {
                runtime.close();
            }
            if (migrator != null) {
                migrator.close();
            }
            report.put("pools_closed", (runtime == null || runtime.isClosed()) && (migrator == null || migrator.isClosed()));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(settings.evidence().toFile(), report);
        }
        if (failure != null) {
            // Do not attach the driver/Flyway cause: it can carry connection diagnostics into public test output.
            throw new AssertionError("Owned candidate stage failed at " + phase + " (" + failure.getClass().getSimpleName() + ")");
        }
        assertEquals(true, report.get("pools_closed"));
    }

    private static HikariDataSource pool(Settings settings, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(settings.url());
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setPoolName("candidate-" + settings.stage() + "-" + user);
        return new HikariDataSource(config);
    }

    private static void identity(Connection connection, Settings settings, String role) throws Exception {
        try (var statement = connection.prepareStatement("SELECT current_database(),current_user,session_user,"
                + "pg_get_userbyid(d.datdba),shobj_description(d.oid,'pg_database'),"
                + "r.rolsuper,r.rolcreatedb,r.rolcreaterole,r.rolreplication,r.rolbypassrls "
                + "FROM pg_database d JOIN pg_roles r ON r.rolname=current_user WHERE d.datname=current_database()")) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(settings.database(), rows.getString(1));
                assertNotEquals("mop_test", rows.getString(1));
                assertEquals(role, rows.getString(2));
                assertEquals(role, rows.getString(3));
                assertEquals("mop_test_migrator", rows.getString(4));
                assertEquals(settings.marker(), rows.getString(5));
                for (int column = 6; column <= 10; column++) {
                    assertFalse(rows.getBoolean(column), "Elevated candidate role rejected");
                }
                assertFalse(rows.next());
            }
        }
        if ("mop_test_runtime".equals(role)) {
            try (var statement = connection.prepareStatement("SELECT NOT has_schema_privilege(current_user,'public','CREATE') "
                    + "AND NOT has_database_privilege(current_user,current_database(),'CREATE') "
                    + "AND NOT pg_has_role(current_user,'mop_test_migrator','MEMBER')");
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1), "Runtime must not inherit ownership or DDL privileges");
            }
        }
    }

    private static void requireEmptyBusinessSchema(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String table = rows.getString(1);
                if (Set.of("flyway_schema_history", "mop_role_capability_limit").contains(table)) {
                    continue;
                }
                if (!table.matches("[a-z_][a-z0-9_]*")) {
                    throw new IllegalStateException("Unexpected public table name");
                }
                try (var count = connection.createStatement(); var result = count.executeQuery("SELECT count(*) FROM public." + table)) {
                    assertTrue(result.next());
                    assertEquals(0, result.getLong(1), "Candidate must start from schema-only business tables");
                }
            }
        }
    }

    private static void requireHistory(Connection connection, int expected) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT version,success FROM public.flyway_schema_history ORDER BY installed_rank");
             var rows = statement.executeQuery()) {
            int version = 0;
            while (rows.next()) {
                assertEquals(Integer.toString(++version), rows.getString(1));
                assertTrue(rows.getBoolean(2));
            }
            assertEquals(expected, version);
        }
    }

    private static void captureAppliedHistory(HikariDataSource migrator, int version, Map<String, Object> entry) {
        try (var connection = migrator.getConnection();
             var statement = connection.prepareStatement("SELECT script,checksum,success,installed_on::text "
                     + "FROM public.flyway_schema_history WHERE version=?")) {
            statement.setQueryTimeout(10);
            statement.setString(1, Integer.toString(version));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    entry.put("history_status", "NOT_APPLIED");
                    return;
                }
                entry.put("history_status", rows.getBoolean(3) ? "APPLIED_FROZEN" : "FAILED_HISTORY_ROW");
                entry.put("history_script", rows.getString(1));
                entry.put("flyway_checksum", rows.getInt(2));
                entry.put("installed_on", rows.getString(4));
                entry.put("history_success", rows.getBoolean(3));
            }
        } catch (SQLException unreadable) {
            entry.put("history_status", "UNKNOWN_HISTORY_PROBE_FAILED");
            entry.put("history_probe_sql_state", sqlState(unreadable));
        }
    }

    private static byte[] resource(Settings settings, int version) throws Exception {
        switch (version) {
            case 51 -> NotificationControlSchemaVerifier.resource();
            case 52 -> AttachmentSecuritySchemaVerifier.resource();
            case 53 -> ReportPublicationSchemaVerifier.resource();
            case 54 -> MonitoringEnrollmentSchemaVerifier.resource();
            case 55 -> MonitoringObjectBindingSchemaVerifier.resource();
            default -> throw new IllegalArgumentException("Unsupported candidate version");
        }
        byte[] bytes;
        try (var input = CandidateSchemaChainPostgresTest.class.getResourceAsStream("/db/migration/" + SCRIPTS.get(version))) {
            if (input == null) {
                throw new IllegalStateException("Candidate classpath resource missing");
            }
            bytes = input.readAllBytes();
        }
        assertArrayEquals(Files.readAllBytes(settings.root().resolve("server/persistence/src/main/resources/db/migration")
                .resolve(SCRIPTS.get(version))), bytes, "Classpath resource must match the reviewed workspace bytes");
        return bytes;
    }

    private static void verify(Connection connection, int version) throws SQLException {
        switch (version) {
            case 51 -> NotificationControlSchemaVerifier.verify(connection);
            case 52 -> AttachmentSecuritySchemaVerifier.verify(connection);
            case 53 -> ReportPublicationSchemaVerifier.verify(connection);
            case 54 -> MonitoringEnrollmentSchemaVerifier.verify(connection);
            case 55 -> MonitoringObjectBindingSchemaVerifier.verify(connection);
            default -> throw new IllegalArgumentException("Unsupported candidate version");
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required candidate setting missing: " + name);
        }
        return value;
    }

    private static String sqlState(Throwable failure) {
        for (Throwable value = failure; value != null; value = value.getCause()) {
            if (value instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                return sql.getSQLState();
            }
        }
        return "NOT_AVAILABLE";
    }

    private static void quietFlyway() {
        LogFactory.setLogCreator(type -> new Log() {
            public boolean isDebugEnabled() { return false; }
            public void debug(String message) { }
            public void info(String message) { }
            public void warn(String message) { }
            public void error(String message) { }
            public void error(String message, Exception failure) { }
            public void notice(String message) { }
        });
    }
}
