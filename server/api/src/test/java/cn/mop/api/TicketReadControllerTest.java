package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated controller checks, not component evidence. */
class TicketReadControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000041",TENANT="55000000-0000-4000-8000-000000000001",OTHER="55000000-0000-4000-8000-000000000002";
    PlatformRepository repository;TicketController controller;
    @BeforeEach void setup(){repository=mock(PlatformRepository.class);controller=new TicketController(repository,new ObjectMapper());when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/tickets");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
    Map<?,?> data(Object value){return (Map<?,?>)((Map<?,?>)value).get("data");}
    Map<String,Object> row(int n){return Map.of("id",String.format("55000000-0000-4000-8000-%012x",n),"created_at",Instant.EPOCH.plusMillis(n).toString());}
    String cursor(){var rows=java.util.stream.IntStream.rangeClosed(1,11).mapToObj(this::row).toList();when(repository.tickets(TENANT,ACTOR,null,null,null,null,null,11)).thenReturn(rows);return (String)data(controller.list(Map.of("page_size","10"),request())).get("next_cursor");}
    @Test void boundedPaginationUsesTrustedTenantActorAndPreservesLiteralUnicodeQuery(){String q="😀".repeat(100);var r=request();r.addHeader("X-Tenant-ID",OTHER);when(repository.tickets(TENANT,ACTOR,null,null,q,null,null,16)).thenReturn(List.of());controller.list(Map.of("q",q),r);verify(repository).tickets(TENANT,ACTOR,null,null,q,null,null,16);String literal=" ÄΟΣİK%_ ";controller.list(Map.of("q",literal),request());verify(repository).tickets(TENANT,ACTOR,null,null,literal,null,null,16);}
    @Test void cursorRetainsFilterActorTenantAndPermissionBindings(){String token=cursor();for(var params:List.of(Map.of("cursor",token,"q","changed"),Map.of("cursor",token+"x")))assertEquals("CURSOR_INVALID",assertThrows(DomainException.class,()->controller.list(params,request())).code);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",8,true)));assertEquals("CURSOR_INVALID",assertThrows(DomainException.class,()->controller.list(Map.of("cursor",token),request())).code);when(repository.membership(ACTOR,OTHER)).thenReturn(Optional.of(new Membership(ACTOR,OTHER,"viewer",7,true)));var other=request();other.getSession().setAttribute("mop.tenant_id",OTHER);assertEquals("CURSOR_INVALID",assertThrows(DomainException.class,()->controller.list(Map.of("cursor",token),other)).code);}
    @Test void malformedQueryAndDuplicateParametersNeverReachRepository(){for(var params:List.of(Map.of("q","😀".repeat(101)),Map.of("q","\ud800"),Map.of("q","x\u0000"),Map.of("q","x\n"),Map.of("category","unknown"),Map.of("urgency","P1"),Map.of("page_size","500"),Map.of("tenant_id",OTHER))){assertEquals(400,assertThrows(DomainException.class,()->controller.list(params,request())).status);}var duplicate=request();duplicate.addParameter("q",new String[]{"a","b"});assertEquals(400,assertThrows(DomainException.class,()->controller.list(Map.of("q","a"),duplicate)).status);verify(repository,never()).tickets(any(),any(),any(),any(),any(),any(),any(),anyInt());}
    @Test void inactiveMembershipCannotReadListDetailOrOperation(){when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.list(Map.of(),request())).status);assertEquals(401,assertThrows(DomainException.class,()->controller.detail("id",request())).status);assertEquals(401,assertThrows(DomainException.class,()->controller.operation("id",request())).status);verify(repository,never()).tickets(any(),any(),any(),any(),any(),any(),any(),anyInt());verify(repository,never()).ticket(any(),any(),any());verify(repository,never()).operation(any(),any(),any());}
    @Test void malformedSessionTenantReturns401InsteadOfCastingOrQuerying(){for(Object value:List.of(17,""," ")){var r=request();r.getSession().setAttribute("mop.tenant_id",value);assertEquals(401,assertThrows(DomainException.class,()->controller.list(Map.of(),r)).status);}verify(repository,never()).membership(any(),any());}
    @Test void unknownRoleRemainsForbiddenAndInvisibleObjectsRemain404(){when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"unrecognized",7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.list(Map.of(),request())).status);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));assertEquals(404,assertThrows(DomainException.class,()->controller.detail("missing",request())).status);assertEquals(404,assertThrows(DomainException.class,()->controller.operation("missing",request())).status);}
    @Test void malformedTenantRemains401ThroughActualExceptionAdviceAndStillAudits()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler(repository)).build();
        mvc.perform(get("/api/v1/tickets").principal(()->ACTOR).sessionAttr("mop.tenant_id",17)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        verify(repository).audit(eq(ACTOR),isNull(),anyString(),eq("request"),isNull(),eq("DENIED"),eq("SESSION_EXPIRED"),anyString());
    }
    @Test void securityErrorWriterDoesNotCastUntrustedSessionScopeOrLoseDenialAudit()throws Exception{
        var r=request();r.getSession().setAttribute("mop.tenant_id",17);var response=new MockHttpServletResponse();
        SecurityConfiguration.writeError(new ObjectMapper(),repository,r,response,401,"SESSION_EXPIRED","会话不存在或已过期");
        assertEquals(401,response.getStatus());verify(repository).audit(eq(ACTOR),isNull(),anyString(),eq("request"),isNull(),eq("DENIED"),eq("SESSION_EXPIRED"),anyString());
    }
}
