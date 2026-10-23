package cn.mop.api;

import static cn.mop.api.ApiResponse.*;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

/** Explicit sample inputs for authorized demo tenants; never a business data fallback. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/demo/examples")
public final class DemoExamplesController {
    private static final Map<String, String> CAPABILITIES =
            Map.of(
                    "sla-scenarios",
                    "sla_scenarios:read",
                    "sla-reports",
                    "sla_scenarios:read",
                    "operational-comparison",
                    "operations:compare_explicit",
                    "capacity-estimates",
                    "capacity:estimate",
                    "observation-window-diagnostics",
                    "configurations:read",
                    "threshold-diagnostics",
                    "configurations:read",
                    "contact-import-preview",
                    "contacts:write");
    private final PlatformRepository platform;
    private final DemoChannelScope scope;
    private final ObjectMapper mapper;
    private final JsonNode examples;

    public DemoExamplesController(
            PlatformRepository platform, DemoChannelScope scope, ObjectMapper mapper) {
        this.platform = platform;
        this.scope = scope;
        this.mapper = mapper;
        try (var stream = getClass().getResourceAsStream("/demo-page-examples.json")) {
            examples = mapper.readTree(stream);
        } catch (Exception invalid) {
            throw new IllegalStateException("Demo example definitions unavailable");
        }
    }

    @GetMapping("/{page}")
    Object read(
            @PathVariable String page, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String capability = CAPABILITIES.get(page);
        if (capability == null || request.getQueryString() != null) {
            throw new DomainException(404, "NOT_FOUND", "示例不存在");
        }
        if (request.getUserPrincipal() == null || request.getSession(false) == null) {
            throw new DomainException(401, "SESSION_EXPIRED", "请先登录");
        }
        String actor = request.getUserPrincipal().getName();
        Object selected = request.getSession().getAttribute("mop.tenant_id");
        if (!(selected instanceof String tenant)) {
            throw new DomainException(401, "SESSION_EXPIRED", "租户会话已失效");
        }
        var membership =
                platform.membership(actor, tenant)
                        .filter(
                                member ->
                                        member.active()
                                                && member.capabilities().contains(capability))
                        .orElseThrow(() -> new DomainException(403, "ACTION_FORBIDDEN", "无此页面权限"));
        JsonNode input = null;
        boolean available = scope.contains(tenant);
        if (available) {
            if (page.equals("sla-reports")) {
                input =
                        mapper.valueToTree(
                                map("month", YearMonth.now(ZoneOffset.ofHours(8)).toString()));
            } else {
                input = examples.path(page).deepCopy();
                if (page.equals("observation-window-diagnostics")) {
                    var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                    var object = (com.fasterxml.jackson.databind.node.ObjectNode) input;
                    object.put("observed_at", now.toString());
                    var samples = object.putArray("samples");
                    for (int seconds : new int[] {120, 60, 0}) {
                        samples.addObject()
                                .put("recorded_at", now.minusSeconds(seconds).toString())
                                .put("state", "KNOWN")
                                .put("value", "85")
                                .put("unit", "%");
                    }
                }
            }
        }
        if (!platform.membership(actor, tenant).filter(membership::equals).isPresent()) {
            throw new DomainException(401, "SESSION_EXPIRED", "读取期间授权发生变化");
        }
        return success(
                map(
                        "tenant_id",
                        tenant,
                        "actor_id",
                        actor,
                        "page",
                        page,
                        "available",
                        available,
                        "source",
                        "EXPLICIT_DEMO_INPUT",
                        "input",
                        input,
                        "business_data_written",
                        false,
                        "provider_accessed",
                        false),
                request);
    }
}
