package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.change.*;
import cn.mop.domain.change.ChangeNoticeApproval.*;
import cn.mop.domain.change.ChangeNoticeApprovalRepository.Access;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated input/authorization/DTO checks; no external database claim. */
class ChangeNoticeApprovalControllerTest {
    static final String ACTOR="76000000-0000-4000-8000-000000000001", TENANT="76000000-0000-4000-8000-000000000002", ID="76000000-0000-4000-8000-000000000003", ASSET="76000000-0000-4000-8000-000000000004";
    PlatformRepository platform; ChangeNoticeApprovalRepository approvals; ChangeNoticeApprovalController controller;
    ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() {
        platform=mock(PlatformRepository.class); approvals=mock(ChangeNoticeApprovalRepository.class); controller=new ChangeNoticeApprovalController(platform,approvals);
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));
        when(platform.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(c->{((Runnable)c.getArgument(6)).run();return ((Supplier<?>)c.getArgument(7)).get();});
        when(approvals.transition(any(),any(),anyLong(),anyLong(),any(),any())).thenReturn(snapshot());
        when(approvals.view(any(),any(),anyLong())).thenReturn(new ChangeNoticeApprovalRepository.View(snapshot(),List.of(Action.SUBMIT,Action.WITHDRAW)));
    }
    static Snapshot snapshot() {
        var d=new ChangeNoticeDraft(UUID.fromString(TENANT),UUID.fromString(ACTOR),null,"合成修订",Instant.parse("2026-10-20T00:00:00Z"),null,List.of(UUID.fromString(ASSET)));
        return ChangeNoticeApproval.create(UUID.fromString(ID),4,d,Instant.parse("2026-10-19T00:00:00Z"));
    }
    Access access(){return new Access(ACTOR,TENANT,7,"approval-test");}
    MockHttpServletRequest request(String method) { var r=new MockHttpServletRequest(method,"/api/v1/change-notice-drafts/"+ID+"/revisions/4/approval/actions");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","approval-test");r.addHeader("Idempotency-Key","approval-test-key");return r; }
    ObjectNode input(){return mapper.createObjectNode().put("expected_version",1).put("action","SUBMIT").putNull("note");}
    static Map<?,?> data(Object o){return (Map<?,?>)((Map<?,?>)o).get("data");}
    @Test void exactRevisionAndVersionUseFreshAuthorization() {
        var result=data(controller.write(ID,"4",input(),request("POST")));
        assertEquals(4L,result.get("draft_revision"));assertEquals(1L,result.get("version"));assertEquals(TENANT,result.get("tenant_id"));
        for(String flag:List.of("effective","device_execution_allowed","alert_suppression_allowed"))assertEquals(false,result.get(flag));assertNull(result.get("automatic_p3_decision"));
        var order=inOrder(approvals);order.verify(approvals).authorizeCurrentAccess(access(),ID,4,Action.SUBMIT);order.verify(approvals).transition(access(),ID,4,1,Action.SUBMIT,null);
    }
    @Test void receiptReplayRechecksPolicyWithoutRepeatingTransition() {
        doAnswer(c->{((Runnable)c.getArgument(6)).run();return Map.of("receipt",true);}).when(platform).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());
        assertEquals(true,data(controller.write(ID,"4",input(),request("POST"))).get("receipt"));verify(approvals).authorizeCurrentAccess(access(),ID,4,Action.SUBMIT);verify(approvals,never()).transition(any(),any(),anyLong(),anyLong(),any(),any());
    }
    @Test void revokedReplayNeverReturnsReceipt() {
        doThrow(new DomainException(403,"ACTION_FORBIDDEN","revoked")).when(approvals).authorizeCurrentAccess(any(),any(),anyLong(),any());
        assertEquals(403,assertThrows(DomainException.class,()->controller.write(ID,"4",input(),request("POST"))).status);verify(approvals,never()).transition(any(),any(),anyLong(),anyLong(),any(),any());
    }
    @Test void exactFieldsNoForgedAuthorityOrActivation() {
        for(String field:List.of("tenant_id","reviewer_id","review_policy_release_id","effective","allowed_actions","actor_id","draft_revision"))assertThrows(DomainException.class,()->controller.write(ID,"4",input().put(field,"forged"),request("POST")));
        for(String field:List.of("expected_version","action","note")){var n=input();n.remove(field);assertThrows(DomainException.class,()->controller.write(ID,"4",n,request("POST")));} verifyNoInteractions(approvals);
    }
    @Test void versionsActionsAndNoteHaveStrictTypesAndBounds() {
        for(String rev:List.of("0","01","-1","4.0","9007199254740991","4\n"))assertThrows(DomainException.class,()->controller.write(ID,rev,input(),request("POST")));
        for(long v:new long[]{0,-1,9007199254740990L})assertThrows(DomainException.class,()->controller.write(ID,"4",input().put("expected_version",v),request("POST")));
        for(String action:List.of("approve","ACTIVATE","APPROVE "))assertThrows(DomainException.class,()->controller.write(ID,"4",input().put("action",action),request("POST")));
        assertThrows(DomainException.class,()->controller.write(ID,"4",input().put("expected_version",1.0),request("POST")));
        assertThrows(DomainException.class,()->controller.write(ID,"4",input().put("note",5),request("POST")));
        for(String note:List.of("", "\u00a0", "\u0000", "\ud800", "😀".repeat(2001)))assertThrows(DomainException.class,()->controller.write(ID,"4",input().put("note",note),request("POST")));
        assertThrows(DomainException.class,()->controller.write(ID,"4",input().put("action","REJECT"),request("POST")));verifyNoInteractions(approvals);
    }
    @Test void unicodeTextPreservedAndHashIgnoresFieldOrder() {
        var n=input().put("note"," \n😀 ");controller.write(ID,"4",n,request("POST"));
        controller.write(ID,"4",mapper.createObjectNode().put("note"," \n😀 ").put("action","SUBMIT").put("expected_version",1),request("POST"));
        controller.write(ID,"4",n.deepCopy().put("expected_version",2),request("POST"));
        var hashes=ArgumentCaptor.forClass(String.class);verify(platform,times(3)).idempotentAuthorized(any(),any(),any(),any(),hashes.capture(),anyLong(),any(),any());assertEquals(hashes.getAllValues().get(0),hashes.getAllValues().get(1));assertNotEquals(hashes.getAllValues().get(0),hashes.getAllValues().get(2));
        verify(approvals,times(2)).transition(access(),ID,4,1,Action.SUBMIT," \n😀 ");
    }
    @Test void parsingRejectsDuplicatesTrailingDocumentsDepthAndOversize() {
        for(String raw:List.of("{\"action\":1,\"action\":2}","{}{}","{\"a\":{\"b\":{}}}")){var r=request("POST");r.setContent(raw.getBytes(StandardCharsets.UTF_8));assertThrows(DomainException.class,()->controller.action(ID,"4",r));}
        var r=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return -1;}};r.setContent(new byte[ChangeNoticeApprovalController.MAX_BODY_BYTES+1]);assertEquals(413,assertThrows(DomainException.class,()->ChangeNoticeApprovalController.body(r)).status);
    }
    @Test void deniedMembershipPrecedesBodyParsing() {
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));
        var r=request("POST");r.setContent("not json".getBytes(StandardCharsets.UTF_8));assertEquals(403,assertThrows(DomainException.class,()->controller.action(ID,"4",r)).status);
        assertEquals(401,assertThrows(DomainException.class,()->controller.get(ID,"4",new MockHttpServletRequest())).status);verifyNoInteractions(approvals);
    }
    @Test void queryAndIdempotencyHeadersAreStrict() {
        var q=request("POST");q.addParameter("dry_run","true");assertThrows(DomainException.class,()->controller.write(ID,"4",input(),q));
        var missing=request("POST");missing.removeHeader("Idempotency-Key");assertThrows(DomainException.class,()->controller.write(ID,"4",input(),missing));
        var duplicate=request("POST");duplicate.addHeader("Idempotency-Key","other");assertThrows(DomainException.class,()->controller.write(ID,"4",input(),duplicate));
        var get=request("GET");get.addParameter("tenant_id",TENANT);assertThrows(DomainException.class,()->controller.get(ID,"4",get));verifyNoInteractions(approvals);
    }
    @Test void historyCursorPaginationRetainsExactRevision() {
        var e=new Event(UUID.fromString(ID),4,snapshot().fingerprint(),2,State.DRAFT,State.IN_REVIEW,Action.SUBMIT,UUID.fromString(ACTOR),7,null,null,Instant.parse("2026-10-19T01:00:00Z"));
        when(approvals.history(access(),ID,4,16,null)).thenReturn(Collections.nCopies(16,e));var out=data(controller.history(ID,"4",request("GET")));assertEquals(true,out.get("has_more"));assertEquals(2L,out.get("next_before_version"));assertEquals(15,((List<?>)out.get("items")).size());
        for(String cursor:List.of("1","0","01","2.0")){var r=request("GET");r.addParameter("before_version",cursor);assertThrows(DomainException.class,()->controller.history(ID,"4",r));}
        var duplicate=request("GET");duplicate.addParameter("page_size","10","20");assertThrows(DomainException.class,()->controller.history(ID,"4",duplicate));
    }
    @Test void getExposesOnlyRepositoryAuthorizedActionsAndReceiptsAreConservative() {
        assertEquals(List.of("SUBMIT","WITHDRAW"),data(controller.get(ID,"4",request("GET"))).get("allowed_actions"));
        assertEquals(List.of(),data(controller.write(ID,"4",input(),request("POST"))).get("allowed_actions"));
        verify(approvals).view(access(),ID,4);
    }
    @Test void configurationRefusesFixtureRepository() {assertThrows(IllegalStateException.class,()->new ChangeNoticeApprovalConfiguration().changeNoticeApprovalRepository(mock(javax.sql.DataSource.class),platform,mapper));}
}
