package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.domain.sla.ServiceTierRepository.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Tenant service tier snapshot and append-only change ledger (M2-10 / M5-8 / M5-20). */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class ServiceTierController {
    private static final long MAX = 9007199254740990L;
    private static final String TARGET_KEY = "sla.tier_availability_target";
    private final PlatformRepository platform;
    private final ServiceTierRepository serviceTiers;
    private final RuntimeConfigurationRepository runtime;
    private final RuntimeConfigurationCatalog catalog;
    private final ObjectMapper mapper;

    public ServiceTierController(PlatformRepository platform, ServiceTierRepository serviceTiers,
            RuntimeConfigurationRepository runtime, RuntimeConfigurationCatalog catalog, ObjectMapper mapper) {
        this.platform = platform;
        this.serviceTiers = serviceTiers;
        this.runtime = runtime;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @ModelAttribute
    void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @GetMapping("/service-tiers")
    Object get(HttpServletRequest r) {
        Access a = access(r, "sla_scenarios:read", true, false);
        var q = query(r, Set.of("page_size", "before_version"));
        int limit = size(q.get("page_size"), 25, 100);
        Long before = q.containsKey("before_version") ? positiveVersion(q.get("before_version")) : null;
        ChangePage page = serviceTiers.history(a, limit, before);
        var items = new ArrayList<Map<String, Object>>();
        for (Change c : page.changes()) items.add(changeDto(c, a.tenantId()));
        return success(map(
            "tenant_id", a.tenantId(),
            "current", page.current().map(s -> snapshotDto(s, a.tenantId())).orElse(null),
            "items", items,
            "has_more", page.hasMore(),
            "next_before_version", page.nextBeforeVersion(),
            "observed_at", page.observedAt().toString(),
            "customer_approval_status", "UNVERIFIED",
            "in_flight_ticket_retroactive", false,
            "source", "postgresql"
        ), r);
    }

    @PostMapping(value = "/service-tiers/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object change(HttpServletRequest r) {
        Access a = access(r, "configurations:write", true, true);
        if (r.getQueryString() != null) throw invalid();
        JsonNode b = AssetExportJobController.body(r);
        fields(b, Set.of("expected_version", "to_tier", "effective_mode", "contract_device_count", "billing_cycle", "monthly_fee_fen", "reason"));
        long expected = version(b.get("expected_version"));
        Tier toTier = parseEnum(b.get("to_tier"), Tier.class);
        EffectiveMode mode = parseEnum(b.get("effective_mode"), EffectiveMode.class);
        Integer devices = nullableInt(b.get("contract_device_count"), 0, 1_000_000);
        BillingCycle cycle = b.get("billing_cycle").isNull() ? null : parseEnum(b.get("billing_cycle"), BillingCycle.class);
        Long fee = nullableLong(b.get("monthly_fee_fen"), 0, MAX);
        String reason = text(b.get("reason"), 1000);
        Command cmd = new Command(expected, toTier, mode, devices, cycle, fee, reason);
        String key = r.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid();
        String payloadDigest = digest("SERVICE_TIER_CHANGE_V1\n" + expected + "\n" + toTier.name() + "\n" + mode.name()
            + "\n" + devices + "\n" + (cycle == null ? "null" : cycle.name()) + "\n" + fee + "\n" + reason);
        Object result = platform.idempotentAuthorized(a.actorId(), a.tenantId(), "POST " + route(r), key, payloadDigest, a.permissionVersion(),
            () -> serviceTiers.authorizeWrite(a),
            () -> {
                BigDecimal target = resolveAvailabilityTarget(a, cmd.toTier());
                Snapshot updated = serviceTiers.applyChange(a, cmd, target);
                return snapshotDto(updated, a.tenantId());
            });
        return success(result, r);
    }

    private BigDecimal resolveAvailabilityTarget(Access a, Tier tier) {
        if (runtime == null || catalog == null) return null;
        var row = runtime.read(new RuntimeConfigurationRepository.Access(a.tenantId(), a.actorId(), a.permissionVersion(), a.traceId()), TARGET_KEY);
        if (row == null || !TARGET_KEY.equals(row.get("configuration_key")) || !a.tenantId().equals(row.get("tenant_id")) || !"postgresql".equals(row.get("source")))
            throw unavailable();
        String status = String.valueOf(row.get("status"));
        String definition = catalog.hash(TARGET_KEY);
        Map<?, ?> release = row.get("selected_release") instanceof Map<?, ?> m ? m : null;
        Object value = null;
        if ("ACTIVE".equals(status)) {
            if (release != null && definition.equals(release.get("definition_hash"))) {
                try {
                    catalog.validate(TARGET_KEY, mapper.valueToTree(row.get("effective_value")));
                    value = row.get("effective_value");
                } catch (RuntimeException ignored) {
                    value = null;
                }
            }
        } else if (Set.of("UNCONFIGURED", "SCHEDULED").contains(status) && release == null) {
            var entry = catalog.entry(TARGET_KEY);
            if ("configurable_default".equals(entry.get("contract_value_kind"))) value = entry.get("value");
        } else if (!Set.of("DISABLED", "EXPIRED").contains(status) || release == null) {
            throw unavailable();
        }
        if (value == null) return null;
        String label = switch (tier) { case BASIC -> "基础"; case STANDARD -> "标准"; case ADVANCED -> "进阶"; };
        JsonNode node = mapper.valueToTree(value).get(label);
        if (node == null || !node.isNumber()) return null;
        try {
            BigDecimal target = node.decimalValue();
            ServiceTierRepository.validateMetrics(target, null, null);
            return target;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> snapshotDto(Snapshot s, String expectedTenant) {
        if (s == null || !s.tenantId().equals(expectedTenant)) throw unavailable();
        return map(
            "tenant_id", s.tenantId(),
            "version", s.version(),
            "tier", s.tier().name(),
            "availability_target", s.availabilityTarget(),
            "contract_device_count", s.contractDeviceCount(),
            "billing_cycle", s.billingCycle() == null ? null : s.billingCycle().name(),
            "monthly_fee_fen", s.monthlyFeeFen(),
            "effective_mode", s.effectiveMode().name(),
            "effective_from", s.effectiveFrom().toString(),
            "updated_by", s.updatedBy(),
            "updated_at", s.updatedAt().toString(),
            "customer_approval_status", s.customerApprovalStatus()
        );
    }

    private static Map<String, Object> changeDto(Change c, String expectedTenant) {
        if (c == null || !c.tenantId().equals(expectedTenant)) throw unavailable();
        return map(
            "id", c.id(),
            "tenant_id", c.tenantId(),
            "version", c.version(),
            "previous_version", c.previousVersion(),
            "from_tier", c.fromTier() == null ? null : c.fromTier().name(),
            "to_tier", c.toTier().name(),
            "availability_target", c.availabilityTarget(),
            "contract_device_count", c.contractDeviceCount(),
            "billing_cycle", c.billingCycle() == null ? null : c.billingCycle().name(),
            "monthly_fee_fen", c.monthlyFeeFen(),
            "effective_mode", c.effectiveMode().name(),
            "effective_from", c.effectiveFrom().toString(),
            "reason", c.reason(),
            "approved_by", c.approvedBy(),
            "changed_at", c.changedAt().toString(),
            "customer_approval_status", c.customerApprovalStatus()
        );
    }

    private Access access(HttpServletRequest r, String capability, boolean internalOnly, boolean padminOnly) {
        if (r.getUserPrincipal() == null || r.getSession(false) == null) throw expired();
        Object t = r.getSession(false).getAttribute("mop.tenant_id");
        if (!(t instanceof String tenant) || tenant.isBlank()) throw expired();
        String actor = r.getUserPrincipal().getName();
        var m = platform.membership(actor, tenant).orElseThrow(ServiceTierController::expired);
        if (!m.active() || m.permissionVersion() < 1) throw expired();
        if (!m.capabilities().contains(capability)
                || internalOnly && !Capabilities.internal(m.role())
                || padminOnly && !"padmin".equals(m.role()))
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无此服务档位权限");
        return new Access(tenant, actor, m.permissionVersion(), trace(r));
    }

    private static Map<String, String> query(HttpServletRequest r, Set<String> keys) {
        var result = new LinkedHashMap<String, String>();
        r.getParameterMap().forEach((k, v) -> {
            if (!keys.contains(k) || v.length != 1 || v[0].isBlank() || v[0].length() > 64) throw invalid();
            result.put(k, v[0]);
        });
        return result;
    }

    private static int size(String raw, int fallback, int max) {
        if (raw == null) return fallback;
        if (!raw.matches("[1-9][0-9]{0,2}")) throw invalid();
        int n = Integer.parseInt(raw);
        if (n > max) throw invalid();
        return n;
    }

    private static long positiveVersion(String raw) {
        try {
            if (!raw.matches("[1-9][0-9]{0,15}")) throw invalid();
            long n = Long.parseLong(raw);
            if (n > MAX) throw invalid();
            return n;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static void fields(JsonNode b, Set<String> allowed) {
        if (b == null || !b.isObject() || b.size() != allowed.size()) throw invalid();
        b.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw invalid(); });
    }

    private static long version(JsonNode v) {
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 0 || v.longValue() > MAX)
            throw invalid();
        return v.longValue();
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode v, Class<E> type) {
        if (v == null || !v.isTextual()) throw invalid();
        try { return Enum.valueOf(type, v.textValue()); }
        catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static Integer nullableInt(JsonNode v, int min, int max) {
        if (v == null) throw invalid();
        if (v.isNull()) return null;
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < min || v.intValue() > max) throw invalid();
        return v.intValue();
    }

    private static Long nullableLong(JsonNode v, long min, long max) {
        if (v == null) throw invalid();
        if (v.isNull()) return null;
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < min || v.longValue() > max) throw invalid();
        return v.longValue();
    }

    private static String text(JsonNode v, int max) {
        if (v == null || !v.isTextual()) throw invalid();
        String s = v.textValue();
        if (s.trim().isEmpty() || s.codePointCount(0, s.length()) > max) throw invalid();
        if (s.chars().anyMatch(ch -> ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' || ch == 0x7f)) throw invalid();
        return s;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "服务档位请求参数无效"); }
    private static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前会话已失效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "服务档位或配置来源未通过核对"); }
}
