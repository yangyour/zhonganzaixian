package cn.mop.api;

import java.time.Instant;
import java.util.Map;
import static cn.mop.api.ApiResponse.map;

/** Pure projection of an explicitly declared success metric; never infers overall device health. */
final class MonitoringTargetSignal {
    private MonitoringTargetSignal() {}
    static Map<String,Object> evaluate(byte[] response, MonitoringPresetRegistry.Target target,
            Instant evaluated, Instant checked, int maxAge, int lookback) {
        String metric=target.preset().successMetric();
        var result=map("metric",metric,"status",metric==null?"NOT_SUPPORTED":"UNKNOWN","value",null,
            "sample_at",null,"sample_age_seconds",null,"health_verified",false);
        if(metric==null)return result;
        try {
            var decoded=MonitoringScrapeObservationService.decodeMetric(response,target,evaluated,checked,maxAge,lookback,metric);
            String status=(String)decoded.get("observation_status");
            result.put("status",switch(status){case "SCRAPE_SUCCEEDED"->"SIGNAL_SUCCESS";case "SCRAPE_FAILED"->"SIGNAL_FAILURE";default->status;});
            result.put("value",decoded.get("scrape_up"));result.put("sample_at",decoded.get("sample_at"));result.put("sample_age_seconds",decoded.get("sample_age_seconds"));
        } catch(MonitoringScrapeObservationService.InvalidResponse failure) {
            result.put("status","INVALID_RESPONSE");
        }
        return result;
    }
    static String query(MonitoringPresetRegistry.Target target) {
        if(target.preset().successMetric()==null)throw MonitoringScrapeObservationService.invalid();
        return MonitoringScrapeObservationService.queryMetric(target,target.preset().successMetric());
    }
}
