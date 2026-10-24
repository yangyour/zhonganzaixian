package cn.mop.api;
import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringEnrollmentProbeTest {
 static String config(){return "{\"version\":1,\"targets\":[{\"id\":\"79000000-0000-4000-8000-000000000003\",\"tenant_id\":\"79000000-0000-4000-8000-000000000002\",\"asset_id\":\"79000000-0000-4000-8000-000000000001\",\"preset_id\":\"linux-node\",\"enabled\":false,\"endpoint\":\"http://192.0.2.1:9100/metrics\",\"target\":null,\"auth_profile\":null,\"interval_seconds\":null,\"timeout_seconds\":5}]}";}
 @Test void disabledOrMissingExactAllowlistNeverTouchesTransport(){for(boolean enabled:new boolean[]{false,true}){var count=new AtomicInteger();var service=new MonitoringEnrollmentProbe(enabled,"[]","[]",t->{count.incrementAndGet();return MonitoringPresetProbeService.base(t);});var registry=service.registry(config());var target=registry.forTenant("79000000-0000-4000-8000-000000000002").get(0);assertThrows(DomainException.class,()->service.probe(registry,target));assertEquals(0,count.get());}}
 @Test void enabledExactTargetUsesExistingBoundedProbeContract(){var count=new AtomicInteger();var service=new MonitoringEnrollmentProbe(true,"[\"http://192.0.2.1:9100/metrics\"]","[]",t->{count.incrementAndGet();return MonitoringPresetProbeService.outcome(t,"FAILED","CONNECTION_FAILED");});var registry=service.registry(config());var target=registry.forTenant("79000000-0000-4000-8000-000000000002").get(0);var result=service.probe(registry,target);assertEquals(1,count.get());assertEquals(false,result.get("continuous_collection_verified"));assertEquals(false,result.get("health_verified"));}
}
