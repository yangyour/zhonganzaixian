package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
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

/** Actual repository security filter with isolated HTTP dependencies, not remote OIDC evidence. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles("local_fixture")
@ContextConfiguration(classes=FileControllerHttpSecurityTest.Configuration.class)
class FileControllerHttpSecurityTest {
    private static final String ACTOR="55000000-0000-4000-8000-000000000003",OTHER="55000000-0000-4000-8000-000000000004",TENANT="55000000-0000-4000-8000-000000000001",OTHER_TENANT="55000000-0000-4000-8000-000000000002",FILE="55000000-0000-4000-8000-000000000009",GRANT="55000000-0000-4000-8000-000000000010";
    @org.springframework.context.annotation.Configuration @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
        @Bean PhotoObjectStore store(){return mock(PhotoObjectStore.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
        @Bean FileController files(PlatformRepository repository,PhotoObjectStore store,ObjectMapper mapper){return new FileController(repository,new PhotoPolicy("image/jpeg,image/png"),store,mapper);}
        @Bean TicketController tickets(PlatformRepository repository,ObjectMapper mapper){return new TicketController(repository,mapper);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());}
    }
    @Autowired WebApplicationContext context;@Autowired PlatformRepository repository;@Autowired PhotoObjectStore store;private MockMvc mvc;
    @BeforeEach void setup(){reset(repository,store);mvc=webAppContextSetup(context).apply(springSecurity()).build();for(String actor:List.of(ACTOR,OTHER))for(String tenant:List.of(TENANT,OTHER_TENANT))when(repository.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"viewer",8,true)));when(store.ready()).thenReturn(true);when(repository.idempotent(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any())).thenAnswer(call->call.getArgument(6,Supplier.class).get());when(repository.acceptPhoto(eq(TENANT),eq(ACTOR),any())).thenReturn(Map.of("operation_id",GRANT,"status","PENDING"));}
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR)).header("Idempotency-Key","isolated-http-request");}
    private MockPart file(String contentType)throws Exception{var file=new MockPart("file","original.png",PhotoPolicyTest.image("png"));if(contentType!=null)file.getHeaders().set("Content-Type",contentType);return file;}
    private MockPart purpose(){return new MockPart("purpose","customer_report_photo".getBytes(StandardCharsets.UTF_8));}

    @Test void uploadAndGrantUseExistingCsrfFilterAndPermitUnspecifiedBrowserMime()throws Exception{
        mvc.perform(scoped(multipart("/api/v1/files").part(file("image/png"),purpose()))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));verify(repository,never()).acceptPhoto(anyString(),anyString(),any());
        for(String media:new String[]{null,"application/octet-stream"})mvc.perform(scoped(multipart("/api/v1/files").part(file(media),purpose())).with(csrf().asHeader())).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(scoped(post("/api/v1/files/"+FILE+"/content-grants").contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));verify(repository,never()).contentGrant(anyString(),anyString(),anyString());
    }
    @Test void multipartUnknownDuplicatePurposeInvalidContentAndQueryAreRejected()throws Exception{
        mvc.perform(scoped(multipart("/api/v1/files").part(file("image/png"),purpose(),purpose())).with(csrf().asHeader())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(scoped(multipart("/api/v1/files").part(file("image/png"),purpose(),new MockPart("actor_id","forged".getBytes(StandardCharsets.UTF_8)))).with(csrf().asHeader())).andExpect(status().isBadRequest());
        mvc.perform(scoped(multipart("/api/v1/files").part(file("image/jpeg"),purpose())).with(csrf().asHeader())).andExpect(status().isBadRequest());
        mvc.perform(scoped(multipart("/api/v1/files?tenant_id=forged").part(file("image/png"),purpose())).with(csrf().asHeader())).andExpect(status().isBadRequest());verify(repository,never()).acceptPhoto(anyString(),anyString(),any());
    }
    @Test void multipartResolverFailuresUseSamePublicValidationCode()throws Exception{
        when(repository.file(TENANT,ACTOR,FILE)).thenThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(PhotoPolicy.MAX_SIZE));mvc.perform(scoped(get("/api/v1/files/"+FILE))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
    @Test void forbiddenJsonFieldsAndDuplicateKeysCannotCreateGrantsOrAppendAttachments()throws Exception{
        for(String body:List.of("{\"actor_id\":\"forged\"}","{\"purpose\":\"a\",\"purpose\":\"b\"}"))mvc.perform(scoped(post("/api/v1/files/"+FILE+"/content-grants").contentType(MediaType.APPLICATION_JSON).content(body)).with(csrf().asHeader())).andExpect(status().isBadRequest());
        mvc.perform(scoped(post("/api/v1/tickets/"+FILE+"/attachments").contentType(MediaType.APPLICATION_JSON).content("{\"expected_version\":1,\"attachment_ids\":[\""+FILE+"\"]}"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));verify(repository,never()).contentGrant(anyString(),anyString(),anyString());verify(repository,never()).attachPhotos(anyString(),anyString(),anyString(),anyLong(),anyList());
    }
    @Test void unavailableObjectsUse404ForOtherCustomerAndTenantAndNeverReadStore()throws Exception{
        mvc.perform(scoped(get("/api/v1/files/"+FILE)).with(user(OTHER))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));verify(repository).file(TENANT,OTHER,FILE);
        var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",OTHER_TENANT);mvc.perform(get("/api/v1/files/"+FILE).session(session).with(user(ACTOR))).andExpect(status().isNotFound());verify(repository).file(OTHER_TENANT,ACTOR,FILE);
        when(repository.photoContent(TENANT,OTHER,FILE,GRANT)).thenThrow(new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见"));mvc.perform(scoped(get("/api/v1/files/"+FILE+"/content").param("grant",GRANT)).with(user(OTHER))).andExpect(status().isNotFound());verify(store,never()).read(anyString(),anyLong(),anyString());
    }
}
