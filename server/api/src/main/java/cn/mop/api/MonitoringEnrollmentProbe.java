package cn.mop.api;
import cn.mop.domain.DomainException;
import java.util.*;
import java.util.concurrent.Semaphore;
/** Shares the existing bounded probe protocol; exact server allowlists, no URLs accepted from clients. */
final class MonitoringEnrollmentProbe {
 private final boolean enabled;private final String endpoints,downstreams;private final MonitoringPresetProbeService.Runner runner;private final Semaphore slot=new Semaphore(1);
 MonitoringEnrollmentProbe(boolean enabled,String endpoints,String downstreams,MonitoringPresetProbeService.Runner runner){this.enabled=enabled;MonitoringPresetRegistry.allowlist(endpoints);MonitoringPresetRegistry.allowlist(downstreams);this.endpoints=endpoints;this.downstreams=downstreams;this.runner=runner;}
 MonitoringPresetRegistry registry(String configuration){return new MonitoringPresetRegistry(configuration,endpoints,downstreams);}
 boolean allowed(MonitoringPresetRegistry registry,MonitoringPresetRegistry.Target target){return enabled&&registry.approved(target);}
 Map<String,Object> probe(MonitoringPresetRegistry registry,MonitoringPresetRegistry.Target target){
  if(!allowed(registry,target))throw new DomainException(422,"CONFIG_PENDING","持久登记目标未配置精确端点与下游探测许可");
  if(!slot.tryAcquire())throw new DomainException(429,"RATE_LIMITED","已有登记探测在进行，请稍后重试");try{return runner.run(target);}finally{slot.release();}
 }
}
