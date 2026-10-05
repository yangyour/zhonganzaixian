package cn.mop.persistence;

import java.sql.Connection;
import java.sql.DriverManager;

/** Integration tests inspect already-applied schemas; only the separate CLI may migrate. */
final class DesignatedSchemaPrecondition {
    private DesignatedSchemaPrecondition() {}
    static void requireV4(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, required("MOP_MIGRATION_USERNAME"), required("MOP_MIGRATION_PASSWORD"))) {
            connection.setReadOnly(true);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT current_database(),current_user,(SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('1','2','3','4') AND success),NOT EXISTS(SELECT 1 FROM public.flyway_schema_history WHERE NOT success)")) {
                if (!rows.next() || !"mop_test".equals(rows.getString(1)) || !"mop_test_migrator".equals(rows.getString(2)) || rows.getInt(3) != 4 || !rows.getBoolean(4)) {
                    throw new IllegalStateException("Designated schema baseline is not ready; run the separately authorized migration CLI");
                }
            }
        }
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing private test schema inspection setting " + key);
        return value;
    }
}
