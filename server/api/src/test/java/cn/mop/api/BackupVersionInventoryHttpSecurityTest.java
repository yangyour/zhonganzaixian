package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.PostgresBackupVersionRepository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static cn.mop.api.BackupVersionInventoryControllerTest.*;
/** Real MVC/security filter chain with synthetic service doubles; not real PostgreSQL or browser acceptance. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_backup_inventory_http"})
@ContextConfiguration(classes=BackupVersionInventoryHttpSecurityTest.Configuration.class)
class BackupVersionInventoryHttpSecurityTest {
 static final String ROOT="/api/v1/assets/"+ASSET+"/backup-versions";
 @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_backup_inventory_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration implements WebMvcConfigurer {
  @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}
  @Bean BackupVersionInventoryQuery inventory(){return mock(BackupVersionInventoryQuery.class);}
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean BackupVersionInventoryController controller(PlatformRepository platform,ObjectProvider<BackupVersionInventoryQuery> provider){return new BackupVersionInventoryController(platform,provider);}
  @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository platform;@Autowired BackupVersionInventoryQuery inventory;MockMvc mvc;
 @BeforeEach void setup(){reset(platform,inventory);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member(7)));when(inventory.inventory(any(),any(),any(),anyInt())).thenReturn(new InventoryPage(List.of(item(10)),false,null));}
 MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR));}
 @Test void anonymousAndMissingTenantAreDeniedBeforeEnumeration()throws Exception{mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());mvc.perform(get(ROOT).with(user(ACTOR))).andExpect(status().isUnauthorized());verifyNoInteractions(inventory);}
 @Test void authorizedReadIsPrivateMetadataNotDownloadOrObjectHealth()throws Exception{mvc.perform(scoped(get(ROOT))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer")).andExpect(jsonPath("$.data.asset_id").value(ASSET)).andExpect(jsonPath("$.data.items[0].registration_and_run_completion_recorded").value(true)).andExpect(jsonPath("$.data.download_authorized").value(false)).andExpect(jsonPath("$.data.current_object_health_verified").value(false)).andExpect(jsonPath("$.data.items[0].key_reference").doesNotExist()).andExpect(jsonPath("$.data.items[0].download_url").doesNotExist());}
 @Test void writeMethodsNeverReachInventoryEvenWithCsrf()throws Exception{for(String method:List.of("POST","PUT","PATCH","DELETE")){mvc.perform(scoped(request(org.springframework.http.HttpMethod.valueOf(method),ROOT))).andExpect(status().isForbidden());mvc.perform(scoped(request(org.springframework.http.HttpMethod.valueOf(method),ROOT)).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());}verifyNoInteractions(inventory);}
 @Test void forgedTenantAndDuplicatePageArgumentsAreRejected()throws Exception{mvc.perform(scoped(get(ROOT).param("tenant_id",TENANT))).andExpect(status().isBadRequest());mvc.perform(scoped(get(ROOT).param("page_size","10","20"))).andExpect(status().isBadRequest());verifyNoInteractions(inventory);}
 @Test void adminRoleCannotOverrideIndependentDenial()throws Exception{when(inventory.inventory(any(),any(),any(),anyInt())).thenThrow(new DomainException(403,"PRIVATE_POLICY_DETAIL","SYNTHETIC_PRIVATE_DETAIL"));mvc.perform(scoped(get(ROOT))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BACKUP_VERSION_INVENTORY_ACCESS_DENIED")).andExpect(jsonPath("$.data").doesNotExist());}
 @Test void unreadyServiceNeverReturnsEmptySuccess()throws Exception{when(inventory.inventory(any(),any(),any(),anyInt())).thenThrow(new DomainException(503,"PRIVATE_UNREADY","SYNTHETIC_PRIVATE_DETAIL"));mvc.perform(scoped(get(ROOT))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("BACKUP_VERSION_INVENTORY_UNAVAILABLE")).andExpect(jsonPath("$.data").doesNotExist());}
 @Test void staleMembershipSuppressesEntireResponse()throws Exception{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member(7)),Optional.of(member(8)));mvc.perform(scoped(get(ROOT))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data").doesNotExist());}
 @Test void decodedCursorIsPinnedWithoutScopeOverrides()throws Exception{when(inventory.inventory(any(),any(),any(),anyInt())).thenReturn(new InventoryPage(List.of(),false,null));mvc.perform(scoped(get(ROOT).param("page_size","50").param("before_registered_at",AT.toString()).param("before_version_id",id(30).toString()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.page_size").value(50));verify(inventory).inventory(access(),scope(),new InventoryCursor(scope(),AT,id(30)),50);}
}
