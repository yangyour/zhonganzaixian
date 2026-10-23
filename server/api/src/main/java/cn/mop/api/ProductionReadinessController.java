package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.RuntimeDatabaseGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authorized live diagnostic for configuration operators. No activation, migration or credential material is accepted. */
@RestController
@Profile("remote_test")
@RequestMapping("/api/v1/operations/production-readiness")
public class ProductionReadinessController {
    private final PlatformRepository platform;
    private final DataSource source;
    private final Environment environment;

    public ProductionReadinessController(PlatformRepository platform, DataSource source, Environment environment) {
        this.platform = platform;
        this.source = source;
        this.environment = environment;
    }

    @GetMapping
    Object inspect(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new DomainException(400, "VALIDATION_ERROR", "运行检查不接受连接信息或调用者作用域参数");
        }
        if (request.getUserPrincipal() == null || request.getSession(false) == null) {
            throw new DomainException(401, "SESSION_EXPIRED", "会话不存在或已过期");
        }
        String actor = request.getUserPrincipal().getName();
        String tenant = (String) request.getSession(false).getAttribute("mop.tenant_id");
        var member = platform.membership(actor, tenant)
            .orElseThrow(() -> new DomainException(401, "SESSION_EXPIRED", "成员关系已失效"));
        if (!member.active() || !member.capabilities().contains("configurations:read")) {
            throw new DomainException(403, "ACTION_FORBIDDEN", "当前成员无运行配置检查权限");
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        String observedAt = null;
        String databaseVersion = null;
        boolean runtimeRestricted = false;
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            var observation = RuntimeDatabaseGuard.inspect(connection);
            observedAt = observation.inspectedAt().toString();
            databaseVersion = observation.serverVersion();
            runtimeRestricted = observation.accepted();
            checks.add(check("DATABASE_NAMESPACE", observation.namespaceRestricted(), "DEDICATED_TEST_SCOPE", "RUNTIME_SCOPE_REJECTED"));
            checks.add(check("DATABASE_ROLE", observation.roleRestricted(), "ROLE_RESTRICTED", "ELEVATED_ROLE_REJECTED"));
            checks.add(check("DATABASE_DDL", observation.ddlRestricted(), "RUNTIME_DDL_DENIED", "RUNTIME_DDL_PRIVILEGE_REJECTED"));
            checks.add(check("MIGRATION_IDENTITY", observation.migrationSeparated(), "MIGRATION_IDENTITY_SEPARATE", "MIGRATION_ROLE_MEMBERSHIP_REJECTED"));
            checks.add(check("GLOBAL_DATABASE_CAPABILITIES", observation.globalCapabilitiesRestricted(), "GLOBAL_CAPABILITIES_DENIED", "GLOBAL_CAPABILITIES_REJECTED"));
        } catch (Exception unavailable) {
            observedAt = null;
            databaseVersion = null;
            runtimeRestricted = false;
            checks.clear();
            checks.add(ApiResponse.map("id", "DATABASE_OBSERVATION", "status", "NOT_READY", "code", "DATABASE_OBSERVATION_UNAVAILABLE"));
        }
        boolean secureSettings;
        try {
            SecureSessionConfiguration.requireSafeSettings(environment);
            secureSettings = environment.getProperty("mop.security.require-https", Boolean.class, false);
        } catch (RuntimeException invalid) {
            secureSettings = false;
        }
        checks.add(check("SECURE_SESSION_SETTINGS", secureSettings, "DIRECT_TLS_SETTINGS_VALID", "SECURE_SESSION_NOT_CONFIGURED"));
        checks.add(ApiResponse.map("id", "PUBLIC_TLS_CERTIFICATE", "status", "NOT_RUN", "code", "PUBLIC_ENTRY_PROBE_REQUIRED"));
        checks.add(ApiResponse.map("id", "PRODUCTION_DATABASE_ISOLATION", "status", "CUSTOMER_GATED", "code", "Q02_PENDING"));
        checks.add(ApiResponse.map("id", "PRODUCTION_RUNTIME", "status", "BLOCKED", "code", "REMOTE_TEST_IS_NOT_PRODUCTION"));
        return ApiResponse.success(ApiResponse.map("tenant_id", tenant, "actor_id", actor,
            "permission_version", member.permissionVersion(), "runtime_mode", platform.metadata().mode(),
            "runtime_database_restricted", runtimeRestricted, "database_observed_at", observedAt,
            "database_version", databaseVersion, "production_database_kind", null, "production_isolation_strategy", null,
            "production_ready", false, "activation_performed", false, "checks", checks), request);
    }

    private static Map<String, Object> check(String id, boolean passed, String ok, String failure) {
        return ApiResponse.map("id", id, "status", passed ? "PASS" : "BLOCKED", "code", passed ? ok : failure);
    }
}
