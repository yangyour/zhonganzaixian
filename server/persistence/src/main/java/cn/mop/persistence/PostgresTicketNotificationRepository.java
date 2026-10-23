package cn.mop.persistence;

import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.notification.TicketNotificationPlanner;
import cn.mop.domain.notification.TicketNotificationPlanner.*;
import cn.mop.domain.notification.TicketNotificationRepository;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Relays committed ticket workflow outbox facts into immutable intents and recipient-owned in-app records. */
public final class PostgresTicketNotificationRepository implements TicketNotificationRepository {
    private static final List<String> TABLES = List.of("mop_notification_intent", "mop_notification_message");
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z"), MAX = Instant.parse("9999-12-31T23:59:59.999Z");
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final ObjectMapper mapper;

    public PostgresTicketNotificationRepository(DataSource source, PostgresPlatformRepository platform, ObjectMapper mapper) {
        this.source = Objects.requireNonNull(source); this.platform = Objects.requireNonNull(platform); this.mapper = mapper.copy();
    }

    @Override public int relay(String tenant, int limit) {
        uuid(tenant); if (limit < 1 || limit > 100) throw invalid();
        return platform.notificationRelayTransaction(() -> {
            Connection c = platform.notificationRelayConnection();
            try {
                try (var s = statement(c, "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")) {
                    s.setString(1, "mop.notification.relay|" + tenant);
                    try (var r = s.executeQuery()) { r.next(); if (!r.getBoolean(1)) return 0; }
                }
                record Row(String outbox, String ticket, String type, String event) {}
                var rows = new ArrayList<Row>();
                try (var s = statement(c, "SELECT o.id::text,o.ticket_id::text,o.event_type,o.event_id::text FROM public.mop_ticket_workflow_outbox o WHERE o.tenant_id=?::uuid AND NOT EXISTS(SELECT 1 FROM public.mop_notification_intent i WHERE i.tenant_id=o.tenant_id AND i.outbox_id=o.id) ORDER BY o.created_at,o.id LIMIT ?")) {
                    s.setString(1, tenant); s.setInt(2, limit);
                    try (var r = s.executeQuery()) { while (r.next()) rows.add(new Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4))); }
                }
                Instant now = now(c); int processed = 0;
                for (Row row : rows) {
                    Plan plan = plan(c, tenant, row.ticket(), row.type(), row.event());
                    plan = plan.retainActive(active(c, tenant, row.ticket(), plan.recipients()));
                    String intent = UUID.randomUUID().toString();
                    try (var s = statement(c, "INSERT INTO public.mop_notification_intent(id,tenant_id,source_type,outbox_id,ticket_id,event_type,phase,priority,recipient_status,recipient_count,pending_reasons,external_dispatch,external_blockers,created_at) VALUES(?::uuid,?::uuid,'TICKET_WORKFLOW',?::uuid,?::uuid,?,?,?,?,?,?::jsonb,'NOT_CONFIGURED',?::jsonb,?)")) {
                        s.setString(1, intent); s.setString(2, tenant); s.setString(3, row.outbox()); s.setString(4, row.ticket()); s.setString(5, plan.eventType()); s.setString(6, plan.phase());
                        s.setString(7, plan.priority()); s.setString(8, plan.status().name()); s.setInt(9, plan.recipients().size()); s.setString(10, encode(plan.pendingReasons()));
                        s.setString(11, encode(TicketNotificationPlanner.EXTERNAL_BLOCKERS)); time(s, 12, now); s.executeUpdate();
                    }
                    for (Recipient recipient : plan.recipients()) {
                        try (var s = statement(c, "INSERT INTO public.mop_notification_message(id,tenant_id,intent_id,ticket_id,recipient_id,audience,title,body,created_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?)")) {
                            s.setString(1, UUID.randomUUID().toString()); s.setString(2, tenant); s.setString(3, intent); s.setString(4, row.ticket()); s.setString(5, recipient.userId());
                            s.setString(6, recipient.audience().name()); s.setString(7, recipient.title()); s.setString(8, recipient.body()); time(s, 9, now); s.executeUpdate();
                        }
                    }
                    processed++;
                }
                return processed;
            } catch (SQLException e) { throw unavailable(); }
        });
    }

    private Plan plan(Connection c, String tenant, String ticket, String type, String event) throws SQLException {
        String reporter, title, receipt;
        try (var s = statement(c, "SELECT reporter_id::text,title,receipt_no FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid")) {
            s.setString(1, tenant); s.setString(2, ticket);
            try (var r = s.executeQuery()) { if (!r.next()) throw unavailable(); reporter = r.getString(1); title = r.getString(2); receipt = r.getString(3); }
        }
        JsonNode snapshot, details;
        try (var s = statement(c, "SELECT snapshot,details FROM public.mop_ticket_workflow_event WHERE tenant_id=?::uuid AND id=?::uuid AND ticket_id=?::uuid")) {
            s.setString(1, tenant); s.setString(2, event); s.setString(3, ticket);
            try (var r = s.executeQuery()) { if (!r.next()) throw unavailable(); snapshot = tree(r.getString(1)); details = tree(r.getString(2)); }
        }
        var collaborators = new ArrayList<String>();
        snapshot.path("collaborators").forEach(n -> { if (n.isTextual()) collaborators.add(n.textValue()); });
        return TicketNotificationPlanner.plan(new Input(type, receipt, title, reporter, text(snapshot.get("assigneeId")), collaborators,
            text(details.get("previous_assignee_id")), text(snapshot.path("sla").get("priority"))));
    }

    private static Set<String> active(Connection c, String tenant, String ticket, List<Recipient> recipients) throws SQLException {
        var result = new HashSet<String>();
        for (Recipient recipient : recipients) {
            try (var s = statement(c, "SELECT m.role_code,t.reporter_id::text FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id JOIN public.mop_ticket t ON t.tenant_id=m.tenant_id AND t.id=?::uuid WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active FOR SHARE OF m,u")) {
                s.setString(1, ticket); s.setString(2, tenant); s.setString(3, recipient.userId());
                try (var r = s.executeQuery()) {
                    if (!r.next()) continue;
                    String role = r.getString(1);
                    boolean eligible = recipient.audience() == Audience.CUSTOMER ? Capabilities.customer(role) && recipient.userId().equals(r.getString(2)) : Capabilities.internal(role);
                    if (eligible) result.add(recipient.userId());
                }
            }
        }
        return result;
    }

    @Override public Page messages(Access a, int limit, Instant before, String beforeId, boolean unread) {
        cursor(limit, 50, before, beforeId);
        return read(a, "tickets:read", c -> {
            long count;
            try (var s = statement(c, "SELECT count(*) FROM public.mop_notification_message WHERE tenant_id=?::uuid AND recipient_id=?::uuid AND read_at IS NULL")) {
                s.setString(1, a.tenantId()); s.setString(2, a.actorId()); try (var r = s.executeQuery()) { r.next(); count = r.getLong(1); }
            }
            var items = new ArrayList<Message>();
            String sql = MESSAGE + " WHERE m.tenant_id=?::uuid AND m.recipient_id=?::uuid" + (unread ? " AND m.read_at IS NULL" : "") + (before == null ? "" : " AND (m.created_at,m.id)<(?,?::uuid)") + " ORDER BY m.created_at DESC,m.id DESC LIMIT ?";
            try (var s = statement(c, sql)) {
                int n = 1; s.setString(n++, a.tenantId()); s.setString(n++, a.actorId());
                if (before != null) { time(s, n++, before); s.setString(n++, beforeId); }
                s.setInt(n, limit + 1);
                try (var r = s.executeQuery()) { while (r.next()) items.add(message(r)); }
            }
            boolean more = items.size() > limit; if (more) items.remove(items.size() - 1);
            return new Page(items, more, count, now(c));
        });
    }

    @Override public void authorizeRead(Access a, String id) {
        Connection c = platform.idempotentCommandConnection();
        try { member(c, a, "tickets:read", true); own(c, a, uuid(id), false); } catch (SQLException e) { throw unavailable(); }
    }

    @Override public Message markRead(Access a, String id, long expected) {
        if (expected < 0 || expected > 1) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "tickets:read", true); Message old = own(c, a, uuid(id), true);
            if (old.version() != expected) throw new DomainException(409, "VERSION_CONFLICT", "通知已读状态已变化，请刷新后核对");
            if (old.readAt() != null) return old;
            try (var s = statement(c, "UPDATE public.mop_notification_message SET version=1,read_at=date_trunc('milliseconds',clock_timestamp()) WHERE tenant_id=?::uuid AND id=?::uuid AND recipient_id=?::uuid AND version=0")) {
                s.setString(1, a.tenantId()); s.setString(2, id); s.setString(3, a.actorId()); if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "通知状态已变化");
            }
            platform.audit(a.actorId(), a.tenantId(), "notification.read", "notification_message", id, "SUCCEEDED", "EXPLICIT_FIRST_READ_IN_APP_ONLY", a.traceId());
            return own(c, a, id, false);
        } catch (SQLException e) { throw unavailable(); }
    }

    @Override public IntentPage intents(Access a, int limit, Instant before, String beforeId) {
        cursor(limit, 100, before, beforeId);
        return read(a, "ticket_notes:read_internal", c -> {
            var items = new ArrayList<Intent>();
            String sql = "SELECT i.*,t.receipt_no FROM public.mop_notification_intent i JOIN public.mop_ticket t ON t.tenant_id=i.tenant_id AND t.id=i.ticket_id WHERE i.tenant_id=?::uuid" + (before == null ? "" : " AND (i.created_at,i.id)<(?,?::uuid)") + " ORDER BY i.created_at DESC,i.id DESC LIMIT ?";
            try (var s = statement(c, sql)) {
                int n = 1; s.setString(n++, a.tenantId()); if (before != null) { time(s, n++, before); s.setString(n++, beforeId); } s.setInt(n, limit + 1);
                try (var r = s.executeQuery()) {
                    while (r.next()) items.add(new Intent(r.getString("id"), r.getString("ticket_id"), r.getString("receipt_no"), r.getString("event_type"), r.getString("phase"), r.getString("priority"),
                        r.getString("recipient_status"), r.getInt("recipient_count"), list(r.getString("pending_reasons")), r.getString("external_dispatch"), list(r.getString("external_blockers")), instant(r, "created_at")));
                }
            }
            boolean more = items.size() > limit; if (more) items.remove(items.size() - 1);
            return new IntentPage(items, more, now(c));
        });
    }

    private static final String MESSAGE = "SELECT m.*,i.event_type FROM public.mop_notification_message m JOIN public.mop_notification_intent i ON i.tenant_id=m.tenant_id AND i.id=m.intent_id";
    private static Message own(Connection c, Access a, String id, boolean lock) throws SQLException {
        try (var s = statement(c, MESSAGE + " WHERE m.tenant_id=?::uuid AND m.id=?::uuid AND m.recipient_id=?::uuid" + (lock ? " FOR UPDATE OF m" : ""))) {
            s.setString(1, a.tenantId()); s.setString(2, id); s.setString(3, a.actorId());
            try (var r = s.executeQuery()) { if (!r.next()) throw new DomainException(404, "OBJECT_NOT_FOUND", "当前范围没有这条通知"); return message(r); }
        }
    }
    private static Message message(ResultSet r) throws SQLException {
        return new Message(r.getString("id"), r.getString("tenant_id"), r.getString("intent_id"), r.getString("ticket_id"), r.getString("recipient_id"), r.getString("audience"),
            r.getString("event_type"), r.getString("title"), r.getString("body"), instant(r, "created_at"), r.getLong("version"), instant(r, "read_at"));
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T read(Access a, String capability, Work<T> work) {
        platform.requireStandaloneRead();
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try { member(c, a, capability, false); T result = work.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException | Error e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw unavailable(); }
    }
    private static String member(Connection c, Access a, String capability, boolean lock) throws SQLException {
        if (a == null) throw new DomainException(401, "SESSION_EXPIRED", "会话已失效");
        PostgresAuthorization.require(c, a.actorId(), a.tenantId(), a.permissionVersion(), capability);
        try (var s = statement(c, "SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?" + (lock ? " FOR SHARE OF m,u" : ""))) {
            s.setString(1, a.tenantId()); s.setString(2, a.actorId()); s.setLong(3, a.permissionVersion());
            try (var r = s.executeQuery()) { if (!r.next()) throw new DomainException(401, "SESSION_EXPIRED", "成员关系或权限版本已失效"); return r.getString(1); }
        }
    }
    @Override public List<String> readinessProblems() {
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            try (var s = statement(c, "SELECT current_database()='mop_test' AND current_user='mop_test_runtime'"); var r = s.executeQuery()) { if (!r.next() || !r.getBoolean(1)) throw new SQLException(); }
            // The consumer must be able to read committed source facts and current recipient authorization.
            for (String table : List.of("mop_ticket_workflow_outbox", "mop_ticket_workflow_event",
                    "mop_ticket", "mop_membership", "mop_user")) {
                try (var s = statement(c, "SELECT has_table_privilege(current_user,?,'SELECT')")) {
                    s.setString(1, "public." + table);
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !r.getBoolean(1)) {
                            throw new SQLException();
                        }
                    }
                }
            }
            for (String table : TABLES) {
                try (var s = statement(c, "SELECT has_table_privilege(current_user,?,'SELECT') AND has_table_privilege(current_user,?,'INSERT') AND NOT has_table_privilege(current_user,?,'DELETE,TRUNCATE')")) {
                    for (int n = 1; n <= 3; n++) s.setString(n, "public." + table);
                    try (var r = s.executeQuery()) { if (!r.next() || !r.getBoolean(1)) throw new SQLException(); }
                }
            }
            try (var s = statement(c, "SELECT count(*)=3 FROM pg_trigger WHERE tgname IN ('mop_notification_intent_guard','mop_notification_intent_count','mop_notification_message_guard') AND tgenabled='O'"); var r = s.executeQuery()) { if (!r.next() || !r.getBoolean(1)) throw new SQLException(); }
            return List.of();
        } catch (SQLException | RuntimeException e) { return List.of("TICKET_NOTIFICATION_SCHEMA_OR_ACCESS_UNAVAILABLE"); }
    }
    private static void cursor(int limit, int max, Instant before, String beforeId) {
        if (limit < 1 || limit > max || (before == null) != (beforeId == null)) throw invalid();
        if (before != null && (before.getNano() % 1_000_000 != 0 || before.isBefore(MIN) || before.isAfter(MAX))) throw invalid();
        if (beforeId != null) uuid(beforeId);
    }
    private JsonNode tree(String json) { try { return mapper.readTree(json); } catch (Exception e) { throw unavailable(); } }
    private List<String> list(String json) { var out = new ArrayList<String>(); tree(json).forEach(n -> out.add(n.asText())); return out; }
    private String encode(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw unavailable(); } }
    private static String text(JsonNode n) { return n == null || !n.isTextual() || n.textValue().isBlank() ? null : n.textValue(); }
    private static PreparedStatement statement(Connection c, String sql) throws SQLException { var s = c.prepareStatement(sql); s.setQueryTimeout(10); return s; }
    private static void time(PreparedStatement s, int n, Instant t) throws SQLException { s.setObject(n, OffsetDateTime.ofInstant(t, ZoneOffset.UTC)); }
    private static Instant instant(ResultSet r, String key) throws SQLException { var t = r.getObject(key, OffsetDateTime.class); return t == null ? null : t.toInstant(); }
    private static Instant now(Connection c) throws SQLException { try (var s = statement(c, "SELECT date_trunc('milliseconds',clock_timestamp())"); var r = s.executeQuery()) { r.next(); return r.getObject(1, OffsetDateTime.class).toInstant(); } }
    private static String uuid(String v) { try { if (v == null || !UUID.fromString(v).toString().equals(v)) throw new IllegalArgumentException(); return v; } catch (IllegalArgumentException e) { throw invalid(); } }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "通知范围、版本或分页参数无效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "通知持久化结构或依赖不可用，未返回替代结果"); }
}
