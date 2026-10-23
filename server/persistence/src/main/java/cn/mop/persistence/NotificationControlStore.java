package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Immutable V51 control journal with transaction-scoped live authorization and exact command recovery. */
public final class NotificationControlStore {
    public enum Kind { POLICY, PREFERENCE, BINDING, REQUEST, ROUTE, RECEIPT }

    public record Access(UUID tenantId, UUID actorId, long permissionVersion, String trace) {
        public Access {
            if (tenantId == null || actorId == null || permissionVersion < 1
                    || permissionVersion > 9_007_199_254_740_991L || trace == null
                    || !trace.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw invalid();
            }
        }
    }

    /** Copies prevent a caller changing already validated or committed state. */
    public record Entry(Kind kind, UUID id, long version, UUID commandId, JsonNode state, Instant recordedAt) {
        public Entry {
            state = state.deepCopy();
        }

        @Override
        public JsonNode state() {
            return state.deepCopy();
        }
    }

    public record Page(List<Entry> items, UUID nextId, Instant nextTime) {
        public Page {
            items = List.copyOf(items);
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final DataSource source;
    private final ObjectMapper mapper;

    public NotificationControlStore(DataSource source, ObjectMapper mapper) {
        this.source = Objects.requireNonNull(source);
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /** Resolves the explicitly configured service principal again on each polling cycle. */
    public Access workerAccess(UUID tenant, UUID actor) {
        try (var connection = source.getConnection(); var query = query(connection,
                "SELECT m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id "
                        + "WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active")) {
            query.setObject(1, tenant);
            query.setObject(2, actor);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new DomainException(403, "NOTIFICATION_WORKER_UNAUTHORIZED", "通知后台身份未获当前租户授权");
                }
                return new Access(tenant, actor, rows.getLong(1), "notification-worker-" + UUID.randomUUID());
            }
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    public boolean ready() {
        try (var connection = source.getConnection(); var query = query(connection,
                "SELECT current_user='mop_test_runtime' AND has_table_privilege(current_user,'public.mop_notification_control','SELECT') "
                        + "AND has_table_privilege(current_user,'public.mop_notification_control','INSERT') "
                        + "AND NOT has_table_privilege(current_user,'public.mop_notification_control','DELETE,TRUNCATE') "
                        + "AND EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='mop_notification_control_guard' "
                        + "AND tgrelid='public.mop_notification_control'::regclass AND tgenabled='O')")) {
            try (var rows = query.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        } catch (SQLException failure) {
            return false;
        }
    }

    /** Owner-only publications use the existing own-preference capability; operators use existing internal grants. */
    <T> T transaction(Access access, String capability, Work<T> work) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                authorize(connection, access, capability);
                T result = work.run(connection);
                authorize(connection, access, capability);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (DomainException rejected) {
            throw rejected;
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState()) || "23514".equals(failure.getSQLState())) {
                throw conflict();
            }
            throw unavailable();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    static void authorize(Connection connection, Access access, String capability) throws SQLException {
        PostgresAuthorization.require(connection, access.actorId().toString(), access.tenantId().toString(),
                access.permissionVersion(), capability);
        try (var query = query(connection, "SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id "
                + "WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active AND m.permission_version=? FOR SHARE OF m,u")) {
            query.setObject(1, access.tenantId());
            query.setObject(2, access.actorId());
            query.setLong(3, access.permissionVersion());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new DomainException(401, "SESSION_EXPIRED", "当前通知授权已失效");
                }
            }
        }
    }

    Optional<Entry> current(Connection connection, UUID tenant, Kind kind, UUID id, boolean lock) throws SQLException {
        if (lock) {
            try (var query = query(connection, "SELECT 1 FROM public.mop_notification_control "
                    + "WHERE tenant_id=? AND kind=? AND object_id=? AND version=0 FOR UPDATE")) {
                scope(query, tenant, kind, id);
                query.executeQuery().close();
            }
        }
        try (var query = query(connection, "SELECT * FROM public.mop_notification_control "
                + "WHERE tenant_id=? AND kind=? AND object_id=? ORDER BY version DESC LIMIT 1")) {
            scope(query, tenant, kind, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? Optional.of(entry(rows)) : Optional.empty();
            }
        }
    }

    Optional<Entry> replay(Connection connection, Access access, Kind kind, UUID id, long expected,
                           UUID command, String action, String requestHash) throws SQLException {
        try (var query = query(connection, "SELECT * FROM public.mop_notification_control WHERE tenant_id=? AND command_id=?")) {
            query.setObject(1, access.tenantId());
            query.setObject(2, command);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Entry entry = entry(rows);
                if (entry.kind() != kind || !entry.id().equals(id) || rows.getLong("expected_version") != expected
                        || !rows.getObject("actor_id", UUID.class).equals(access.actorId())
                        || rows.getLong("permission_version") != access.permissionVersion()
                        || !action.equals(rows.getString("action"))
                        || !requestHash.equals(entry.state().path("request_sha256").asText())) {
                    throw conflict();
                }
                return Optional.of(entry);
            }
        }
    }

    Entry append(Connection connection, Access access, Kind kind, UUID id, long expectedVersion,
                 UUID commandId, String action, JsonNode state) throws SQLException {
        if (id == null || commandId == null || expectedVersion < -1 || expectedVersion >= 100000
                || action == null || !action.matches("[A-Z][A-Z0-9_]{0,39}") || state == null || !state.isObject()) {
            throw invalid();
        }
        String encoded = encode(state);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 60000) {
            throw invalid();
        }
        String fingerprint = hash(kind + "\n" + id + "\n" + expectedVersion + "\n" + access.actorId()
                + "\n" + access.permissionVersion() + "\n" + action + "\n" + encoded);
        try (var query = query(connection, "SELECT * FROM public.mop_notification_control WHERE tenant_id=? AND command_id=?")) {
            query.setObject(1, access.tenantId());
            query.setObject(2, commandId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (!fingerprint.equals(rows.getString("command_sha256"))) {
                        throw conflict();
                    }
                    return entry(rows);
                }
            }
        }
        var current = current(connection, access.tenantId(), kind, id, true);
        if (current.map(Entry::version).orElse(-1L) != expectedVersion) {
            throw conflict();
        }
        try (var query = query(connection, "INSERT INTO public.mop_notification_control "
                + "(tenant_id,kind,object_id,version,expected_version,command_id,actor_id,permission_version,action,trace_id,state,command_sha256) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING *")) {
            scope(query, access.tenantId(), kind, id);
            query.setLong(4, expectedVersion + 1);
            query.setLong(5, expectedVersion);
            query.setObject(6, commandId);
            query.setObject(7, access.actorId());
            query.setLong(8, access.permissionVersion());
            query.setString(9, action);
            query.setString(10, access.trace());
            query.setString(11, encoded);
            query.setString(12, fingerprint);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw unavailable();
                }
                return entry(rows);
            }
        }
    }

    public Optional<Entry> command(Access access, UUID commandId) {
        return transaction(access, "notification_preferences:read_own", connection -> {
            try (var query = query(connection, "SELECT * FROM public.mop_notification_control "
                    + "WHERE tenant_id=? AND command_id=? AND actor_id=?")) {
                query.setObject(1, access.tenantId());
                query.setObject(2, commandId);
                query.setObject(3, access.actorId());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    Entry entry = entry(rows);
                    authorize(connection, access, entry.kind() == Kind.PREFERENCE
                            ? "notification_preferences:read_own" : "ticket_notes:read_internal");
                    return Optional.of(entry);
                }
            }
        });
    }

    public Page page(Access access, Kind kind, int limit, Instant before, UUID beforeId) {
        if (limit < 1 || limit > 100 || (before == null) != (beforeId == null)) {
            throw invalid();
        }
        return transaction(access, "ticket_notes:read_internal", connection -> {
            String cursor = before == null ? "" : " AND (r.recorded_at,r.object_id)<(?,?)";
            try (var query = query(connection, "SELECT h.*,r.recorded_at AS root_time FROM public.mop_notification_control r "
                    + "CROSS JOIN LATERAL (SELECT * FROM public.mop_notification_control h WHERE h.tenant_id=r.tenant_id "
                    + "AND h.kind=r.kind AND h.object_id=r.object_id ORDER BY version DESC LIMIT 1) h "
                    + "WHERE r.tenant_id=? AND r.kind=? AND r.version=0" + cursor
                    + " ORDER BY r.recorded_at DESC,r.object_id DESC LIMIT ?")) {
                query.setObject(1, access.tenantId());
                query.setString(2, kind.name());
                int index = 3;
                if (before != null) {
                    query.setObject(index++, OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC));
                    query.setObject(index++, beforeId);
                }
                query.setInt(index, limit + 1);
                var entries = new ArrayList<Entry>();
                var times = new ArrayList<Instant>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        entries.add(entry(rows));
                        times.add(rows.getObject("root_time", OffsetDateTime.class).toInstant());
                    }
                }
                boolean more = entries.size() > limit;
                if (more) {
                    entries.remove(entries.size() - 1);
                }
                return new Page(entries, more ? entries.get(limit - 1).id() : null, more ? times.get(limit - 1) : null);
            }
        });
    }

    Entry entry(java.sql.ResultSet rows) throws SQLException {
        try {
            JsonNode state = mapper.readTree(rows.getString("state"));
            String fingerprint = hash(rows.getString("kind") + "\n" + rows.getObject("object_id", UUID.class)
                    + "\n" + rows.getLong("expected_version") + "\n" + rows.getObject("actor_id", UUID.class)
                    + "\n" + rows.getLong("permission_version") + "\n" + rows.getString("action") + "\n" + encode(state));
            if (!fingerprint.equals(rows.getString("command_sha256"))) {
                throw unavailable();
            }
            return new Entry(Kind.valueOf(rows.getString("kind")), rows.getObject("object_id", UUID.class),
                    rows.getLong("version"), rows.getObject("command_id", UUID.class),
                    state, rows.getObject("recorded_at", OffsetDateTime.class).toInstant());
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    static PreparedStatement query(Connection connection, String sql) throws SQLException {
        var query = connection.prepareStatement(sql);
        query.setQueryTimeout(10);
        return query;
    }

    private static void scope(PreparedStatement query, UUID tenant, Kind kind, UUID id) throws SQLException {
        query.setObject(1, tenant);
        query.setString(2, kind.name());
        query.setObject(3, id);
    }

    String encode(JsonNode value) {
        try {
            return mapper.writeValueAsString(canonical(value));
        } catch (java.io.IOException failure) {
            throw invalid();
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode();
            var names = new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                result.set(name, canonical(value.get(name)));
            }
            return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    static UUID identity(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static DomainException conflict() {
        return new DomainException(409, "NOTIFICATION_COMMAND_CONFLICT", "通知版本或原命令不匹配，请核对当前记录");
    }

    static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "通知配置或命令无效");
    }

    static DomainException unavailable() {
        return new DomainException(503, "NOTIFICATION_CONTROL_UNAVAILABLE", "通知控制存储尚未配置或暂不可用");
    }
}
