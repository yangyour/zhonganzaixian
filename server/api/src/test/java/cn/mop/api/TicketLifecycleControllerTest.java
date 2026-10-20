package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketLifecycleControllerTest {
    private static final String TENANT="10000000-0000-0000-0000-000000000001", ACTOR="10000000-0000-0000-0000-000000000002", TICKET="10000000-0000-0000-0000-000000000003";
    private final ObjectMapper mapper=new ObjectMapper();
    private final PlatformRepository platform=mock(PlatformRepository.class);
    private final TicketLifecycleRepository repository=mock(TicketLifecycleRepository.class);
    private final TicketLifecycleController controller=new TicketLifecycleController(platform,repository,mapper);
    private MockHttpServletRequest request(String capability){
        var request=new MockHttpServletRequest("POST","/api/v1/tickets/"+TICKET+"/lifecycle/actions/accept");
        request.setUserPrincipal(()->ACTOR);request.getSession().setAttribute("mop.tenant_id",TENANT);
        request.addHeader("Idempotency-Key","test-command-1");
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",1,true,List.of(capability))));
        return request;
    }
    @Test void acceptsOnlyExactActionFields() throws Exception {
        assertEquals(TicketLifecycle.Action.ACCEPT,TicketLifecycleController.parse("accept",mapper.readTree("{\"expected_version\":1}")).action());
        for(String body:List.of("{}","{\"expected_version\":0}","{\"expected_version\":1.0}","{\"expected_version\":9007199254740991}","{\"expected_version\":1,\"tenant_id\":\"override\"}"))
            assertThrows(DomainException.class,()->TicketLifecycleController.parse("accept",mapper.readTree(body)));
        assertThrows(DomainException.class,()->TicketLifecycleController.parse("auto-close",mapper.readTree("{\"expected_version\":1}")));
    }
    @Test void rejectsDuplicateTrailingNestedAndOversizedBodies(){
        for(String body:List.of("{\"expected_version\":1,\"expected_version\":2}","{} {}","{\"reason\":{\"nested\":{}}}"," ".repeat(65537))){
            var request=new MockHttpServletRequest();request.setContent(body.getBytes(StandardCharsets.UTF_8));
            assertThrows(DomainException.class,()->TicketLifecycleController.body(request));
        }
    }
    @Test void confirmationRequiresExactSnapshotHash() throws Exception {
        assertEquals("a".repeat(64),TicketLifecycleController.parse("confirm",mapper.readTree("{\"expected_version\":2,\"expected_solution_hash\":\""+"a".repeat(64)+"\"}")).expectedSolutionHash());
        assertThrows(DomainException.class,()->TicketLifecycleController.parse("confirm",mapper.readTree("{\"expected_version\":2,\"expected_solution_hash\":\"wrong\"}")));
    }
    @Test void authorizationPrecedesRepositoryAccess(){
        var request=request("tickets:read");
        assertEquals(403,assertThrows(DomainException.class,()->controller.request(TICKET,"accept",request)).status);
        verifyNoInteractions(repository);
    }
    @Test void missingSessionCannotRead(){
        assertEquals(401,assertThrows(DomainException.class,()->controller.get(TICKET,new MockHttpServletRequest())).status);
        verifyNoInteractions(repository);
    }
    @Test void duplicateQueryParametersAreRejected(){
        var request=request("tickets:read");request.addParameter("page_size","15","25");
        assertThrows(DomainException.class,()->controller.history(TICKET,request));verifyNoInteractions(repository);
    }
    @Test void writeUsesFreshResourceAuthorizationBeforeCommand() throws Exception {
        var request=request("tickets:lifecycle_write");
        when(platform.idempotentAuthorized(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any(),any()))
            .thenAnswer(invocation->{invocation.<Runnable>getArgument(6).run();return invocation.<Supplier<Object>>getArgument(7).get();});
        when(repository.act(any(),eq(TICKET),any())).thenReturn(entry());
        controller.write(TICKET,"accept",mapper.readTree("{\"expected_version\":1}"),request);
        var order=inOrder(repository);order.verify(repository).authorizeCurrentAccess(any(),eq(TICKET),eq(false));order.verify(repository).act(any(),eq(TICKET),any());
    }
    @Test void noAssigneeIdentityOrConfigurationLeaksToCustomer(){
        var dto=TicketLifecycleController.dto(entry(),false);
        assertNull(dto.get("assigned_engineer_id"));assertFalse(dto.containsKey("opening_snapshot"));assertEquals(false,dto.get("production_approved"));
        assertEquals("NOT_INTEGRATED",dto.get("notification_status"));
    }
    @Test void readRejectsMembershipChangeDuringRead(){
        var request=request("tickets:read");
        when(repository.get(any(),eq(TICKET))).thenAnswer(invocation->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());return Optional.empty();});
        assertEquals(401,assertThrows(DomainException.class,()->controller.get(TICKET,request)).status);
    }
    @Test void solutionReadReturnsContentButNotEngineerIdentity(){
        var request=request("tickets:read");
        var frozen=new TicketResolutionSnapshot(UUID.fromString(TENANT),UUID.fromString(TICKET),2,UUID.fromString(ACTOR),Instant.parse("2026-10-20T00:00:00Z"),"故障摘要","此处是已经核实的解决方案说明，需客户阅读后决定是否确认。");
        request.addParameter("expected_hash",frozen.sha256());when(repository.solution(any(),eq(TICKET),eq(frozen.sha256()))).thenReturn(Optional.of(frozen));
        Object response=controller.solution(TICKET,request);
        String encoded;
        try{encoded=mapper.findAndRegisterModules().writeValueAsString(response);}catch(Exception e){throw new AssertionError(e);}
        assertTrue(encoded.contains(frozen.solution()));assertFalse(encoded.contains("engineer_id"));assertTrue(encoded.contains(frozen.sha256()));
    }
    @Test void solutionReadRequiresBoundHashBeforeAccess(){
        var request=request("tickets:read");assertThrows(DomainException.class,()->controller.solution(TICKET,request));verifyNoInteractions(repository);
    }
    @Test void openingReadsConfigurationInRepositoryAndDoesNotAcceptClientRules(){
        var request=request("tickets:lifecycle_write");request.setContent("{\"expected_version\":0,\"priority\":\"P1\",\"service_tier\":\"ADVANCED\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(DomainException.class,()->controller.start(TICKET,request));verifyNoInteractions(repository);
    }
    @Test void openingBindsZeroVersionAndFreshResourceAuthorization(){
        var request=request("tickets:lifecycle_write");request.setRequestURI("/api/v1/tickets/"+TICKET+"/lifecycle");request.setContent("{\"expected_version\":0,\"priority\":\"P2\"}".getBytes(StandardCharsets.UTF_8));
        when(platform.idempotentAuthorized(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any(),any()))
            .thenAnswer(invocation->{invocation.<Runnable>getArgument(6).run();return invocation.<Supplier<Object>>getArgument(7).get();});
        when(repository.startConfigured(any(),eq(TICKET),eq("P2"))).thenReturn(entry());controller.start(TICKET,request);
        var order=inOrder(repository);order.verify(repository).authorizeCurrentAccess(any(),eq(TICKET),eq(false));order.verify(repository).startConfigured(any(),eq(TICKET),eq("P2"));
    }
    private TicketLifecycleRepository.Entry entry(){
        var now=Instant.parse("2026-10-20T00:00:00Z");
        return new TicketLifecycleRepository.Entry(TICKET,new TicketLifecycle.Snapshot(TENANT,TicketLifecycle.State.NEW,1,0,now,null,null,null,null,now.plusSeconds(60),now.plusSeconds(120)),ACTOR,List.of(),"{\"private\":true}");
    }
    @Test void slaRequiresCurrentReadPermissionAndHasNoWriteSideEffect(){
        var request=request("tickets:read");when(repository.sla(any(),eq(TICKET))).thenReturn(Optional.empty());
        assertNotNull(controller.sla(TICKET,request));verify(repository).sla(any(),eq(TICKET));verifyNoMoreInteractions(repository);
    }
    @Test void slaRejectsCallerSuppliedObservationOrTenant(){
        var request=request("tickets:read");request.addParameter("observed_at","2099-01-01T00:00:00Z");
        assertThrows(DomainException.class,()->controller.sla(TICKET,request));verifyNoInteractions(repository);
    }
    @Test void bindingRechecksResourceBeforeCallingRepository()throws Exception{
        var request=request("tickets:lifecycle_write");request.setContent(("{\"expected_lifecycle_version\":1,\"release_id\":\""+ACTOR+"\",\"release_version\":2}").getBytes(StandardCharsets.UTF_8));
        when(platform.idempotentAuthorized(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any(),any())).thenAnswer(i->{i.<Runnable>getArgument(6).run();return i.<Supplier<Object>>getArgument(7).get();});
        when(repository.bindSlaPolicy(any(),eq(TICKET),eq(1L),eq(ACTOR),eq(2L))).thenReturn(new TicketLifecycleRepository.PolicyBinding(TENANT,TICKET,1,"BOUND",ACTOR,2L,"EXCLUDE_CONFIRMED_PAUSES",Instant.parse("2026-10-20T00:00:00Z"),"binding:"+TICKET+":"+ACTOR+":2",null));
        assertNotNull(controller.bindSlaPolicy(TICKET,request));var order=inOrder(repository);order.verify(repository).authorizeCurrentAccess(any(),eq(TICKET),eq(false));order.verify(repository).bindSlaPolicy(any(),eq(TICKET),eq(1L),eq(ACTOR),eq(2L));
    }
}
