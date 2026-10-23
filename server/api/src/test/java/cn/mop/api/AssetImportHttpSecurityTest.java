package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Actual application security filter with isolated ports; this is not remote OIDC evidence. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_asset_import_http"})
@ContextConfiguration(classes=AssetImportHttpSecurityTest.Configuration.class)
class AssetImportHttpSecurityTest {
    private static final String ACTOR=AssetImportControllerTest.ACTOR,TENANT=AssetImportControllerTest.TENANT,SOURCE=AssetImportControllerTest.SOURCE,OP=AssetImportControllerTest.OP;
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_asset_import_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
        @Bean AssetImportSourceRepository sources(){return mock(AssetImportSourceRepository.class);}
        @Bean AssetImportObjectStore store(){return mock(AssetImportObjectStore.class);}
        @Bean
        cn.mop.domain.assetimport.CmdbImportExecutionRepository executions() {
            return mock(cn.mop.domain.assetimport.CmdbImportExecutionRepository.class);
        }
        @Bean
        CmdbImportExecutionController executionController(PlatformRepository repository) {
            return new CmdbImportExecutionController(repository, executions());
        }
        @Bean ObjectMapper mapper(){return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
        @Bean AssetImportController imports(PlatformRepository repository,AssetImportSourceRepository sources,AssetImportObjectStore store,ObjectMapper mapper){return new AssetImportController(repository,sources,store,new AssetImportPolicy(),mapper);}
        @Bean FileController photos(PlatformRepository repository,ObjectMapper mapper){return new FileController(repository,new PhotoPolicy("image/png,image/jpeg"),mock(PhotoObjectStore.class),mapper);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;@Autowired PlatformRepository repository;@Autowired AssetImportSourceRepository sources;@Autowired AssetImportObjectStore store;private MockMvc mvc;
    @BeforeEach void setup(){reset(repository,sources,store);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",9,true)));when(sources.ready()).thenReturn(true);when(store.ready()).thenReturn(true);when(repository.idempotent(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any())).thenAnswer(call->call.getArgument(6,Supplier.class).get());when(sources.acceptSource(any(),anyString(),any(),any())).thenReturn(Map.of("source_id",SOURCE,"operation_id",OP,"status","PENDING"));when(sources.requestParse(any(),anyString(),anyLong(),any())).thenReturn(Map.of("source_id",SOURCE,"operation_id",OP,"status","PENDING"));}
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder request){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return request.session(session).with(user(ACTOR)).header("Idempotency-Key","isolated-http-import");}
    private MockPart file(){return new MockPart("file","test.csv","设备名称,备注\r\n虚构设备,测试\r\n".getBytes(StandardCharsets.UTF_8));}
    private MockPart purpose(){return new MockPart("purpose","asset_import_source".getBytes(StandardCharsets.UTF_8));}
    private MockPart format(){return new MockPart("format","CSV".getBytes(StandardCharsets.UTF_8));}
    @Test void writeCsrfAndAnonymousReadsUseExistingEnvelopes()throws Exception{
        mvc.perform(scoped(multipart("/api/v1/assets/import-sources").part(file(),purpose(),format()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(scoped(post("/api/v1/assets/import-sources/"+SOURCE+"/parses").contentType(MediaType.APPLICATION_JSON).content(AssetImportControllerTest.selection()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assets/import-policy")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));verifyNoInteractions(sources,store);
    }
    @Test void acceptedReadbackIdsAndNoStoreDoNotClaimImportSuccess()throws Exception{
        mvc.perform(scoped(multipart("/api/v1/assets/import-sources").part(file(),purpose(),format())).with(csrf().asHeader())).andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.source_id").value(SOURCE)).andExpect(jsonPath("$.data.operation_id").value(OP)).andExpect(jsonPath("$.data.status").value("PENDING")).andExpect(jsonPath("$.data.can_import").doesNotExist());
        mvc.perform(scoped(get("/api/v1/assets/import-policy"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_import").value(false)).andExpect(jsonPath("$.data.business_status").value("CONFIG_PENDING"));verify(store,never()).storeImmutable(anyString(),any(),any(),anyString());
    }
    @Test void currentRoleOverridesBrowserAuthoritiesAndExpiredMembershipCannotRead()throws Exception{
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",10,true)));mvc.perform(scoped(get("/api/v1/assets/import-policy")).with(user(ACTOR).roles("PADMIN"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());mvc.perform(scoped(get("/api/v1/assets/import-sources/"+SOURCE))).andExpect(status().isUnauthorized());verifyNoInteractions(sources,store);
    }
    @Test void duplicateTrailingUnknownAndFractionalJsonCannotRequestParse()throws Exception{
        for(String body:List.of(AssetImportControllerTest.selection().replace("\"expected_version\":2","\"expected_version\":2,\"expected_version\":3"),AssetImportControllerTest.selection()+" {}",AssetImportControllerTest.selection().replace("\"header_row\":1","\"header_row\":1.5"),AssetImportControllerTest.selection().replace("\"encoding\":\"UTF-8\"","\"tenant_id\":\"forged\"")))mvc.perform(scoped(post("/api/v1/assets/import-sources/"+SOURCE+"/parses").contentType(MediaType.APPLICATION_JSON).content(body)).with(csrf().asHeader())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));verifyNoInteractions(sources,store);
    }
    @Test void originalSourceMissingOrUnavailablePreserves404And503()throws Exception{
        when(sources.source(any(),eq(SOURCE))).thenThrow(new DomainException(404,"OBJECT_NOT_FOUND","对象不可见"));mvc.perform(scoped(get("/api/v1/assets/import-sources/"+SOURCE))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));when(sources.source(any(),eq(SOURCE))).thenThrow(new DomainException(503,"UPSTREAM_UNAVAILABLE","指定源不可用"));mvc.perform(scoped(get("/api/v1/assets/import-sources/"+SOURCE))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").doesNotExist());verifyNoInteractions(store);
    }
    @Test void oversizedDeepManyNodeAndLongNameBodiesAreRejectedBeforeTreeOrSourceAccess()throws Exception{
        for(String body:List.of("{\"unknown\":\""+"x".repeat(16384)+"\"}","{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}","{\"unknown\":["+String.join(",",Collections.nCopies(260,"1"))+"]}","{\""+"x".repeat(65)+"\":1}"))mvc.perform(scoped(post("/api/v1/assets/import-sources/"+SOURCE+"/parses").contentType(MediaType.APPLICATION_JSON).content(body)).with(csrf().asHeader())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));verifyNoInteractions(sources,store);
    }
    @Test void largerGlobalImportLimitNeverExpandsPhotoLimit()throws Exception{
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",9,true)));mvc.perform(scoped(multipart("/api/v1/files").part(new MockPart("file","too-large.png",new byte[PhotoPolicy.MAX_SIZE+1]),new MockPart("purpose","customer_report_photo".getBytes(StandardCharsets.UTF_8)))).with(csrf().asHeader())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));verify(repository,never()).acceptPhoto(anyString(),anyString(),any());
    }
    @Test
    void cmdbExecutionKeepsActualCsrfFilterAndCurrentMemberAuthorization() throws Exception {
        String url = "/api/v1/assets/import-sources/" + SOURCE + "/cmdb-imports";
        String body = "{\"expected_version\":1,\"source_parse_hash\":\"" + "a".repeat(64) + "\"}";
        mvc.perform(scoped(post(url).contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        when(repository.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 9, true)));
        mvc.perform(scoped(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).with(csrf().asHeader()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));
        when(repository.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
        mvc.perform(scoped(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).with(csrf().asHeader()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }
}
