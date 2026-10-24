package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.backup.BackupRunHistoryReader;
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
/** Real MVC/security filter assembly, synthetic repository; not real OIDC/PG. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_backup_run_history"})
@ContextConfiguration(classes=BackupRunHistoryHttpTest.Config.class)
class BackupRunHistoryHttpTest {
 @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_backup_run_history") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
 static class Config implements WebMvcConfigurer{
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}
  @Bean BackupRunHistoryReader reader(){return mock(BackupRunHistoryReader.class);}
  @Bean BackupRunHistoryController controller(PlatformRepository platform,BackupRunHistoryReader reader){return new BackupRunHistoryController(platform,reader);}
  public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
 }
 @Autowired WebApplicationContext context;@Autowired PlatformRepository platform;@Autowired BackupRunHistoryReader reader;MockMvc mvc;MockHttpSession session;
 final String actor=BackupRunHistoryControllerTest.id(2).toString(),tenant=BackupRunHistoryControllerTest.id(1).toString(),asset=BackupRunHistoryControllerTest.id(3).toString();
 @BeforeEach void setup(){reset(platform,reader);mvc=webAppContextSetup(context).apply(springSecurity()).build();session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",tenant);when(platform.membership(actor,tenant)).thenReturn(Optional.of(new Membership(actor,tenant,"padmin",1,true)));when(reader.read(any(),any(),isNull(),eq(20))).thenAnswer(c->new BackupRunHistoryReader.Page(c.getArgument(0),c.getArgument(1),Instant.parse("2026-10-23T20:00:00Z"),20,0,List.of(),null));}
 String path(){return "/api/v1/assets/"+asset+"/backup-runs";}
 @Test void readsUseSessionNoStoreAndNoMutationRoute()throws Exception{mvc.perform(get(path()).session(session).with(user(actor))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.total_count").value(0)).andExpect(jsonPath("$.data.automatic_ticket_allowed").value(false));mvc.perform(post(path()).session(session).with(user(actor)).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());}
 @Test void missingSessionAndScopeOverrideAreRejected()throws Exception{mvc.perform(get(path())).andExpect(status().isUnauthorized());mvc.perform(get(path()).session(session).with(user(actor)).param("tenant_id",tenant)).andExpect(status().isBadRequest());verifyNoInteractions(reader);}
 @Test void unavailableBindingIsNotEmptySuccess()throws Exception{when(reader.read(any(),any(),isNull(),eq(20))).thenThrow(new DomainException(503,"BACKUP_RUN_HISTORY_NOT_CONFIGURED","未配置"));mvc.perform(get(path()).session(session).with(user(actor))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").doesNotExist());}
}
