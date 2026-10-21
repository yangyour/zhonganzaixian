package cn.mop.domain.ticket;

import java.time.Instant;
import java.util.*;

public interface TicketWorkflowRepository {
    record Access(String tenantId,String actorId,long permissionVersion,String traceId) {}
    record Query(String state,String priority,String assigneeId,Instant beforeCreatedAt,String beforeId,int limit) {}
    @FunctionalInterface interface SlaProvider {
        TicketSlaSnapshot freeze(Access access,String priority,CustomerConfirmationWindow.Tier tier,Instant startedAt);
    }
    void authorizeCommand(Access access,String ticketId,boolean customerAction);
    Map<String,Object> read(Access access,String ticketId);
    default Map<String,Object> readSla(Access access,String ticketId,CustomerConfirmationWindow.Tier currentTenantTier){throw new UnsupportedOperationException();}
    List<Map<String,Object>> list(Access access,Query query);
    List<Map<String,Object>> events(Access access,String ticketId,long beforeVersion,int limit);
    List<Map<String,Object>> assignees(Access access);
    Map<String,Object> activate(Access access,String ticketId,long expectedVersion,String priority,CustomerConfirmationWindow.Tier tier,String reason,SlaProvider provider);
    Map<String,Object> transition(Access access,String ticketId,WorkflowLifecycle.Command command);
    int tick(String tenantId,int limit);
    List<String> readinessProblems();
}
