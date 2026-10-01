package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated report/append routing; the repository suite owns transactional READY/CAS evidence. */
class TicketPhotoControllerTest {
    private static final String ACTOR="55000000-0000-4000-8000-000000000003",TENANT="55000000-0000-4000-8000-000000000001",TICKET="55000000-0000-4000-8000-000000000009",FILE="55000000-0000-4000-8000-000000000010",OPERATION="55000000-0000-4000-8000-000000000011";
    private PlatformRepository repository;private TicketController controller;private final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup(){repository=mock(PlatformRepository.class);controller=new TicketController(repository,mapper);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",8,true)));when(repository.idempotent(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any())).thenAnswer(call->call.getArgument(6,Supplier.class).get());}
    private MockHttpServletRequest request(String path){var request=new MockHttpServletRequest("POST","/api/v1"+path);var session=new MockHttpSession();session.setAttribute("mop.tenant_id",TENANT);request.setSession(session);request.setUserPrincipal(()->ACTOR);request.addHeader("Idempotency-Key","isolated-ticket-request");request.setAttribute("mop.trace_id","isolated-trace");return request;}
    private ObjectNode append(){return mapper.createObjectNode().put("expected_version",2).set("attachment_ids",mapper.createArrayNode().add(FILE));}
    private ObjectNode report(){return mapper.createObjectNode().put("source","customer_report").put("description","虚构报障").put("category","网络不通").put("impact_scope","company").put("contact_name","虚构联系人").put("contact_phone","12345").put("urgency","normal");}
    @Test void appendPassesTrustedScopeExpectedVersionAndUniqueReferencesInsideReplayTransaction(){
        Map<String,Object> result=Map.of("id",TICKET,"version",3L,"attachments",List.of(Map.of("id",FILE)));when(repository.attachPhotos(TENANT,ACTOR,TICKET,2,List.of(FILE))).thenReturn(result);Object response=controller.attachments(TICKET,append(),request("/tickets/"+TICKET+"/attachments"));assertEquals(result,((Map<?,?>)response).get("data"));verify(repository).idempotent(eq(ACTOR),eq(TENANT),eq("/api/v1/tickets/"+TICKET+"/attachments"),eq("isolated-ticket-request"),anyString(),eq(8L),any());verify(repository).audit(ACTOR,TENANT,"ticket.attachments.add","ticket",TICKET,"SUCCEEDED","READY_ORIGINAL_PHOTOS_UNSCANNED","isolated-trace");
    }
    @Test void appendRejectsUnknownFieldsEmptyArraysDuplicatesAndInvalidVersions(){
        for(String field:List.of("actor_id","tenant_id","reporter_id","state"))assertThrows(DomainException.class,()->controller.attachments(TICKET,append().put(field,"forged"),request("/tickets/"+TICKET+"/attachments")));
        assertThrows(DomainException.class,()->controller.attachments(TICKET,append().set("attachment_ids",mapper.createArrayNode()),request("/tickets/"+TICKET+"/attachments")));assertThrows(DomainException.class,()->controller.attachments(TICKET,append().set("attachment_ids",mapper.createArrayNode().add(FILE).add(FILE)),request("/tickets/"+TICKET+"/attachments")));
        for(long version:List.of(0L,-1L))assertThrows(DomainException.class,()->controller.attachments(TICKET,append().put("expected_version",version),request("/tickets/"+TICKET+"/attachments")));assertThrows(DomainException.class,()->controller.attachments(TICKET,append().put("expected_version",1.5),request("/tickets/"+TICKET+"/attachments")));verify(repository,never()).attachPhotos(anyString(),anyString(),anyString(),anyLong(),anyList());
    }
    @Test void internalRoleCannotAppendAndRepositoryVisibilityOrCasErrorsStayExplicit(){
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",9,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.attachments(TICKET,append(),request("/tickets/"+TICKET+"/attachments"))).status);verify(repository,never()).attachPhotos(anyString(),anyString(),anyString(),anyLong(),anyList());
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",10,true)));for(DomainException error:List.of(new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见"),new DomainException(409,"VERSION_CONFLICT","对象版本已变化"))){doThrow(error).when(repository).attachPhotos(TENANT,ACTOR,TICKET,2,List.of(FILE));assertSame(error,assertThrows(DomainException.class,()->controller.attachments(TICKET,append(),request("/tickets/"+TICKET+"/attachments"))));}
    }
    @Test void noPhotoReportDoesNotRequirePhotoComponentsAndReadyReferencesReachRepository(){
        when(repository.registerCustomerReport(eq(TENANT),eq(ACTOR),any())).thenReturn(new CustomerReportRegistration(OPERATION,TICKET,true));assertEquals(202,controller.create(report(),request("/tickets")).getStatusCode().value());verify(repository).registerCustomerReport(eq(TENANT),eq(ACTOR),argThat(report->report.attachmentIds().isEmpty()));
        controller.create(report().set("attachment_ids",mapper.createArrayNode().add(FILE)),request("/tickets"));verify(repository).registerCustomerReport(eq(TENANT),eq(ACTOR),argThat(report->report.attachmentIds().equals(List.of(FILE))));verify(repository,never()).acceptPhoto(anyString(),anyString(),any());
    }
    @Test void semanticDuplicateWithNewPhotosAuditsAppendWithoutClaimingNewRegistration(){
        when(repository.registerCustomerReport(eq(TENANT),eq(ACTOR),any())).thenReturn(new CustomerReportRegistration(OPERATION,TICKET,false,true));controller.create(report().set("attachment_ids",mapper.createArrayNode().add(FILE)),request("/tickets"));verify(repository,never()).audit(anyString(),anyString(),eq("ticket.customer_report.register"),anyString(),anyString(),anyString(),anyString(),anyString());verify(repository).audit(ACTOR,TENANT,"ticket.attachments.add","ticket",TICKET,"SUCCEEDED","READY_ORIGINAL_PHOTOS_UNSCANNED","isolated-trace");
    }
}
