package cn.mop.domain.monitoring;
import java.time.Instant;
import java.util.List;
import cn.mop.domain.monitoring.MonitoringEnrollment.*;
public interface MonitoringEnrollmentRepository {
 record Access(String actorId,String tenantId,long permissionVersion,String traceId){}
 record Creation(String catalogSha256,String configurationSha256,String configuration,List<AssetSnapshot> assets) {public Creation{assets=List.copyOf(assets);}}
 /** Internal transport summary without caller-controlled timestamps; persistence stamps database receipt time. */
 record ProbeResult(java.util.UUID targetId,String configurationSha256,String status,Integer sampleCount,List<String> matchedMetrics,String reason){public ProbeResult{matchedMetrics=List.copyOf(matchedMetrics);}}
 void authorize(Access access,String id,boolean write);
 State create(Access access,Creation input);
 State get(Access access,String id);
 List<State> list(Access access,int limit,Instant before,String beforeId);
 State change(Access access,String id,long expected,String action,String reason);
 State authorizeProbe(Access access,String id,long expected);
 State recordProbe(Access access,String id,long expected,ProbeResult probe);
}
