package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.notification.DeliveryJournalReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_delivery_journal_http"}) @ContextConfiguration(classes=DeliveryJournalHttpSecurityTest.Configuration.class)
class DeliveryJournalHttpSecurityTest {
 static final String A="79000000-0000-4000-8000-000000000001",T="79000000-0000-4000-8000-000000000002",D="79000000-0000-4000-8000-000000000003",BASE="/api/v1/notification-deliveries";
 @TestConfiguration @Profile("isolated_delivery_journal_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class}) static class Configuration {
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}@Bean DeliveryJournalReader reader(){return mock(DeliveryJournalReader.class);}
  @Bean DeliveryJournalController controller(PlatformRepository p,DeliveryJournalReader r,ObjectMapper mapper){return new DeliveryJournalController(p,r,mapper,new org.springframework.mock.env.MockEnvironment().withProperty("MOP_DELIVERY_JOURNAL_READ_ENABLED","true"));}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository platform;@Autowired DeliveryJournalReader reader;MockMvc mvc;
 @BeforeEach void setup(){reset(platform,reader);when(platform.membership(A,T)).thenReturn(Optional.of(new Membership(A,T,"padmin",7,true)));mvc=webAppContextSetup(context).apply(springSecurity()).build();}
 MockHttpSession session(){var s=new MockHttpSession();s.setAttribute("mop.tenant_id",T);return s;}
 @Test void anonymousCannotRead()throws Exception{mvc.perform(get(BASE)).andExpect(status().isUnauthorized());verifyNoInteractions(reader);}
 @Test void noRetryOrSendPostEndpointExists()throws Exception{mvc.perform(post(BASE+"/"+D).session(session()).with(user(A)).with(csrf())).andExpect(status().isMethodNotAllowed());verifyNoInteractions(reader);}
 @Test void foreignDeliveryStays404()throws Exception{when(reader.detail(any(),eq(UUID.fromString(D)),isNull(),eq(100))).thenThrow(new DomainException(404,"DELIVERY_JOURNAL_NOT_FOUND","当前范围未找到"));mvc.perform(get(BASE+"/"+D).session(session()).with(user(A))).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DELIVERY_JOURNAL_NOT_FOUND"));}
 @Test void duplicateQueryAndHalfCursorRejected()throws Exception{mvc.perform(get(BASE).param("page_size","10","20").session(session()).with(user(A))).andExpect(status().isBadRequest());mvc.perform(get(BASE).param("before","2026-10-24T00:00:00Z").session(session()).with(user(A))).andExpect(status().isBadRequest());verifyNoInteractions(reader);}
}
