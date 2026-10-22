package cn.mop.api;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class MonitoringPresetConfiguration {
    @Bean MonitoringOnboardingPlanner monitoringOnboardingPlanner(Environment env){
        return new MonitoringOnboardingPlanner(env.getProperty("MOP_MONITORING_ONBOARDING_COLLECTORS","{\"version\":1,\"collectors\":[]}"));
    }
    @Bean MonitoringPresetProbeService monitoringPresetProbeService(Environment env){
        String enabled=env.getProperty("MOP_MONITORING_PRESET_TEST_ENABLED","false");
        if(!enabled.equals("true")&&!enabled.equals("false"))throw MonitoringPresetRegistry.invalid();
        if(enabled.equals("false"))return new MonitoringPresetProbeService(null,t->{throw new IllegalStateException("Disabled probe");});
        var registry=new MonitoringPresetRegistry(env.getProperty("MOP_MONITORING_PRESET_TARGETS"),env.getProperty("MOP_MONITORING_PRESET_ALLOWED_ENDPOINTS","[]"),env.getProperty("MOP_MONITORING_PRESET_ALLOWED_TARGETS","[]"));
        return new MonitoringPresetProbeService(registry,MonitoringPresetProbeService.network());
    }
}
