package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.assetimport.CmdbImportExecutionRepository;
import cn.mop.domain.assetimport.source.AssetImportSourceRepository.Access;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Source identity and optimistic version only; tenant, cells, rules and references are never supplied by this command. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/assets/import-sources")
public final class CmdbImportExecutionController {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2)
                    .maxStringLength(128).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    private final CmdbImportExecutionRepository imports;

    public CmdbImportExecutionController(PlatformRepository platform, CmdbImportExecutionRepository imports) {
        this.platform = platform;
        this.imports = imports;
    }

    @PostMapping(value = "/{id}/cmdb-imports", consumes = MediaType.APPLICATION_JSON_VALUE)
    Object execute(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        Access access = access(request);
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw invalid();
        }
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new DomainException(404, "OBJECT_NOT_FOUND", "来源不存在或不可见");
        }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw invalid();
        }
        CmdbImportExecutionRepository.Command command = command(request);
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (command.expectedVersion() + "|" + command.parseHash()).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return success(platform.idempotentAuthorized(access.actorId(), access.tenantId(), route(request), key,
                digest, access.permissionVersion(), () -> imports.authorize(access, id),
                () -> imports.execute(access, id, command)), request);
    }

    private Access access(HttpServletRequest request) {
        if (request.getUserPrincipal() == null || request.getSession(false) == null
                || !(request.getSession(false).getAttribute("mop.tenant_id") instanceof String tenant)) {
            throw expired();
        }
        String actor = request.getUserPrincipal().getName();
        var member = platform.membership(actor, tenant).orElseThrow(CmdbImportExecutionController::expired);
        if (!member.active() || !actor.equals(member.userId()) || !tenant.equals(member.tenantId())
                || member.permissionVersion() < 1 || member.permissionVersion() > 9007199254740991L) {
            throw expired();
        }
        if (!member.capabilities().contains("assets:write") || !Set.of("padmin", "msp").contains(member.role())) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员没有资产导入权限");
        }
        return new Access(actor, tenant, member.permissionVersion(), trace(request));
    }

    private static CmdbImportExecutionRepository.Command command(HttpServletRequest request) {
        try {
            if (request.getContentLengthLong() > 1024) {
                throw invalid();
            }
            byte[] bytes = request.getInputStream().readNBytes(1025);
            if (bytes.length > 1024) {
                throw invalid();
            }
            JsonNode body = JSON.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 2
                    || !body.path("expected_version").isIntegralNumber() || !body.path("expected_version").canConvertToLong()
                    || !body.path("source_parse_hash").isTextual()) {
                throw invalid();
            }
            return new CmdbImportExecutionRepository.Command(body.path("expected_version").longValue(), body.path("source_parse_hash").textValue());
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    private static DomainException expired() {
        return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员关系已失效");
    }

    private static DomainException invalid() {
        return new DomainException(400, "VALIDATION_ERROR", "只接受来源版本、解析摘要及幂等键，请刷新来源后重试");
    }
}
