package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketWorkflowControllerTest {
    static final String TENANT="55000000-0000-4000-8000-000000000011",ACTOR="55000000-0000-4000-8000-000000000001",TICKET="55000000-0000-4000-8000-000000000021";
    PlatformRepository platform;TicketWorkflowRepository workflow;TicketRuntimePolicy policy;TicketWorkflowController controller;ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);workflow=mock(TicketWorkflowRepository.class);policy=mock(TicketRuntimePolicy.class);controller=new TicketWorkflowController(platform,workflow,policy,mapper);role("msp");when(platform.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(c->{((Runnable)c.getArgument(6)).run();return ((Supplier<?>)c.getArgument(7)).get();});}
    void role(String r){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,r,7,true)));}
    MockHttpServletRequest request(String action,String body){var r=new MockHttpServletRequest("POST","/api/v1/tickets/"+TICKET+"/"+action);r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","trace");r.addHeader("Idempotency-Key","SYNTHETIC-workflow");r.setContent(body.getBytes(StandardCharsets.UTF_8));return r;}
    @Test void activationUsesServerSessionAndProtectedReceiptTransaction(){var r=request("activate","{\"expected_version\":0,\"priority\":\"P1\",\"service_tier\":\"STANDARD\",\"reason\":\"SYNTHETIC_TEST\"}");r.addHeader("X-Tenant-Id","forged");controller.activate(TICKET,r);verify(workflow).authorizeCommand(new TicketWorkflowRepository.Access(TENANT,ACTOR,7,"trace"),TICKET,false);verify(workflow).activate(any(),eq(TICKET),eq(0L),eq("P1"),eq(CustomerConfirmationWindow.Tier.STANDARD),eq("SYNTHETIC_TEST"),eq(policy));}
    @Test void acceptRequiresExplicitVersionAndDoesNotTakeCallerIdentity(){controller.action(TICKET,"accept",request("accept","{\"expected_version\":1}"));verify(workflow).transition(any(),eq(TICKET),argThat(c->c.expectedVersion()==1&&c.action()==TicketLifecycle.Action.ACCEPT));for(String field:List.of("tenant_id","actor_id","status","accepted_at","sla_deadline"))assertEquals(400,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",request("accept","{\"expected_version\":1,\""+field+"\":\"forged\"}"))).status);}
    @Test void viewerCannotAcceptAndStaffCannotClose(){role("viewer");assertEquals(403,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",request("accept","{\"expected_version\":1}"))).status);role("padmin");assertEquals(403,assertThrows(DomainException.class,()->controller.action(TICKET,"close",request("close","{\"expected_version\":4,\"note\":\"确认\"}"))).status);verifyNoInteractions(workflow);}
    @Test void callerWithoutEffectiveGrantIsDeniedEvenWithBaseRole(){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,List.of("tickets:read"))));assertEquals(403,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",request("accept","{\"expected_version\":1}"))).status);}
    @Test void duplicateJsonAndTrailingDataAreRejected(){for(String b:List.of("{\"expected_version\":1,\"expected_version\":2}","{\"expected_version\":1} {}","[]","{\"expected_version\":1.1}","{\"expected_version\":0}"))assertEquals(400,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",request("accept",b))).status);verifyNoInteractions(workflow);}
    @Test void oversizedBodyAndMissingIdempotencyKeyCannotWrite(){assertEquals(413,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",request("accept"," ".repeat(32769)))).status);var r=request("accept","{\"expected_version\":1}");r.removeHeader("Idempotency-Key");assertEquals(400,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",r)).status);verifyNoInteractions(workflow);}
    @Test void invalidOrForeignObjectErrorsDoNotBecomeSuccessfulReceipts(){assertEquals(400,assertThrows(DomainException.class,()->controller.get("invalid",request("workflow",""))).status);when(workflow.read(any(),eq(TICKET))).thenThrow(new DomainException(404,"OBJECT_NOT_FOUND","missing"));assertEquals(404,assertThrows(DomainException.class,()->controller.get(TICKET,request("workflow",""))).status);}
    @Test void emptyOrShortResolutionIsRejectedBeforeRepository(){for(String note:List.of("已处理"," ","x".repeat(19)))assertEquals(400,assertThrows(DomainException.class,()->controller.action(TICKET,"resolve",request("resolve","{\"expected_version\":3,\"note\":\""+note+"\"}"))).status);verifyNoInteractions(workflow);}
    @Test void listRejectsUnboundedDuplicateAndSpoofedQuery(){for(String key:List.of("tenant_id","reporter","from","unknown")){var r=request("list","");r.addParameter(key,"value");assertEquals(400,assertThrows(DomainException.class,()->controller.list(r)).status);}var r=request("list","");r.addParameter("limit","101");assertEquals(400,assertThrows(DomainException.class,()->controller.list(r)).status);var dup=request("list","");dup.addParameter("limit",new String[]{"25","50"});assertEquals(400,assertThrows(DomainException.class,()->controller.list(dup)).status);}
    @Test void customersCannotFilterInternalAssignee(){role("viewer");var r=request("list","");r.addParameter("assignee",ACTOR);assertEquals(403,assertThrows(DomainException.class,()->controller.list(r)).status);}
    @Test void changedPermissionInvalidSessionFailsBeforeWrites(){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());assertEquals(401,assertThrows(DomainException.class,()->controller.action(TICKET,"accept",request("accept","{\"expected_version\":1}"))).status);verifyNoInteractions(workflow);}
    @Test void slaInspectionPassesCurrentTenantServiceTierAndActivateCanFallbackToConfiguredTier(){
        var tiers=mock(cn.mop.domain.sla.ServiceTierRepository.class);
        var withTiers=new TicketWorkflowController(platform,workflow,policy,mapper,tiers);
        when(tiers.current(any())).thenReturn(Optional.of(new cn.mop.domain.sla.ServiceTierRepository.Snapshot(
            TENANT,2L,CustomerConfirmationWindow.Tier.ADVANCED,new java.math.BigDecimal("99.99"),50,
            cn.mop.domain.sla.ServiceTierRepository.BillingCycle.MONTHLY,100000L,
            cn.mop.domain.sla.ServiceTierRepository.EffectiveMode.IMMEDIATE,java.time.Instant.parse("2026-10-18T00:00:00Z"),
            ACTOR,java.time.Instant.parse("2026-10-18T00:00:00Z"),"UNVERIFIED")));
        var slaReq=new MockHttpServletRequest("GET","/api/v1/tickets/"+TICKET+"/sla");
        slaReq.setUserPrincipal(()->ACTOR);slaReq.getSession().setAttribute("mop.tenant_id",TENANT);slaReq.setAttribute("mop.trace_id","trace");
        withTiers.sla(TICKET,slaReq);
        verify(workflow).readSla(new TicketWorkflowRepository.Access(TENANT,ACTOR,7,"trace"),TICKET,CustomerConfirmationWindow.Tier.ADVANCED);
        withTiers.activate(TICKET,request("activate","{\"expected_version\":0,\"priority\":\"P2\",\"service_tier\":null,\"reason\":\"SYNTHETIC_FALLBACK\"}"));
        verify(workflow).activate(any(),eq(TICKET),eq(0L),eq("P2"),eq(CustomerConfirmationWindow.Tier.ADVANCED),eq("SYNTHETIC_FALLBACK"),eq(policy));
    }
}
