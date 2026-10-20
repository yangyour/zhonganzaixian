package cn.mop.domain.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Trusted service boundary; opening JSON must be assembled from verified server-side configuration. */
public interface TicketLifecycleRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Entry(String ticketId,TicketLifecycle.Snapshot snapshot,String assigneeId,
                 List<String> collaboratorIds,String openingSnapshotJson) {
        public Entry { collaboratorIds=List.copyOf(collaboratorIds); }
    }
    record Action(TicketLifecycle.Action action,long expectedVersion,String reason,
                  String expectedSolutionHash,String assigneeId,boolean keepPriorAssignee) {}
    Optional<Entry> get(Access access,String ticketId);
    Optional<TicketResolutionSnapshot> solution(Access access,String ticketId,String expectedHash);
    List<Entry> history(Access access,String ticketId,long beforeVersion,int pageSize);
    void authorizeCurrentAccess(Access access,String ticketId,boolean customerAction);
    Entry startConfigured(Access access,String ticketId,String priority);
    Entry start(Access access,String ticketId,String openingSnapshotJson,Instant responseDueAt,Instant recoveryDueAt);
    Entry act(Access access,String ticketId,Action action);
    List<String> readinessProblems();
}
