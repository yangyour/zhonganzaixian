package cn.mop.persistence;

import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static cn.mop.persistence.TestSchemaMigration.*;

final class WeComSchemaVerifier {
    static final String SCRIPT="V38__wecom_bindings_deliveries_and_login_state.sql";
    static final String SHA256="32dc38c2dab0ad385f79e9e7c886c456c06664f8b740c496a8af7bf2d9e9c0fb";
    static void resource()throws Exception {
        try(var in=WeComSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)) {
            if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("WeCom migration differs from reviewed candidate");
        }
    }
    static void verify(Connection c)throws SQLException {
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on'");
        verifyRuntimeAcl(c,"mop_wecom_binding",Set.of("active","version","updated_at","updated_by"));
        verifyRuntimeAcl(c,"mop_wecom_delivery",Set.of("status","version","attempt_id","provider_message_id","error_code","provider_code","started_at","finished_at"));
        verifyRuntimeAcl(c,"mop_wecom_login_state",Set.of("consumed_at"));
        for(String table:List.of("mop_wecom_binding","mop_wecom_delivery","mop_wecom_login_state")) {
            requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)","public."+table);
            requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal","public."+table);
            requireTrue(c,"SELECT bool_and(convalidated) FROM pg_constraint WHERE conrelid=to_regclass(?)","public."+table);
        }
        requireTrue(c,"SELECT count(*)=3 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ('public.mop_wecom_binding_guard()'::regprocedure,'public.mop_wecom_delivery_guard()'::regprocedure,'public.mop_wecom_login_state_guard()'::regprocedure)");
    }
}
