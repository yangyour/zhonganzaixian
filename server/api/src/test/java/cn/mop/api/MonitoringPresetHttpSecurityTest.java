package cn.mop.api;
import cn.mop.domain.*;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
/** Existing application security chain with mocked repositories/probe; not a real identity-provider test. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","monitoring_preset_http_unit"})
@ContextConfiguration(classes=MonitoringPresetHttpSecurityTest.Configuration.class)
class MonitoringPresetHttpSecurityTest {
 static final String ACTOR=MonitoringPresetControllerTest.ACTOR,TENANT=MonitoringPresetControllerTest.TENANT;
 static final String BASE="/api/v1/monitoring-onboarding/registered-targets";
 @org.springframework.boot.test.context.TestConfiguration @Profile("monitoring_preset_http_unit") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration {
  @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean AtomicInteger probes(){return new AtomicInteger();}
  @Bean MonitoringPresetRegistry registry(){return MonitoringPresetTest.registry(MonitoringPresetTest.config("linux-node"));}
  @Bean MonitoringPresetProbeService service(MonitoringPresetRegistry r,AtomicInteger probes){return new MonitoringPresetProbeService(r,t->{probes.incrementAndGet();return MonitoringPresetProbeService.outcome(t,"FAILED","CONNECTION_FAILED");});}
  @Bean MonitoringOnboardingPlanner onboardingPlanner(){return MonitoringOnboardingPlannerTest.empty();}
  @Bean MonitoringOnboardingController onboardingController(PlatformRepository p,MonitoringOnboardingPlanner planner){return new MonitoringOnboardingController(p,planner);}
  @Bean MonitoringPresetController controller(PlatformRepository p,MonitoringPresetProbeService s){return new MonitoringPresetController(p,s);}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository repository;@Autowired MonitoringPresetRegistry registry;@Autowired AtomicInteger probes;
 MockMvc mvc;MonitoringPresetRegistry.Target target;
 @BeforeEach void setup(){reset(repository);probes.set(0);target=registry.forTenant(TENANT).get(0);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));when(repository.authorizedAsset(TENANT,ACTOR,7,target.assetId())).thenReturn(Optional.of(new Asset(target.assetId(),TENANT,"Synthetic","other","192.0.2.10","site","unknown","DRAFT","test_seed",null,null,1,Instant.EPOCH,Instant.EPOCH,null,null)));mvc=webAppContextSetup(context).apply(springSecurity()).build();}
 String body(){return "{\"configuration_sha256\":\""+target.hash()+"\"}";}
 @Test void anonymousCannotEnumerateTargets()throws Exception{mvc.perform(get(BASE)).andExpect(status().isUnauthorized());assertEquals(0,probes.get());}
 @Test void csrfRequiredBeforeProbeSideEffect()throws Exception{mvc.perform(post(BASE+"/"+target.id()+"/test").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content(body())).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));assertEquals(0,probes.get());}
 @Test void validCsrfAndCurrentScopePermitExactlyOneProbe()throws Exception{mvc.perform(post(BASE+"/"+target.id()+"/test").with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content(body())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.source").value("SERVER_ONE_SHOT_PROBE")).andExpect(jsonPath("$.data.report.health_verified").value(false));assertEquals(1,probes.get());}
 @Test void listRequiresBothCapabilitiesAndReturnsNoProbe()throws Exception{mvc.perform(get(BASE).with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].target_id").value(target.id()));assertEquals(0,probes.get());when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true,List.of("assets:read"))));mvc.perform(get(BASE).with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isForbidden());}
 @Test void authenticatedScopeCannotSubmitDestinationOverride()throws Exception{mvc.perform(post(BASE+"/"+target.id()+"/test").with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content("{\"endpoint\":\"http://192.0.2.1:1/\"}")).andExpect(status().isBadRequest());assertEquals(0,probes.get());}

 @Test void expandedQueryIsAcceptedOnlyForAuthorizedRead()throws Exception{
  mvc.perform(get(BASE).param("view","expanded").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].target_id").value(target.id()));
  mvc.perform(get(BASE).param("view","expanded")).andExpect(status().isUnauthorized());
  mvc.perform(get(BASE).param("view","expanded","expanded").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isBadRequest());assertEquals(0,probes.get());
 }

 String planBody(){return "{\"version\":1,\"assets\":[{\"asset_id\":\""+target.assetId()+"\",\"profiles\":[\"linux-node\"]}]}";}
 @Test void catalogHasScopedSchemasButAnonymousCannotRead()throws Exception{
  mvc.perform(get("/api/v1/monitoring-onboarding/presets")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/monitoring-onboarding/presets").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.catalog.presets.length()").value(13));assertEquals(0,probes.get());
 }
 @Test void planRequiresCsrfEvenThoughItIsPreparationOnly()throws Exception{
  mvc.perform(post("/api/v1/monitoring-onboarding/plans").with(user(ACTOR)).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content(planBody())).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));assertEquals(0,probes.get());
 }
 @Test void validPlanUsesCurrentAssetAndNeverCallsProbe()throws Exception{
  mvc.perform(post("/api/v1/monitoring-onboarding/plans").with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content(planBody())).andExpect(status().isOk()).andExpect(jsonPath("$.data.plan.valid").value(true)).andExpect(jsonPath("$.data.plan.configuration.targets[0].endpoint").value("http://192.0.2.10:9100/metrics")).andExpect(jsonPath("$.data.plan.configuration.targets[0].enabled").value(false)).andExpect(jsonPath("$.data.asset_scope_checked").value(true));assertEquals(0,probes.get());
 }
 @Test void planRejectsCredentialOverridesAndReadOnlyRole()throws Exception{
  mvc.perform(post("/api/v1/monitoring-onboarding/plans").with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content(planBody().replace("\"version\":1","\"version\":1,\"endpoint\":\"http://127.0.0.1:1/\""))).andExpect(status().isBadRequest());
  when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true,List.of("assets:read","configurations:read"))));
  mvc.perform(post("/api/v1/monitoring-onboarding/plans").with(user(ACTOR)).with(csrf().asHeader()).sessionAttr("mop.tenant_id",TENANT).contentType("application/json").content(planBody())).andExpect(status().isForbidden());assertEquals(0,probes.get());
 }
}
