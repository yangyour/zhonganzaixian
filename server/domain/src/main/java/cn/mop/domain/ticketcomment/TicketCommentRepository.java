package cn.mop.domain.ticketcomment;

import java.time.Instant;
import java.util.List;

/** Reporter plus current-tenant internal readers only; no customer-wide sharing or INTERNAL-note conversion. */
public interface TicketCommentRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    /** Deliberately excludes actor ID/name: customer projection must not disclose internal identity. */
    record Entry(String id,String tenantId,String ticketId,long observedTicketVersion,TicketCommentAccess.AuthorKind authorKind,TicketComment comment,Instant createdAt) {}
    List<Entry> list(Access access,String ticketId,int limit,Instant beforeCreatedAt,String beforeId);
    Entry get(Access access,String ticketId,String id);
    /** Required before replay lookup via idempotentAuthorized; checks current reporter visibility, not old parent CAS. */
    void authorizeCurrentAccess(Access access,String ticketId);
    /** Protected idempotent transaction; verify fresh membership/reporter and parent version, append + audit atomically. */
    Entry create(Access access,String ticketId,long expectedTicketVersion,TicketComment comment);
    List<String> readinessProblems();
}
