package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetmetadata.AssetLedgerFactsRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Bounded same-tenant joins in one read-only snapshot, independent of internal metadata editing rights. */
public final class PostgresAssetLedgerFactsRepository implements AssetLedgerFactsRepository {
    /** Existing designated runtime pool; no fallback, migration or per-row connection. */
    private final DataSource source;

    public PostgresAssetLedgerFactsRepository(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public Snapshot read(Access access, List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty() || assetIds.size() > 100
                || assetIds.stream().anyMatch(id -> id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                || new HashSet<>(assetIds).size() != assetIds.size()) {
            throw new DomainException(400, "VALIDATION_ERROR", "资料读取需要1至100个不重复的规范资产编号");
        }
        if (access == null) {
            throw new DomainException(401, "SESSION_EXPIRED", "当前授权上下文缺失");
        }
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                PostgresAuthorization.require(c, access.actorId(), access.tenantId(), access.permissionVersion(), "assets:read");
                Instant observed;
                try (var s = c.prepareStatement("SELECT transaction_timestamp()")) {
                    s.setQueryTimeout(5);
                    try (var r = s.executeQuery()) {
                        r.next();
                        observed = r.getObject(1, OffsetDateTime.class).toInstant();
                    }
                }
                String sql = "SELECT a.id,a.version,a.site_id,a.source,s.name site_name,d.revision_version,"
                        + "r.asset_version metadata_version,r.recorded_at,r.business_description,r.owner_id,"
                        + "r.verification_status,r.monitoring_applied,r.cmdb_applied,"
                        + "m.user_id owner_member_id,m.active owner_member_active,u.id owner_user_id,"
                        + "u.display_name owner_name,u.active owner_user_active,"
                        + "CASE WHEN a.source='manual' THEN (SELECT max(h.asset_version) FROM public.mop_asset_manual_revision h "
                        + "WHERE h.tenant_id=a.tenant_id AND h.asset_id=a.id) END latest_metadata_version "
                        + "FROM public.mop_asset a JOIN public.mop_site s ON s.tenant_id=a.tenant_id AND s.id=a.site_id "
                        + "LEFT JOIN public.mop_asset_manual_details d ON a.source='manual' AND d.tenant_id=a.tenant_id AND d.asset_id=a.id "
                        + "LEFT JOIN public.mop_asset_manual_revision r ON r.tenant_id=d.tenant_id AND r.asset_id=d.asset_id AND r.asset_version=d.revision_version "
                        + "LEFT JOIN public.mop_membership m ON m.tenant_id=a.tenant_id AND m.user_id=r.owner_id "
                        + "LEFT JOIN public.mop_user u ON u.id=m.user_id "
                        + "WHERE a.tenant_id=?::uuid AND a.id=ANY(?::uuid[]) ORDER BY a.id LIMIT 100";
                List<Facts> facts = new ArrayList<>();
                java.sql.Array selected = c.createArrayOf("uuid", assetIds.toArray());
                try (var s = c.prepareStatement(sql)) {
                    s.setQueryTimeout(10);
                    s.setString(1, access.tenantId());
                    s.setArray(2, selected);
                    try (var r = s.executeQuery()) {
                        while (r.next()) {
                            facts.add(facts(r));
                        }
                    }
                } finally {
                    selected.free();
                }
                if (facts.size() != assetIds.size()) {
                    throw new DomainException(404, "OBJECT_NOT_FOUND", "资产不存在或不可见，未返回部分资料");
                }
                c.commit();
                return new Snapshot(access.tenantId(), observed, facts);
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // The outer failure is returned without raw database details.
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private Facts facts(ResultSet r) throws SQLException {
        String assetId = r.getString("id");
        long version = r.getLong("version");
        String siteId = r.getString("site_id"), siteName = r.getString("site_name");
        if (!text(siteId, 64) || !text(siteName, 100)) {
            throw unavailable();
        }
        Long head = nullableLong(r, "revision_version");
        Long revision = nullableLong(r, "metadata_version");
        Long latest = nullableLong(r, "latest_metadata_version");
        if (head == null) {
            if (latest != null) {
                throw unavailable();
            }
            return new Facts(assetId, version, siteId, siteName, null,
                    null, "NOT_RECORDED", null, null);
        }
        if (!head.equals(revision) || !head.equals(latest) || head < 2 || head > version || !"SELF_REPORTED".equals(r.getString("verification_status"))
                || r.getBoolean("monitoring_applied") || r.getBoolean("cmdb_applied")) {
            throw unavailable();
        }
        var recorded = r.getObject("recorded_at", OffsetDateTime.class);
        if (recorded == null) {
            throw unavailable();
        }
        Owner owner = null;
        String ownerId = r.getString("owner_id");
        String business = r.getString("business_description");
        if (business != null && !text(business, 200)) {
            throw unavailable();
        }
        if (ownerId != null) {
            if (!ownerId.equals(r.getString("owner_member_id")) || !ownerId.equals(r.getString("owner_user_id"))
                    || !text(r.getString("owner_name"), 100)) {
                throw unavailable();
            }
            owner = new Owner(ownerId, r.getString("owner_name"),
                    r.getBoolean("owner_member_active") && r.getBoolean("owner_user_active") ? "ACTIVE" : "INACTIVE");
        }
        return new Facts(assetId, version, siteId, siteName, revision,
                recorded.toInstant(), "MANUAL_DECLARATION", business, owner);
    }

    private static boolean text(String value, int maximum) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maximum
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static Long nullableLong(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        return r.wasNull() ? null : value;
    }

    private static DomainException unavailable() {
        return new DomainException(503, "ASSET_LEDGER_FACTS_UNAVAILABLE", "资产资料来源或数据库读取不可用，未返回替代值");
    }
}
