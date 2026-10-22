package cn.mop.persistence;

import cn.mop.api.*;
import cn.mop.domain.*;
import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.sql.*;import java.time.Duration;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.mop.persistence.BackupVersionPostgresTest.*;

/** Real HTTP + security filter + PostgreSQL query; synthetic login, registration proofs, keys and object source. */
@EnabledIfEnvironmentVariable(named="MOP_BACKUP_INVENTORY_HTTP_PG_TEST_ENVIRONMENT",matches="dot-backup-inventory-http-pg-disposable")
class BackupVersionInventoryHttpPostgresTest {
 static final AtomicBoolean inventoryAllowed=new AtomicBoolean(true);static final AtomicReference<UUID> deniedVersion=new AtomicReference<>();
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @Profile("isolated_inventory_http_postgres")
 @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
 @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration {
  @Bean PlatformRepository platform(ObjectMapper mapper){return new PostgresPlatformRepository(runtime,mapper);}
  @Bean BackupVersionInventoryController controller(PlatformRepository p,ObjectProvider<BackupVersionInventoryQuery> queries){return new BackupVersionInventoryController(p,queries);}
  @Bean SyntheticSession session(SecurityContextRepository contexts){return new SyntheticSession(contexts);}
 }
 @RestController @Profile("isolated_inventory_http_postgres") static class SyntheticSession {
  final SecurityContextRepository contexts;SyntheticSession(SecurityContextRepository c){contexts=c;}
  @PostMapping("/api/v1/fixture/session") Map<String,Object> login(HttpServletRequest req,HttpServletResponse res){var c=SecurityContextHolder.createEmptyContext();c.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));req.getSession().setAttribute("mop.tenant_id",tenant.toString());contexts.saveContext(c,req,res);return Map.of("synthetic",true);}
 }
 static void currentDatabaseAuthority(Connection c,Access a)throws SQLException{
  assertFalse(c.getAutoCommit());
  try(var q=c.prepareStatement("SELECT m.permission_version FROM mop_membership m JOIN mop_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active FOR SHARE OF m,u")){q.setObject(1,a.tenantId());q.setObject(2,a.actorId());try(var r=q.executeQuery()){if(!r.next()||r.getLong(1)!=a.permissionVersion())throw new DomainException(401,"EXPIRED","synthetic");}}
  PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),"assets:read");
 }
 static void initialize()throws Exception{
  String url=System.getenv("SPRING_DATASOURCE_URL");if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"))throw new IllegalStateException("Disposable loopback only");
  runtime=ds("mop_test_runtime",System.getenv("SPRING_DATASOURCE_PASSWORD"));migrator=ds("mop_test_migrator",System.getenv("MOP_MIGRATION_PASSWORD"));
  try(var c=migrator.getConnection()){
   BackupArchiveSchemaVerifier.verify(c);BackupVersionSchemaVerifier.verify(c);
   try(var q=c.createStatement();var r=q.executeQuery("SELECT tenant_id FROM mop_site ORDER BY tenant_id,id LIMIT 1")){assertTrue(r.next());tenant=r.getObject(1,UUID.class);}
   try(var q=c.createStatement();var r=q.executeQuery("SELECT id FROM mop_user WHERE role_code='padmin' ORDER BY id LIMIT 1")){assertTrue(r.next());actor=r.getObject(1,UUID.class);}
  }
  fault=Fault.NONE;revoked.set(false);objects.clear();objectChecks.set(0);completionChecks.set(0);open.set(0);threadOpen.remove();setupTracker();inventoryAllowed.set(true);deniedVersion.set(null);
  var keys=new BackupEnvelopeEncryption.DataKeyProvider(){
   public BackupEnvelopeEncryption.KeyMaterial create(BackupEnvelopeEncryption.Access a,BackupEnvelopeEncryption.Scope s){return key();}
   public BackupEnvelopeEncryption.KeyMaterial unwrap(BackupEnvelopeEncryption.Access a,BackupEnvelopeEncryption.Scope s,String r,byte[] w){return key();}
   BackupEnvelopeEncryption.KeyMaterial key(){byte[] k=new byte[32];Arrays.fill(k,(byte)31);return new BackupEnvelopeEncryption.KeyMaterial("kms-key-ref:synthetic-http-pg",new byte[]{6},k);}
  };crypto=new BackupEnvelopeEncryption(keys,(a,s,o)->true,1024);
 }
 @Test void actualDatabaseMetadataPagesRespectIndependentAuthorityOverLoopbackHttp()throws Exception{
  initialize();var f=fixture();UUID asset=f.scope().assetId();var firstRegistration=register(repo(),f,id(),1,REF,REF);BackupVersionInventoryPostgresTest.release(f);
  var second=BackupVersionInventoryPostgresTest.additional(asset);var secondRegistration=register(repo(),second,id(),1,REF,REF);BackupVersionInventoryPostgresTest.release(second);
  var empty=fixture();BackupVersionInventoryPostgresTest.release(empty);
  int objectsBefore=objectChecks.get(),completionBefore=completionChecks.get();
  var app=new SpringApplication(Configuration.class);app.setAdditionalProfiles("local_fixture","isolated_inventory_http_postgres");app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off","logging.level.root","WARN"));
  ServletWebServerApplicationContext context=null;boolean stopped=false;var statuses=new ArrayList<Integer>();var evidence=new LinkedHashMap<String,Object>();
  try{
   context=(ServletWebServerApplicationContext)app.run("--server.address=127.0.0.1","--server.port=0");String root="http://127.0.0.1:"+context.getWebServer().getPort();String target=root+"/api/v1/assets/"+asset+"/backup-versions";
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).proxy(new ProxySelector(){public List<Proxy> select(URI u){if(!"127.0.0.1".equals(u.getHost()))throw new AssertionError("Loopback only");return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress s,java.io.IOException e){}}).build();var mapper=new ObjectMapper();
   java.util.function.Function<String,HttpRequest> get=url->HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
   var anonymous=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(anonymous.statusCode());assertEquals(401,anonymous.statusCode());
   var login=client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/fixture/session")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());statuses.add(login.statusCode());assertEquals(200,login.statusCode());
   var absent=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(absent.statusCode());assertEquals(503,absent.statusCode());assertEquals("BACKUP_VERSION_INVENTORY_NOT_CONFIGURED",mapper.readTree(absent.body()).path("error").path("code").asText());
   BackupVersionInventoryQuery query=BackupVersionInventoryQuery.postgres(runtime,(c,a,s,o)->{currentDatabaseAuthority(c,a);if(o!=Operation.READ||Objects.equals(s.versionId(),deniedVersion.get()))throw new DomainException(403,"SYNTHETIC_DENIED","PRIVATE_SYNTHETIC_DETAIL");},(c,a,s)->{currentDatabaseAuthority(c,a);if(!inventoryAllowed.get())throw new DomainException(403,"SYNTHETIC_DENIED","PRIVATE_SYNTHETIC_DETAIL");});
   context.getBeanFactory().registerSingleton("explicitPostgresInventoryBinding",query);
   var beforeReads=BackupVersionInventoryPostgresTest.snapshot();
   var first=client.send(get.apply(target+"?page_size=1"),HttpResponse.BodyHandlers.ofString());statuses.add(first.statusCode());assertEquals(200,first.statusCode());JsonNode firstPage=mapper.readTree(first.body()).path("data");assertEquals(1,firstPage.path("items").size());assertTrue(firstPage.path("has_more").asBoolean());assertEquals("no-store",first.headers().firstValue("cache-control").orElseThrow());assertEquals("no-referrer",first.headers().firstValue("referrer-policy").orElseThrow());
   String cursor="?page_size=1&before_registered_at="+URLEncoder.encode(firstPage.path("next_before_registered_at").asText(),java.nio.charset.StandardCharsets.UTF_8)+"&before_version_id="+firstPage.path("next_before_version_id").asText();
   var next=client.send(get.apply(target+cursor),HttpResponse.BodyHandlers.ofString());statuses.add(next.statusCode());assertEquals(200,next.statusCode());JsonNode secondPage=mapper.readTree(next.body()).path("data");assertEquals(1,secondPage.path("items").size());assertFalse(secondPage.path("has_more").asBoolean());assertTrue(secondPage.path("next_before_version_id").isNull());
   var versions=Set.of(firstPage.path("items").get(0).path("version_id").asText(),secondPage.path("items").get(0).path("version_id").asText());assertEquals(Set.of(f.scope().versionId().toString(),second.scope().versionId().toString()),versions);
   for(JsonNode p:List.of(firstPage,secondPage)){assertEquals(actor.toString(),p.path("actor_id").asText());assertEquals(tenant.toString(),p.path("tenant_id").asText());assertTrue(p.path("total_count").isNull());for(String flag:List.of("object_storage_accessed","download_authorized","device_execution_authorized","retention_verified","recovery_verified","current_object_health_verified"))assertFalse(p.path(flag).asBoolean(true));assertFalse(p.toString().contains("kms-key-ref"));assertFalse(p.toString().contains("key_reference"));}
   var defaultResponse=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(defaultResponse.statusCode());assertEquals(200,defaultResponse.statusCode());JsonNode defaultPage=mapper.readTree(defaultResponse.body()).path("data");assertEquals(15,defaultPage.path("page_size").asInt());assertEquals(2,defaultPage.path("items").size());assertFalse(defaultPage.path("has_more").asBoolean());
   var emptyResponse=client.send(get.apply(root+"/api/v1/assets/"+empty.scope().assetId()+"/backup-versions"),HttpResponse.BodyHandlers.ofString());statuses.add(emptyResponse.statusCode());assertEquals(200,emptyResponse.statusCode());var emptyPage=mapper.readTree(emptyResponse.body()).path("data");assertEquals(0,emptyPage.path("items").size());
   assertEquals(beforeReads,BackupVersionInventoryPostgresTest.snapshot());assertEquals(objectsBefore,objectChecks.get());assertEquals(completionBefore,completionChecks.get());
   evidence.put("default_page",defaultPage);evidence.put("first_page",firstPage);evidence.put("second_page",secondPage);evidence.put("empty_page",emptyPage);evidence.put("positive_read_all_tables_unchanged",beforeReads.size());evidence.put("additional_object_or_completion_verifier_calls",0);
   var forged=client.send(get.apply(target+"?tenant_id="+tenant),HttpResponse.BodyHandlers.ofString());statuses.add(forged.statusCode());assertEquals(400,forged.statusCode());
   var unpaired=client.send(get.apply(target+"?before_version_id="+f.scope().versionId()),HttpResponse.BodyHandlers.ofString());statuses.add(unpaired.statusCode());assertEquals(400,unpaired.statusCode());
   inventoryAllowed.set(false);var denied=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(denied.statusCode());assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("PRIVATE_SYNTHETIC_DETAIL"));inventoryAllowed.set(true);
   deniedVersion.set(UUID.fromString(secondPage.path("items").get(0).path("version_id").asText()));var lookaheadDenied=client.send(get.apply(target+"?page_size=1"),HttpResponse.BodyHandlers.ofString());statuses.add(lookaheadDenied.statusCode());assertEquals(403,lookaheadDenied.statusCode());assertFalse(mapper.readTree(lookaheadDenied.body()).has("data"));deniedVersion.set(null);
   var unknown=client.send(get.apply(root+"/api/v1/assets/"+id()+"/backup-versions"),HttpResponse.BodyHandlers.ofString());statuses.add(unknown.statusCode());assertEquals(404,unknown.statusCode());
   assertEquals(objectsBefore,objectChecks.get());assertEquals(completionBefore,completionChecks.get());assertEquals(0,open.get());
   evidence.put("result","PASS_REAL_HTTP_AND_POSTGRES_WITH_SYNTHETIC_LOGIN_AND_INDEPENDENT_GRANTS");
  }finally{
   if(context!=null){context.close();stopped=!context.isActive();}objects.clear();inventoryAllowed.set(true);deniedVersion.set(null);
   evidence.put("http_statuses",statuses);evidence.put("server_stopped",stopped);evidence.put("real_kms_or_provider_access",false);evidence.put("synthetic_registration_and_completion_proofs",true);
   String output=System.getProperty("mop.inventory.http.pg.evidence");if(output!=null)new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(),evidence);
  }
 }
}
