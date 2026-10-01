package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static cn.mop.api.ApiResponse.*;

@RestController
@RequestMapping("/api/v1")
@Profile("remote_test")
public class ConfigurationRevisionController {
    private final PlatformRepository repository;
    private final ObjectMapper mapper;
    private static final Pattern SENSITIVE_REFERENCE = Pattern.compile("token|password|secret|credential|private[_:-]?key", Pattern.CASE_INSENSITIVE);
    private record Access(String actor, String tenant, long permissionVersion) {}

    public ConfigurationRevisionController(PlatformRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @GetMapping("/configurations/{key}/revisions")
    Object list(@PathVariable String key, HttpServletRequest request) {
        Access access = access(request);
        noQuery(request);
        return success(map("items", repository.configurationRevisions(access.tenant, key)), request);
    }

    @GetMapping("/configuration-revisions/{id}")
    Object detail(@PathVariable String id, HttpServletRequest request) {
        Access access = access(request);
        noQuery(request);
        return success(repository.configurationRevision(access.tenant, id).orElseThrow(ConfigurationRevisionController::notFound), request);
    }

    @PostMapping("/configurations/{key}/revisions")
    Object create(@PathVariable String key, @RequestBody JsonNode body, HttpServletRequest request) {
        Access access = access(request);
        fields(body, Set.of("proposed_value", "reason", "evidence_refs"));
        Map<String, Object> entry = repository.configuration(key);
        if ("fixed_constraint".equals(entry.get("contract_value_kind"))) throw new DomainException(422, "STATE_NOT_ALLOWED", "合同固定约束不能修改");
        JsonNode proposed = body.get("proposed_value");
        ConfigurationValueValidator.validate(entry, proposed);
        String reason = text(body, "reason", 500);
        List<Map<String, String>> references = evidence(body.get("evidence_refs"));
        return success(write(access, body, request, () -> {
            Map<String, Object> result = repository.createConfigurationRevision(access.tenant, access.actor, key, mapper.convertValue(proposed, Object.class), reason, references);
            audit(access, result.get("id").toString(), "create", request);
            return result;
        }), request);
    }

    @PostMapping("/configuration-revisions/{id}/submit")
    Object submit(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        Access access = access(request);
        fields(body, Set.of("expected_version"));
        return transition(access, id, body, "SUBMIT", null, request);
    }

    @PostMapping("/configuration-revisions/{id}/review")
    Object review(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        Access access = access(request);
        fields(body, Set.of("expected_version", "decision", "comment"));
        String decision = text(body, "decision", 7);
        if (!Set.of("approve", "reject").contains(decision)) throw invalid("decision 仅允许 approve 或 reject");
        return transition(access, id, body, decision.equals("approve") ? "APPROVE" : "REJECT", text(body, "comment", 500), request);
    }

    @PostMapping("/configuration-revisions/{id}/withdraw")
    Object withdraw(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
        Access access = access(request);
        fields(body, Set.of("expected_version", "reason"));
        return transition(access, id, body, "WITHDRAW", text(body, "reason", 500), request);
    }

    private Object transition(Access access, String id, JsonNode body, String action, String comment, HttpServletRequest request) {
        long expectedVersion = version(body);
        return success(write(access, body, request, () -> {
            Map<String, Object> result = repository.transitionConfigurationRevision(access.tenant, access.actor, id, expectedVersion, action, comment);
            audit(access, id, action.toLowerCase(Locale.ROOT), request);
            return result;
        }), request);
    }

    private void audit(Access access, String id, String action, HttpServletRequest request) {
        repository.audit(access.actor, access.tenant, "configuration_revision." + action, "configuration_revision", id, "SUCCEEDED", "INTERNAL_REVIEW_ONLY_UNVERIFIED_NOT_EFFECTIVE", trace(request));
    }

    private Access access(HttpServletRequest request) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null) throw new DomainException(401, "SESSION_EXPIRED", "会话不存在或已过期");
        String actor = request.getUserPrincipal().getName(), tenant = (String) request.getSession(false).getAttribute("mop.tenant_id");
        Membership membership = repository.membership(actor, tenant).orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "成员关系已失效，请重新登录"));
        requireAdministrator(membership);
        return new Access(actor, tenant, membership.permissionVersion());
    }

    private Object write(Access access, JsonNode body, HttpServletRequest request, Supplier<Object> command) {
        noQuery(request);
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw invalid("Idempotency-Key 为必填，长度1至128且仅包含字母数字及._:-");
        return repository.idempotent(access.actor, access.tenant, route(request), key, hash(body), access.permissionVersion, () -> {
            Membership current = repository.membership(access.actor, access.tenant).orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "成员关系已失效"));
            requireAdministrator(current);
            return command.get();
        });
    }

    private static void requireAdministrator(Membership membership) {
        if (!"padmin".equals(membership.role())) throw new DomainException(403, "ACTION_FORBIDDEN", "当前角色无配置修订或审批权限");
    }

    static List<Map<String, String>> evidence(JsonNode value) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > 5) throw invalid("evidence_refs 必须包含1至5个书面证据引用");
        List<Map<String, String>> result = new ArrayList<>();
        for (JsonNode item : value) {
            fields(item, Set.of("reference", "title", "sha256"));
            String reference = text(item, "reference", 160), title = text(item, "title", 200), hash = text(item, "sha256", 64);
            if (!reference.matches("document-ref:[A-Za-z0-9._:-]+") || SENSITIVE_REFERENCE.matcher(reference).find() || reference.substring("document-ref:".length()).matches("[A-Za-z]:.*"))
                throw invalid("reference 必须为非敏感 document-ref 标识，禁止URL、磁盘路径和秘密");
            if (!hash.matches("[0-9A-Fa-f]{64}")) throw invalid("sha256 必须为64位十六进制书面证据摘要");
            result.add(Map.of("reference", reference, "title", title, "sha256", hash.toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(result);
    }

    static String text(JsonNode body, String key, int max) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual()) throw invalid(key + " 必须为字符串");
        String raw = value.asText(), result = raw.strip();
        if (result.isEmpty() || raw.codePointCount(0, raw.length()) > max || raw.codePoints().anyMatch(ConfigurationValueValidator::invalidCharacter)) throw invalid(key + " 为空、过长或含控制字符或无效Unicode");
        return result;
    }

    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid("请求体必须为JSON对象");
        body.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw invalid("存在未知或禁止字段"); });
    }

    private static long version(JsonNode body) {
        JsonNode value = body.get("expected_version");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1) throw invalid("expected_version 必须为正整数");
        return value.asLong();
    }

    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw invalid("配置修订接口不接受查询参数"); }
    private String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(value)))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> object.set(name, canonical(value.get(name)))); return object;
        }
        if (value.isArray()) { var array = mapper.createArrayNode(); value.forEach(item -> array.add(canonical(item))); return array; }
        return value;
    }
    private static DomainException invalid(String message) { return new DomainException(400, "VALIDATION_ERROR", message); }
    private static DomainException notFound() { return new DomainException(404, "OBJECT_NOT_FOUND", "对象不存在或不可见"); }
}
