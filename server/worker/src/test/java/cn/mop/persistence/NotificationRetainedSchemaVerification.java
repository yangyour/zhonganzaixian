package cn.mop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only retained-V51 gate. This class has no Flyway, migration, repair, schema creation or cleanup path. */
final class NotificationRetainedSchemaVerification {
    private static final String MODE = "PREAPPLIED_V51";
    private final String url;
    private final String database;
    private final String ownerMarker;
    private final String password;
    private final String expectedHistory;
    private final int expectedChecksum;
    private final Path evidence;
    private final Map<String, Object> report = new LinkedHashMap<>();

    private NotificationRetainedSchemaVerification(String url) throws Exception {
        database = required("MOP_NOTIFICATION_EXPECTED_DATABASE");
        assertTrue(database.matches("mop_notification_test_[a-f0-9]{12}"));
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/" + database));
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        assertTrue(port >= 1 && port <= 65535);
        this.url = url;
        ownerMarker = required("MOP_NOTIFICATION_EXPECTED_OWNER_MARKER");
        assertTrue(ownerMarker.matches("MOP_CANDIDATE_CHAIN:[a-f0-9]{32}:N01:[a-f0-9]{32}"));
        expectedHistory = required("MOP_NOTIFICATION_EXPECTED_HISTORY_SHA256");
        assertTrue(expectedHistory.matches("[a-f0-9]{64}"));
        expectedChecksum = Integer.parseInt(required("MOP_NOTIFICATION_EXPECTED_V51_CHECKSUM"));
        password = required("MOP_MIGRATION_PASSWORD");
        Path root = Path.of(required("MOP_NOTIFICATION_REPOSITORY_ROOT")).toRealPath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")));
        Path local = root.resolve(".local");
        assertEquals(local, local.toRealPath());
        evidence = Path.of(required("MOP_NOTIFICATION_RETAINED_EVIDENCE_PATH")).toAbsolutePath().normalize();
        assertTrue(evidence.startsWith(local));
        assertTrue(evidence.getParent().toRealPath().startsWith(local));
        assertFalse(Files.exists(evidence));
        assertEquals("java-retained-evidence.json", evidence.getFileName().toString());
        report.put("schema_mode", MODE);
        report.put("database", database);
        report.put("owner_marker", ownerMarker);
        report.put("migration_calls", 0);
        report.put("schema_mutations", 0);
        report.put("v51_resource_sha256", NotificationControlSchemaVerifier.SHA256);
    }

    static NotificationRetainedSchemaVerification inspectIfRequested(String url) throws Exception {
        String mode = System.getenv("MOP_NOTIFICATION_ROUTING_SCHEMA_MODE");
        if (mode == null || "FRESH_APPLY".equals(mode)) {
            return null;
        }
        assertEquals(MODE, mode, "Unknown schema mode must not fall back to fresh migration");
        var verification = new NotificationRetainedSchemaVerification(url);
        verification.report.put("history_before", verification.readOnlyVerify());
        return verification;
    }

    void finish(boolean poolsClosed) throws Exception {
        Throwable failure = null;
        try {
            report.put("history_after", readOnlyVerify());
            assertEquals(report.get("history_before"), report.get("history_after"));
            assertTrue(poolsClosed);
            report.put("history_unchanged", true);
            report.put("status", "PASSED");
        } catch (Throwable problem) {
            failure = problem;
            report.put("status", "FAILED");
            report.put("failure_type", problem.getClass().getSimpleName());
        } finally {
            report.put("pools_closed", poolsClosed);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), report);
        }
        if (failure != null) {
            throw new AssertionError("Retained notification schema verification failed (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private Map<String, Object> readOnlyVerify() throws Exception {
        NotificationControlSchemaVerifier.resource();
        try (Connection connection = DriverManager.getConnection(url, "mop_test_migrator", password)) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL timezone='UTC'");
                }
                try (var query = connection.prepareStatement("SELECT current_database(),current_user,session_user,"
                        + "current_setting('transaction_read_only'),pg_get_userbyid(datdba),shobj_description(oid,'pg_database') "
                        + "FROM pg_database WHERE datname=current_database()")) {
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(database, row.getString(1));
                        assertEquals("mop_test_migrator", row.getString(2));
                        assertEquals("mop_test_migrator", row.getString(3));
                        assertEquals("on", row.getString(4));
                        assertEquals("mop_test_migrator", row.getString(5));
                        assertEquals(ownerMarker, row.getString(6));
                    }
                }
                int count = 0;
                String installed = null;
                try (var query = connection.prepareStatement("SELECT version,script,checksum,success,installed_on::text "
                        + "FROM public.flyway_schema_history ORDER BY installed_rank")) {
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            assertEquals(Integer.toString(++count), rows.getString(1));
                            assertTrue(rows.getBoolean(4));
                            if (count == 51) {
                                assertEquals("V51__notification_routing_control.sql", rows.getString(2));
                                assertEquals(expectedChecksum, rows.getInt(3));
                                installed = rows.getString(5);
                            }
                        }
                    }
                }
                assertEquals(51, count);
                String history;
                try (var query = connection.prepareStatement("SELECT encode(sha256(convert_to("
                        + "COALESCE(jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text,'[]'),'UTF8')),'hex') "
                        + "FROM public.flyway_schema_history h"); var row = query.executeQuery()) {
                    assertTrue(row.next());
                    history = row.getString(1);
                    assertEquals(expectedHistory, history);
                }
                NotificationControlSchemaVerifier.verify(connection);
                connection.commit();
                return Map.of("rows", count, "sha256", history, "v51_checksum", expectedChecksum, "v51_installed_on", installed);
            } catch (Exception | AssertionError failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required retained notification setting missing: " + name);
        }
        return value;
    }

    @Override
    public String toString() {
        return "NotificationRetainedSchemaVerification[credentials=REDACTED]";
    }
}
