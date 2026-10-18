package cn.mop.persistence;

import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Candidate V35 verifier; execution evidence, not this class, determines whether it has been applied. */
final class ServiceTierSchemaVerifier {
    static final String SCRIPT = "V35__service_tiers_and_change_history.sql";
    static final String SHA256 = "f8bf2639d6ebb467a0d515147307ceb2e13c425b2337cbad69c88eee91330ed0";

    static void resource() throws Exception {
        try (var in = ServiceTierSchemaVerifier.class.getResourceAsStream("/db/migration/" + SCRIPT)) {
            if (in == null || !SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))
                throw new IllegalStateException("Service tier migration differs from reviewed candidate");
        }
    }

    static void verify(Connection c) throws SQLException {
        requireTrue(c, "SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        verifyRuntimeAcl(c, "mop_service_tier", Set.of("version", "tier", "availability_target", "contract_device_count",
            "billing_cycle", "monthly_fee_fen", "effective_mode", "effective_from", "updated_by", "updated_at"));
        verifyRuntimeAcl(c, "mop_service_tier_change", Set.of());
        for (String table : List.of("mop_service_tier", "mop_service_tier_change"))
            requireTrue(c, "SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)", "public." + table);
        requireTrue(c, "SELECT count(*)=2 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid='public.mop_service_tier'::regclass AND NOT tgisinternal");
        requireTrue(c, "SELECT count(*)=2 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid='public.mop_service_tier_change'::regclass AND NOT tgisinternal");
        requireTrue(c, "SELECT tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgname='mop_service_tier_head_sync' AND tgrelid='public.mop_service_tier'::regclass");
        requireTrue(c, "SELECT tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgname='mop_service_tier_change_sync' AND tgrelid='public.mop_service_tier_change'::regclass");
        requireTrue(c, "SELECT count(*)=3 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ('public.mop_service_tier_head_guard()'::regprocedure,'public.mop_service_tier_change_guard()'::regprocedure,'public.mop_service_tier_sync_guard()'::regprocedure)");
        requireTrue(c, "SELECT count(*)>=18 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ('public.mop_service_tier'::regclass,'public.mop_service_tier_change'::regclass)");
    }
}
