package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.worklog.*;
import cn.mop.domain.worklog.WorklogRepository.*;
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

class WorklogControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011",TICKET="55000000-0000-4000-8000-000000000021",ID="55000000-0000-4000-8000-000000000031";
    ObjectMapper mapper=new ObjectMapper();PlatformRepository platform;WorklogRepository worklogs;WorklogController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);worklogs=mock(WorklogRepository.class);controller=new WorklogController(platform,worklogs,mapper);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true)));}
    MockHttpServletRequest request(String method){var r=new MockHttpServletRequest(method,"/api/v1/tickets/"+TICKET+"/worklogs");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");r.addHeader("Idempotency-Key","synthetic-request");return r;}
    Access access(){return new Access(ACTOR,TENANT,7,"synthetic-trace");}
    ObjectNode body(){return mapper.createObjectNode().put("expected_ticket_version",1).put("kind","REMOTE").put("started_at","2026-10-02T00:00:00Z").put("ended_at","2026-10-04T01:00:00Z").putNull("note");}
    ManualWorklog facts(){return new ManualWorklog(ManualWorklog.Kind.REMOTE,Instant.parse("2026-10-02T00:00:00Z"),Instant.parse("2026-10-04T01:00:00Z"),null);}
    Entry entry(long version){return new Entry(ID,TENANT,TICKET,ACTOR,version,Instant.parse("2026-10-02T00:00:00Z"),Instant.parse("2026-10-02T00:00:00Z"),facts());}
    Map<?,?> data(Object response){return (Map<?,?>)((Map<?,?>)response).get("data");}
    @Test void createUsesTrustedActorPermissionAndIdempotentTransaction(){
        when(platform.idempotent(eq(ACTOR),eq(TENANT),eq("POST /api/v1/tickets/"+TICKET+"/worklogs"),eq("synthetic-request"),anyString(),eq(7L),any())).thenAnswer(c->((Supplier<?>)c.getArgument(6)).get());when(worklogs.create(access(),TICKET,1,facts())).thenReturn(entry(1));
        var request=request("POST");request.addHeader("X-Tenant-ID","forged");Map<?,?> value=data(controller.write(TICKET,null,body(),request));assertEquals(ACTOR,value.get("owner_id"));assertEquals(TENANT,value.get("tenant_id"));assertEquals(49*3600000L,value.get("elapsed_millis"));assertEquals("SELF_REPORTED",value.get("verification_status"));assertEquals("CONFIG_PENDING",value.get("financial_status"));for(String key:List.of("default_minutes","labor_cost","transport_cost","gross_margin"))assertNull(value.get(key));verify(worklogs).create(access(),TICKET,1,facts());
    }
    @Test void updateUsesOwnRecordVersionAndDoesNotAcceptParentOrOwnerMutation(){
        when(platform.idempotent(eq(ACTOR),eq(TENANT),anyString(),anyString(),anyString(),eq(7L),any())).thenAnswer(c->((Supplier<?>)c.getArgument(6)).get());when(worklogs.update(access(),TICKET,ID,1,facts())).thenReturn(entry(2));var body=body();body.remove("expected_ticket_version");body.put("expected_version",1);assertEquals(2L,data(controller.write(TICKET,ID,body,request("PATCH"))).get("version"));verify(worklogs).update(access(),TICKET,ID,1,facts());
        for(String key:List.of("owner_id","tenant_id","ticket_id","expected_ticket_version"))assertEquals(400,assertThrows(DomainException.class,()->controller.write(TICKET,ID,body.deepCopy().put(key,ACTOR),request("PATCH"))).status);
    }
    @Test void onlyEngineerOwnRoleHasActionsAndExpiredMembershipIsRejected(){
        for(String role:List.of("padmin","viewer","tadmin","unknown")){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.get(TICKET,ID,request("GET"))).status);assertEquals(403,assertThrows(DomainException.class,()->controller.write(TICKET,null,body(),request("POST"))).status);}
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.get(TICKET,ID,request("GET"))).status);var anonymous=request("GET");anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.get(TICKET,ID,anonymous)).status);verifyNoInteractions(worklogs);
    }
    @Test void listsAndHistoriesAreBoundedAndReadOnly(){
        when(worklogs.listOwn(access(),TICKET,11,null,null)).thenReturn(Collections.nCopies(11,entry(1)));var request=request("GET");request.addParameter("page_size","10");Map<?,?> value=data(controller.list(TICKET,request));assertEquals(10,((List<?>)value.get("items")).size());assertEquals(true,value.get("has_more"));assertEquals(ID,value.get("next_before_id"));
        var next=request("GET");next.addParameter("before_created_at","2026-10-02T00:00:00Z");next.addParameter("before_id",ID);when(worklogs.listOwn(access(),TICKET,16,entry(1).createdAt(),ID)).thenReturn(List.of());assertEquals(false,data(controller.list(TICKET,next)).get("has_more"));
        when(worklogs.historyOwn(access(),TICKET,ID,16,2L)).thenReturn(List.of(new Revision(1,"CREATE",ACTOR,entry(1).createdAt(),facts())));var history=request("GET");history.addParameter("before_version","2");assertNull(data(controller.history(TICKET,ID,history)).get("next_before_version"));verify(platform,never()).idempotent(any(),any(),any(),any(),any(),anyLong(),any());
    }
    @Test void extraDuplicateOrUnpairedQueryAndUnsafeVersionsAreRejected(){
        for(String key:List.of("tenant_id","owner_id","before_id","before_created_at")){var request=request("GET");request.addParameter(key,ID);assertEquals(400,assertThrows(DomainException.class,()->controller.list(TICKET,request)).status);}
        var duplicate=request("GET");duplicate.addParameter("page_size",new String[]{"10","15"});assertEquals(400,assertThrows(DomainException.class,()->controller.list(TICKET,duplicate)).status);
        for(String version:List.of("0","-1","1.5","9007199254740991")){var request=request("GET");request.addParameter("before_version",version);assertEquals(400,assertThrows(DomainException.class,()->controller.history(TICKET,ID,request)).status);}verifyNoInteractions(worklogs);
    }
    @Test void strictFactFieldsRejectDefaultsUnknownsAndInvalidIntervals(){
        List<JsonNode> candidates=new ArrayList<>();candidates.add(body().put("expected_ticket_version",0));candidates.add(body().put("expected_ticket_version",1.5));candidates.add(body().put("expected_ticket_version",Long.MAX_VALUE));candidates.add(body().put("kind","remote"));candidates.add(body().put("started_at","2026-10-02T00:00:00"));candidates.add(body().put("ended_at","2026-10-01T00:00:00Z"));candidates.add(body().put("note",5));candidates.add(body().put("note"," "));candidates.add(body().put("labor_cost",0));var missing=body();missing.remove("note");candidates.add(missing);for(var b:candidates)assertEquals(400,assertThrows(DomainException.class,()->controller.write(TICKET,null,b,request("POST"))).status);verifyNoInteractions(worklogs);
    }
    @Test void boundedParserRejectsDuplicateTrailingNestedAndOversizedJson()throws Exception{
        for(String value:List.of("{\"kind\":\"REMOTE\",\"kind\":\"ONSITE\"}","{} {}","[[[1]]]"," ".repeat(WorklogController.MAX_BODY_BYTES+1),"["+"0,".repeat(40)+"0]")){var request=request("POST");request.setContent(value.getBytes(StandardCharsets.UTF_8));assertEquals(400,assertThrows(DomainException.class,()->WorklogController.body(request)).status);}
        var value=body().put("note","😀".repeat(2000));var request=request("POST");request.setContent(mapper.writeValueAsBytes(value));assertEquals(value,WorklogController.body(request));
    }
    @Test void sameSemanticFieldOrderHasSameHashButChangedValueDoesNot(){
        List<String> hashes=new ArrayList<>();when(platform.idempotent(any(),any(),any(),any(),any(),anyLong(),any())).thenAnswer(c->{hashes.add(c.getArgument(4));return Map.of("replayed",true);});var first=body();var reverse=mapper.createObjectNode().putNull("note").put("ended_at",first.get("ended_at").textValue()).put("started_at",first.get("started_at").textValue()).put("kind","REMOTE").put("expected_ticket_version",1);controller.write(TICKET,null,first,request("POST"));controller.write(TICKET,null,reverse,request("POST"));controller.write(TICKET,null,reverse.put("note","明确更改"),request("POST"));assertEquals(hashes.get(0),hashes.get(1));assertNotEquals(hashes.get(0),hashes.get(2));verifyNoInteractions(worklogs);
    }
}
