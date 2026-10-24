package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.DeliveryReceiptState;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.domain.notification.NotificationRouter;
import cn.mop.domain.notification.NotificationRouter.Priority;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft.LevelPreference;
import cn.mop.domain.notification.QuietHours;
import cn.mop.persistence.NotificationControlStore.Access;
import cn.mop.persistence.NotificationControlStore.Entry;
import cn.mop.persistence.NotificationControlStore.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static cn.mop.persistence.NotificationControlStore.*;

/** Configuration publication and source-to-route transactions; publication does not enable external transport. */
public final class NotificationRoutingService {
    private static final String READ = "ticket_notes:read_internal";
    private static final String WRITE = "configurations:write";
    private static final Set<String> POLICY_KEYS = Set.of("published", "resource_pool_id", "matrix", "aggregate_seconds",
            "precedence", "retry_budget", "retry_delay_seconds");
    private final NotificationControlStore store;
    private final ObjectMapper mapper;

    public NotificationRoutingService(NotificationControlStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper.copy();
    }

    /** Customer decision 2026-10-20: P1/P2 are never delayed by an aggregation window. */
    static Duration aggregateWait(Priority priority, long configuredSeconds) {
        return priority == Priority.P1 || priority == Priority.P2 ? Duration.ZERO : Duration.ofSeconds(configuredSeconds);
    }

    public Entry policy(Access access) {
        return store.transaction(access, READ, connection -> store.current(connection, access.tenantId(), Kind.POLICY,
                policyId(access.tenantId()), false).orElse(null));
    }

    /** Explicit internal publication; null values stay null in drafts and prohibit publication. */
    public Entry savePolicy(Access access, long version, UUID command, JsonNode body) {
        validatePolicy(body);
        UUID id = policyId(access.tenantId());
        String digest = hash(store.encode(body));
        return store.transaction(access, WRITE, connection -> {
            var replay = store.replay(connection, access, Kind.POLICY, id, version, command, "SAVE_POLICY", digest);
            if (replay.isPresent()) {
                return replay.get();
            }
            ObjectNode state = body.deepCopy();
            state.put("request_sha256", digest);
            state.put("external_dispatch_enabled", false);
            state.put("customer_production_approved", false);
            return store.append(connection, access, Kind.POLICY, id, version, command, "SAVE_POLICY", state);
        });
    }

    public Entry preference(Access access) {
        return store.transaction(access, "notification_preferences:read_own", connection -> store.current(connection,
                access.tenantId(), Kind.PREFERENCE, access.actorId(), false).orElse(null));
    }

    /** Publish only the owner's exact stored V26 revision; its draft flags and history are not changed. */
    public Entry publishPreference(Access access, long version, UUID command, UUID draft, long draftVersion, boolean published) {
        String digest = hash(draft + "\n" + draftVersion + "\n" + published);
        return store.transaction(access, "notification_preferences:write_own", connection -> {
            var replay = store.replay(connection, access, Kind.PREFERENCE, access.actorId(), version, command,
                    "PUBLISH_PREFERENCE", digest);
            if (replay.isPresent()) {
                return replay.get();
            }
            try (var query = query(connection, "SELECT r.* FROM public.mop_notification_preference_draft d "
                    + "JOIN public.mop_notification_preference_revision r ON r.tenant_id=d.tenant_id "
                    + "AND r.preference_id=d.id AND r.version=d.version WHERE d.tenant_id=? AND d.id=? "
                    + "AND d.owner_id=? AND d.version=? FOR SHARE OF d")) {
                query.setObject(1, access.tenantId());
                query.setObject(2, draft);
                query.setObject(3, access.actorId());
                query.setLong(4, draftVersion);
                try (var row = query.executeQuery()) {
                    if (!row.next()) {
                        throw missing();
                    }
                    var state = mapper.createObjectNode();
                    state.put("draft_id", draft.toString());
                    state.put("draft_version", draftVersion);
                    state.put("published", published);
                    state.put("level", row.getString("requested_level"));
                    var array = row.getArray("requested_channels");
                    if (array == null) {
                        state.putNull("channels");
                    } else {
                        state.set("channels", mapper.valueToTree((String[]) array.getArray()));
                    }
                    Boolean quiet = (Boolean) row.getObject("quiet_enabled");
                    if (quiet == null) {
                        state.putNull("quiet_enabled");
                    } else {
                        state.put("quiet_enabled", quiet);
                    }
                    state.put("quiet_start", row.getString("quiet_start"));
                    state.put("quiet_end", row.getString("quiet_end"));
                    state.put("timezone", row.getString("timezone"));
                    var requested = new cn.mop.domain.notification.OwnNotificationPreferenceDraft(
                            access.tenantId(), access.actorId(), state.path("level").isNull() ? null
                            : LevelPreference.valueOf(state.path("level").asText()),
                            state.path("channels").isNull() ? null : new ArrayList<>(channels(state.get("channels"))),
                            row.getBoolean("quiet_window_supplied") ? new cn.mop.domain.contact.CustomerContact.QuietWindow(
                                    quiet, time(state.get("quiet_start")), time(state.get("quiet_end"))) : null);
                    if (!requested.fingerprint().equals(row.getString("content_sha256"))) {
                        throw unavailable();
                    }
                    state.put("request_sha256", digest);
                    state.put("external_dispatch_enabled", false);
                    if (published && (state.path("level").isNull() || state.path("channels").isNull()
                            || quiet == null || !row.getBoolean("quiet_window_supplied"))) {
                        throw new DomainException(409, "NOTIFICATION_PREFERENCE_INCOMPLETE", "请先明确级别、渠道及静音设置再发布");
                    }
                    return store.append(connection, access, Kind.PREFERENCE, access.actorId(), version, command,
                            "PUBLISH_PREFERENCE", state);
                }
            }
        });
    }

    /** References resolve to existing scoped channel bindings; contact addresses and secrets are not copied. */
    public Entry saveBinding(Access access, long version, UUID command, JsonNode body) {
        exact(body, Set.of("receiver_id", "channel", "binding_id", "valid_until", "active"));
        UUID receiver = uuid(body.get("receiver_id"));
        UUID binding = uuid(body.get("binding_id"));
        Channel channel = channel(body.get("channel"));
        boolean active = bool(body.get("active"));
        Instant until = instant(body.get("valid_until"));
        UUID id = bindingId(access.tenantId(), receiver, channel);
        String digest = hash(store.encode(body));
        return store.transaction(access, WRITE, connection -> {
            var replay = store.replay(connection, access, Kind.BINDING, id, version, command, "SAVE_BINDING", digest);
            if (replay.isPresent()) {
                return replay.get();
            }
            requireReceiver(connection, access.tenantId(), receiver);
            if (active && (!until.isAfter(Instant.now()) || !channelBinding(connection, access.tenantId(), receiver, channel, binding))) {
                throw new DomainException(409, "NOTIFICATION_BINDING_UNVERIFIED", "渠道绑定不存在、停用或过期；未授权派发");
            }
            ObjectNode state = body.deepCopy();
            state.put("request_sha256", digest);
            state.put("external_dispatch_enabled", false);
            return store.append(connection, access, Kind.BINDING, id, version, command, "SAVE_BINDING", state);
        });
    }

    public Entry detail(Access access, Kind kind, UUID id) {
        return store.transaction(access, READ, connection -> store.current(connection, access.tenantId(), kind, id, false)
                .orElseThrow(NotificationRoutingService::missing));
    }

    public Entry binding(Access access, UUID receiver, Channel channel) {
        return store.transaction(access, READ, connection -> {
            requireReceiver(connection, access.tenantId(), receiver);
            return store.current(connection, access.tenantId(), Kind.BINDING,
                    bindingId(access.tenantId(), receiver, channel), false).orElse(null);
        });
    }

    /** Discover committed in-app source records; event and recipient facts cannot be provided by a browser. */
    public int discover(Access access, int limit) {
        if (limit < 1 || limit > 100) {
            throw invalid();
        }
        return store.transaction(access, WRITE, connection -> {
            try (var lock = query(connection, "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "notification-routing-discovery|" + access.tenantId());
                try (var row = lock.executeQuery()) {
                    row.next();
                    if (!row.getBoolean(1)) {
                        return 0;
                    }
                }
            }
            try (var query = query(connection, "SELECT m.id,m.intent_id,m.recipient_id,m.ticket_id,i.priority,i.phase,o.created_at "
                    + "FROM public.mop_notification_message m JOIN public.mop_notification_intent i ON i.tenant_id=m.tenant_id AND i.id=m.intent_id "
                    + "JOIN public.mop_ticket_workflow_outbox o ON o.tenant_id=i.tenant_id AND o.id=i.outbox_id "
                    + "WHERE m.tenant_id=? AND NOT EXISTS(SELECT 1 FROM public.mop_notification_control c WHERE c.tenant_id=m.tenant_id "
                    + "AND c.kind='REQUEST' AND c.object_id=m.id AND c.version=0) ORDER BY m.created_at,m.id LIMIT ?")) {
                query.setObject(1, access.tenantId());
                query.setInt(2, limit);
                int count = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        UUID message = rows.getObject("id", UUID.class);
                        var state = mapper.createObjectNode();
                        state.put("message_id", message.toString());
                        state.put("intent_id", rows.getString("intent_id"));
                        state.put("receiver_id", rows.getString("recipient_id"));
                        state.put("ticket_id", rows.getString("ticket_id"));
                        state.put("priority", rows.getString("priority"));
                        state.put("phase", rows.getString("phase"));
                        state.put("occurred_at", rows.getObject("created_at", OffsetDateTime.class).toInstant().toString());
                        state.put("status", "WAITING_CONFIGURATION");
                        state.put("reason", "PUBLISHED_ROUTING_CONFIGURATION_REQUIRED");
                        state.put("request_sha256", hash(message.toString()));
                        store.append(connection, access, Kind.REQUEST, message, -1,
                                identity("notification-discovery|" + access.tenantId() + "|" + message), "DISCOVER", state);
                        count++;
                    }
                }
                return count;
            }
        });
    }

    /** Plan with the current published matrix, owner's publication, bindings and receipt history in one transaction. */
    public Entry plan(Access access, UUID requestId, long expected, UUID command, Instant now) {
        String digest = hash(requestId + "\n" + expected);
        return store.transaction(access, WRITE, connection -> {
            var replay = store.replay(connection, access, Kind.REQUEST, requestId, expected, command, "PLAN", digest);
            if (replay.isPresent()) {
                return replay.get();
            }
            var request = store.current(connection, access.tenantId(), Kind.REQUEST, requestId, true)
                    .orElseThrow(NotificationRoutingService::missing);
            if (request.version() != expected) {
                throw conflict();
            }
            ObjectNode state = (ObjectNode) request.state();
            if ("CANCELLED".equals(state.path("status").asText())) {
                return request;
            }
            UUID receiver = uuid(state.get("receiver_id"));
            requireReceiver(connection, access.tenantId(), receiver);
            var policy = store.current(connection, access.tenantId(), Kind.POLICY, policyId(access.tenantId()), true).orElse(null);
            var preference = store.current(connection, access.tenantId(), Kind.PREFERENCE, receiver, true).orElse(null);
            if (policy == null || !policy.state().path("published").asBoolean()
                    || preference == null || !preference.state().path("published").asBoolean() || state.path("priority").isNull()) {
                suspendRoutes(connection, access, requestId, Set.of(), "PUBLISHED_ROUTING_CONFIGURATION_REQUIRED");
                state.put("status", "WAITING_CONFIGURATION");
                state.put("reason", "PUBLISHED_ROUTING_CONFIGURATION_REQUIRED");
                if (state.equals(request.state())) {
                    return request;
                }
                state.put("request_sha256", digest);
                return store.append(connection, access, Kind.REQUEST, requestId, expected, command, "PLAN", state);
            }
            JsonNode values = policy.state();
            Priority priority = Priority.valueOf(state.path("priority").asText());
            var channels = channels(values.path("matrix").get(priority.name()));
            suspendRoutes(connection, access, requestId, channels, "FILTERED_CHANNEL");
            var matrix = new NotificationRouter.Matrix(access.tenantId(), policy.commandId().toString(), priority, channels,
                    aggregateWait(priority, values.path("aggregate_seconds").asLong()), Duration.ofMinutes(5),
                    NotificationRouter.Precedence.valueOf(values.path("precedence").asText()));
            JsonNode preferences = preference.state();
            var quiet = new QuietHours.Policy(preference.commandId().toString(), preferences.path("quiet_enabled").asBoolean(),
                    ZoneId.of(preferences.path("timezone").asText()), time(preferences.get("quiet_start")), time(preferences.get("quiet_end")));
            var effective = new NotificationRouter.Preference(access.tenantId(), receiver, preference.commandId().toString(),
                    LevelPreference.valueOf(preferences.path("level").asText()), channels(preferences.get("channels")), quiet);
            var bindings = new ArrayList<NotificationRouter.Binding>();
            for (Channel channel : channels) {
                var binding = store.current(connection, access.tenantId(), Kind.BINDING,
                        bindingId(access.tenantId(), receiver, channel), true).orElse(null);
                if (binding != null && binding.state().path("active").asBoolean()) {
                    UUID reference = uuid(binding.state().get("binding_id"));
                    if (channelBinding(connection, access.tenantId(), receiver, channel, reference)) {
                        bindings.add(new NotificationRouter.Binding(access.tenantId(), receiver, channel,
                                reference.toString(), instant(binding.state().get("valid_until"))));
                    }
                }
            }
            var event = new NotificationRouter.Event(access.tenantId(), uuid(values.get("resource_pool_id")),
                    uuid(state.get("intent_id")), priority, state.path("phase").asText(), instant(state.get("occurred_at")));
            var plan = NotificationRouter.plan(event, receiver, matrix, effective, bindings, history(connection, event, receiver), now);
            for (var decision : plan.decisions()) {
                UUID routeId = identity("notification-route|" + access.tenantId() + "|" + requestId + "|" + decision.channel());
                var existing = store.current(connection, access.tenantId(), Kind.ROUTE, routeId, true).orElse(null);
                if (existing != null && (existing.state().path("attempt").asInt() > 0
                        || "CANCELLED".equals(existing.state().path("status").asText()))) {
                    continue;
                }
                ObjectNode route = state.deepCopy();
                route.remove("route_count");
                route.put("channel", decision.channel().name());
                route.put("resource_pool_id", event.poolId().toString());
                route.put("policy_version", policy.version());
                route.put("preference_version", preference.version());
                route.put("binding_id", decision.bindingReference());
                route.put("not_before", decision.notBefore() == null ? null : decision.notBefore().toString());
                if (existing != null && decision.notBefore() != null
                        && !existing.state().path("not_before").isNull()
                        && existing.state().path("policy_version").asLong() == policy.version()
                        && existing.state().path("preference_version").asLong() == preference.version()
                        && "QUEUED".equals(existing.state().path("status").asText())) {
                    route.set("not_before", existing.state().get("not_before"));
                }
                route.put("status", decision.outcome() == NotificationRouter.Outcome.PLANNED ? "QUEUED" : "WAITING_CONFIGURATION");
                route.put("decision", decision.outcome().name());
                route.put("reason", decision.outcome().name());
                route.put("attempt", 0);
                route.put("external_dispatch_enabled", false);
                route.put("request_sha256", hash(routeId.toString()));
                if (existing == null || !store.encode(existing.state()).equals(store.encode(route))) {
                    long previous = existing == null ? -1 : existing.version();
                    store.append(connection, access, Kind.ROUTE, routeId, previous,
                            identity("notification-route-command|" + routeId + "|" + previous + "|" + hash(store.encode(route))), "ROUTE", route);
                }
            }
            state.put("status", "ROUTED");
            state.put("reason", plan.outcome().name());
            state.put("route_count", plan.decisions().size());
            if (state.equals(request.state())) {
                return request;
            }
            state.put("request_sha256", digest);
            return store.append(connection, access, Kind.REQUEST, requestId, expected, command, "PLAN", state);
        });
    }

    private List<NotificationRouter.Prior> history(Connection connection, NotificationRouter.Event event, UUID receiver)
            throws SQLException {
        var history = new ArrayList<NotificationRouter.Prior>();
        try (var roots = query(connection, "SELECT delivery_id,channel FROM public.mop_delivery_receipt_journal "
                + "WHERE tenant_id=? AND resource_pool_id=? AND event_id=? AND receiver_id=? AND stage=? AND version=0")) {
            roots.setObject(1, event.tenantId());
            roots.setObject(2, event.poolId());
            roots.setObject(3, event.eventId());
            roots.setObject(4, receiver);
            roots.setString(5, event.phase());
            try (var rows = roots.executeQuery()) {
                while (rows.next()) {
                    UUID delivery = rows.getObject("delivery_id", UUID.class);
                    var key = new DeliveryReceiptState.Deduplication(event.poolId(), event.eventId(),
                            Channel.valueOf(rows.getString("channel")), receiver, event.phase());
                    var scope = new DeliveryReceiptSnapshotCodec.Scope(event.tenantId(), delivery, key);
                    try (var records = query(connection, "SELECT version,encoded_record,record_sha256 FROM public.mop_delivery_receipt_journal "
                            + "WHERE tenant_id=? AND delivery_id=? ORDER BY version LIMIT 12002")) {
                        records.setObject(1, event.tenantId());
                        records.setObject(2, delivery);
                        try (var entries = records.executeQuery()) {
                            if (!entries.next() || entries.getLong("version") != 0) {
                                throw unavailable();
                            }
                            byte[] root = entries.getBytes("encoded_record");
                            String rootHash = entries.getString("record_sha256");
                            String head = rootHash;
                            var transitions = new ArrayList<DeliveryReceiptJournal.Entry>();
                            while (entries.next()) {
                                if (entries.getLong("version") != transitions.size() + 1L) {
                                    throw unavailable();
                                }
                                head = entries.getString("record_sha256");
                                transitions.add(new DeliveryReceiptJournal.Entry(entries.getBytes("encoded_record"), head));
                            }
                            var receipt = DeliveryReceiptJournal.restore(root, rootHash, scope, transitions, transitions.size(), head).state();
                            boolean unresolved = receipt.status() == DeliveryReceiptState.Status.UNKNOWN
                                    || receipt.status() == DeliveryReceiptState.Status.ACCEPTED;
                            for (var attempt : receipt.attempts()) {
                                history.add(new NotificationRouter.Prior(event.tenantId(), key, attempt.startedAt(), unresolved));
                            }
                        }
                    }
                }
            }
        }
        return history;
    }

    private void suspendRoutes(Connection connection, Access access, UUID request, Set<Channel> retained, String reason)
            throws SQLException {
        for (Channel channel : Channel.values()) {
            if (retained.contains(channel)) {
                continue;
            }
            UUID id = identity("notification-route|" + access.tenantId() + "|" + request + "|" + channel);
            var old = store.current(connection, access.tenantId(), Kind.ROUTE, id, true).orElse(null);
            if (old == null || old.state().path("attempt").asInt() > 0 || "CANCELLED".equals(old.state().path("status").asText())) {
                continue;
            }
            ObjectNode state = (ObjectNode) old.state();
            state.put("status", "WAITING_CONFIGURATION");
            state.put("reason", reason);
            state.put("decision", reason);
            state.putNull("not_before");
            if (!state.equals(old.state())) {
                String hash = hash(store.encode(state));
                store.append(connection, access, Kind.ROUTE, id, old.version(),
                        identity("notification-route-suspend|" + id + "|" + old.version() + "|" + hash), "ROUTE_SUSPEND", state);
            }
        }
    }

    public static void validatePolicy(JsonNode body) {
        exact(body, POLICY_KEYS);
        boolean published = bool(body.get("published"));
        nullableNumber(body.get("aggregate_seconds"), 0, 86400);
        nullableNumber(body.get("retry_budget"), 0, 100);
        nullableNumber(body.get("retry_delay_seconds"), 1, 86400);
        if (!body.path("resource_pool_id").isNull()) {
            uuid(body.get("resource_pool_id"));
        }
        if (!body.path("precedence").isNull()
                && !"MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS".equals(body.path("precedence").asText())) {
            throw invalid();
        }
        if (!body.path("matrix").isNull()) {
            exact(body.get("matrix"), Set.of("P1", "P2", "P3", "P4"));
            body.get("matrix").elements().forEachRemaining(NotificationRoutingService::channels);
        }
        if (published && POLICY_KEYS.stream().anyMatch(key -> body.get(key).isNull())) {
            throw new DomainException(409, "NOTIFICATION_POLICY_INCOMPLETE", "待确认配置仍为空，不能发布路由规则");
        }
    }

    static UUID policyId(UUID tenant) {
        return identity("notification-policy|" + tenant);
    }

    static UUID bindingId(UUID tenant, UUID receiver, Channel channel) {
        return identity("notification-binding|" + tenant + "|" + receiver + "|" + channel);
    }

    static boolean channelBinding(Connection connection, UUID tenant, UUID receiver, Channel channel, UUID binding) throws SQLException {
        String table = channel == Channel.EMAIL ? "mop_email_binding" : channel == Channel.WECHAT_WORK ? "mop_wecom_binding" : null;
        if (table == null) {
            return false;
        }
        try (var query = query(connection, "SELECT 1 FROM public." + table + " WHERE tenant_id=? AND user_id=? AND id=? AND active FOR SHARE")) {
            query.setObject(1, tenant);
            query.setObject(2, receiver);
            query.setObject(3, binding);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    static void requireReceiver(Connection connection, UUID tenant, UUID receiver) throws SQLException {
        try (var query = query(connection, "SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id "
                + "WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active FOR SHARE OF m,u")) {
            query.setObject(1, tenant);
            query.setObject(2, receiver);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw missing();
                }
            }
        }
    }

    static void exact(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject()) {
            throw invalid();
        }
        var actual = new HashSet<String>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw invalid();
        }
    }

    static UUID uuid(JsonNode value) {
        try {
            UUID id = UUID.fromString(value.textValue());
            if (!id.toString().equals(value.textValue())) {
                throw invalid();
            }
            return id;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.textValue());
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    static Channel channel(JsonNode value) {
        try {
            return Channel.valueOf(value.textValue());
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    static Set<Channel> channels(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > Channel.values().length) {
            throw invalid();
        }
        var result = EnumSet.noneOf(Channel.class);
        value.forEach(item -> {
            if (!result.add(channel(item))) {
                throw invalid();
            }
        });
        return result;
    }

    static boolean bool(JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    private static LocalTime time(JsonNode value) {
        return value == null || value.isNull() ? null : LocalTime.parse(value.asText());
    }

    private static void nullableNumber(JsonNode value, int minimum, int maximum) {
        if (value == null || !value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum)) {
            throw invalid();
        }
    }

    private static DomainException missing() {
        return new DomainException(404, "NOT_FOUND", "当前授权范围未找到通知对象");
    }
}
