package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.notification.TicketNotificationRepository;
import cn.mop.domain.notification.TicketNotificationRepository.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Recipient-owned in-app ticket notifications and the internal intent ledger. No external channel is invoked. */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class TicketNotificationController {
    private final PlatformRepository platform; private final TicketNotificationRepository notifications;
    public TicketNotificationController(PlatformRepository platform, TicketNotificationRepository notifications) { this.platform = platform; this.notifications = notifications; }
    @ModelAttribute void headers(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer"); }

    @GetMapping("/my/notifications") Object list(HttpServletRequest r) {
        Access a = access(r, "tickets:read", false);
        var q = query(r, Set.of("page_size", "before_created_at", "before_id", "unread_only"));
        int limit = size(q.get("page_size"), 20, 50); String unread = q.get("unread_only");
        if (unread != null && !Set.of("true", "false").contains(unread)) throw invalid();
        Instant before = cursorTime(q.get("before_created_at"), q.get("before_id"));
        var page = notifications.messages(a, limit, before, q.get("before_id"), "true".equals(unread));
        var items = new ArrayList<Map<String, Object>>(); for (var m : page.items()) items.add(dto(m, a, page.observedAt()));
        var last = page.hasMore() ? page.items().get(page.items().size() - 1) : null;
        return success(map("items", items, "has_more", page.hasMore(), "next_before_created_at", last == null ? null : last.createdAt().toString(), "next_before_id", last == null ? null : last.id(),
            "unread_count", page.unreadCount(), "observed_at", page.observedAt().toString(), "external_dispatch", "NOT_CONFIGURED", "source", "postgresql"), r);
    }

    @PostMapping(value = "/my/notifications/{id}/read", consumes = MediaType.APPLICATION_JSON_VALUE) Object read(@PathVariable String id, HttpServletRequest r) {
        Access a = access(r, "tickets:read", false); uuid(id); if (r.getQueryString() != null) throw invalid();
        var b = AssetExportJobController.body(r);
        if (!b.isObject() || b.size() != 1 || !b.path("expected_version").isIntegralNumber() || !b.path("expected_version").canConvertToLong() || b.path("expected_version").longValue() < 0 || b.path("expected_version").longValue() > 1) throw invalid();
        long version = b.path("expected_version").longValue(); String key = r.getHeader("Idempotency-Key"); if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, digest("TICKET_NOTIFICATION_READ_V1\n" + id + "\n" + version), a.permissionVersion(),
            () -> notifications.authorizeRead(a, id), () -> {
                var m = notifications.markRead(a, id, version);
                if (!m.id().equals(id) || !m.tenantId().equals(a.tenantId()) || !m.recipientId().equals(a.actorId()) || m.version() != 1 || m.readAt() == null) throw unavailable();
                return map("id", m.id(), "version", m.version(), "read_at", m.readAt().toString());
            });
        return success(result, r);
    }

    @GetMapping("/notification-intents") Object intents(HttpServletRequest r) {
        Access a = access(r, "ticket_notes:read_internal", true);
        var q = query(r, Set.of("page_size", "before_created_at", "before_id"));
        int limit = size(q.get("page_size"), 25, 100);
        Instant before = cursorTime(q.get("before_created_at"), q.get("before_id"));
        var page = notifications.intents(a, limit, before, q.get("before_id"));
        var items = new ArrayList<Map<String, Object>>();
        for (var i : page.items()) items.add(map("id", i.id(), "ticket_id", i.ticketId(), "receipt_no", i.receiptNo(), "event_type", i.eventType(), "phase", i.phase(), "priority", i.priority(),
            "recipient_status", i.recipientStatus(), "recipient_count", i.recipientCount(), "pending_reasons", i.pendingReasons(), "external_dispatch", i.externalDispatch(),
            "external_blockers", i.externalBlockers(), "created_at", i.createdAt().toString()));
        var last = page.hasMore() ? page.items().get(page.items().size() - 1) : null;
        return success(map("items", items, "has_more", page.hasMore(), "next_before_created_at", last == null ? null : last.createdAt().toString(), "next_before_id", last == null ? null : last.id(),
            "observed_at", page.observedAt().toString(), "source", "postgresql"), r);
    }

    private Map<String, Object> dto(Message m, Access a, Instant now) {
        if (m == null || !m.tenantId().equals(a.tenantId()) || !m.recipientId().equals(a.actorId()) || m.createdAt().isAfter(now) || m.version() < 0 || m.version() > 1 || (m.version() == 0) != (m.readAt() == null)) throw unavailable();
        return map("id", m.id(), "ticket_id", m.ticketId(), "audience", m.audience(), "event_type", m.eventType(), "title", m.title(), "body", m.body(),
            "created_at", m.createdAt().toString(), "version", m.version(), "read_at", m.readAt() == null ? null : m.readAt().toString(), "channel", "IN_APP", "source", "postgresql");
    }
    private Access access(HttpServletRequest r, String capability, boolean internalOnly) {
        if (r.getUserPrincipal() == null || r.getSession(false) == null) throw expired();
        Object t = r.getSession(false).getAttribute("mop.tenant_id"); if (!(t instanceof String tenant) || tenant.isBlank()) throw expired();
        String actor = r.getUserPrincipal().getName();
        var m = platform.membership(actor, tenant).orElseThrow(TicketNotificationController::expired); if (!m.active() || m.permissionVersion() < 1) throw expired();
        if (!m.capabilities().contains(capability) || internalOnly && !Capabilities.internal(m.role())) throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份没有该通知权限");
        return new Access(tenant, actor, m.permissionVersion(), trace(r));
    }
    private static Map<String, String> query(HttpServletRequest r, Set<String> keys) {
        var result = new LinkedHashMap<String, String>();
        r.getParameterMap().forEach((k, v) -> { if (!keys.contains(k) || v.length != 1 || v[0].isBlank() || v[0].length() > 64) throw invalid(); result.put(k, v[0]); });
        return result;
    }
    private static int size(String raw, int fallback, int max) {
        if (raw == null) return fallback; if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
        int n = Integer.parseInt(raw); if (n > max) throw invalid(); return n;
    }
    private static Instant cursorTime(String before, String id) {
        if ((before == null) != (id == null)) throw invalid(); if (before == null) return null;
        try { Instant time = Instant.parse(before); if (!before.equals(time.toString()) || time.getNano() % 1_000_000 != 0) throw invalid(); uuid(id); return time; }
        catch (RuntimeException e) { throw invalid(); }
    }
    private static String digest(String value) { try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static void uuid(String id) { try { if (id == null || !UUID.fromString(id).toString().equals(id)) throw invalid(); } catch (IllegalArgumentException e) { throw invalid(); } }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "通知请求参数无效"); }
    private static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前成员会话已失效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "通知内容或授权范围未通过核对"); }
}
