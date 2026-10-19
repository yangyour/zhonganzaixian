import cn.mop.persistence.*;import cn.mop.domain.*;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;import org.postgresql.ds.PGSimpleDataSource;import java.util.*;
public class DotSyntheticReceipts {
 public static void main(String[] args){
  if(!"dot-upstream-e739027-fresh-pg".equals(System.getenv("MOP_TEST_ENVIRONMENT_NAME")))throw new IllegalStateException();
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("SPRING_DATASOURCE_URL"));ds.setUser(System.getenv("SPRING_DATASOURCE_USERNAME"));ds.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));
  var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  var repo=new PostgresPlatformRepository(ds,mapper);
  for(String seed:List.of("viewerA","viewerB","tadmin")){
   String actor=repo.users().stream().filter(u->u.id().equals(uuid(seed))).findFirst().orElseThrow().id();String tenant=seed.equals("viewerB")?"55000000-0000-4000-8000-000000000002":"55000000-0000-4000-8000-000000000001";
   repo.idempotent(actor,tenant,"POST /dot-disposable/synthetic-receipts",UUID.randomUUID().toString(),"d".repeat(64),repo.membership(actor,tenant).orElseThrow().permissionVersion(),()->{
    var r=repo.registerCustomerReport(tenant,actor,new CustomerReport(null,"DOT DISPOSABLE SYNTHETIC TEST RECEIPT "+seed,"网络不通","company","虚构联系人","+86 (010) 1234-5678","urgent",null));
    repo.audit(actor,tenant,"dot.disposable.test.receipt","ticket",r.ticketId(),"SUCCEEDED","SYNTHETIC_LOCAL_TEST_ONLY",UUID.randomUUID().toString());return Map.of("operation_id",r.operationId());
   });
  }
  System.out.println("DOT_TEST_SEED synthetic_receipts=3 tenants=2 actual_repository_transactions=true");
 }
 static String uuid(String seed){try{var bytes=java.security.MessageDigest.getInstance("MD5").digest(("mop-fixture-user-"+seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));var h=HexFormat.of().formatHex(bytes);return h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20);}catch(Exception e){throw new RuntimeException(e);}}
}
