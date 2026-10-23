package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;

/** Serializes all registered-asset admissions against the tenant's explicitly published device limit. */
final class PostgresAssetQuota {
    private static final String KEY = "tenant.contract_device_quota";

    private PostgresAssetQuota() {}

    /** Must run on the same command transaction as the insertion; the durable asset row consumes the slot. */
    static void requireRoom(Connection c, String tenant) throws SQLException {
        // Keep the same order as CMDB imports and runtime publication; never take admission before publication.
        PostgresCmdbImportRepository.lock(c, "mop.runtime.configuration|" + tenant + "|" + KEY);
        PostgresCmdbImportRepository.lock(c, "mop.asset.admission|" + tenant);
        long limit;
        String sql = "SELECT value_json::text,action,expires_at IS NULL OR expires_at>clock_timestamp() valid "
                + "FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid "
                + "AND configuration_key=? AND effective_at<=clock_timestamp() ORDER BY version DESC LIMIT 1";
        try (var s = c.prepareStatement(sql)) {
            s.setQueryTimeout(10);
            s.setString(1, tenant);
            s.setString(2, KEY);
            try (var r = s.executeQuery()) {
                if (!r.next() || "DISABLE".equals(r.getString("action")) || !r.getBoolean("valid")) {
                    throw pending();
                }
                try {
                    limit = new BigDecimal(r.getString(1)).longValueExact();
                    if (limit < 0 || limit > 9007199254740990L) {
                        throw new ArithmeticException();
                    }
                } catch (RuntimeException invalid) {
                    throw pending();
                }
            }
        }
        try (var s = c.prepareStatement("SELECT count(*) FROM public.mop_asset WHERE tenant_id=?::uuid")) {
            s.setQueryTimeout(10);
            s.setString(1, tenant);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("Asset count unavailable");
                }
                if (r.getLong(1) >= limit) {
                    throw new DomainException(409, "ASSET_QUOTA_EXCEEDED", "当前租户已达到已发布设备配额，未创建资产");
                }
            }
        }
    }

    private static DomainException pending() {
        return new DomainException(422, "ASSET_QUOTA_CONFIGURATION_REQUIRED", "当前租户缺少有效已发布设备配额，未创建资产");
    }
}
