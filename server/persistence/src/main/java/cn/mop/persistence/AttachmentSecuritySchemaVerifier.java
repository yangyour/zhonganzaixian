package cn.mop.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import static cn.mop.persistence.TestSchemaMigration.*;

/** V52 candidate resource/structure checks. This class never applies SQL. */
final class AttachmentSecuritySchemaVerifier {
    static final String SCRIPT="V52__attachment_security_journal.sql";
    static final String SHA256="ca8140bb6f6958408a7683d41ccb9ba0a03657bcfe3fb76f3f6fede361d103ef";
    static byte[] sql() throws Exception {
        try(var stream=AttachmentSecuritySchemaVerifier.class.getResourceAsStream("/db/migration/"+SCRIPT)) {
            if(stream==null)throw new IllegalStateException("Attachment candidate resource missing");
            return stream.readAllBytes();
        }
    }
    static void resource() throws Exception {
        if(!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql()))))
            throw new IllegalStateException("Attachment candidate resource changed without review");
    }
    static void verify(Connection connection) throws SQLException {
        verifyRuntimeAcl(connection,"mop_attachment_security_revision",Set.of());
        requireConstraint(connection,"mop_attachment_security_revision","p",List.of("tenant_id","file_id","version"),null,List.of(),false);
        requireConstraint(connection,"mop_attachment_security_revision","f",List.of("tenant_id","file_id"),"mop_file",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_attachment_security_revision","f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT relpersistence='p' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_attachment_security_revision'::regclass");
        requireTrue(connection,"SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND NOT tgdeferrable AND tgfoid='public.mop_attachment_security_guard()'::regprocedure) FROM pg_trigger WHERE tgrelid='public.mop_attachment_security_revision'::regclass AND NOT tgisinternal");
        try {
            resource();
            String source=new String(sql(),StandardCharsets.UTF_8);
            int start=source.indexOf("AS $$",source.indexOf("CREATE FUNCTION"))+5;
            String body=source.substring(start,source.indexOf("$$;",start));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            requireTrue(connection,"SELECT NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog'] AND encode(sha256(convert_to(prosrc,'UTF8')),'hex')=? FROM pg_proc WHERE oid='public.mop_attachment_security_guard()'::regprocedure",digest);
        } catch(Exception failure) { throw new SQLException("Attachment guard unavailable"); }
    }
}
