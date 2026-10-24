package cn.mop.adapters.monitoring;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectMapping.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class ZabbixMappedEventReaderTest {
 final UUID pool=UUID.randomUUID();final Key key=new Key(pool,ObjectType.HOST,"23");final ObjectMapper json=new ObjectMapper();
 JsonNode row(int phase,List<String> hosts){var data=new HashMap<String,Object>();data.put("eventid","44");data.put("source","0");data.put("object","0");data.put("objectid","55");data.put("clock","1792800000");data.put("ns","123456789");data.put("value",String.valueOf(phase));data.put("severity","4");data.put("r_eventid","0");data.put("hosts",hosts.stream().map(h->Map.of("hostid",h)).toList());return json.valueToTree(List.of(data));}
 ZabbixMappedEventReader reader(JsonNode rows){return new ZabbixMappedEventReader(pool,q->{assertEquals("44",q.firstEventId());assertEquals("44",q.lastEventId());assertEquals(1,q.pageSize());return ZabbixEventPuller.decode(q,rows);});}
 @Test void exactProblemPreservesSourceNanosecondsAndCanonicalAllowlist()throws Exception{var event=reader(row(1,List.of("23"))).read(key,"44");assertEquals(RawMonitoringEvent.Phase.PROBLEM,event.phase());assertEquals(123456789,event.occurredAt().getNano());assertEquals("23",event.sourceHostId());var payload=json.readTree(event.payload());assertEquals("55",payload.get("trigger_id").asText());assertEquals(9,payload.size());assertFalse(payload.has("tenant_id"));byte[] copy=event.payload();copy[0]=0;assertNotEquals(0,event.payload()[0]);}
 @Test void rawRecoveryRemainsOnlySourcePhaseNotObservedT2(){var event=reader(row(0,List.of("23"))).read(key,"44");assertEquals(RawMonitoringEvent.Phase.RECOVERED,event.phase());assertFalse(new String(event.payload(),java.nio.charset.StandardCharsets.UTF_8).contains("confirmed_t2"));}
 @Test void foreignOrMultipleHostEventIsNeverAFirstMatchWinner(){for(var hosts:List.of(List.of("24"),List.of("23","24"),List.<String>of()))assertEquals(404,assertThrows(DomainException.class,()->reader(row(1,hosts)).read(key,"44")).status);}
 @Test void emptyExactQueryIsNotAStoredOrHealthyResult(){assertEquals(404,assertThrows(DomainException.class,()->reader(json.createArrayNode()).read(key,"44")).status);}
 @Test void itemCannotBeInferredFromHostEvent(){assertEquals(422,assertThrows(DomainException.class,()->new ZabbixMappedEventReader(pool,q->{throw new AssertionError("Must not read source");}).read(new Key(pool,ObjectType.ITEM,"99"),"44")).status);}
 @Test void providerFailureIsSanitized(){var e=assertThrows(DomainException.class,()->new ZabbixMappedEventReader(pool,q->{throw new IllegalStateException("PRIVATE_SOURCE_TOKEN");}).read(key,"44"));assertEquals(503,e.status);assertFalse(e.getMessage().contains("TOKEN"));}
 @Test void noncanonicalIdNeverCallsSource(){assertEquals(400,assertThrows(DomainException.class,()->new ZabbixMappedEventReader(pool,q->{throw new AssertionError();}).read(key,"044")).status);}
}
