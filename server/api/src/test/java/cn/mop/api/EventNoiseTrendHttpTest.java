package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.EventNoiseTrendReader;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.mockito.Mockito.*;
/** MVC/security tests with synthetic source, not live PG or OIDC. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_event_noise_http"})
@ContextConfiguration(classes=EventNoiseTrendHttpTest.Config.class)
class EventNoiseTrendHttpTest {
 @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_event_noise_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Config implements WebMvcConfigurer{
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}
  @Bean EventNoiseTrendReader reader(){return mock(EventNoiseTrendReader.class);}
  @Bean EventNoiseTrendController controller(PlatformRepository platform,EventNoiseTrendReader reader){return new EventNoiseTrendController(platform,reader);}
  public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository platform;@Autowired EventNoiseTrendReader reader;MockMvc mvc;MockHttpSession session;
 final String actor="79000000-0000-4000-8000-000000000001",tenant="79000000-0000-4000-8000-000000000002",path="/api/v1/metrics/event-noise-trend";final Instant at=Instant.parse("2026-10-23T20:00:00Z");
 @BeforeEach void setup(){reset(platform,reader);mvc=webAppContextSetup(context).apply(springSecurity()).build();session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",1,true)));when(reader.read(any(),any())).thenAnswer(c->EventNoiseTrendReader.assemble(c.getArgument(0),c.getArgument(1),at,List.of()));when(reader.records(any(),any(),any(),isNull(),eq(20))).thenAnswer(c->new EventNoiseTrendReader.RecordPage(c.getArgument(0),c.getArgument(1),c.getArgument(2),at,20,0,List.of(),null));}
 @Test void trendAndRawDetailUseNoStoreAndExposeNoMutation()throws Exception{mvc.perform(get(path).session(session).with(user(actor))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.buckets.length()").value(24)).andExpect(jsonPath("$.data.noise_reduction_percent").value(org.hamcrest.Matchers.nullValue()));mvc.perform(get(path+"/records").session(session).with(user(actor)).param("from",at.minusSeconds(3600).toString()).param("to",at.toString())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.layer").value("RAW_INTAKE"));for(String endpoint:List.of(path,path+"/records"))mvc.perform(post(endpoint).session(session).with(user(actor)).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());}
 @Test void anonymousScopeOverridesAndCustomerRolesFailBeforeSource()throws Exception{mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).session(session).with(user(actor)).param("tenant_id",tenant)).andExpect(status().isBadRequest());when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"tadmin",1,true)));mvc.perform(get(path).session(session).with(user(actor))).andExpect(status().isForbidden());verifyNoInteractions(reader);}
}
