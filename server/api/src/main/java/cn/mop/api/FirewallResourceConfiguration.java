package cn.mop.api;

import cn.mop.adapters.monitoring.ZabbixReadClient;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration @Profile("remote_test")
public class FirewallResourceConfiguration {
    @Bean FirewallResourceService firewallResourceService(Environment env){
        String enabled=env.getProperty("MOP_AF_RESOURCE_ENABLED","false");if(!List.of("true","false").contains(enabled))throw new IllegalStateException("Invalid AF resource enable flag");
        if(enabled.equals("false"))return new FirewallResourceService(null,null,Clock.systemUTC());
        try{var binding=FirewallResourceService.parse(env.getProperty("MOP_AF_RESOURCE_BINDING"));var reader=new ZabbixReadClient(env.getProperty("MOP_AF_RESOURCE_ENDPOINT"),env.getProperty("MOP_AF_RESOURCE_TOKEN"));return new FirewallResourceService(binding,reader::call,Clock.systemUTC());}
        catch(Exception failure){throw new IllegalStateException("Invalid private AF resource configuration");}
    }
}
