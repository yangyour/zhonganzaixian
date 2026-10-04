package cn.mop.domain;

import java.time.Instant;
import java.util.UUID;

/** Recorded database inventory, not licensed usage, monitored asset count, quota or production status. */
public record TenantInventory(String id, String name, Instant createdAt,
                              long registeredAssetCount, long registeredSiteCount, long activeMemberCount) {
    public TenantInventory {
        if (!canonicalUuid(id) || name == null || name.isBlank() || createdAt == null || registeredAssetCount < 0 || registeredSiteCount < 0 || activeMemberCount < 0)
            throw new IllegalArgumentException("Invalid tenant inventory facts");
    }
    public record Query(String afterId, int limit) {
        public Query {
            if (afterId != null && !canonicalUuid(afterId) || limit < 1 || limit > 101)
                throw new DomainException(400, "VALIDATION_ERROR", "Tenant inventory cursor or technical page limit invalid");
        }
    }
    public static boolean canonicalUuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") && UUID.fromString(value).toString().equals(value);
    }
}
