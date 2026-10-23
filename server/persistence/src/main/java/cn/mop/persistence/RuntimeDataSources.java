package cn.mop.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;

/** Shared runtime-only connection factory. Explicit migration/admin tools must use their separate guarded entrypoints. */
public final class RuntimeDataSources {
    private RuntimeDataSources() {
    }

    public static void validateConfiguration(HikariConfig configuration) {
        String url = configuration.getJdbcUrl();
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test")
            || !"mop_test_runtime".equals(configuration.getUsername())
            || configuration.getPassword() == null || configuration.getPassword().isBlank()) {
            throw new IllegalArgumentException("FAIL_CLOSED: restricted loopback test runtime configuration required");
        }
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("FAIL_CLOSED: invalid runtime connection port");
        }
    }

    public static HikariDataSource openRestrictedTest(HikariConfig configuration) {
        validateConfiguration(configuration);
        HikariDataSource source = null;
        try {
            source = new HikariDataSource(configuration);
            try (var connection = source.getConnection()) {
                RuntimeDatabaseGuard.requireRestrictedTestConnection(connection);
            }
            return source;
        } catch (SQLException | RuntimeException unavailable) {
            if (source != null) {
                source.close();
            }
            throw new IllegalStateException("FAIL_CLOSED: restricted test runtime connection or privilege check failed");
        }
    }
}
