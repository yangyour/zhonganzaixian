package cn.mop.domain.configuration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;

/** Scope identifiers are supplied by trusted tenant/resource lookups, never by a settings form. */
public record ConfigurationScope(Kind kind, String tenantId, String id) {
    public enum Kind { PLATFORM, RESOURCE_POOL, TENANT, SITE, DEVICE, RULE, SERVICE_TIER, INTERFACE_INSTANCE }
    public enum Environment { REMOTE_TEST, PRODUCTION }

    public ConfigurationScope {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.PLATFORM) {
            if (tenantId != null || id != null) throw new IllegalArgumentException("Platform scope has no tenant or identifier");
        } else {
            requireText(id, "scope identifier");
            if (kind == Kind.RESOURCE_POOL) {
                if (tenantId != null) throw new IllegalArgumentException("Resource-pool scope is explicitly shared");
            } else {
                requireText(tenantId, "scope tenant");
                if (kind == Kind.TENANT && !tenantId.equals(id)) throw new IllegalArgumentException("Tenant scope identifier must equal its tenant");
            }
        }
    }

    /** Additional scope identifiers must already be authorized for this tenant by the caller. */
    public record Context(String tenantId, Environment environment, Instant at, Map<Kind, String> authorizedScopeIds) {
        public Context {
            requireText(tenantId, "tenant");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(at, "resolution instant");
            Objects.requireNonNull(authorizedScopeIds, "authorized scopes");
            Map<Kind, String> copy = new EnumMap<>(Kind.class);
            authorizedScopeIds.forEach((kind, id) -> {
                Objects.requireNonNull(kind, "scope kind");
                if (kind == Kind.PLATFORM || kind == Kind.TENANT) throw new IllegalArgumentException("Platform and tenant matching is explicit in the context");
                requireText(id, "authorized scope identifier");
                copy.put(kind, id);
            });
            authorizedScopeIds = Collections.unmodifiableMap(copy);
        }
    }

    public boolean matches(Context context) {
        Objects.requireNonNull(context, "context");
        return switch (kind) {
            case PLATFORM -> true;
            case RESOURCE_POOL -> id.equals(context.authorizedScopeIds().get(kind));
            case TENANT -> tenantId.equals(context.tenantId());
            default -> tenantId.equals(context.tenantId()) && id.equals(context.authorizedScopeIds().get(kind));
        };
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
