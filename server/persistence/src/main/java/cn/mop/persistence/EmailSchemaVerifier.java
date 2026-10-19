package cn.mop.persistence;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

final class EmailSchemaVerifier {
    static final String SCRIPT="V39__email_bindings_and_delivery_ledger.sql";
    static final String SHA256="3d1b4cd557b6c367b2a36e9f49a2154d8a925a8afe249c103cd59373f32a6dea";
    static void resource()throws Exception {
        try(var in=EmailSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)) {
            if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Email migration differs from reviewed candidate");
        }
    }
    static void verify(Connection c)throws SQLException {
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on'");
        verifyRuntimeAcl(c,"mop_email_binding",Set.of("email","active","version","updated_at","updated_by"));
        verifyRuntimeAcl(c,"mop_email_delivery",Set.of("status","version","attempt_id","message_id","error_code","smtp_code","started_at","finished_at"));
        for(String table:List.of("mop_email_binding","mop_email_delivery")) {
            requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)","public."+table);
            requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal","public."+table);
            requireTrue(c,"SELECT bool_and(convalidated) FROM pg_constraint WHERE conrelid=to_regclass(?)","public."+table);
        }
        requireTrue(c,"SELECT count(*)=2 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ('public.mop_email_binding_guard()'::regprocedure,'public.mop_email_delivery_guard()'::regprocedure)");
    }
}
