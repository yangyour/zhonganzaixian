package cn.mop.api;
import cn.mop.adapters.monitoring.ZabbixReadClient;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration @Profile("remote_test")
public class NetworkMonitoringConfiguration {
    @Bean NetworkMonitoringService networkMonitoringService(Environment env){
        String enabled=env.getProperty("MOP_NETWORK_MONITORING_ENABLED","false");
        if(!List.of("true","false").contains(enabled))throw new IllegalStateException("Invalid network monitoring enable flag");
        if(enabled.equals("false"))return new NetworkMonitoringService(List.of(),null,Clock.systemUTC(),false);
        try{
            var bindings=NetworkMonitoringService.parseBindings(env.getProperty("MOP_NETWORK_MONITORING_BINDINGS"));
            var client=new ZabbixReadClient(env.getProperty("MOP_NETWORK_MONITORING_ENDPOINT"),env.getProperty("MOP_NETWORK_MONITORING_TOKEN"));
            return new NetworkMonitoringService(bindings,client::call,Clock.systemUTC(),true);
        }catch(Exception failure){throw new IllegalStateException("Invalid private network monitoring configuration");}
    }
}
