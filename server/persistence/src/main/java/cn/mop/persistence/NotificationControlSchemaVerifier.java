package cn.mop.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import static cn.mop.persistence.TestSchemaMigration.*;

/** Explicit V51 verification; startup never applies this schema. */
final class NotificationControlSchemaVerifier {
    static final String SCRIPT = "V51__notification_routing_control.sql";
    static final String SHA256 = "51f26c2d0d74903a10ea04525335fff8abfa8eecf1e048e1a623a535e609d37b";

    static void resource() throws Exception {
        if (!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql())))) {
            throw new IllegalStateException("Notification control migration differs from reviewed resource");
        }
    }

    static void verify(Connection connection) throws SQLException {
        requireTrue(connection, "SELECT current_database()='mop_test' OR current_database() ~ '^mop_notification_test_[a-f0-9]{12}$'");
        verifyRuntimeAcl(connection, "mop_notification_control", Set.of("root_version"));
        requireConstraint(connection, "mop_notification_control", "p", List.of("tenant_id", "kind", "object_id", "version"), null, List.of(), false);
        requireConstraint(connection, "mop_notification_control", "u", List.of("tenant_id", "command_id"), null, List.of(), false);
        requireConstraint(connection, "mop_notification_control", "f", List.of("tenant_id", "actor_id"),
                "mop_membership", List.of("tenant_id", "user_id"), false);
        requireTrue(connection, "SELECT relpersistence='p' AND relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') "
                + "FROM pg_class WHERE oid='public.mop_notification_control'::regclass");
        requireTrue(connection, "SELECT count(*)=1 AND bool_and(tgenabled='O' AND tgtype=31 AND NOT tgdeferrable "
                + "AND tgfoid='public.mop_notification_control_guard()'::regprocedure) FROM pg_trigger "
                + "WHERE tgrelid='public.mop_notification_control'::regclass AND NOT tgisinternal");
        try {
            String source = new String(sql(), StandardCharsets.UTF_8);
            int start = source.indexOf("AS $$", source.indexOf("CREATE FUNCTION")) + 5;
            String body = source.substring(start, source.indexOf("$$;", start));
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            requireTrue(connection, "SELECT NOT prosecdef AND proconfig=ARRAY['search_path=pg_catalog'] "
                    + "AND encode(sha256(convert_to(prosrc,'UTF8')),'hex')=? FROM pg_proc "
                    + "WHERE oid='public.mop_notification_control_guard()'::regprocedure", digest);
        } catch (Exception failure) {
            throw new SQLException("Notification control guard unavailable");
        }
    }

    private static byte[] sql() throws Exception {
        try (var source = NotificationControlSchemaVerifier.class.getResourceAsStream("/db/migration/" + SCRIPT)) {
            if (source == null) {
                throw new IllegalStateException("Notification control resource missing");
            }
            return source.readAllBytes();
        }
    }
}
