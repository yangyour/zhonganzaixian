package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real MVC/converter/error-response boundary with synthetic identity repository, no live identity changes. */
class ProjectReadResponseAdviceTest {
 final String actor="79000000-0000-4000-8000-000000000001";
 final ProjectIdentityRepository accounts=mock(ProjectIdentityRepository.class);final PlatformRepository platform=mock(PlatformRepository.class);final MockHttpSession session=new MockHttpSession();
 final ProjectSessionValidationFilter filter=new ProjectSessionValidationFilter(new ProjectIdentityService(accounts,new ObjectMapper(),"01".repeat(32),false));
 Runnable duringRead=()->{};MockMvc mvc;
 @RestController class Endpoint {
  @GetMapping("/api/v1/unit/private") Object get(HttpServletResponse response){duringRead.run();response.setHeader("ETag","private-version");response.setHeader("Last-Modified","Wed, 21 Oct 2015 07:28:00 GMT");return Map.of("data",Map.of("private_value","MUST_NOT_LEAK"));}
  @GetMapping("/api/v1/auth/options") Object options(){return Map.of("data",Map.of("mode","project_local"));}
  @GetMapping("/api/v1/account") Object account(){duringRead.run();return Map.of("data",Map.of("account","own"));}
  @GetMapping("/api/v1/unit/fail") Object error(){duringRead.run();throw new DomainException(404,"OBJECT_NOT_FOUND","Not found");}
  @PostMapping("/api/v1/unit/private") Object write(){duringRead.run();return Map.of("data",Map.of("acknowledgement_only",true));}
 }
 @BeforeEach void setup(){when(accounts.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(5,false)));authenticate(5);mvc=standaloneSetup(new Endpoint()).setControllerAdvice(new ApiExceptionHandler(platform),new ProjectReadResponseAdvice()).setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper())).addFilters(filter).build();}
 void authenticate(long version){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ProjectIdentityService.LocalPrincipal(actor,version),null,List.of()));}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
 @Test void stableStandardJsonReadChecksAccountAtEntryAndAtConverter()throws Exception{mvc.perform(get("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.private_value").value("MUST_NOT_LEAK"));verify(accounts,times(2)).sessionState(actor);}
 @ParameterizedTest @ValueSource(strings={"revision","removed","replaced_authentication","session_id","anonymous"})
 void revokeDuringAnOtherwiseUnguardedControllerNeverSerializesData(String change)throws Exception{
  duringRead=()->{switch(change){case "revision"->when(accounts.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(6,false)));case "removed"->when(accounts.sessionState(actor)).thenReturn(Optional.empty());case "replaced_authentication"->authenticate(5);case "session_id"->session.changeSessionId();case "anonymous"->SecurityContextHolder.clearContext();}};
  var result=mvc.perform(get("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED")).andExpect(jsonPath("$.data").doesNotExist()).andExpect(header().doesNotExist("ETag")).andExpect(header().doesNotExist("Last-Modified")).andReturn();assertFalse(result.getResponse().getContentAsString().contains("MUST_NOT_LEAK"));assertFalse(session.isInvalid());
 }
 @Test void identityDependencyFailureIs503AndNotProviderText()throws Exception{duringRead=()->when(accounts.sessionState(actor)).thenThrow(new IllegalStateException("private connection detail"));var result=mvc.perform(get("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("IDENTITY_UNAVAILABLE")).andReturn();assertFalse(result.getResponse().getContentAsString().contains("private connection detail"));assertFalse(result.getResponse().getContentAsString().contains("MUST_NOT_LEAK"));}
 @Test void forcedPasswordChangeBlocksPrivateReadButKeepsOwnAccountRoute()throws Exception{duringRead=()->when(accounts.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(5,true)));mvc.perform(get("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PASSWORD_CHANGE_REQUIRED"));mvc.perform(get("/api/v1/account").session(session).principal(()->actor)).andExpect(status().isOk());}
 @Test void existingErrorResponseDoesNotRecursivelyFailIdentityRecheck()throws Exception{duringRead=()->when(accounts.sessionState(actor)).thenThrow(new IllegalStateException("unavailable"));mvc.perform(get("/api/v1/unit/fail").session(session).principal(()->actor)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));verify(accounts).sessionState(actor);}
 @Test void committedWriteAcknowledgmentIsNotTurnedIntoUncertainReadFailure()throws Exception{duringRead=()->when(accounts.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(6,false)));mvc.perform(post("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.acknowledgement_only").value(true));verify(accounts).sessionState(actor);}
 @Test void nonJsonConvertersAreOutsideThisGuard(){var advice=new ProjectReadResponseAdvice();assertTrue(advice.supports(null,MappingJackson2HttpMessageConverter.class));assertFalse(advice.supports(null,ByteArrayHttpMessageConverter.class));}
 @Test void missingInstalledFilterFailsClosedForProjectAuthenticatedReads()throws Exception{var noFilter=standaloneSetup(new Endpoint()).setControllerAdvice(new ApiExceptionHandler(platform),new ProjectReadResponseAdvice()).build();noFilter.perform(get("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("IDENTITY_UNAVAILABLE"));}
 @Test void publicAnonymousOptionsRemainAvailableWithoutInventedIdentityAuthority()throws Exception{SecurityContextHolder.clearContext();mvc.perform(get("/api/v1/auth/options")).andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("project_local"));verifyNoInteractions(accounts);}
 @Test void headReadRechecksBeforeSuccessfulMetadata()throws Exception{duringRead=()->when(accounts.sessionState(actor)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(6,false)));mvc.perform(head("/api/v1/unit/private").session(session).principal(()->actor)).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("ETag"));}
}
