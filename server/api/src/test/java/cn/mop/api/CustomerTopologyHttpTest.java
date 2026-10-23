package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.assetdependency.AssetDependencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
/** Real MVC/security chain, mocked data repositories; not live PostgreSQL acceptance. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","customer_topology_http_unit"})
@ContextConfiguration(classes=CustomerTopologyHttpTest.Configuration.class)
class CustomerTopologyHttpTest {
 static final String ACTOR=CustomerTopologyControllerTest.ACTOR,TENANT=CustomerTopologyControllerTest.TENANT,BASE="/api/v1/customer-topology";
 @org.springframework.boot.test.context.TestConfiguration @Profile("customer_topology_http_unit") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration {
  @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
  @Bean AssetDependencyRepository dependencies(){return mock(AssetDependencyRepository.class);}
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean AssetDependencyController legacyController(PlatformRepository p,AssetDependencyRepository d,ObjectMapper mapper){return new AssetDependencyController(p,d,mapper);}
  @Bean CustomerTopologyController controller(PlatformRepository p,AssetDependencyRepository d){return new CustomerTopologyController(p,d);}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository repository;@Autowired AssetDependencyRepository dependencies;MockMvc mvc;
 @BeforeEach void setup(){reset(repository,dependencies);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));when(dependencies.current(TENANT,ACTOR)).thenReturn(new AssetDependencyRepository.Draft(TENANT,0,null,List.of()));mvc=webAppContextSetup(context).apply(springSecurity()).build();}
 @Test void anonymousCannotReadTopology()throws Exception{mvc.perform(get(BASE)).andExpect(status().isUnauthorized());verifyNoInteractions(dependencies);}
 @Test void viewerWithAssetsCapabilityReadsOnlyCurrentCustomerAndNoStore()throws Exception{mvc.perform(get(BASE).with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT).header("X-Tenant-ID",CustomerTopologyControllerTest.id(999))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.tenant_id").value(TENANT)).andExpect(jsonPath("$.data.physical_topology_verified").value(false)).andExpect(jsonPath("$.data.nodes").isEmpty());}
 @Test void capabilityRevocationIsEnforcedByBackend()throws Exception{when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true,List.of())));mvc.perform(get(BASE).with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isForbidden());verifyNoInteractions(dependencies);}
 @Test void duplicateAndCrossTenantQueriesFail()throws Exception{for(var params:List.of(new String[]{"tenant_id",TENANT},new String[]{"offset","0","0"})){var r=get(BASE).with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT).param(params[0],Arrays.copyOfRange(params,1,params.length));mvc.perform(r).andExpect(status().isBadRequest());}verifyNoInteractions(dependencies);}
 @Test void postIsNotAnActivationRouteEvenWithCsrf()throws Exception{mvc.perform(post(BASE).with(user(ACTOR)).with(csrf()).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isMethodNotAllowed());verifyNoInteractions(dependencies);}
 @Test void versionConflictHasActionableCode()throws Exception{mvc.perform(get(BASE).param("graph_version","1").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TOPOLOGY_VERSION_CHANGED"));}
 @Test void legacyDependencyReadRejectsRevocationDuringRepositoryRead()throws Exception{
  String asset=CustomerTopologyControllerTest.A;
  when(repository.authorizedAsset(TENANT,ACTOR,7,asset)).thenReturn(Optional.of(new Asset(asset,TENANT,"Synthetic","switch","192.0.2.1","site","unknown","DRAFT","manual",null,null,1,null,null,null,null)));
  when(dependencies.current(TENANT,ACTOR)).thenAnswer(call->{when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());return new AssetDependencyRepository.Draft(TENANT,0,null,List.of());});
  mvc.perform(get("/api/v1/assets/"+asset+"/dependencies").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data").doesNotExist());
 }
 @Test void legacyDependencyReadRemainsReadOnlyForAuthorizedViewer()throws Exception{
  String asset=CustomerTopologyControllerTest.A;
  when(repository.authorizedAsset(TENANT,ACTOR,7,asset)).thenReturn(Optional.of(new Asset(asset,TENANT,"Synthetic","switch","192.0.2.1","site","unknown","DRAFT","manual",null,null,1,null,null,null,null)));
  mvc.perform(get("/api/v1/assets/"+asset+"/dependencies").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isOk()).andExpect(jsonPath("$.data.tenant_id").value(TENANT)).andExpect(jsonPath("$.data.monitoring_applied").value(false));
  verify(dependencies,never()).append(anyString(),anyString(),anyLong(),anyList(),any());
 }
}