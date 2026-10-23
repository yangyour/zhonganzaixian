package cn.mop.persistence;

import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated JDBC boundary checks; actual restricted-role SQL is covered by the designated rollback suite. */
class PostgresAssetQuotaTest {
    private final List<String> order = new ArrayList<>();

    private Connection connection(String limit, String action, boolean valid, long used) throws Exception {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            PreparedStatement s = mock(PreparedStatement.class);
            if (sql.contains("pg_advisory_xact_lock")) {
                doAnswer(bound -> {
                    order.add(bound.getArgument(1).toString());
                    return null;
                }).when(s).setObject(eq(1), any());
            } else {
                ResultSet r = mock(ResultSet.class);
                when(s.executeQuery()).thenAnswer(query -> {
                    order.add(sql.contains("value_json") ? "read-limit" : "count-assets");
                    return r;
                });
                when(r.next()).thenReturn(sql.contains("value_json") ? limit != null : true);
                when(r.getString(1)).thenReturn(limit);
                when(r.getString("action")).thenReturn(action);
                when(r.getBoolean("valid")).thenReturn(valid);
                when(r.getLong(1)).thenReturn(used);
            }
            return s;
        });
        return c;
    }

    @Test
    void locksPublicationBeforeAdmissionAndCountsOnlyAfterBoth() throws Exception {
        PostgresAssetQuota.requireRoom(connection("2", "PUBLISH", true, 1), "tenant");
        assertEquals(List.of("mop.runtime.configuration|tenant|tenant.contract_device_quota",
                "mop.asset.admission|tenant", "read-limit", "count-assets"), order);
    }

    @Test
    void unknownDisabledExpiredAndMalformedLimitsDoNotBecomeUnlimited() throws Exception {
        for (String value : Arrays.asList(null, "null", "-1", "1.5", "9007199254740991", "\"10\"")) {
            var c = connection(value, "PUBLISH", true, 0);
            assertEquals("ASSET_QUOTA_CONFIGURATION_REQUIRED", assertThrows(DomainException.class,
                    () -> PostgresAssetQuota.requireRoom(c, "tenant")).code);
        }
        for (var c : List.of(connection("2", "DISABLE", true, 0), connection("2", "PUBLISH", false, 0))) {
            assertEquals(422, assertThrows(DomainException.class, () -> PostgresAssetQuota.requireRoom(c, "tenant")).status);
        }
        assertFalse(order.contains("count-assets"));
    }

    @Test
    void zeroFullAndLoweredBelowUsageRefuseAdditionalAssets() throws Exception {
        for (long used : new long[]{0, 1, 20}) {
            var c = connection("0", "PUBLISH", true, used);
            assertEquals("ASSET_QUOTA_EXCEEDED", assertThrows(DomainException.class,
                    () -> PostgresAssetQuota.requireRoom(c, "tenant")).code);
        }
        var full = connection("3", "ROLLBACK", true, 3);
        assertEquals(409, assertThrows(DomainException.class, () -> PostgresAssetQuota.requireRoom(full, "tenant")).status);
    }

    @Test
    void sqlFailureIsPropagatedForOuterTransactionRollback() throws Exception {
        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenThrow(new SQLException("unit-only"));
        assertThrows(SQLException.class, () -> PostgresAssetQuota.requireRoom(c, "tenant"));
    }
}
