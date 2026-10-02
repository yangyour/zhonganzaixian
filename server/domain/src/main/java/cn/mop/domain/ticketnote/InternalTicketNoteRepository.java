package cn.mop.domain.ticketnote;

import java.time.Instant;
import java.util.List;

/** Current-tenant internal-only immutable notes. The implementation must reauthorize every operation. */
public interface InternalTicketNoteRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Entry(String id,String tenantId,String ticketId,String actorId,long observedTicketVersion,InternalTicketNote note,Instant createdAt) {}
    List<Entry> list(Access access,String ticketId,int limit,Instant beforeCreatedAt,String beforeId);
    Entry get(Access access,String ticketId,String id);
    /** Protected platform idempotent transaction only; lock/check parent version, do not change ticket or SLA state. */
    Entry create(Access access,String ticketId,long expectedTicketVersion,InternalTicketNote note);
    List<String> readinessProblems();
}
