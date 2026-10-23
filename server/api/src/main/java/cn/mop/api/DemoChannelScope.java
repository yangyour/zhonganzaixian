package cn.mop.api;

import static cn.mop.api.ApiResponse.map;

import cn.mop.domain.DomainException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit demo tenants may store disabled bindings; they never use a real provider. */
@Component
@Profile("remote_test")
public final class DemoChannelScope {
    private static final Set<String> PROTECTED =
            Set.of("55000000-0000-4000-8000-000000000001", "55000000-0000-4000-8000-000000000002");
    private final Set<String> tenants;

    /** Empty by default. Tenant names/usernames do not grant demo access. */
    public DemoChannelScope(@Value("${MOP_DEMO_CHANNEL_TENANT_IDS:}") String configured) {
        if (configured == null || configured.isBlank()) {
            tenants = Set.of();
            return;
        }
        List<String> values = Arrays.asList(configured.split(",", -1));
        if (values.size() > 20
                || values.stream()
                        .anyMatch(
                                value ->
                                        !value.equals(UUID.fromString(value).toString())
                                                || PROTECTED.contains(value))) {
            throw new IllegalArgumentException("Invalid explicit demo channel namespace");
        }
        tenants = Set.copyOf(values);
        if (tenants.size() != values.size()) {
            throw new IllegalArgumentException("Duplicate demo channel namespace");
        }
    }

    public boolean contains(String tenant) {
        return tenants.contains(tenant);
    }

    /** Separate database binding keys cannot collide with the real provider identity. */
    public String corp(String tenant) {
        requireTenant(tenant);
        return "wwDEMO" + tenant.replace("-", "");
    }

    public String sender(String tenant) {
        requireTenant(tenant);
        return "demo-" + tenant + "@example.invalid";
    }

    public void rejectOutbound(String tenant) {
        if (contains(tenant)) {
            throw new DomainException(
                    409, "DEMO_CHANNEL_DISABLED", "演示渠道不配置真实供应商，仅保存停用绑定，禁止外发与连接检查");
        }
    }

    public void validateBinding(String tenant, boolean active, String address, boolean email) {
        if (!contains(tenant)) {
            return;
        }
        if (active) {
            rejectOutbound(tenant);
        }
        if (address == null
                || (email
                        ? !address.matches("[A-Za-z0-9._-]+@example\\.invalid")
                        : !address.matches("DEMO_[A-Za-z0-9_-]{1,50}"))) {
            throw new DomainException(
                    400, "DEMO_BINDING_REQUIRED", "演示绑定仅接受example.invalid邮箱或DEMO_成员标识");
        }
    }

    public Map<String, Object> status(String tenant, boolean email) {
        requireTenant(tenant);
        Map<String, Object> status =
                map(
                        "enabled",
                        false,
                        "tenant_id",
                        tenant,
                        "secret_configured",
                        false,
                        "dispatch_enabled",
                        false,
                        "automatic_notifications_enabled",
                        false,
                        "last_check",
                        map("ready", false, "reason", "演示渠道停用，未连接真实供应商"),
                        "source",
                        "DEMO_DISABLED_CONFIGURATION");
        if (email) {
            status.putAll(
                    map(
                            "sender",
                            sender(tenant),
                            "sender_name",
                            "[DEMO] 停用邮件渠道",
                            "smtp_host",
                            "smtp.example.invalid",
                            "smtp_port",
                            465,
                            "tls_mode",
                            "IMPLICIT_TLS",
                            "certificate_verification",
                            true,
                            "allowed_recipients",
                            List.of(),
                            "test_recipient",
                            null,
                            "receipt_query_supported",
                            false));
        } else {
            status.putAll(
                    map(
                            "corp_id",
                            corp(tenant),
                            "agent_id",
                            1,
                            "test_user_id",
                            null,
                            "login_enabled",
                            false,
                            "callback_url",
                            null));
        }
        return status;
    }

    private void requireTenant(String tenant) {
        if (!contains(tenant)) {
            throw new DomainException(404, "NOT_FOUND", "对象不存在或不在授权范围");
        }
    }
}
