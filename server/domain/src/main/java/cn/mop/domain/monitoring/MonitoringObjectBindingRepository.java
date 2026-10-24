package cn.mop.domain.monitoring;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import java.time.Instant;
import java.util.*;
/** Internal tenant-authorized publication history. Writes use the caller's existing durable idempotency transaction. */
public interface MonitoringObjectBindingRepository {
 record Access(String tenantId,String actorId,long permissionVersion,String traceId){}
 record View(State state,String availability,boolean draftAssetCurrent,boolean currentActorCanActivate,String policyReference,String sourceDefinitionSha256){public boolean dispatchAllowed(){return false;}public boolean monitoringApplied(){return false;}public boolean deviceIdentityVerified(){return false;}}
 record Cursor(Instant createdAt,UUID id){}
 record Page(List<View> items,Cursor next){public Page{items=List.copyOf(items);}}
 record Receipt(UUID id,long acceptedVersion,Action action){public boolean acknowledgementOnly(){return true;}public boolean currentMappingClaimed(){return false;}}
 interface Source {
  String provider(UUID poolId);
  String definitionSha256(UUID poolId);
  SourceMetadata verify(MonitoringObjectMapping.Key key);
  default FetchedEvent readEvent(MonitoringObjectMapping.Key key,String sourceEventId){throw new cn.mop.domain.DomainException(503,"MAPPING_CAPTURE_UNAVAILABLE","来源事件读取尚未配置");}
        /** Reads only the explicitly bounded host window; no source write, checkpoint or health conclusion. */
        default MappedSourcePage.Fetched readPage(MonitoringObjectMapping.Key key, MappedSourcePage.Window window) {
            throw new cn.mop.domain.DomainException(503, "MAPPING_SOURCE_PAGE_UNAVAILABLE", "有界来源分页读取尚未配置");
        }
 }
 record FetchedEvent(String sourceEventId,RawMonitoringEvent.Phase phase,Instant occurredAt,String sourceHostId,byte[] payload){
  public FetchedEvent{if(sourceEventId==null||!sourceEventId.matches("[1-9][0-9]{0,19}")||phase==null||occurredAt==null||sourceHostId==null||!sourceHostId.matches("[1-9][0-9]{0,19}")||payload==null||payload.length==0||payload.length>RawMonitoringEvent.MAX_PAYLOAD_BYTES)throw new IllegalArgumentException("Invalid bound source event");payload=payload.clone();}
  @Override public byte[] payload(){return payload.clone();}
  @Override public String toString(){return "BoundSourceEvent[unprocessed; payload redacted]";}
 }
 record CaptureReceipt(UUID mappingId,long mappingVersion,String sourceEventId,RawMonitoringEvent.Phase phase,String outcome,UUID recordId,Instant firstReceivedAt){
  public CaptureReceipt{if(mappingId==null||mappingVersion<1||mappingVersion>MonitoringObjectBinding.MAX_VERSION||sourceEventId==null||phase==null||!Set.of("RAW_STORED","RAW_ALREADY_PRESENT","CONFLICT_HELD","CONFLICT_ALREADY_HELD").contains(outcome)||recordId==null||firstReceivedAt==null)throw new IllegalArgumentException("Invalid source capture receipt");}
 }
 CaptureReceipt capture(Access access,UUID id,long expectedVersion,String sourceEventId,String expectedDefinition);
    /** One explicitly requested problem intake; raw conflict evidence is retained without business consumption. */
    record ProblemIntakeReceipt(CaptureReceipt capture, MonitoringEventCenterRepository.EventSummary event) {
        public boolean businessProcessed() {
            return event != null;
        }
    }

    /** Server-read source identity only. Implementations must retain raw facts and business effects in one transaction. */
    default ProblemIntakeReceipt intakeProblem(
            Access access,
            UUID id,
            long expectedVersion,
            String sourceEventId,
            String expectedDefinition) {
        throw new cn.mop.domain.DomainException(503, "MAPPING_PROBLEM_INTAKE_DISABLED", "来源问题事件消费尚未配置");
    }
    /** Consumes exactly one server-read page under the existing caller-owned durable command transaction. */
    default MappedSourcePage.Receipt intakePage(
            Access access,
            UUID id,
            long expectedVersion,
            String expectedDefinition,
            MappedSourcePage.Window window) {
        throw new cn.mop.domain.DomainException(503, "MAPPING_PAGE_INTAKE_DISABLED", "有界来源分页消费尚未配置");
    }
 void authorize(Access access,UUID id,boolean write);
 Page list(Access access,UUID assetId,Cursor before);
 View get(Access access,UUID id);
 Receipt create(Access access,UUID assetId,long assetVersion,MonitoringObjectMapping.Key key,String reason);
 Receipt change(Access access,UUID id,Action action,long expectedVersion,UUID assetId,Long assetVersion,String expectedPolicy,String expectedDefinition,String reason);
}
