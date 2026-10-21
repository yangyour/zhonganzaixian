package cn.mop.persistence;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Candidate V34 verifier; execution evidence, not this class, determines whether it has been applied. */
final class NotificationIntentSchemaVerifier {
    static final String SCRIPT="V34__ticket_notification_intents.sql";
    static final String SHA256="7951a0abb91fd94ae6ba3a1b5769e32780b23b08720e2bd17851c64f256cdb51";
    static void resource()throws Exception{try(var in=NotificationIntentSchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)){if(in==null||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))))throw new IllegalStateException("Notification intent migration differs from reviewed candidate");}}
    static void verify(Connection c)throws SQLException{
        requireTrue(c,"SELECT current_database()='mop_test' AND current_setting('fsync')='on' AND current_setting('full_page_writes')='on'");
        verifyRuntimeAcl(c,"mop_notification_intent",Set.of());
        verifyRuntimeAcl(c,"mop_notification_message",Set.of("version","read_at"));
        for(String table:List.of("mop_notification_intent","mop_notification_message"))
            requireTrue(c,"SELECT relkind='r' AND relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=to_regclass(?)","public."+table);
        requireTrue(c,"SELECT count(*)=2 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid='public.mop_notification_intent'::regclass AND NOT tgisinternal");
        requireTrue(c,"SELECT count(*)=1 AND bool_and(tgenabled='O') FROM pg_trigger WHERE tgrelid='public.mop_notification_message'::regclass AND NOT tgisinternal");
        requireTrue(c,"SELECT tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgname='mop_notification_intent_count' AND tgrelid='public.mop_notification_intent'::regclass");
        requireTrue(c,"SELECT count(*)=3 AND bool_and(NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog']) FROM pg_proc WHERE oid IN ('public.mop_notification_intent_guard()'::regprocedure,'public.mop_notification_intent_count()'::regprocedure,'public.mop_notification_message_guard()'::regprocedure)");
        requireTrue(c,"SELECT count(*)>=20 AND bool_and(convalidated) FROM pg_constraint WHERE conrelid IN ('public.mop_notification_intent'::regclass,'public.mop_notification_message'::regclass)");
    }
}
