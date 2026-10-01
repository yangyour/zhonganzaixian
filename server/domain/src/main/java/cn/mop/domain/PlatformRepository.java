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
    default List<Map<String, Object>> configurationRevisions(String tenantId, String key) { throw revisionsUnavailable(); }
    default Optional<Map<String, Object>> configurationRevision(String tenantId, String id) { throw revisionsUnavailable(); }
    default Map<String, Object> createConfigurationRevision(String tenantId, String actorId, String key, Object proposed, String reason, List<Map<String, String>> evidenceRefs) { throw revisionsUnavailable(); }
    default Map<String, Object> transitionConfigurationRevision(String tenantId, String actorId, String id, long expectedVersion, String action, String comment) { throw revisionsUnavailable(); }
    private static DomainException revisionsUnavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "配置修订需要指定测试环境 PostgreSQL，历史夹具不提供集成结果"); }
    void audit(String actor, String tenant, String action, String resourceType, String resourceId, String result, String reason, String trace);
    List<Map<String, Object>> audits(String tenant);
}
