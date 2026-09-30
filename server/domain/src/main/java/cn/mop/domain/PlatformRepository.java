package cn.mop.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** All writes inside idempotent() participate in one durable transaction. */
public interface PlatformRepository extends AssetRepository {
    RepositoryMetadata metadata();
    boolean dependencyReady();
    List<FixtureUser> users();
    Optional<FixtureUser> user(String id);
    Optional<FixtureUser> resolveIdentity(String identitySource, String subject);
    List<Tenant> allowedTenants(String userId);
    Optional<Membership> membership(String userId, String tenantId);
    Tenant tenant(String id);
    default Object idempotent(String actor, String tenant, String route, String requestKey, String hash, Supplier<Object> command) {
        return idempotent(actor,tenant,route,requestKey,hash,0,command);
    }
    Object idempotent(String actor, String tenant, String route, String requestKey, String hash, long expectedPermissionVersion, Supplier<Object> command);
    List<Map<String, Object>> configurations();
    Map<String, Object> configuration(String key);
    Map<String, Object> draftConfiguration(String key, Object proposed, long expectedVersion);
    void audit(String actor, String tenant, String action, String resourceType, String resourceId, String result, String reason, String trace);
    List<Map<String, Object>> audits(String tenant);
}
