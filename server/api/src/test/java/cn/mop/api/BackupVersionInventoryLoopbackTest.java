package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.mop.api.BackupVersionInventoryControllerTest.*;
/** Real loopback HTTP/filter/session only. All accounts and inventory authority/storage are synthetic; never connect a DB or provider. */
@EnabledIfEnvironmentVariable(named="MOP_BACKUP_VERSION_INVENTORY_HTTP_TEST_ENVIRONMENT",matches="dot-backup-version-inventory-disposable-http")
class BackupVersionInventoryLoopbackTest {
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @Profile("isolated_backup_inventory_loopback")
 @EnableAutoConfiguration(exclude={DataSourceAutoConfiguration.class,UserDetailsServiceAutoConfiguration.class})
 @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration {
  @Bean PlatformRepository platform(){var p=mock(PlatformRepository.class);when(p.membership(ACTOR,TENANT)).thenReturn(Optional.of(member(7)));return p;}
  @Bean BackupVersionInventoryController controller(PlatformRepository platform,ObjectProvider<BackupVersionInventoryQuery> queries){return new BackupVersionInventoryController(platform,queries);}
  @Bean FixtureSession fixtureSession(SecurityContextRepository contexts){return new FixtureSession(contexts);}
 }
 @RestController @Profile("isolated_backup_inventory_loopback") static class FixtureSession {
  final SecurityContextRepository contexts;FixtureSession(SecurityContextRepository contexts){this.contexts=contexts;}
  @PostMapping("/api/v1/fixture/session") Map<String,Object> login(HttpServletRequest request,HttpServletResponse response){var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR,null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));request.getSession().setAttribute("mop.tenant_id",TENANT);contexts.saveContext(context,request,response);return Map.of("synthetic",true);}
 }
 @Test void privateReadDefaultMissingBindingAndIndependentDenialOverRealLoopbackHttp()throws Exception{
  var app=new SpringApplication(Configuration.class);app.setAdditionalProfiles("local_fixture","isolated_backup_inventory_loopback");app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.main.banner-mode","off","logging.level.root","WARN"));
  var statuses=new ArrayList<Integer>();ServletWebServerApplicationContext context=null;boolean stopped=false;
  try{
   context=(ServletWebServerApplicationContext)app.run("--server.address=127.0.0.1","--server.port=0");int port=context.getWebServer().getPort();String root="http://127.0.0.1:"+port;String target=root+"/api/v1/assets/"+ASSET+"/backup-versions";
   var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).cookieHandler(cookies).proxy(new ProxySelector(){public List<Proxy> select(URI uri){if(!"127.0.0.1".equals(uri.getHost()))throw new AssertionError("Loopback only");return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress sa,java.io.IOException error){}}).build();
   java.util.function.Function<String,HttpRequest> get=url->HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();var mapper=new ObjectMapper();
   var anonymous=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(anonymous.statusCode());assertEquals(401,anonymous.statusCode());
   var login=client.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/fixture/session")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());statuses.add(login.statusCode());assertEquals(200,login.statusCode());
   var missing=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(missing.statusCode());assertEquals(503,missing.statusCode());assertEquals("BACKUP_VERSION_INVENTORY_NOT_CONFIGURED",mapper.readTree(missing.body()).path("error").path("code").asText());
   var authorized=new AtomicBoolean(true);BackupVersionInventoryQuery reader=(a,s,b,n)->{assertEquals(access(),a);assertEquals(scope(),s);if(!authorized.get())throw new DomainException(403,"SYNTHETIC_DENIAL","SYNTHETIC_PRIVATE_DETAIL");return new InventoryPage(List.of(item(10)),false,null);};context.getBeanFactory().registerSingleton("explicitSyntheticInventoryBinding",reader);
   var success=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(success.statusCode());assertEquals(200,success.statusCode());var result=mapper.readTree(success.body()).path("data");assertEquals(ASSET,result.path("asset_id").asText());assertFalse(result.path("download_authorized").asBoolean(true));assertFalse(result.path("object_storage_accessed").asBoolean(true));assertFalse(result.path("items").get(0).has("key_reference"));assertEquals("no-store",success.headers().firstValue("cache-control").orElseThrow());
   var forged=client.send(get.apply(target+"?tenant_id="+TENANT),HttpResponse.BodyHandlers.ofString());statuses.add(forged.statusCode());assertEquals(400,forged.statusCode());
   authorized.set(false);var denied=client.send(get.apply(target),HttpResponse.BodyHandlers.ofString());statuses.add(denied.statusCode());assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("SYNTHETIC_PRIVATE_DETAIL"));
  }finally{if(context!=null){context.close();stopped=!context.isActive();}String output=System.getProperty("mop.inventory.http.evidence");if(output!=null)new ObjectMapper().writeValue(Path.of(output).toFile(),Map.of("scope","REAL_LOOPBACK_HTTP_WITH_SYNTHETIC_AUTHORITY_AND_STORAGE_NOT_DATABASE_ACCEPTANCE","http_statuses",statuses,"server_stopped",stopped,"real_database_or_provider_access",false));}
 }
}
