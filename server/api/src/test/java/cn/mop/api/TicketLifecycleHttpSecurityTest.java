package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticket.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Isolated MVC/filter tests; not real PostgreSQL or browser acceptance. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_lifecycle_http"})
@ContextConfiguration(classes=TicketLifecycleHttpSecurityTest.Configuration.class)
class TicketLifecycleHttpSecurityTest {
    private static final String ACTOR="10000000-0000-0000-0000-000000000001",TENANT="10000000-0000-0000-0000-000000000002",TICKET="10000000-0000-0000-0000-000000000003";
    private static final String ROOT="/api/v1/tickets/"+TICKET+"/lifecycle";
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_lifecycle_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}
        @Bean TicketLifecycleRepository repository(){return mock(TicketLifecycleRepository.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
        @Bean TicketSlaController slaController(TicketLifecycleController controller){return new TicketSlaController(controller);}
        @Bean TicketLifecycleController controller(PlatformRepository platform,TicketLifecycleRepository repository,ObjectMapper mapper){return new TicketLifecycleController(platform,repository,mapper);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository platform;
    @Autowired TicketLifecycleRepository repository;
    private MockMvc mvc;
    @BeforeEach void setup(){reset(platform,repository);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,List.of("tickets:read","tickets:lifecycle_write"))));when(repository.get(any(),any())).thenReturn(Optional.empty());}
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR));}
    @Test void getRequiresSessionAndDoesNotInventAnOpening()throws Exception{
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(scoped(get(ROOT))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.lifecycle").value(nullValue())).andExpect(jsonPath("$.data.status").value("NOT_STARTED"));
    }
    @Test void allActionWritesRequireCsrfBeforeRepository()throws Exception{
        for(String action:List.of("accept","transfer","wait-for-vendor","resume","submit-resolution","confirm","not-resolved","reopen"))
            mvc.perform(scoped(post(ROOT+"/actions/"+action)).contentType("application/json").content("{\"expected_version\":1}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        verifyNoInteractions(repository);
    }
    @Test void malformedAndDuplicateBodiesFailBeforeWrite()throws Exception{
        for(String body:List.of("{\"expected_version\":1,\"expected_version\":2}","{\"expected_version\":1,\"actor_id\":\"forged\"}"))
            mvc.perform(scoped(post(ROOT+"/actions/accept")).with(csrf().asHeader()).header("Idempotency-Key","http-test-1").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void disabledDependencyRemainsAnErrorNotEmptySuccess()throws Exception{
        doThrow(new DomainException(422,"CONFIG_PENDING","Lifecycle disabled")).when(repository).get(any(),any());
        mvc.perform(scoped(get(ROOT))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("CONFIG_PENDING")).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void customerCannotUseEngineerActionEvenWithCsrf()throws Exception{
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true,List.of("tickets:read","tickets:customer_decide"))));
        mvc.perform(scoped(post(ROOT+"/actions/accept")).with(csrf().asHeader()).contentType("application/json").content("{\"expected_version\":1}")).andExpect(status().isForbidden());verifyNoInteractions(repository);
    }
    @Test void openingRequiresCsrfAndNeverAcceptsClientDeadline()throws Exception{
        mvc.perform(scoped(post(ROOT)).contentType("application/json").content("{\"expected_version\":0,\"priority\":\"P1\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(scoped(post(ROOT)).with(csrf().asHeader()).header("Idempotency-Key","opening-test").contentType("application/json").content("{\"expected_version\":0,\"priority\":\"P1\",\"response_due_at\":\"2026-10-21T00:00:00Z\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void slaRouteUsesReadAuthorizationAndNeverClaimsDelivery()throws Exception{
        when(repository.sla(any(),any())).thenReturn(Optional.empty());
        mvc.perform(scoped(get("/api/v1/tickets/"+TICKET+"/sla"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NOT_STARTED")).andExpect(jsonPath("$.data.production_approved").value(false));
        mvc.perform(get("/api/v1/tickets/"+TICKET+"/sla")).andExpect(status().isUnauthorized());
    }
    @Test void policyBindingRequiresCsrfAndCannotAcceptRawPolicy()throws Exception{
        String path="/api/v1/tickets/"+TICKET+"/sla/policy-binding";
        mvc.perform(scoped(post(path)).contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(scoped(post(path)).with(csrf().asHeader()).contentType("application/json").content("{\"expected_lifecycle_version\":1,\"release_id\":\""+TICKET+"\",\"release_version\":1,\"clock_policy\":\"override\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void customerCannotBindEvenWithCsrf()throws Exception{
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true,List.of("tickets:read","tickets:customer_decide"))));
        mvc.perform(scoped(post("/api/v1/tickets/"+TICKET+"/sla/policy-binding")).with(csrf().asHeader()).contentType("application/json").content("{}")).andExpect(status().isForbidden());verifyNoInteractions(repository);
    }
    @Test void remindersAreAuthenticatedReadOnlyAndDoNotClaimDelivery()throws Exception{
        when(repository.reminders(any(),any())).thenReturn(new TicketLifecycleRepository.ReminderPage(TENANT,TICKET,1,"PENDING",null,null,List.of()));
        mvc.perform(scoped(get("/api/v1/tickets/"+TICKET+"/sla/reminders"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.scan_state").value("PENDING")).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/v1/tickets/"+TICKET+"/sla/reminders")).andExpect(status().isUnauthorized());
        mvc.perform(scoped(post("/api/v1/tickets/"+TICKET+"/sla/reminders")).with(csrf().asHeader())).andExpect(status().isMethodNotAllowed());
    }
}
