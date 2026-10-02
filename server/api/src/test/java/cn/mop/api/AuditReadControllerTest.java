package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.audit.AuditReader;
import cn.mop.domain.audit.AuditReader.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditReadControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011";
    PlatformRepository platform;AuditReader audits;AuditReadController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);audits=mock(AuditReader.class);controller=new AuditReadController(platform,audits);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true)));}
    Access access(){return new Access(ACTOR,TENANT,7);}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/audit/records");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");return r;}
    Map<?,?> data(Object response){return (Map<?,?>)((Map<?,?>)response).get("data");}
    @Test void defaultListUsesTrustedCurrentMembershipAndAdvertisesLocalScopeOnly(){
        var filters=new Filters(null,null,null,null,null,null,null,null);when(audits.list(access(),filters,15,null,null)).thenReturn(new Page(List.of(),0,null,false));var r=request();r.addHeader("X-Tenant-ID","forged");var data=data(controller.records(r));assertEquals(0L,data.get("snapshot_sequence"));assertEquals("CURRENT_TENANT",data.get("scope"));assertEquals(false,data.get("externally_anchored"));assertEquals("EACH_RECORD_LOCAL_HASH_ONLY",data.get("integrity_scope"));verify(audits).list(access(),filters,15,null,null);verify(platform,never()).audit(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void filtersAreExactAndNormalizeOnlyExplicitTimestampAndActorSyntax(){
        var r=request();r.addParameter("page_size","10");r.addParameter("snapshot_sequence","100");r.addParameter("before_sequence","50");r.addParameter("actor_id",ACTOR.toUpperCase());r.addParameter("action","exact.action");r.addParameter("result","DENIED");r.addParameter("resource_type","asset");r.addParameter("resource_id","synthetic-resource");r.addParameter("trace_id","synthetic-trace");r.addParameter("occurred_from","2026-10-02T08:00:00+08:00");r.addParameter("occurred_before","2026-10-02T09:00:00+08:00");
        var filters=new Filters(ACTOR,"exact.action","DENIED","asset","synthetic-resource","synthetic-trace",Instant.parse("2026-10-02T00:00:00Z"),Instant.parse("2026-10-02T01:00:00Z"));when(audits.list(access(),filters,10,100L,50L)).thenReturn(new Page(List.of(),100,null,false));controller.records(r);verify(audits).list(access(),filters,10,100L,50L);
    }
    @Test void unsupportedRoleInactiveAndAnonymousRequestsNeverQueryAudit(){
        for(String role:List.of("msp","viewer","tadmin","unknown")){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.records(request())).status);assertEquals(403,assertThrows(DomainException.class,()->controller.integrity(request())).status);}
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.records(request())).status);var r=request();r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.integrity(r)).status);verifyNoInteractions(audits);
    }
    @Test void extraDuplicateUnpairedAndUnsafeSequenceQueriesAreRejected(){
        for(var pair:List.of(List.of("tenant_id",TENANT),List.of("namespace","platform"),List.of("page_size","1000"),List.of("before_sequence","50"),List.of("snapshot_sequence","9007199254740992"),List.of("snapshot_sequence","01"),List.of("actor_id","not-uuid"),List.of("action","\u00a0"))){var r=request();r.addParameter(pair.get(0),pair.get(1));assertEquals(400,assertThrows(DomainException.class,()->controller.records(r)).status);}
        var duplicate=request();duplicate.addParameter("page_size",new String[]{"10","15"});assertEquals(400,assertThrows(DomainException.class,()->controller.records(duplicate)).status);var before=request();before.addParameter("snapshot_sequence","10");before.addParameter("before_sequence","11");assertEquals(400,assertThrows(DomainException.class,()->controller.records(before)).status);verifyNoInteractions(audits);
    }
    @Test void ambiguousInvalidReversedAndOverpreciseTimeFiltersAreRejected(){
        for(String value:List.of("2026-02-29T00:00:00Z","2026-10-02T00:00:00","2026-10-02T00:00:00.1234Z","0001-01-01T00:00:00+01:00")){var r=request();r.addParameter("occurred_from",value);assertEquals(400,assertThrows(DomainException.class,()->controller.records(r)).status);}
        var r=request();r.addParameter("occurred_from","2026-10-02T00:00:00Z");r.addParameter("occurred_before","2026-10-02T00:00:00Z");assertEquals(400,assertThrows(DomainException.class,()->controller.records(r)).status);verifyNoInteractions(audits);
    }
    @Test void validPartialAndEmptyProofNeverBecomeExternallyAnchoredOrWholeChainClaims(){
        Instant checked=Instant.parse("2026-10-02T00:00:00Z");when(audits.verify(access(),1,null)).thenReturn(new Integrity("VALID",true,2000,1L,1000L,1000,null,"NONE","0".repeat(64),"a".repeat(64),true,1001L,checked));var partial=data(controller.integrity(request()));assertEquals(false,partial.get("whole_snapshot_chain_checked"));assertEquals(false,partial.get("externally_anchored"));assertEquals(1001L,partial.get("next_from_sequence"));
        when(audits.verify(access(),1,null)).thenReturn(new Integrity("EMPTY",null,0,null,null,0,null,"NONE",null,null,false,null,checked));var empty=data(controller.integrity(request()));assertNull(empty.get("valid"));assertEquals(false,empty.get("whole_snapshot_chain_checked"));
        when(audits.verify(access(),1,null)).thenReturn(new Integrity("VALID",true,10,1L,10L,10,null,"NONE","0".repeat(64),"a".repeat(64),false,null,checked));assertEquals(true,data(controller.integrity(request())).get("whole_snapshot_chain_checked"));
    }
    @Test void integrityIntervalsAreBoundedAndFailureRemainsExplicit(){
        for(var values:List.of(List.of("0","1"),List.of("1","1001"),List.of("2","1"),List.of("9007199254740992","9007199254740992"))){var r=request();r.addParameter("from_sequence",values.get(0));r.addParameter("to_sequence",values.get(1));assertEquals(400,assertThrows(DomainException.class,()->controller.integrity(r)).status);}
        var r=request();r.addParameter("from_sequence","3");r.addParameter("to_sequence","5");when(audits.verify(access(),3,5L)).thenReturn(new Integrity("INVALID",false,20,3L,5L,1,4L,"HASH_MISMATCH","a".repeat(64),"b".repeat(64),true,null,Instant.parse("2026-10-02T00:00:00Z")));var result=data(controller.integrity(r));assertEquals(false,result.get("valid"));assertEquals("HASH_MISMATCH",result.get("failure"));assertEquals(false,result.get("whole_snapshot_chain_checked"));assertNull(result.get("next_from_sequence"));
    }
}
