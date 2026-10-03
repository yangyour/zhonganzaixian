package cn.mop.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.time.Instant;

/** All writes inside idempotent() participate in one durable transaction. */
public interface PlatformRepository extends AssetRepository {
    RepositoryMetadata metadata();
    boolean dependencyReady();
    /** Safe readiness reasons for the designated durable photo schema and runtime privileges. */
    default List<String> photoDependencyProblems(){return List.of("PHOTO_REPOSITORY_UNAVAILABLE");}
    default List<String> assetReadProblems(){return List.of("ASSET_UNICODE_SEARCH_UNAVAILABLE");}
    default List<String> manualIncidentProblems(){return List.of("MANUAL_INCIDENT_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    default List<String> workingCalendarProblems(){return List.of("WORKING_CALENDAR_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    default List<String> ticketCommentProblems(){return List.of("TICKET_COMMENT_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    default List<String> internalNoteProblems(){return List.of("INTERNAL_NOTE_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    default List<String> assetMetadataProblems(){return List.of("ASSET_METADATA_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    default List<String> worklogProblems(){return List.of("WORKLOG_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    default List<String> assetDependencyProblems(){return List.of("ASSET_DEPENDENCY_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    /** Authorize and read the bounded ledger in one real database snapshot; no unbounded/fixture fallback. */
    default List<Asset> authorizedAssets(String tenant,String actor,long permissionVersion,AssetReadQuery query){throw new DomainException(503,"UPSTREAM_UNAVAILABLE","资产授权分页需要真实PostgreSQL仓储");}
    default Optional<Asset> authorizedAsset(String tenant,String actor,long permissionVersion,String id){throw new DomainException(503,"UPSTREAM_UNAVAILABLE","资产授权回读需要真实PostgreSQL仓储");}
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
    /** Resource authorization runs in the protected transaction before looking up any cached receipt. */
    default Object idempotentAuthorized(String actor,String tenant,String route,String requestKey,String hash,long expectedPermissionVersion,Runnable authorize,Supplier<Object> command){throw new DomainException(503,"UPSTREAM_UNAVAILABLE","资源级重放授权需要真实PostgreSQL事务");}
    List<Map<String, Object>> configurations();
    Map<String, Object> configuration(String key);
    Map<String, Object> draftConfiguration(String key, Object proposed, long expectedVersion);
    default List<Map<String, Object>> configurationRevisions(String tenantId, String key) { throw revisionsUnavailable(); }
    default Optional<Map<String, Object>> configurationRevision(String tenantId, String id) { throw revisionsUnavailable(); }
    default Map<String, Object> createConfigurationRevision(String tenantId, String actorId, String key, Object proposed, String reason, List<Map<String, String>> evidenceRefs) { throw revisionsUnavailable(); }
    default Map<String, Object> transitionConfigurationRevision(String tenantId, String actorId, String id, long expectedVersion, String action, String comment) { throw revisionsUnavailable(); }
    private static DomainException revisionsUnavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "配置修订需要指定测试环境 PostgreSQL，历史夹具不提供集成结果"); }
    default CustomerReportRegistration registerCustomerReport(String tenantId, String actorId, CustomerReport report) { throw ticketsUnavailable(); }
    default List<Map<String, Object>> tickets(String tenantId, String actorId, String category, String urgency, String query, Instant afterCreated, String afterId, int limit) { throw ticketsUnavailable(); }
    default Optional<Map<String, Object>> ticket(String tenantId, String actorId, String id) { throw ticketsUnavailable(); }
    default Optional<Map<String, Object>> operation(String tenantId, String actorId, String id) { throw ticketsUnavailable(); }
    default Map<String,Object> acceptPhoto(String tenant,String actor,PhotoUpload upload){throw ticketsUnavailable();}
    default Optional<Map<String,Object>> file(String tenant,String actor,String id){throw ticketsUnavailable();}
    default Map<String,Object> contentGrant(String tenant,String actor,String id){throw ticketsUnavailable();}
    default Map<String,Object> photoContent(String tenant,String actor,String id,String grant){throw ticketsUnavailable();}
    default Map<String,Object> attachPhotos(String tenant,String actor,String ticket,long expected,java.util.List<String> ids){throw ticketsUnavailable();}
    default boolean processNextPhoto(String worker,PhotoObjectStore store){throw ticketsUnavailable();}
    private static DomainException ticketsUnavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "报障受理需要指定测试环境 PostgreSQL，历史夹具不提供集成结果"); }
    void audit(String actor, String tenant, String action, String resourceType, String resourceId, String result, String reason, String trace);
    List<Map<String, Object>> audits(String tenant);
}
