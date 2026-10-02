package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticketnote.*;
import cn.mop.domain.ticketnote.InternalTicketNoteRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalTicketNoteControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011",TICKET="55000000-0000-4000-8000-000000000021",ID="55000000-0000-4000-8000-000000000031";
    ObjectMapper mapper=new ObjectMapper();PlatformRepository platform;InternalTicketNoteRepository notes;InternalTicketNoteController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);notes=mock(InternalTicketNoteRepository.class);controller=new InternalTicketNoteController(platform,notes,mapper);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true)));}
    MockHttpServletRequest request(String method){var r=new MockHttpServletRequest(method,"/api/v1/tickets/"+TICKET+"/internal-notes");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");r.addHeader("Idempotency-Key","synthetic-request");return r;}
    Access access(){return new Access(ACTOR,TENANT,7,"synthetic-trace");}
    ObjectNode body(){return mapper.createObjectNode().put("expected_ticket_version",1).put("text","内部备注原文\n第二行");}
    Entry entry(){return new Entry(ID,TENANT,TICKET,ACTOR,1,new InternalTicketNote(body().get("text").textValue()),Instant.EPOCH);}
    Map<?,?> data(Object response){return (Map<?,?>)((Map<?,?>)response).get("data");}
    @Test void appendUsesTrustedScopeAndOriginalTextWithoutCustomerOrWorkflowClaim(){
        when(platform.idempotent(eq(ACTOR),eq(TENANT),eq("POST /api/v1/tickets/"+TICKET+"/internal-notes"),eq("synthetic-request"),anyString(),eq(7L),any())).thenAnswer(c->((Supplier<?>)c.getArgument(6)).get());when(notes.create(access(),TICKET,1,entry().note())).thenReturn(entry());var request=request("POST");request.addHeader("X-Tenant-ID","forged");var value=data(controller.write(TICKET,body(),request));assertEquals(ACTOR,value.get("actor_id"));assertEquals(TENANT,value.get("tenant_id"));assertEquals(entry().note().text(),value.get("text"));assertEquals(entry().note().sha256(),value.get("text_sha256"));assertEquals("INTERNAL",value.get("visibility"));assertEquals(true,value.get("immutable"));assertEquals(false,value.get("ticket_state_changed"));assertEquals(false,value.get("sla_changed"));verify(notes).create(access(),TICKET,1,entry().note());
    }
    @Test void onlyKnownInternalRolesHaveNoteCapabilities(){
        when(notes.get(access(),TICKET,ID)).thenReturn(entry());for(String role:List.of("msp","padmin")){assertTrue(Capabilities.forRole(role).containsAll(List.of("ticket_notes:read_internal","ticket_notes:write_internal")));when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(ID,data(controller.get(TICKET,ID,request("GET"))).get("id"));}
        for(String role:List.of("viewer","tadmin","unknown")){assertFalse(Capabilities.forRole(role).contains("ticket_notes:read_internal"));assertFalse(Capabilities.forRole(role).contains("ticket_notes:write_internal"));when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.get(TICKET,ID,request("GET"))).status);assertEquals(403,assertThrows(DomainException.class,()->controller.write(TICKET,body(),request("POST"))).status);}assertTrue(Capabilities.forRole(null).isEmpty());
    }
    @Test void inactiveOrAnonymousMembershipCannotRead(){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.list(TICKET,request("GET"))).status);var anonymous=request("GET");anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.get(TICKET,ID,anonymous)).status);verifyNoInteractions(notes);}
    @Test void pairedKeysetListIsBoundedAndReadOnly(){var r=request("GET");r.addParameter("page_size","10");when(notes.list(access(),TICKET,11,null,null)).thenReturn(Collections.nCopies(11,entry()));var value=data(controller.list(TICKET,r));assertEquals(10,((List<?>)value.get("items")).size());assertEquals(true,value.get("has_more"));assertEquals(ID,value.get("next_before_id"));var next=request("GET");next.addParameter("before_created_at","1970-01-01T08:00:00+08:00");next.addParameter("before_id",ID);when(notes.list(access(),TICKET,16,Instant.EPOCH,ID)).thenReturn(List.of());assertNull(data(controller.list(TICKET,next)).get("next_before_id"));verify(platform,never()).idempotent(any(),any(),any(),any(),any(),anyLong(),any());}
    @Test void rejectsSpoofedVisibilityActorAttachmentsAndUnsafeParentVersion(){for(String key:List.of("visibility","tenant_id","actor_id","ticket_id","attachment_ids","state","sla_changed"))assertEquals(400,assertThrows(DomainException.class,()->controller.write(TICKET,body().put(key,"forged"),request("POST"))).status);for(JsonNode value:List.of(body().put("expected_ticket_version",0),body().put("expected_ticket_version",1.5),body().put("expected_ticket_version",Long.MAX_VALUE),body().putNull("text"),body().put("text","\ufeff"),body().put("text","x\u0000")))assertEquals(400,assertThrows(DomainException.class,()->controller.write(TICKET,value,request("POST"))).status);verifyNoInteractions(notes);}
    @Test void queryOverridesDuplicatesAndMissingKeyAreRejected(){for(String key:List.of("tenant_id","owner_id","before_created_at","before_id")){var r=request("GET");r.addParameter(key,ID);assertEquals(400,assertThrows(DomainException.class,()->controller.list(TICKET,r)).status);}var dup=request("GET");dup.addParameter("page_size",new String[]{"10","15"});assertEquals(400,assertThrows(DomainException.class,()->controller.list(TICKET,dup)).status);var noKey=request("POST");noKey.removeHeader("Idempotency-Key");assertEquals(400,assertThrows(DomainException.class,()->controller.write(TICKET,body(),noKey)).status);verifyNoInteractions(notes);}
    @Test void boundedJsonRejectsDuplicateTrailingNestedAndOversizedInput()throws Exception{for(String text:List.of("{\"text\":\"a\",\"text\":\"b\"}","{} {}","[[[1]]]"," ".repeat(32769),"[0,0,0,0,0,0,0,0,0]")){var r=request("POST");r.setContent(text.getBytes(StandardCharsets.UTF_8));assertEquals(400,assertThrows(DomainException.class,()->InternalTicketNoteController.body(r)).status);}var expected=body().put("text","😀".repeat(2000));var r=request("POST");r.setContent(mapper.writeValueAsBytes(expected));assertEquals(expected,InternalTicketNoteController.body(r));}
    @Test void reorderedFieldsReplayButChangedRawTextHashesDiffer(){List<String> hashes=new ArrayList<>();when(platform.idempotent(any(),any(),any(),any(),any(),anyLong(),any())).thenAnswer(c->{hashes.add(c.getArgument(4));return Map.of("replayed",true);});controller.write(TICKET,body(),request("POST"));var reversed=mapper.createObjectNode().put("text",body().get("text").textValue()).put("expected_ticket_version",1);controller.write(TICKET,reversed,request("POST"));controller.write(TICKET,reversed.put("text","其他原文"),request("POST"));assertEquals(hashes.get(0),hashes.get(1));assertNotEquals(hashes.get(0),hashes.get(2));verifyNoInteractions(notes);}
}
