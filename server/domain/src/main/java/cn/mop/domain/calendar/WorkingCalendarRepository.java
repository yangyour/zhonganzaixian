package cn.mop.domain.calendar;

import java.time.Instant;
import java.util.List;

/** Current-tenant configuration drafts. No effective calendar, customer signoff, SLA promise or recurring defaults. */
public interface WorkingCalendarRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Snapshot(String id,String tenantId,long version,WorkingCalendarDraft draft,String actorId,Instant createdAt,Instant updatedAt) {}
    record Revision(String calendarId,String tenantId,long version,WorkingCalendarDraft before,WorkingCalendarDraft after,String actorId,Instant recordedAt) {}
    List<Snapshot> list(Access access,int limit,Instant beforeCreatedAt,String beforeId);
    Snapshot get(Access access,String calendarId);
    List<Revision> history(Access access,String calendarId,int limit,Long beforeVersion);
    /** These writes must use a protected idempotent transaction with fresh configuration authorization and audit. */
    /** Run inside idempotentAuthorized before reading a cached receipt; null calendar means create scope. */
    void authorizeCurrentAccess(Access access,String calendarId);
    Snapshot create(Access access,WorkingCalendarDraft draft);
    Snapshot update(Access access,String calendarId,long expectedVersion,WorkingCalendarDraft draft);
    List<String> readinessProblems();
}
