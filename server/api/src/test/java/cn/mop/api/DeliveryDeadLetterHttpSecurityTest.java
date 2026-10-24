package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.persistence.DeliveryDeadLetterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_delivery_dead_letter_http"})
@ContextConfiguration(classes=DeliveryDeadLetterHttpSecurityTest.Configuration.class)
class DeliveryDeadLetterHttpSecurityTest {
 @TestConfiguration @Profile("isolated_delivery_dead_letter_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration implements WebMvcConfigurer{
  @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}@Bean DeliveryDeadLetterService service(){return mock(DeliveryDeadLetterService.class);}
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean DeliveryDeadLetterController controller(PlatformRepository p,DeliveryDeadLetterService s,ObjectMapper m){return new DeliveryDeadLetterController(p,s,m);}
  public void configureMessageConverters(List<HttpMessageConverter<?>> values){values.add(new MappingJackson2HttpMessageConverter(mapper()));}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository platform;@Autowired DeliveryDeadLetterService service;MockMvc mvc;
 final String actor=DeliveryDeadLetterControllerTest.ACTOR,tenant=DeliveryDeadLetterControllerTest.TENANT,path="/api/v1/notification-deliveries/"+DeliveryDeadLetterControllerTest.ID+"/dead-letter",body="{\"expected_version\":2,\"reason\":\"Synthetic reason\"}";
 MockHttpSession session(){var s=new MockHttpSession();s.setAttribute("mop.tenant_id",tenant);return s;}
 @BeforeEach void setup(){reset(platform,service);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",7,true)));when(service.dispose(any(),any(),anyLong(),any(),anyString(),anyString())).thenReturn(DeliveryDeadLetterControllerTest.receipt());}
 @Test void sessionCsrfAndExplicitOriginalCommandAreMandatory()throws Exception{
  mvc.perform(post(path).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
  mvc.perform(post(path).session(session()).with(user(actor)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  mvc.perform(post(path).session(session()).with(user(actor)).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);
 }
 @Test void onlyExplicitPostReturnsNoStoreOriginalReceiptWithoutSendAuthority()throws Exception{
  mvc.perform(post(path).session(session()).with(user(actor)).with(csrf().asHeader()).header("Idempotency-Key",DeliveryDeadLetterControllerTest.COMMAND).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.applied_version").value(3)).andExpect(jsonPath("$.data.fresh_send_authorized").value(false));
  mvc.perform(get(path).session(session()).with(user(actor))).andExpect(status().isMethodNotAllowed());
 }
}
