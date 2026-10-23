package cn.mop.api;

import com.fasterxml.jackson.databind.node.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.map;

/** One evaluation snapshot for scrape and declared service signal; never joins unrelated samples. */
final class MonitoringServiceObservation {
    private MonitoringServiceObservation() {}
    static Map<String,Object> pending(MonitoringPresetRegistry.Target target) {
        return map("metric",target.preset().successMetric(),"status",target.preset().successMetric()==null?"NOT_SUPPORTED":"NOT_CHECKED",
            "value",null,"sample_at",null,"sample_age_seconds",null,"health_verified",false);
    }
    static String query(MonitoringPresetRegistry.Target target) {
        if(target.preset().successMetric()==null)return MonitoringScrapeObservationService.query(target);
        return "("+tag(MonitoringScrapeObservationService.query(target),"scrape")+") or ("+
            tag(MonitoringTargetSignal.query(target),"service")+")";
    }
    private static String tag(String query,String prefix) {
        String quote=String.valueOf((char)34), marker=","+quote+"mop_read_kind"+quote+",";
        return query.replace(marker+quote+"state"+quote+",",marker+quote+prefix+"_state"+quote+",")
            .replace(marker+quote+"sample_time"+quote+",",marker+quote+prefix+"_time"+quote+",");
    }
    static Map<String,Object> decode(byte[] bytes,MonitoringPresetRegistry.Target target,Instant evaluated,Instant checked,int maxAge,int lookback) {
        if(target.preset().successMetric()==null){var result=MonitoringScrapeObservationService.decode(bytes,target,evaluated,checked,maxAge,lookback);result.put("service_signal",pending(target));return result;}
        try {
            if(bytes==null||bytes.length>MonitoringHttpTransport.MAX_BYTES)throw MonitoringScrapeObservationService.bad("RESPONSE_TOO_LARGE");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root=MonitoringPresetRegistry.JSON.readTree(text);var rows=root.path("data").path("result");
            if(!root.isObject()||!rows.isArray()||rows.size()>4)throw MonitoringScrapeObservationService.bad("AMBIGUOUS_OR_INVALID_RESULT");
            ObjectNode scrape=(ObjectNode)root.deepCopy(),service=(ObjectNode)root.deepCopy();
            ArrayNode scrapeRows=((ObjectNode)scrape.get("data")).putArray("result"),serviceRows=((ObjectNode)service.get("data")).putArray("result");
            for(var row:rows){
                String kind=row.path("metric").path("mop_read_kind").asText();
                if(!Set.of("scrape_state","scrape_time","service_state","service_time").contains(kind))throw MonitoringScrapeObservationService.bad("INVALID_SERIES_KIND");
                ObjectNode copy=(ObjectNode)row.deepCopy();((ObjectNode)copy.get("metric")).put("mop_read_kind",kind.endsWith("_state")?"state":"sample_time");
                (kind.startsWith("scrape_")?scrapeRows:serviceRows).add(copy);
            }
            var result=MonitoringScrapeObservationService.decode(MonitoringPresetRegistry.JSON.writeValueAsBytes(scrape),target,evaluated,checked,maxAge,lookback);
            var signal=MonitoringTargetSignal.evaluate(MonitoringPresetRegistry.JSON.writeValueAsBytes(service),target,evaluated,checked,maxAge,lookback);
            if(!"SCRAPE_SUCCEEDED".equals(result.get("observation_status")))signal=blocked(target,"BLOCKED_SCRAPE");
            else if(Set.of("SIGNAL_SUCCESS","SIGNAL_FAILURE","STALE").contains(signal.get("status")) &&
                !scrapeRows.get(0).path("metric").path("job").equals(serviceRows.get(0).path("metric").path("job")))signal=blocked(target,"INVALID_RESPONSE");
            else if(Set.of("SIGNAL_SUCCESS","SIGNAL_FAILURE").contains(signal.get("status")) && !Objects.equals(result.get("sample_at"),signal.get("sample_at"))) {
                signal.put("status","OUT_OF_SYNC");signal.put("value",null);
            }
            result.put("service_signal",signal);return result;
        } catch(MonitoringScrapeObservationService.InvalidResponse failure){throw failure;}
        catch(Exception failure){throw MonitoringScrapeObservationService.bad("INVALID_SOURCE_RESPONSE");}
    }
    private static Map<String,Object> blocked(MonitoringPresetRegistry.Target target,String status){var r=pending(target);r.put("status",status);return r;}
}
