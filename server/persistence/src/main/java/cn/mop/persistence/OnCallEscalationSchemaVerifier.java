package cn.mop.persistence;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Candidate V36 verifier; execution evidence, not this class, determines whether it has been applied. */
final class OnCallEscalationSchemaVerifier {
    static final String SCRIPT = "V36__oncall_escalation_and_sla_monthly_results.sql";
    static final String SHA256 = "52f43d3f5eb43967ac8ff895da9b5503d2e1e83fc29d52adea2ce6076ab7c0cb";

    static void resource() throws Exception {
        try (var in = OnCallEscalationSchemaVerifier.class.getResourceAsStream("/db/migration/" + SCRIPT)) {
            if (in == null || !SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))
                throw new IllegalStateException("On-call escalation and SLA monthly migration differs from reviewed candidate");
        }
    }

    static void verify(Connection c) throws SQLException {
        requireTrue(c, "SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        verifyRuntimeAcl(c, "mop_oncall_roster", Set.of("version", "primary_oncall_user_id", "backup_oncall_user_id", "note", "updated_by", "updated_at"));
        verifyRuntimeAcl(c, "mop_oncall_handoff", Set.of());
        verifyRuntimeAcl(c, "mop_escalation_policy", Set.of("version", "unanswered_escalate_count", "steps_json", "updated_by", "updated_at"));
        verifyRuntimeAcl(c, "mop_escalation_step_run", Set.of());
        verifyRuntimeAcl(c, "mop_sla_monthly_result", Set.of("version", "tier_snapshot", "total_ticket_count", "applicable_count",
            "met_count", "not_met_count", "unresolved_count", "not_applicable_count", "achievement_numerator", "achievement_denominator",
            "compensation_ratio", "monthly_fee_fen", "formula_version", "items_json", "frozen", "frozen_at", "evaluated_by", "evaluated_at"));
        for (String table : List.of("mop_oncall_roster", "mop_oncall_handoff", "mop_escalation_policy", "mop_escalation_step_run", "mop_sla_monthly_result")) {
            requireTrue(c, "SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)", "public." + table);
            requireTrue(c, "SELECT count(*)=1 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal", "public." + table);
        }
        requireTrue(c, "SELECT count(*)=5 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ("
            + "'public.mop_oncall_roster_guard()'::regprocedure,"
            + "'public.mop_oncall_handoff_guard()'::regprocedure,"
            + "'public.mop_escalation_policy_guard()'::regprocedure,"
            + "'public.mop_escalation_step_run_guard()'::regprocedure,"
            + "'public.mop_sla_monthly_result_guard()'::regprocedure)");
        requireTrue(c, "SELECT count(*)>=45 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ("
            + "'public.mop_oncall_roster'::regclass,"
            + "'public.mop_oncall_handoff'::regclass,"
            + "'public.mop_escalation_policy'::regclass,"
            + "'public.mop_escalation_step_run'::regclass,"
            + "'public.mop_sla_monthly_result'::regclass)");
    }
}
