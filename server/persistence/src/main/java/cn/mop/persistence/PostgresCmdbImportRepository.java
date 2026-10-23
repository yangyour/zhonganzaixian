package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.CmdbImportExecutionRepository;
import cn.mop.domain.assetimport.HostFormatValidator;
import cn.mop.domain.assetimport.source.AssetImportSourceRepository.Access;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository;
import cn.mop.domain.assetmetadata.ManualWarrantyUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;

/** One PostgreSQL transaction for source ownership, current rules, references, drafts, audit and receipt. */
public final class PostgresCmdbImportRepository implements CmdbImportExecutionRepository {
    public static final List<String> CONFIGURATION_KEYS = List.of("asset.import_steps", "asset.ip_scope", "tenant.contract_device_quota");
    /** All writers below participate in this repository's existing protected command connection. */
    private final PostgresPlatformRepository platform;
    /** Reads and locks the owner-bound artifact without opening another connection. */
    private final PostgresAssetImportRepository sources;
    /** Reuses manual draft constraints and immutable revision history, without applying monitoring. */
    private final ManualAssetMetadataRepository metadata;
    private final Planner planner;
    private final ObjectMapper mapper;
    private final Map<String, String> definitionHashes;

    public PostgresCmdbImportRepository(PostgresPlatformRepository platform, PostgresAssetImportRepository sources,
            ManualAssetMetadataRepository metadata, Planner planner, ObjectMapper mapper, Map<String, String> definitionHashes) {
        this.platform = Objects.requireNonNull(platform);
        this.sources = Objects.requireNonNull(sources);
        this.metadata = Objects.requireNonNull(metadata);
        this.planner = Objects.requireNonNull(planner);
        this.mapper = Objects.requireNonNull(mapper);
        this.definitionHashes = Map.copyOf(definitionHashes);
        if (!this.definitionHashes.keySet().equals(Set.copyOf(CONFIGURATION_KEYS))) {
            throw new IllegalArgumentException("All consumer definitions required");
        }
    }

    @Override
    public void authorize(Access access, String sourceId) {
        Connection c = platform.idempotentCommandConnection();
        sources.authorizeExecution(access, sourceId);
        try (var s = statement(c, "SELECT role_code FROM public.mop_membership "
                + "WHERE tenant_id=?::uuid AND user_id=?::uuid AND active", access.tenantId(), access.actorId());
                var r = s.executeQuery()) {
            if (!r.next() || !Set.of("padmin", "msp").contains(r.getString(1))) {
                throw new DomainException(403, "ACTION_FORBIDDEN", "当前来源导入仅开放授权内部角色，客户维护范围待确认");
            }
        } catch (SQLException failure) {
            throw new DomainException(503, "CMDB_IMPORT_UNAVAILABLE", "无法核验当前导入权限");
        }
    }

    @Override
    public Map<String, Object> execute(Access access, String sourceId, Command command) {
        Connection c = platform.idempotentCommandConnection();
        try {
            authorize(access, sourceId);
            var source = sources.executionSource(access, sourceId);
            if (source.version() != command.expectedVersion() || !source.parseHash().equals(command.parseHash())) {
                throw new DomainException(409, "VERSION_CONFLICT", "来源版本或解析摘要已变化");
            }
            // The source row serializes different keys. The immutable success audit is also the consumed marker.
            try (var s = statement(c, "SELECT 1 FROM public.mop_audit WHERE tenant_id=?::uuid "
                    + "AND payload->>'resource_id'=? AND payload->>'action'='asset.cmdb_import.execute' "
                    + "AND payload->>'result'='SUCCEEDED'", access.tenantId(), sourceId);
                    var r = s.executeQuery()) {
                if (r.next()) {
                    throw new DomainException(409, "CMDB_SOURCE_ALREADY_IMPORTED", "该来源已有成功导入回执，不能重复导入");
                }
            }
            for (String key : CONFIGURATION_KEYS) {
                lock(c, "mop.runtime.configuration|" + access.tenantId() + "|" + key);
            }
            Map<String, Object> releases = new LinkedHashMap<>();
            Map<String, Object> values = new HashMap<>();
            for (String key : CONFIGURATION_KEYS) {
                values.put(key, activeValue(c, access, key, releases));
            }
            if (!(values.get("asset.import_steps") instanceof Map<?, ?> rawPolicy)) {
                throw pending();
            }
            @SuppressWarnings("unchecked")
            Plan plan = planner.prepare(source.parsed(), (Map<String, Object>) rawPolicy);
            String scope = Objects.toString(values.get("asset.ip_scope"), "");
            if (!Set.of("TENANT", "SITE").contains(scope)) {
                throw pending();
            }
            long quota;
            try {
                quota = new java.math.BigDecimal(values.get("tenant.contract_device_quota").toString()).longValueExact();
                if (quota < 0 || quota > 9007199254740990L) {
                    throw new ArithmeticException();
                }
            } catch (RuntimeException invalid) {
                throw pending();
            }
            lock(c, "mop.asset.admission|" + access.tenantId());
            long count;
            try (var s = statement(c, "SELECT count(*) FROM public.mop_asset WHERE tenant_id=?::uuid", access.tenantId());
                    var r = s.executeQuery()) {
                r.next();
                count = r.getLong(1);
            }
            if (count > quota || plan.rows().size() > quota - count) {
                throw new DomainException(409, "ASSET_QUOTA_EXCEEDED", "当前资产数量与本批记录超过已发布租户设备配额");
            }
            validateReferencesAndDuplicates(c, access, plan, scope);
            var metadataAccess = new ManualAssetMetadataRepository.Access(access.actorId(), access.tenantId(), access.permissionVersion(), access.traceId());
            List<Map<String, Object>> imported = new ArrayList<>();
            for (Row row : plan.rows()) {
                var data = row.metadata();
                var asset = platform.insert(access.tenantId(), access.actorId(), data.name(), data.type(), row.address(), data.siteId());
                var updated = metadata.update(metadataAccess, asset.id(), asset.version(), data);
                var complete = metadata.updateWarranty(metadataAccess, asset.id(), updated.snapshot().asset().version(),
                        new ManualWarrantyUpdate(row.warranty(), data.warrantyExpiry(), data.overdueServiceDate(), data.note()));
                imported.add(Map.of("record", row.record(), "source_row", row.sourceRow(), "asset_id", asset.id(),
                        "asset_version", complete.snapshot().asset().version()));
            }
            // Expiring policy cannot authorize a batch that finished after its validity window.
            Map<String, Object> finalReleases = new LinkedHashMap<>();
            for (String key : CONFIGURATION_KEYS) {
                activeValue(c, access, key, finalReleases);
            }
            if (!releases.equals(finalReleases)) {
                throw new DomainException(409, "CMDB_IMPORT_CONFIGURATION_CHANGED", "导入期间定时发布的配置已生效，请重新校验后重试");
            }
            platform.audit(access.actorId(), access.tenantId(), "asset.cmdb_import.execute", "asset_import_source",
                    sourceId, "SUCCEEDED", "SOURCE_BACKED_DRAFTS_NO_MONITORING", access.traceId());
            var result = new LinkedHashMap<String, Object>();
            result.put("source_id", sourceId);
            result.put("source_version", source.version());
            result.put("artifact_id", source.artifactId());
            result.put("parse_hash", source.parseHash());
            result.put("tenant_id", access.tenantId());
            result.put("status", "IMPORTED_DRAFTS");
            result.put("assets", imported);
            result.put("imported_count", imported.size());
            result.put("configuration_releases", releases);
            result.put("quota", Map.of("limit", quota, "before", count, "after", count + imported.size()));
            result.put("extended_attributes", "RETAINED_IN_IMMUTABLE_SOURCE");
            result.put("monitoring_started", false);
            result.put("production_approved", false);
            return result;
        } catch (SQLException failure) {
            throw new DomainException(503, "CMDB_IMPORT_UNAVAILABLE", "导入数据库操作失败，整批事务回滚");
        }
    }

    private Object activeValue(Connection c, Access a, String key, Map<String, Object> releases) throws SQLException {
        String sql = "SELECT id,version,value_json,definition_hash,action,expires_at IS NULL OR expires_at>clock_timestamp() valid "
                + "FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND configuration_key=? "
                + "AND effective_at<=clock_timestamp() ORDER BY version DESC LIMIT 1";
        try (var s = statement(c, sql, a.tenantId(), key); var r = s.executeQuery()) {
            if (!r.next() || "DISABLE".equals(r.getString("action")) || !r.getBoolean("valid")
                    || !definitionHashes.get(key).equals(r.getString("definition_hash"))) {
                throw pending();
            }
            releases.put(key, Map.of("id", r.getString("id"), "version", r.getLong("version")));
            try {
                Object value = mapper.readValue(r.getString("value_json"), Object.class);
                if (value == null) {
                    throw pending();
                }
                return value;
            } catch (java.io.IOException invalid) {
                throw pending();
            }
        }
    }

    private void validateReferencesAndDuplicates(Connection c, Access a, Plan plan, String scope) throws SQLException {
        Set<String> names = new HashSet<>();
        Set<List<String>> addresses = new HashSet<>();
        try (var s = statement(c, "SELECT name,management_ip,site_id FROM public.mop_asset WHERE tenant_id=?::uuid", a.tenantId());
                var r = s.executeQuery()) {
            while (r.next()) {
                names.add(r.getString("name"));
                var address = HostFormatValidator.check(r.getString("management_ip"), plan.addressPolicy());
                if (!address.valid()) {
                    throw new DomainException(409, "CMDB_EXISTING_ADDRESS_UNRESOLVED", "已有资产地址不符合当前规范，无法可靠判定冲突");
                }
                addresses.add(List.of(address.canonical(), "SITE".equals(scope) ? r.getString("site_id") : ""));
            }
        }
        for (Row row : plan.rows()) {
            var data = row.metadata();
            // Runtime has SELECT only on sites. The composite foreign key protects the reference at insertion.
            try (var s = statement(c, "SELECT 1 FROM public.mop_site WHERE tenant_id=?::uuid AND id=?", a.tenantId(), data.siteId());
                    var r = s.executeQuery()) {
                if (!r.next()) {
                    throw missing();
                }
            }
            if (data.ownerId() != null) {
                try (var s = statement(c, "SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id "
                        + "WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active FOR SHARE OF m,u",
                        a.tenantId(), data.ownerId());
                        var r = s.executeQuery()) {
                    if (!r.next()) {
                        throw missing();
                    }
                }
            }
            if (!names.add(data.name()) || !addresses.add(List.of(row.address(), "SITE".equals(scope) ? data.siteId() : ""))) {
                throw new DomainException(409, "CMDB_ASSET_CONFLICT", "本批或当前租户已有同名或冲突域内同地址资产",
                        List.of(Map.of("record", row.record(), "source_row", row.sourceRow())));
            }
        }
    }

    static void lock(Connection c, String key) throws SQLException {
        try (var s = statement(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", key)) {
            s.execute();
        }
    }

    private static PreparedStatement statement(Connection c, String sql, Object... args) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(15);
        for (int i = 0; i < args.length; i++) {
            s.setObject(i + 1, args[i]);
        }
        return s;
    }

    private static DomainException pending() {
        return new DomainException(422, "CMDB_IMPORT_CONFIGURATION_REQUIRED", "导入规则、IP冲突域和设备配额须为当前租户已审核发布且有效的配置");
    }

    private static DomainException missing() {
        return new DomainException(404, "OBJECT_NOT_FOUND", "导入关联对象不存在或不可见");
    }
}
