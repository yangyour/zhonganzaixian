package cn.mop.domain.manualincident;
import java.time.Instant;
import java.util.List;
/** Separate immutable human confirmation. Original V13 registration and its original receipt never change. */
public interface ManualRecoveryRepository {
    record Access(String actorId,String tenantId,long permissionVersion,String traceId) {}
    record Confirmation(String id,String tenantId,String incidentId,String actorId,ManualRecoveryConfirmation value,Instant recordedAt) {
        public Confirmation { if(id==null||id.isBlank()||tenantId==null||tenantId.isBlank()||incidentId==null||incidentId.isBlank()||actorId==null||actorId.isBlank()||value==null)throw new IllegalArgumentException("Incomplete human confirmation evidence");value.validateRecordedAt(recordedAt); }
    }
    record Current(String incidentId,String tenantId,long version,Confirmation confirmation) {
        public Current { if(incidentId==null||incidentId.isBlank()||tenantId==null||tenantId.isBlank()||version<0||version>1||(version==0)!=(confirmation==null)||confirmation!=null&&(!incidentId.equals(confirmation.incidentId())||!tenantId.equals(confirmation.tenantId())))throw new IllegalArgumentException("Inconsistent recovery confirmation projection"); }
        public String manualRecoveryStatus(){return confirmation==null?"NOT_CONFIRMED":"HUMAN_CONFIRMED";}
    }
    Current get(Access access,String incidentId);
    /** Recheck current internal member and same-tenant MANUAL registration before looking up a saved receipt. */
    void authorizeCurrentIncident(Access access,String incidentId);
    /** Fresh authorization, unique confirmation CAS and audit/receipt in caller's protected idempotent transaction. */
    Confirmation confirm(Access access,String incidentId,long expectedVersion,ManualRecoveryConfirmation confirmation);
    List<String> readinessProblems();
}
