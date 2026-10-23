package cn.mop.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/** Read-only enforcement of the designated test runtime's least-privilege boundary; no production DDL decision. */
public final class RuntimeDatabaseGuard {
    private RuntimeDatabaseGuard() {
    }

    public record Observation(boolean namespaceRestricted, boolean roleRestricted, boolean ddlRestricted,
                              boolean migrationSeparated, boolean globalCapabilitiesRestricted,
                              String serverVersion, Instant inspectedAt) {
        public boolean accepted() {
            return namespaceRestricted && roleRestricted && ddlRestricted && migrationSeparated && globalCapabilitiesRestricted;
        }
    }

    public static Observation inspect(Connection connection) throws SQLException {
        String sql = "SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND session_user='mop_test_runtime', "
            + "NOT (r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication), "
            + "NOT has_database_privilege(current_user,current_database(),'CREATE') AND NOT has_schema_privilege(current_user,'public','CREATE'), "
            + "EXISTS(SELECT 1 FROM pg_roles m WHERE m.rolname='mop_test_migrator') "
            + "AND NOT EXISTS(SELECT 1 FROM pg_roles m WHERE m.rolname='mop_test_migrator' AND pg_has_role(current_user,m.oid,'MEMBER')), "
            + "NOT EXISTS(SELECT 1 FROM pg_roles p WHERE p.rolname IN ('pg_read_all_data','pg_write_all_data',"
            + "'pg_execute_server_program','pg_read_server_files','pg_write_server_files','pg_signal_backend','pg_checkpoint',"
            + "'pg_monitor','pg_read_all_settings','pg_read_all_stats','pg_stat_scan_tables','pg_use_reserved_connections',"
            + "'pg_create_subscription','pg_maintain') "
            + "AND pg_has_role(current_user,p.oid,'USAGE')), current_setting('server_version'), clock_timestamp() "
            + "FROM pg_roles r WHERE r.rolname=current_user";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Runtime role observation unavailable", "08006");
                }
                return new Observation(rows.getBoolean(1), rows.getBoolean(2), rows.getBoolean(3), rows.getBoolean(4),
                    rows.getBoolean(5), rows.getString(6), rows.getTimestamp(7).toInstant());
            }
        }
    }

    public static void requireRestrictedTestConnection(Connection connection) throws SQLException {
        if (!inspect(connection).accepted()) {
            throw new IllegalStateException("FAIL_CLOSED: runtime database identity or privileges exceed the dedicated test boundary");
        }
    }
}
