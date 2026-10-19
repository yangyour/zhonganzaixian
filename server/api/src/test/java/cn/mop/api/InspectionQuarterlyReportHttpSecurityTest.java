package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.inspection.*;
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

/** Isolated MVC/filter checks with mocked repositories; not PG/OIDC/monitoring integration. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration @ActiveProfiles({"local_fixture","isolated_quarterly_http"})
@ContextConfiguration(classes=InspectionQuarterlyReportHttpSecurityTest.Configuration.class)
class InspectionQuarterlyReportHttpSecurityTest {
    private static final String ACTOR=InspectionWorksheetControllerTest.ACTOR,TENANT=InspectionWorksheetControllerTest.TENANT;
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_quarterly_http") @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository repository(){return mock(PlatformRepository.class);}
        @Bean InspectionQuarterlyReportRepository reader(){return mock(InspectionQuarterlyReportRepository.class);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean InspectionQuarterlyReportController controller(PlatformRepository repository,InspectionQuarterlyReportRepository reader){return new InspectionQuarterlyReportController(repository,reader);}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository repository;
    @Autowired InspectionQuarterlyReportRepository reader;
    private MockMvc mvc;
    final InspectionQuarterlyReportControllerTest fixture=new InspectionQuarterlyReportControllerTest();
    @BeforeEach void setup(){fixture.setup();reset(repository,reader);mvc=webAppContextSetup(context).apply(springSecurity()).build();when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));when(reader.create(any(),anyLong(),any())).thenReturn(fixture.summary);when(reader.get(any(),any())).thenReturn(new InspectionQuarterlyReportRepository.Stored(fixture.summary,fixture.document));when(repository.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(c->{((Runnable)c.getArgument(6)).run();return ((java.util.function.Supplier<?>)c.getArgument(7)).get();});}
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder){var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return builder.session(session).with(user(ACTOR));}
    static final String BASE="/api/v1/inspection-quarterly-reports";
    @Test void csrfRequiredBeforeMutation()throws Exception{
        mvc.perform(scoped(post(BASE)).contentType("application/json").content(fixture.input().toString()).header("Idempotency-Key","test")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));verifyNoInteractions(reader);
        mvc.perform(scoped(post(BASE)).with(csrf().asHeader()).contentType("application/json").content(fixture.input().toString()).header("Idempotency-Key","test")).andExpect(status().isOk()).andExpect(jsonPath("$.data.generation_status").value("DRAFT")).andExpect(jsonPath("$.data.report_published").value(false));verify(reader).authorizeWrite(any());
    }
    @Test void detailRequiresSessionAndRemainsNoStore()throws Exception{
        mvc.perform(scoped(get(BASE+"/"+fixture.summary.id()))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.frozen_document.configuration_source").value("REQUEST_DRAFT_NOT_APPROVED"));mvc.perform(get(BASE+"/"+fixture.summary.id())).andExpect(status().isUnauthorized());
    }
    @Test void forgedBodyAndDuplicateKeysAreSanitized()throws Exception{
        for(String body:List.of("{}{}","{\"year\":1,\"year\":2}",fixture.input().put("tenant_id","SECRET").toString()))mvc.perform(scoped(post(BASE)).with(csrf().asHeader()).contentType("application/json").content(body).header("Idempotency-Key","test")).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));verifyNoInteractions(reader);
    }
    @Test void currentSourceReadCapabilityRequiredEvenWithReportWrite()throws Exception{
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true,List.of("inspection_quarterly_reports:write"))));mvc.perform(scoped(post(BASE)).with(csrf().asHeader()).contentType("application/json").content(fixture.input().toString()).header("Idempotency-Key","test")).andExpect(status().isForbidden());verifyNoInteractions(reader);
    }
    @Test void databaseAndUnexpectedErrorsAreSanitized()throws Exception{
        when(reader.get(any(),any())).thenThrow(new IllegalStateException("SECRET JDBC SQL"));mvc.perform(scoped(get(BASE+"/"+fixture.summary.id()))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }
    @Test void currentCapabilityOverridesElevatedSessionRole()throws Exception{
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));mvc.perform(scoped(get(BASE+"/"+fixture.summary.id())).with(user(ACTOR).roles("PADMIN"))).andExpect(status().isForbidden());verifyNoInteractions(reader);
    }
}
