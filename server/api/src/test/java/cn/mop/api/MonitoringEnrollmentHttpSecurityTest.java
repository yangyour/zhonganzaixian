package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEnrollmentRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ActiveProfiles({"local_fixture","isolated_monitoring_enrollment_http"})
@ContextConfiguration(classes=MonitoringEnrollmentHttpSecurityTest.Configuration.class)
class MonitoringEnrollmentHttpSecurityTest {
 static final String ACTOR="79000000-0000-4000-8000-000000000001",TENANT="79000000-0000-4000-8000-000000000002",ID="79000000-0000-4000-8000-000000000003",BASE="/api/v1/monitoring-onboarding/enrollments";
 @TestConfiguration @Profile("isolated_monitoring_enrollment_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Configuration implements WebMvcConfigurer {
  @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
  @Bean MonitoringEnrollmentRepository reports(){return mock(MonitoringEnrollmentRepository.class);}
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}
  @Bean MonitoringEnrollmentController controller(PlatformRepository p,MonitoringEnrollmentRepository r,ObjectMapper m){return new MonitoringEnrollmentController(p,r,new MonitoringOnboardingPlanner("{\"version\":1,\"collectors\":[]}"),new MonitoringEnrollmentProbe(false,"[]","[]",target->{throw new AssertionError();}),m,new org.springframework.mock.env.MockEnvironment().withProperty("MOP_MONITORING_ENROLLMENT_ENABLED","true"));}
  @Override public void configureMessageConverters(List<HttpMessageConverter<?>> c){c.add(new MappingJackson2HttpMessageConverter(mapper()));c.add(new ByteArrayHttpMessageConverter());}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository platform;@Autowired MonitoringEnrollmentRepository reports;MockMvc mvc;
 @BeforeEach void setup(){reset(platform,reports);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));}
 MockHttpSession session(){var s=new MockHttpSession();s.setAttribute("mop.tenant_id",TENANT);return s;}
 @Test void unauthenticatedReadAndCsrfFreeWriteRejected()throws Exception{
  mvc.perform(get(BASE)).andExpect(status().isUnauthorized());mvc.perform(post(BASE+"/"+ID+"/actions").session(session()).with(user(ACTOR)).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"expected_version\":1,\"reason\":null}")).andExpect(status().isForbidden());verifyNoInteractions(reports);
 }
 @Test void duplicateJsonFieldsRejectedBeforeRepository()throws Exception{
  mvc.perform(post(BASE+"/"+ID+"/actions").session(session()).with(user(ACTOR)).with(csrf()).header("Idempotency-Key","synthetic-unit").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\",\"expected_version\":1,\"expected_version\":2,\"reason\":null}")).andExpect(status().isBadRequest());verifyNoInteractions(reports);
 }
 @Test void foreignObjectRemains404()throws Exception{
  when(reports.get(any(),eq(ID))).thenThrow(new DomainException(404,"OBJECT_NOT_FOUND","不存在"));mvc.perform(get(BASE+"/"+ID).session(session()).with(user(ACTOR))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
 }
 @Test void emptyPageIsActualEmptyAndNeverAnExternalSend()throws Exception{
  when(reports.list(any(),eq(21),isNull(),isNull())).thenReturn(List.of());mvc.perform(get(BASE).session(session()).with(user(ACTOR))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.external_configuration_applied").value(false));
 }
}
