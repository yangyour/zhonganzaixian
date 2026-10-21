package cn.mop.persistence;
import cn.mop.adapters.cmdb.CustomerCmdbDictionary;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import cn.mop.importparser.ImportFileParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.sql.*;
public class SeedCmdbSource {
 public static void main(String[] ignored)throws Exception{
  if(!"dot-integration-fresh-pg17-11".equals(System.getenv("MOP_TEST_ENVIRONMENT_NAME"))||!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(System.getenv("SPRING_DATASOURCE_URL")))throw new IllegalStateException("Disposable fixture environment required");
  var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername("mop_test_runtime");cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
  try(var ds=new HikariDataSource(cfg)){
   var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
   var platform=new PostgresPlatformRepository(ds,mapper);var repo=new PostgresAssetImportRepository(ds,mapper,platform);
   String tenant="55000000-0000-4000-8000-000000000001";
   String actor=platform.users().stream().filter(u->platform.membership(u.id(),tenant).map(m->m.role().equals("msp")).orElse(false)).findFirst().orElseThrow().id();
   var access=new AssetImportSourceRepository.Access(actor,tenant,platform.membership(actor,tenant).orElseThrow().permissionVersion(),"synthetic-cmdb-source");
   var headers=new ArrayList<String>();CustomerCmdbDictionary.load(mapper).document().path("import_columns").elements().forEachRemaining(v->headers.add(v.asText()));
   String[] row=new String[33];Arrays.fill(row,"");try(var c=ds.getConnection();var st=c.prepareStatement("SELECT name FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id LIMIT 1")){st.setString(1,tenant);try(var rs=st.executeQuery()){if(!rs.next())throw new IllegalStateException("Synthetic fixture asset missing");row[0]=rs.getString(1);}}row[1]="SW";row[2]="192.0.2.1";row[16]="sw_core";
   String first=String.join(",",row);row[0]=" "+row[0];row[2]="192.0.2.2";String second=String.join(",",row);row[0]="SYNTHETIC_FOREIGN_ONLY";row[2]="192.0.2.3";String third=String.join(",",row);row[0]="SYNTHETIC_UNMATCHED_"+UUID.randomUUID();row[2]="192.0.2.4";
   byte[] bytes=(String.join(",",headers)+"\r\n"+first+"\r\n"+second+"\r\n"+third+"\r\n"+String.join(",",row)+"\r\n").getBytes(StandardCharsets.UTF_8);
   Map<String,Object> accepted=(Map<String,Object>)platform.idempotent(actor,tenant,"/qa/cmdb-source",UUID.randomUUID().toString(),PostgresAssetImportRepository.sha(bytes),access.permissionVersion(),()->repo.acceptSource(access,"synthetic-cmdb.csv",ImportFormat.CSV,bytes));
   String sourceId=(String)accepted.get("source_id");if(sourceId==null)throw new IllegalStateException("Source ID missing");
   var store=new AssetImportObjectStore(){final Map<String,byte[]> objects=new HashMap<>();public boolean ready(){return true;}public void storeImmutable(String key,byte[] value,ImportFormat format,String hash){if(!hash.equals(PostgresAssetImportRepository.sha(value)))throw new IllegalStateException();objects.put(key,value.clone());}public byte[] read(String key,long size,String hash){byte[] value=objects.get(key);if(value==null||size!=value.length||!hash.equals(PostgresAssetImportRepository.sha(value)))throw new IllegalStateException();return value.clone();}};
   var parser=new ImportFileParser();if(!repo.processNext("cmdb-fixture-store",store,parser,Set.of(sourceId)))throw new IllegalStateException("Store not processed");
   var current=repo.source(access,sourceId);long version=((Number)current.get("version")).longValue();
   platform.idempotent(actor,tenant,"/qa/cmdb-parse",UUID.randomUUID().toString(),"a".repeat(64),access.permissionVersion(),()->repo.requestParse(access,sourceId,version,new ParseSelection(ImportFormat.CSV,"UTF-8",",",null,1)));
   if(!repo.processNext("cmdb-fixture-parse",store,parser,Set.of(sourceId)))throw new IllegalStateException("Parse not processed");
   current=repo.source(access,sourceId);if(!"PARSED".equals(current.get("parse_status")))throw new IllegalStateException("Source not parsed");
   System.out.println("SEED_RESULT="+mapper.writeValueAsString(Map.of("source_id",sourceId,"source_version",current.get("version"),"parse_hash",((Map<?,?>)current.get("parsed_artifact")).get("parse_hash"),"store_adapter","ISOLATED_MEMORY_TEST_DOUBLE","parser","REAL_IMPORT_FILE_PARSER","asset_writes",false)));
  }
 }
}
