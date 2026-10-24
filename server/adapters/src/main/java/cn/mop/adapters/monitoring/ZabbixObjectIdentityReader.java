package cn.mop.adapters.monitoring;

import cn.mop.domain.monitoring.MonitoringObjectMapping.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Zabbix 7.0 host/item metadata only. Explicit own-host tags, no name/IP or inherited-tag fallback. */
public final class ZabbixObjectIdentityReader {
 private static final BigInteger MAX_ID=new BigInteger("18446744073709551615");
 public record Tags(String pool,String tenant,String asset){
  public Tags{for(var value:Arrays.asList(pool,tenant,asset))if(value==null||value.isBlank()||!value.equals(value.strip())||value.length()>128||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Explicit distinct ownership tag keys required");if(new HashSet<>(List.of(pool,tenant,asset)).size()!=3)throw new IllegalArgumentException("Ownership tag keys must be distinct");}
  @Override public String toString(){return "MonitoringOwnershipTags[configured keys redacted]";}
 }
 public record Snapshot(Key key,String sourceHostId,UUID claimedPoolId,UUID claimedTenantId,UUID claimedAssetId,String metadataSha256){@Override public String toString(){return "MonitoringObjectMetadata[redacted; not dispatch permission]";}}
 public static final class Failure extends IllegalStateException {public final String reason;Failure(String reason){super("Monitoring object metadata rejected: "+reason);this.reason=reason;}}
 @FunctionalInterface interface Reader{JsonNode read(String method,Map<String,Object> parameters);}
 private final UUID pool;private final Tags tags;private final Reader reader;
 public ZabbixObjectIdentityReader(UUID pool,Tags tags,ZabbixReadClient client){this(pool,tags,Objects.requireNonNull(client)::call);}
 ZabbixObjectIdentityReader(UUID pool,Tags tags,Reader reader){this.pool=Objects.requireNonNull(pool);this.tags=Objects.requireNonNull(tags);this.reader=Objects.requireNonNull(reader);}
 public Snapshot read(Key key){
  if(key==null||!key.poolId().equals(pool)||!numeric(key.externalId()))throw new IllegalArgumentException("Exact pool-scoped canonical Zabbix identifier required");
  try{
   String host=key.externalId();
   if(key.objectType()==ObjectType.ITEM){var item=one(reader.read("item.get",Map.of("itemids",List.of(key.externalId()),"output",List.of("itemid","hostid"),"limit",2)),Set.of("itemid","hostid"));if(!key.externalId().equals(text(item,"itemid"))||!numeric(text(item,"hostid")))throw reject("ITEM_IDENTITY_MISMATCH");host=text(item,"hostid");}
   var row=one(reader.read("host.get",Map.of("hostids",List.of(host),"output",List.of("hostid"),"selectTags",List.of("tag","value"),"limit",2)),Set.of("hostid","tags"));
   if(!host.equals(text(row,"hostid"))||!numeric(host))throw reject("HOST_IDENTITY_MISMATCH");
   var input=row.get("tags");if(!input.isArray()||input.size()>128)throw reject("OWNERSHIP_TAGS_INVALID");var selected=new HashMap<String,String>();
   for(var tag:input){if(!fields(tag,Set.of("tag","value"))||!tag.get("tag").isTextual()||!tag.get("value").isTextual())throw reject("OWNERSHIP_TAGS_INVALID");String name=text(tag,"tag");if(Set.of(tags.pool(),tags.tenant(),tags.asset()).contains(name)&&selected.putIfAbsent(name,text(tag,"value"))!=null)throw reject("OWNERSHIP_TAGS_AMBIGUOUS");}
   if(selected.size()!=3)throw reject("OWNERSHIP_TAGS_MISSING");UUID claimedPool=uuid(selected.get(tags.pool())),tenant=uuid(selected.get(tags.tenant())),asset=uuid(selected.get(tags.asset()));if(!claimedPool.equals(pool))throw reject("POOL_CLAIM_MISMATCH");
   return new Snapshot(key,host,claimedPool,tenant,asset,cn.mop.domain.monitoring.MonitoringObjectBinding.metadataHash(key,"ZABBIX_7",host,claimedPool,tenant,asset));
  }catch(Failure failure){throw failure;}catch(Exception failure){throw reject("BOUND_SOURCE_READ_UNAVAILABLE");}
 }
 private static JsonNode one(JsonNode array,Set<String> names){if(array==null||!array.isArray()||array.size()!=1||!fields(array.get(0),names))throw reject("EXACT_OBJECT_REQUIRED");return array.get(0);}
 private static boolean fields(JsonNode value,Set<String> names){if(value==null||!value.isObject()||value.size()!=names.size())return false;var fields=value.fieldNames();while(fields.hasNext())if(!names.contains(fields.next()))return false;return true;}
 private static String text(JsonNode value,String key){var n=value.get(key);return n!=null&&n.isTextual()?n.textValue():null;}
 public static boolean numeric(String value){return value!=null&&value.matches("[1-9][0-9]{0,19}")&&new BigInteger(value).compareTo(MAX_ID)<=0;}
 private static UUID uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw reject("OWNERSHIP_TAG_VALUE_INVALID");return UUID.fromString(value);}
 private static Failure reject(String reason){return new Failure(reason);}
}
