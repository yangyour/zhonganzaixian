package cn.mop.api;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class MonitoringPresetConfiguration {
    @Bean MonitoringOnboardingPlanner monitoringOnboardingPlanner(Environment env){
        return new MonitoringOnboardingPlanner(env.getProperty("MOP_MONITORING_ONBOARDING_COLLECTORS","{\"version\":1,\"collectors\":[]}"));
    }
    @Bean MonitoringPresetProbeService monitoringPresetProbeService(Environment env){
        boolean tests=flag(env,"MOP_MONITORING_PRESET_TEST_ENABLED","false");
        boolean registryEnabled=flag(env,"MOP_MONITORING_PRESET_REGISTRY_ENABLED",Boolean.toString(tests));
        if(tests&&!registryEnabled)throw MonitoringPresetRegistry.invalid();
        if(!registryEnabled)return new MonitoringPresetProbeService(null,t->{throw new IllegalStateException("Disabled probe");},false);
        var registry=new MonitoringPresetRegistry(env.getProperty("MOP_MONITORING_PRESET_TARGETS"),env.getProperty("MOP_MONITORING_PRESET_ALLOWED_ENDPOINTS","[]"),env.getProperty("MOP_MONITORING_PRESET_ALLOWED_TARGETS","[]"));
        MonitoringPresetProbeService.Runner runner=tests?MonitoringPresetProbeService.network():t->{throw new IllegalStateException("Disabled probe");};
        return new MonitoringPresetProbeService(registry,runner,tests);
    }
    @Bean MonitoringScrapeObservationService monitoringScrapeObservationService(Environment env){
        if(!flag(env,"MOP_MONITORING_PROMETHEUS_READ_ENABLED","false"))
            return new MonitoringScrapeObservationService(java.util.Map.of(),(s,t,e)->{throw new IllegalStateException("Disabled read");},java.time.Clock.systemUTC());
        var sources=MonitoringScrapeObservationService.sources(env.getProperty("MOP_MONITORING_PROMETHEUS_SOURCES","{\"version\":1,\"sources\":[]}"),env.getProperty("MOP_MONITORING_PROMETHEUS_ALLOWED_ENDPOINTS","[]"));
        return new MonitoringScrapeObservationService(sources,MonitoringScrapeObservationService.network(),java.time.Clock.systemUTC());
    }
    private static boolean flag(Environment env,String key,String fallback){String value=env.getProperty(key,fallback);if(!value.equals("true")&&!value.equals("false"))throw MonitoringPresetRegistry.invalid();return value.equals("true");}
}
