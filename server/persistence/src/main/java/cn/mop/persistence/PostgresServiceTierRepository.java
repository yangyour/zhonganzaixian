package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;

/** PostgreSQL repository for tenant service tier snapshot and append-only change history (V35). */
public final class PostgresServiceTierRepository implements ServiceTierRepository {
    private final DataSource source;
    private final PostgresPlatformRepository platform;

    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }

    public PostgresServiceTierRepository(DataSource source, PostgresPlatformRepository platform) {
        this.source = source;
        this.platform = platform;
    }

    private <T> T read(Access a, String capability, boolean internalOnly, Work<T> work) {
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                member(c, a, capability, internalOnly, false);
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public Optional<Snapshot> current(Access a) {
        return read(a, "tickets:read", false, c -> loadCurrent(c, a.tenantId()));
    }

    @Override
    public ChangePage history(Access a, int limit, Long beforeVersion) {
        if (limit < 1 || limit > 100 || beforeVersion != null && beforeVersion < 1) throw invalid();
        return read(a, "sla_scenarios:read", true, c -> {
            Instant observed = now(c);
            Optional<Snapshot> head = loadCurrent(c, a.tenantId());
            var rows = new ArrayList<Change>();
            String sql = "SELECT id::text,tenant_id::text,version,previous_version,from_tier,to_tier,availability_target,contract_device_count,billing_cycle,monthly_fee_fen,effective_mode,effective_from,reason,approved_by::text,changed_at,customer_approval_status "
                + "FROM public.mop_service_tier_change WHERE tenant_id=?::uuid"
                + (beforeVersion != null ? " AND version<?" : "")
                + " ORDER BY version DESC LIMIT ?";
            try (var s = statement(c, sql)) {
                int n = 1;
                s.setString(n++, a.tenantId());
                if (beforeVersion != null) s.setLong(n++, beforeVersion);
                s.setInt(n, limit + 1);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        Long prev = r.getLong("previous_version");
                        if (r.wasNull()) prev = null;
                        String fromRaw = r.getString("from_tier");
                        Integer devices = r.getInt("contract_device_count");
                        if (r.wasNull()) devices = null;
                        String cycleRaw = r.getString("billing_cycle");
                        Long fee = r.getLong("monthly_fee_fen");
                        if (r.wasNull()) fee = null;
                        rows.add(new Change(
                            r.getString("id"),
                            r.getString("tenant_id"),
                            r.getLong("version"),
                            prev,
                            fromRaw == null ? null : Tier.valueOf(fromRaw),
                            Tier.valueOf(r.getString("to_tier")),
                            r.getBigDecimal("availability_target"),
                            devices,
                            cycleRaw == null ? null : BillingCycle.valueOf(cycleRaw),
                            fee,
                            EffectiveMode.valueOf(r.getString("effective_mode")),
                            time(r, "effective_from"),
                            r.getString("reason"),
                            r.getString("approved_by"),
                            time(r, "changed_at"),
                            r.getString("customer_approval_status")
                        ));
                    }
                }
            }
            boolean more = rows.size() > limit;
            List<Change> page = rows.subList(0, Math.min(limit, rows.size()));
            Long nextBefore = more ? page.get(page.size() - 1).version() : null;
            return new ChangePage(head, List.copyOf(page), more, nextBefore, observed);
        });
    }

    @Override
    public void authorizeWrite(Access a) {
        try {
            Connection c = platform.idempotentCommandConnection();
            String role = member(c, a, "configurations:write", true, true);
            if (!"padmin".equals(role))
                throw new DomainException(403, "ACTION_FORBIDDEN", "服务档位变更仅向平台管理员开放");
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public Snapshot applyChange(Access a, Command cmd, BigDecimal configuredAvailabilityTarget) {
        if (cmd == null) throw invalid();
        ServiceTierRepository.validateMetrics(configuredAvailabilityTarget, cmd.contractDeviceCount(), cmd.monthlyFeeFen());
        Connection c = platform.idempotentCommandConnection();
        try {
            String role = member(c, a, "configurations:write", true, true);
            if (!"padmin".equals(role))
                throw new DomainException(403, "ACTION_FORBIDDEN", "服务档位变更仅向平台管理员开放");
            lock(c, a.tenantId());
            Optional<Snapshot> existing = loadCurrent(c, a.tenantId());
            long currentVersion = existing.map(Snapshot::version).orElse(0L);
            if (cmd.expectedVersion() != currentVersion)
                throw new DomainException(409, "VERSION_CONFLICT", "服务档位版本已变化，请刷新后重试");
            long nextVersion = currentVersion + 1;
            Long prevVersion = existing.isPresent() ? currentVersion : null;
            Tier fromTier = existing.map(Snapshot::tier).orElse(null);
            Instant at = now(c);
            Instant effectiveFrom = ServiceTierRepository.resolveEffectiveFrom(at, cmd.effectiveMode());
            if (existing.isEmpty()) {
                try (var s = statement(c, "INSERT INTO public.mop_service_tier(tenant_id,version,tier,availability_target,contract_device_count,billing_cycle,monthly_fee_fen,effective_mode,effective_from,updated_by,updated_at) VALUES(?::uuid,?,?,?,?,?,?,?,?,?::uuid,?)")) {
                    s.setString(1, a.tenantId());
                    s.setLong(2, nextVersion);
                    s.setString(3, cmd.toTier().name());
                    s.setBigDecimal(4, configuredAvailabilityTarget);
                    if (cmd.contractDeviceCount() == null) s.setNull(5, Types.INTEGER); else s.setInt(5, cmd.contractDeviceCount());
                    if (cmd.billingCycle() == null) s.setNull(6, Types.VARCHAR); else s.setString(6, cmd.billingCycle().name());
                    if (cmd.monthlyFeeFen() == null) s.setNull(7, Types.BIGINT); else s.setLong(7, cmd.monthlyFeeFen());
                    s.setString(8, cmd.effectiveMode().name());
                    instant(s, 9, effectiveFrom);
                    s.setString(10, a.actorId());
                    instant(s, 11, at);
                    s.executeUpdate();
                }
            } else {
                try (var s = statement(c, "UPDATE public.mop_service_tier SET version=?,tier=?,availability_target=?,contract_device_count=?,billing_cycle=?,monthly_fee_fen=?,effective_mode=?,effective_from=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND version=?")) {
                    s.setLong(1, nextVersion);
                    s.setString(2, cmd.toTier().name());
                    s.setBigDecimal(3, configuredAvailabilityTarget);
                    if (cmd.contractDeviceCount() == null) s.setNull(4, Types.INTEGER); else s.setInt(4, cmd.contractDeviceCount());
                    if (cmd.billingCycle() == null) s.setNull(5, Types.VARCHAR); else s.setString(5, cmd.billingCycle().name());
                    if (cmd.monthlyFeeFen() == null) s.setNull(6, Types.BIGINT); else s.setLong(6, cmd.monthlyFeeFen());
                    s.setString(7, cmd.effectiveMode().name());
                    instant(s, 8, effectiveFrom);
                    s.setString(9, a.actorId());
                    instant(s, 10, at);
                    s.setString(11, a.tenantId());
                    s.setLong(12, currentVersion);
                    if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "服务档位版本已变化，请刷新后重试");
                }
            }
            String changeId = UUID.randomUUID().toString();
            try (var s = statement(c, "INSERT INTO public.mop_service_tier_change(id,tenant_id,version,previous_version,from_tier,to_tier,availability_target,contract_device_count,billing_cycle,monthly_fee_fen,effective_mode,effective_from,reason,approved_by,changed_at,customer_approval_status) VALUES(?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::uuid,?,'UNVERIFIED')")) {
                s.setString(1, changeId);
                s.setString(2, a.tenantId());
                s.setLong(3, nextVersion);
                if (prevVersion == null) s.setNull(4, Types.BIGINT); else s.setLong(4, prevVersion);
                if (fromTier == null) s.setNull(5, Types.VARCHAR); else s.setString(5, fromTier.name());
                s.setString(6, cmd.toTier().name());
                s.setBigDecimal(7, configuredAvailabilityTarget);
                if (cmd.contractDeviceCount() == null) s.setNull(8, Types.INTEGER); else s.setInt(8, cmd.contractDeviceCount());
                if (cmd.billingCycle() == null) s.setNull(9, Types.VARCHAR); else s.setString(9, cmd.billingCycle().name());
                if (cmd.monthlyFeeFen() == null) s.setNull(10, Types.BIGINT); else s.setLong(10, cmd.monthlyFeeFen());
                s.setString(11, cmd.effectiveMode().name());
                instant(s, 12, effectiveFrom);
                s.setString(13, cmd.reason());
                s.setString(14, a.actorId());
                instant(s, 15, at);
                s.executeUpdate();
            }
            platform.audit(a.actorId(), a.tenantId(), "service_tier.change", "service_tier", a.tenantId(), "SUCCEEDED",
                "SERVICE_TIER_V" + nextVersion + "_" + (fromTier == null ? "INIT" : fromTier.name()) + "_TO_" + cmd.toTier().name(), a.traceId());
            return loadCurrent(c, a.tenantId()).orElseThrow(PostgresServiceTierRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    private Optional<Snapshot> loadCurrent(Connection c, String tenant) throws SQLException {
        try (var s = statement(c, "SELECT tenant_id::text,version,tier,availability_target,contract_device_count,billing_cycle,monthly_fee_fen,effective_mode,effective_from,updated_by::text,updated_at FROM public.mop_service_tier WHERE tenant_id=?::uuid")) {
            s.setString(1, tenant);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                Integer devices = r.getInt("contract_device_count");
                if (r.wasNull()) devices = null;
                String cycleRaw = r.getString("billing_cycle");
                Long fee = r.getLong("monthly_fee_fen");
                if (r.wasNull()) fee = null;
                return Optional.of(new Snapshot(
                    r.getString("tenant_id"),
                    r.getLong("version"),
                    Tier.valueOf(r.getString("tier")),
                    r.getBigDecimal("availability_target"),
                    devices,
                    cycleRaw == null ? null : BillingCycle.valueOf(cycleRaw),
                    fee,
                    EffectiveMode.valueOf(r.getString("effective_mode")),
                    time(r, "effective_from"),
                    r.getString("updated_by"),
                    time(r, "updated_at"),
                    "UNVERIFIED"
                ));
            }
        }
    }

    private static String member(Connection c, Access a, String capability, boolean internalOnly, boolean lock) throws SQLException {
        if (a == null) throw expired();
        PostgresAuthorization.require(c, a.actorId(), a.tenantId(), a.permissionVersion(), capability);
        try (var s = statement(c, "SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?" + (lock ? " FOR SHARE OF m,u" : ""))) {
            s.setString(1, a.tenantId());
            s.setString(2, a.actorId());
            s.setLong(3, a.permissionVersion());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw expired();
                String role = r.getString(1);
                if (internalOnly && !Capabilities.internal(role))
                    throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无权访问内部服务档位台账");
                return role;
            }
        }
    }

    private static void lock(Connection c, String tenant) throws SQLException {
        try (var s = statement(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            s.setString(1, "mop.service_tier|" + tenant);
            try (var r = s.executeQuery()) { r.next(); }
        }
    }

    @Override
    public List<String> readinessProblems() {
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            try (var s = statement(c, "SELECT current_database()='mop_test' AND current_user='mop_test_runtime'"
                + " AND has_table_privilege(current_user,'public.mop_service_tier','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_service_tier','DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_service_tier_change','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_service_tier_change','UPDATE,DELETE,TRUNCATE') AND NOT has_any_column_privilege(current_user,'public.mop_service_tier_change','UPDATE')"
                + " AND (SELECT count(*)=4 FROM pg_trigger WHERE tgname IN ('mop_service_tier_head_guard','mop_service_tier_change_guard','mop_service_tier_head_sync','mop_service_tier_change_sync') AND tgenabled='O')");
                 var r = s.executeQuery()) {
                if (!r.next() || !r.getBoolean(1)) return List.of("SERVICE_TIER_SCHEMA_OR_ACL_INVALID");
            }
            return List.of();
        } catch (SQLException | RuntimeException e) {
            return List.of("SERVICE_TIER_SCHEMA_OR_ACCESS_UNAVAILABLE");
        }
    }

    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(10);
        return s;
    }
    private static void instant(PreparedStatement s, int n, Instant t) throws SQLException {
        s.setObject(n, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
    }
    private static Instant time(ResultSet r, String k) throws SQLException {
        return r.getObject(k, OffsetDateTime.class).toInstant();
    }
    private static Instant now(Connection c) throws SQLException {
        try (var s = statement(c, "SELECT date_trunc('milliseconds',clock_timestamp())"); var r = s.executeQuery()) {
            r.next();
            return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "服务档位查询或变更参数无效"); }
    private static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员上下文已失效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "服务档位持久化不可用，未返回替代结果"); }
}
