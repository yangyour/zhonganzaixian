package cn.mop.persistence;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Candidate V37 verifier; execution evidence, not this class, determines whether it has been applied. */
final class MonitoringEventCenterSchemaVerifier {
    static final String SCRIPT = "V37__monitoring_events_noise_funnel_and_maintenance_windows.sql";
    static final String SHA256 = "f6f9cc88bcbc4dcbaf1a76a849aa78ed2cf7af91e7fd9b5c80dbde4d9b9ab351";

    static void resource() throws Exception {
        try (var in = MonitoringEventCenterSchemaVerifier.class.getResourceAsStream("/db/migration/" + SCRIPT)) {
            if (in == null || !SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))
                throw new IllegalStateException("Monitoring event center migration differs from reviewed candidate");
        }
    }

    static void verify(Connection c) throws SQLException {
        requireTrue(c, "SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        verifyRuntimeAcl(c, "mop_monitoring_event", Set.of("version", "occurrence_count", "flap_flip_count", "flap_silenced_until",
            "monitor_state", "handling_state", "suppression_state", "suppression_reason", "notification_empty_state",
            "sla_unknown_marked", "last_occurred_at", "recovered_at", "first_ack_at", "resolved_at", "closed_at",
            "root_cause_note", "ticket_id", "updated_by", "updated_at"));
        verifyRuntimeAcl(c, "mop_monitoring_event_member", Set.of());
        verifyRuntimeAcl(c, "mop_event_noise_record", Set.of());
        verifyRuntimeAcl(c, "mop_maintenance_window", Set.of("version", "ends_at", "status",
            "sla_exclusion_confirmed", "sla_exclusion_confirmed_by", "sla_exclusion_confirmed_at", "updated_by", "updated_at"));
        verifyRuntimeAcl(c, "mop_monitoring_quarantine_decision", Set.of());
        verifyRuntimeAcl(c, "mop_autoticket_attempt", Set.of("version", "attempt_count", "status",
            "ticket_id", "next_retry_at", "last_error", "updated_by", "updated_at"));
        for (String table : List.of("mop_monitoring_event", "mop_monitoring_event_member", "mop_event_noise_record",
            "mop_maintenance_window", "mop_monitoring_quarantine_decision", "mop_autoticket_attempt")) {
            requireTrue(c, "SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)", "public." + table);
            requireTrue(c, "SELECT count(*)=1 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal", "public." + table);
        }
        requireTrue(c, "SELECT count(*)=4 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ("
            + "'public.mop_monitoring_event_guard()'::regprocedure,"
            + "'public.mop_monitoring_event_append_only_guard()'::regprocedure,"
            + "'public.mop_maintenance_window_guard()'::regprocedure,"
            + "'public.mop_autoticket_attempt_guard()'::regprocedure)");
        requireTrue(c, "SELECT count(*)>=50 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ("
            + "'public.mop_monitoring_event'::regclass,"
            + "'public.mop_monitoring_event_member'::regclass,"
            + "'public.mop_event_noise_record'::regclass,"
            + "'public.mop_maintenance_window'::regclass,"
            + "'public.mop_monitoring_quarantine_decision'::regclass,"
            + "'public.mop_autoticket_attempt'::regclass)");
    }
}
