package cn.mop.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** Candidate byte/ACL contracts only; actual row locks and trigger rejection require the isolated PostgreSQL suite. */
class CandidateRootRowLockPermissionsTest {
    private static final Pattern COLUMN_UPDATE_GRANT = Pattern.compile(
            "GRANT\\s+UPDATE\\s*\\(([^)]+)\\)\\s+ON\\s+([a-z0-9_.]+)\\s+TO\\s+mop_test_runtime\\s*;",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMMUTABLE_GUARD_FIRST = Pattern.compile(
            "BEGIN\\s+IF\\s+TG_OP\\s*<>\\s*'INSERT'\\s+THEN\\s+RAISE\\s+EXCEPTION",
            Pattern.CASE_INSENSITIVE);

    @FunctionalInterface
    private interface Verifier {
        void verify(Connection connection) throws SQLException;
    }

    @Test
    void reportRootIdentityCanBeLockedWhileEveryOtherColumnAndTableStayImmutable() throws Exception {
        ReportPublicationSchemaVerifier.resource();
        assertMinimalPermissions(ReportPublicationSchemaVerifier.sql(), "mop_report_publication_document",
                ReportPublicationSchemaVerifier.TABLES, ReportPublicationSchemaVerifier::verify);
    }

    @Test
    void enrollmentRootIdentityCanBeLockedWithoutGrantingRevisionUpdates() throws Exception {
        MonitoringEnrollmentSchemaVerifier.resource();
        assertMinimalPermissions(MonitoringEnrollmentSchemaVerifier.sql(), "mop_monitoring_enrollment",
                List.of("mop_monitoring_enrollment", "mop_monitoring_enrollment_revision"),
                MonitoringEnrollmentSchemaVerifier::verify);
    }

    @Test
    void mappingRootIdentityCanBeLockedWithoutGrantingRevisionUpdates() throws Exception {
        MonitoringObjectBindingSchemaVerifier.resource();
        assertMinimalPermissions(MonitoringObjectBindingSchemaVerifier.sql(), "mop_monitoring_object_binding",
                List.of("mop_monitoring_object_binding", "mop_monitoring_object_binding_revision"),
                MonitoringObjectBindingSchemaVerifier::verify);
    }

    private void assertMinimalPermissions(byte[] resource, String root, List<String> tables, Verifier verifier)
            throws SQLException {
        String sql = new String(resource, StandardCharsets.UTF_8);
        var grants = new ArrayList<String>();
        var matcher = COLUMN_UPDATE_GRANT.matcher(sql);
        while (matcher.find()) {
            grants.add(matcher.group(2) + ":" + matcher.group(1));
        }
        assertEquals(List.of("public." + root + ":id"), grants);
        assertFalse(Pattern.compile("GRANT\\s+UPDATE\\s+ON", Pattern.CASE_INSENSITIVE).matcher(sql).find());
        assertTrue(IMMUTABLE_GUARD_FIRST.matcher(sql).find(), "UPDATE rejection must be the first trigger action");
        assertTrue(sql.contains("BEFORE INSERT OR UPDATE OR DELETE ON public." + root));

        var connection = mock(Connection.class);
        try (var checks = mockStatic(TestSchemaMigration.class)) {
            verifier.verify(connection);
            for (String table : tables) {
                Set<String> allowed = table.equals(root) ? Set.of("id") : Set.of();
                checks.verify(() -> TestSchemaMigration.verifyRuntimeAcl(connection, table, allowed));
            }
        }
    }
}
