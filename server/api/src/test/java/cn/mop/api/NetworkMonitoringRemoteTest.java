package cn.mop.api;
import cn.mop.adapters.monitoring.ZabbixReadClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit designated-source read-only integration, never a fixture or automatic CI network access. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_BASIC_NETWORK_TESTS",matches="true")
class NetworkMonitoringRemoteTest {
    @Test void authenticatedReaderAndCurrentMappingHaveActualSamples(){
        var bindings=NetworkMonitoringService.parseBindings(System.getenv("MOP_NETWORK_MONITORING_BINDINGS"));
        var client=new ZabbixReadClient(System.getenv("MOP_NETWORK_MONITORING_ENDPOINT"),System.getenv("MOP_NETWORK_MONITORING_TOKEN"));
        for(var b:bindings){
            var hosts=assertDoesNotThrow(()->client.call("host.get",Map.of("hostids",List.of(b.hostId()),"output",List.of("hostid","status"))),"host.get transport");
            assertEquals(1,hosts.size());
            var service=new NetworkMonitoringService(List.of(b),client::call,Clock.systemUTC(),true);
            var data=assertDoesNotThrow(()->service.summary(b,"explicit test device"),"summary projection");
            assertEquals(b.assetId(),data.get("asset_id"));
            assertEquals(true,service.readiness().get("ready"),()->service.readiness().toString());
        }
    }
}
