package cn.mop.persistence;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

/** Reuses a recent formal workflow under the signal and workflow locks, without changing its SLA snapshot. */
final class MonitoringTicketDedupe {
    static final String CONFIGURATION_KEY = "ticket.default_dedupe_window_sec";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_SECONDS = 9_007_199_254_740_990L;

    record Policy(long seconds, String reference) {}
    record Match(String eventId, String ticketId, Policy policy) {}

    private MonitoringTicketDedupe() {}

    /** Caller holds the tenant/asset/rule fingerprint lock until commit, including retry and manual paths. */
    static Optional<Match> find(Connection c, String tenant, String eventId, String fingerprint,
                                Instant problemAt, Instant observedAt) throws SQLException {
        Policy policy = policy(c, tenant, observedAt);
        // Select one chronological formal-workflow predecessor. Never fall through to an older
        // ticket when that predecessor's ticket has already closed.
        String sql = "SELECT e.id::text,e.ticket_id::text FROM public.mop_monitoring_event e "
            + "JOIN public.mop_ticket_workflow_authority a ON a.tenant_id=e.tenant_id AND a.ticket_id=e.ticket_id "
            + "WHERE e.tenant_id=?::uuid AND e.fingerprint=? AND e.id<>?::uuid AND a.model='WORKFLOW_V33' "
            + "AND e.problem_at<=? AND extract(epoch FROM (?::timestamptz-e.problem_at))<=? "
            + "ORDER BY e.problem_at DESC,e.id DESC LIMIT 1";
        String previousEvent;
        String ticket;
        try (var statement = statement(c, sql)) {
            statement.setString(1, tenant);
            statement.setString(2, fingerprint);
            statement.setString(3, eventId);
            instant(statement, 4, problemAt);
            instant(statement, 5, problemAt);
            statement.setLong(6, policy.seconds());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                previousEvent = rows.getString(1);
                ticket = rows.getString(2);
            }
        }
        lock(c, "mop.ticket.workflow|" + tenant + "|" + ticket);
        // Authority is immutable once claimed. Unactivated registrations and V42 lifecycles are not
        // candidates because only V33 currently consumes linked monitoring events for SLA/closure.
        try (var statement = statement(c,
                "SELECT EXISTS(SELECT 1 FROM public.mop_ticket_lifecycle_revision "
                    + "WHERE tenant_id=?::uuid AND ticket_id=?::uuid),"
                    + "(SELECT snapshot->>'state' FROM public.mop_ticket_workflow_event "
                    + "WHERE tenant_id=?::uuid AND ticket_id=?::uuid ORDER BY version DESC LIMIT 1)")) {
            statement.setString(1, tenant);
            statement.setString(2, ticket);
            statement.setString(3, tenant);
            statement.setString(4, ticket);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Missing ticket authority result");
                }
                if (rows.getBoolean(1)) {
                    return Optional.empty();
                }
                String state = rows.getString(2);
                if (state != null && Set.of("CLOSED", "CANCELLED").contains(state)) {
                    return Optional.empty();
                }
                if (state == null || !Set.of("OPEN", "ACCEPTED", "IN_PROGRESS", "RESOLVED").contains(state)) {
                    throw new SQLException("Unknown ticket workflow state");
                }
            }
        }
        return Optional.of(new Match(previousEvent, ticket, policy));
    }

    /** Explicit disable/expiry never falls back to the contract default or silently creates another ticket. */
    static Policy policy(Connection c, String tenant, Instant observedAt) throws SQLException {
        lock(c, "mop.runtime.configuration|" + tenant + "|" + CONFIGURATION_KEY);
        try (var statement = statement(c,
                "SELECT id::text,version,action,value_json::text,expires_at "
                    + "FROM public.mop_runtime_configuration_release "
                    + "WHERE tenant_id=?::uuid AND configuration_key=? AND effective_at<=? "
                    + "ORDER BY version DESC LIMIT 1")) {
            statement.setString(1, tenant);
            statement.setString(2, CONFIGURATION_KEY);
            instant(statement, 3, observedAt);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    String action = rows.getString("action");
                    OffsetDateTime expires = rows.getObject("expires_at", OffsetDateTime.class);
                    if (!Set.of("PUBLISH", "ROLLBACK").contains(action)
                            || expires != null && !observedAt.isBefore(expires.toInstant())) {
                        throw pending();
                    }
                    return new Policy(seconds(parse(rows.getString("value_json"))),
                        "release:" + rows.getString("id") + ":" + rows.getLong("version"));
                }
            }
        }
        try (var statement = statement(c,
                "SELECT registry_json::text FROM public.mop_configuration WHERE key=?")) {
            statement.setString(1, CONFIGURATION_KEY);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw pending();
                }
                JsonNode registry = parse(rows.getString(1));
                if (!CONFIGURATION_KEY.equals(registry.path("key").asText())
                        || !"integer".equals(registry.path("type").asText())
                        || !"configurable_default".equals(registry.path("contract_value_kind").asText())) {
                    throw pending();
                }
                return new Policy(seconds(registry.get("value")), "contract-default:" + CONFIGURATION_KEY);
            }
        }
    }

    private static long seconds(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1 || value.longValue() > MAX_SECONDS) {
            throw pending();
        }
        return value.longValue();
    }

    private static JsonNode parse(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed == null) {
                throw pending();
            }
            return parsed;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw pending();
        }
    }

    private static void lock(Connection c, String key) throws SQLException {
        try (var statement = statement(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            statement.setString(1, key);
            statement.execute();
        }
    }

    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        var statement = c.prepareStatement(sql);
        statement.setQueryTimeout(10);
        return statement;
    }

    private static void instant(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private static DomainException pending() {
        return new DomainException(422, "CONFIG_PENDING", "工单去重窗口未生效、已停用、已到期或无效；本次未新建或关联工单");
    }
}
