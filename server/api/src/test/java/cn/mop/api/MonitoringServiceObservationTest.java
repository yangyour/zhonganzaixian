package cn.mop.api;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringServiceObservationTest {
    static ObjectNode response(String up,String value,int serviceAge){
        var t=MonitoringScrapeObservationTest.target("redis",true);var root=MonitoringScrapeObservationTest.response(t,up,MonitoringScrapeObservationTest.seconds(MonitoringScrapeObservationTest.NOW.minusSeconds(5)));
        var rows=MonitoringScrapeObservationTest.rows(root);
        for(var r:rows){var m=(ObjectNode)r.get("metric");m.put("mop_read_kind",m.get("mop_read_kind").asText().equals("state")?"scrape_state":"scrape_time");}
        var other=MonitoringScrapeObservationTest.response(t,value,MonitoringScrapeObservationTest.seconds(MonitoringScrapeObservationTest.NOW.minusSeconds(serviceAge)));
        for(var r:MonitoringScrapeObservationTest.rows(other)){var m=(ObjectNode)r.get("metric");m.put("mop_read_kind",m.get("mop_read_kind").asText().equals("state")?"service_state":"service_time");rows.add(r);}
        return root;
    }
    static Map<String,Object> decode(ObjectNode body){return MonitoringServiceObservation.decode(MonitoringScrapeObservationTest.bytes(body),MonitoringScrapeObservationTest.target("redis",true),MonitoringScrapeObservationTest.NOW,MonitoringScrapeObservationTest.NOW,60,300);}
    static Map<?,?> signal(Map<String,Object> out){return (Map<?,?>)out.get("service_signal");}
    @Test void healthyScrapeAndFailedServiceAreSeparate(){var r=decode(response("1","0",5));assertEquals("SCRAPE_SUCCEEDED",r.get("observation_status"));assertEquals("SIGNAL_FAILURE",signal(r).get("status"));assertEquals(0,signal(r).get("value"));assertEquals(false,signal(r).get("health_verified"));}
    @Test void failedScrapeBlocksOldSuccessfulService(){var r=decode(response("0","1",5));assertEquals("SCRAPE_FAILED",r.get("observation_status"));assertEquals("BLOCKED_SCRAPE",signal(r).get("status"));assertNull(signal(r).get("value"));}
    @Test void serviceFromDifferentScrapeIsNotSuccess(){var r=signal(decode(response("1","1",6)));assertEquals("OUT_OF_SYNC",r.get("status"));assertNull(r.get("value"));}
    @Test void staleAndMissingServiceHaveNoValue(){var r=signal(decode(response("1","1",61)));assertEquals("STALE",r.get("status"));assertNull(r.get("value"));var b=response("1","1",5);var rows=MonitoringScrapeObservationTest.rows(b);rows.remove(3);rows.remove(2);assertEquals("NO_DATA",signal(decode(b)).get("status"));}
    @Test void differentJobCannotSupplySuccessfulService(){var b=response("1","1",5);var rows=MonitoringScrapeObservationTest.rows(b);for(int i=2;i<4;i++)((ObjectNode)rows.get(i).get("metric")).put("job","different");var r=signal(decode(b));assertEquals("INVALID_RESPONSE",r.get("status"));assertNull(r.get("value"));}
    @Test void extraSeriesAndUnknownKindsFailClosed(){var b=response("1","1",5);MonitoringScrapeObservationTest.rows(b).add(MonitoringScrapeObservationTest.rows(b).get(0).deepCopy());assertThrows(MonitoringScrapeObservationService.InvalidResponse.class,()->decode(b));var other=response("1","1",5);((ObjectNode)MonitoringScrapeObservationTest.rows(other).get(0).get("metric")).put("mop_read_kind","foreign");assertThrows(MonitoringScrapeObservationService.InvalidResponse.class,()->decode(other));}
    @Test void newReaderMakesOneCallAndOldReadStaysSeparate(){var t=MonitoringScrapeObservationTest.target("redis",true);var source=MonitoringScrapeObservationTest.source("http://127.0.0.1:9090/api/v1/query");int[] calls={0,0};var s=new MonitoringScrapeObservationService(Map.of(t.tenantId(),source),(a,b,e)->{calls[0]++;return MonitoringScrapeObservationTest.bytes(MonitoringScrapeObservationTest.response(t,"1",MonitoringScrapeObservationTest.seconds(e)));},(a,b,e)->{calls[1]++;return MonitoringScrapeObservationTest.bytes(response("1","0",5));},Clock.fixed(MonitoringScrapeObservationTest.NOW,ZoneOffset.UTC));assertEquals("SIGNAL_FAILURE",signal(s.readService(t)).get("status"));assertArrayEquals(new int[]{0,1},calls);assertFalse(s.read(t).containsKey("service_signal"));assertArrayEquals(new int[]{1,1},calls);}
    @Test void queryContainsBothFixedMetricsAndFourSeparateTags(){var q=MonitoringServiceObservation.query(MonitoringScrapeObservationTest.target("redis",true));for(String value:List.of("up{","redis_up{","scrape_state","scrape_time","service_state","service_time"))assertTrue(q.contains(value));}
    @Test void additionalServiceDimensionsDoNotConfuseSameJobBinding(){var b=response("1","1",5);var rows=MonitoringScrapeObservationTest.rows(b);for(int i=2;i<4;i++)((ObjectNode)rows.get(i).get("metric")).put("server","isolated-source");assertEquals("SIGNAL_SUCCESS",signal(decode(b)).get("status"));}
    @Test void wrongMetricNameCannotBecomeServiceSuccess(){var b=response("1","1",5);((ObjectNode)MonitoringScrapeObservationTest.rows(b).get(2).get("metric")).put("__name__","up");var result=signal(decode(b));assertEquals("INVALID_RESPONSE",result.get("status"));assertNull(result.get("value"));}

}
