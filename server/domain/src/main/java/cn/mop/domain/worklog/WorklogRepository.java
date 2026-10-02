package cn.mop.domain.worklog;

import java.time.Instant;
import java.util.List;

/** Actual self-entered facts only. Financial defaults, team visibility and billing remain separate. */
public interface WorklogRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Entry(String id,String tenantId,String ticketId,String ownerId,long version,Instant createdAt,Instant updatedAt,ManualWorklog facts) {}
    record Revision(long version,String action,String actorId,Instant recordedAt,ManualWorklog facts) {}
    List<Entry> listOwn(Access access,String ticketId,int limit,Instant beforeCreated,String beforeId);
    Entry getOwn(Access access,String ticketId,String id);
    List<Revision> historyOwn(Access access,String ticketId,String id,int limit,Long beforeVersion);
    /** Caller must enter the platform's idempotent command transaction. */
    Entry create(Access access,String ticketId,long expectedTicketVersion,ManualWorklog facts);
    Entry update(Access access,String ticketId,String id,long expectedVersion,ManualWorklog facts);
    List<String> readinessProblems();
}
