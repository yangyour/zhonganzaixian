package cn.mop.domain.manualincident;
import java.time.Instant;
import java.util.List;
/** Separate manual-source registration storage. No monitored recovery, auto ticket creation or five-state transition methods. */
public interface ManualIncidentRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Entry(String id,String tenantId,ManualIncident incident,String assetNameAtRegistration,String actorId,Instant registeredAt) {}
    List<Entry> list(Access access,int limit,Instant beforeRegisteredAt,String beforeId);
    Entry get(Access access,String id);
    /** Fresh internal membership and same-tenant asset authorization before cached receipt lookup. */
    void authorizeCurrentAsset(Access access,String assetId);
    /** Only inside a protected idempotent transaction; recheck asset version, append audit and save original receipt atomically. */
    Entry create(Access access,ManualIncident incident);
    List<String> readinessProblems();
}
