package cn.mop.domain.configuration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Authorized, durable publication log; a test publication is not customer production approval. */
public interface RuntimeConfigurationRepository {
    record Access(String tenantId, String actorId, long permissionVersion, String traceId) {}
    record Command(long expectedVersion, String action, String revisionId, Long revisionVersion,
                   String targetReleaseId, String activation, Instant effectiveAt, Instant expiresAt, String reason) {}
    Map<String,Object> read(Access access, String key);
    /** Fixed public-monitoring key only; callers cannot choose another configuration or capability. */
    Map<String,Object> monitoringRefresh(Access access);
    /** Bounded capacity inputs read with capacity permission in one database snapshot and at one instant. */
    List<String> CAPACITY_KEYS = List.of("monitor.sample_interval_sec", "retention.history_days", "retention.trend_days", "capacity.bytes_per_value", "capacity.trend_ratio", "capacity.headroom", "capacity.proxy_outage_sec");
    List<Map<String,Object>> capacityInputs(Access access);
    List<Map<String,Object>> history(Access access, String key, long beforeVersion);
    Map<String,Object> release(Access access, String key, String id);
    void authorizeCommand(Access access, String key);
    Map<String,Object> publish(Access access, String key, Command command, String definitionHash);
    List<String> readinessProblems();
}
