package cn.mop.adapters.monitoring;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectMapping.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class ZabbixObjectIdentityReaderTest {
 final UUID pool=UUID.randomUUID(),tenant=UUID.randomUUID(),asset=UUID.randomUUID();final ObjectMapper json=new ObjectMapper();
 final ZabbixObjectIdentityReader.Tags tags=new ZabbixObjectIdentityReader.Tags("pool","tenant","asset");
 JsonNode host(){return json.valueToTree(List.of(Map.of("hostid","23","tags",List.of(Map.of("tag","pool","value",pool.toString()),Map.of("tag","tenant","value",tenant.toString()),Map.of("tag","asset","value",asset.toString())))));}
 @Test void hostUsesExplicitOwnTagsAndExactId(){var reader=new ZabbixObjectIdentityReader(pool,tags,(method,params)->{assertEquals("host.get",method);assertEquals(List.of("23"),params.get("hostids"));assertEquals(List.of("tag","value"),params.get("selectTags"));assertFalse(params.containsKey("selectInheritedTags"));return host();});var key=new Key(pool,ObjectType.HOST,"23");var row=reader.read(key);assertEquals(asset,row.claimedAssetId());assertEquals(MonitoringObjectBinding.metadataHash(key,"ZABBIX_7","23",pool,tenant,asset),row.metadataSha256());}
 @Test void itemResolvesHostThenOwnTags(){var calls=new ArrayList<String>();var reader=new ZabbixObjectIdentityReader(pool,tags,(method,params)->{calls.add(method);if(method.equals("item.get")){assertEquals(List.of("44"),params.get("itemids"));return json.valueToTree(List.of(Map.of("itemid","44","hostid","23")));}assertEquals(List.of("23"),params.get("hostids"));return host();});assertEquals("23",reader.read(new Key(pool,ObjectType.ITEM,"44")).sourceHostId());assertEquals(List.of("item.get","host.get"),calls);}
 @Test void noIpNameOrLeadingZeroFallback(){for(String value:List.of("0","023","host-a","192.0.2.1","18446744073709551616"))assertThrows(IllegalArgumentException.class,()->new ZabbixObjectIdentityReader(pool,tags,(m,p)->{throw new AssertionError();}).read(new Key(pool,ObjectType.HOST,value)));assertTrue(ZabbixObjectIdentityReader.numeric("18446744073709551615"));}
 @Test void multipleObjectsNotNewestWinner(){var rows=host().deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)rows).add(rows.get(0).deepCopy());assertFailure(rows,"EXACT_OBJECT_REQUIRED");}
 @Test void missingTagNeverFilledFromMapping(){var rows=host();((com.fasterxml.jackson.databind.node.ArrayNode)rows.get(0).get("tags")).remove(1);assertFailure(rows,"OWNERSHIP_TAGS_MISSING");}
 @Test void duplicateEvenSameValueRejected(){var rows=host();var values=(com.fasterxml.jackson.databind.node.ArrayNode)rows.get(0).get("tags");values.add(values.get(0).deepCopy());assertFailure(rows,"OWNERSHIP_TAGS_AMBIGUOUS");}
 @Test void foreignPoolRejected(){var rows=host();((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0).get("tags").get(0)).put("value",UUID.randomUUID().toString());assertFailure(rows,"POOL_CLAIM_MISMATCH");}
 @Test void noncanonicalUuidRejected(){var rows=host();((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0).get("tags").get(1)).put("value","1-1-1-1-1");assertFailure(rows,"OWNERSHIP_TAG_VALUE_INVALID");}
 @Test void wrongHostAndExtraFieldsRejected(){var rows=host();((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("hostid","24");assertFailure(rows,"HOST_IDENTITY_MISMATCH");((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(0)).put("secret","must not escape");assertFailure(rows,"EXACT_OBJECT_REQUIRED");}
 @Test void providerExceptionIsSanitized(){var failure=assertThrows(ZabbixObjectIdentityReader.Failure.class,()->new ZabbixObjectIdentityReader(pool,tags,(m,p)->{throw new IllegalStateException("private credentials");}).read(new Key(pool,ObjectType.HOST,"23")));assertFalse(failure.getMessage().contains("credentials"));assertEquals("BOUND_SOURCE_READ_UNAVAILABLE",failure.reason);}
 @Test void ownTagKeysMustBeExplicitAndDistinct(){assertThrows(IllegalArgumentException.class,()->new ZabbixObjectIdentityReader.Tags(null,"a","b"));assertThrows(IllegalArgumentException.class,()->new ZabbixObjectIdentityReader.Tags("a","a","b"));}
 void assertFailure(JsonNode rows,String reason){var failure=assertThrows(ZabbixObjectIdentityReader.Failure.class,()->new ZabbixObjectIdentityReader(pool,tags,(m,p)->rows).read(new Key(pool,ObjectType.HOST,"23")));assertEquals(reason,failure.reason);}
}
