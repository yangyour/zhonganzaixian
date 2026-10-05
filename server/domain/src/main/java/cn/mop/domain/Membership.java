package cn.mop.domain;

import java.util.List;
import java.util.Set;

/** Current tenant membership with effective project-managed permissions. */
public record Membership(String userId, String tenantId, String role, long permissionVersion, boolean active,
                         List<String> capabilities) {
    /** Explicit compatibility for historical fixtures and isolated tests. Real PG supplies stored grants. */
    public Membership(String userId, String tenantId, String role, long permissionVersion, boolean active) {
        this(userId, tenantId, role, permissionVersion, active, Capabilities.forRole(role));
    }
    public Membership {
        if (capabilities == null) throw new IllegalArgumentException("Effective permissions are required");
        var allowed = Set.copyOf(Capabilities.forRole(role));
        if (capabilities.stream().anyMatch(value -> value == null || !allowed.contains(value)))
            throw new IllegalArgumentException("Permissions exceed the role's business category");
        var selected = Set.copyOf(capabilities);
        capabilities = active ? Capabilities.forRole(role).stream().filter(selected::contains).toList() : List.of();
    }
}
