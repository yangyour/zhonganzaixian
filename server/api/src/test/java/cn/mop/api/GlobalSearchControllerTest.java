package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.search.*;
import cn.mop.domain.search.GlobalSearchRepository.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GlobalSearchControllerTest {
    static final String ACTOR="60000000-0000-4000-8000-000000000001",TENANT="60000000-0000-4000-8000-000000000011";PlatformRepository platform;GlobalSearchRepository search;GlobalSearchController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);search=mock(GlobalSearchRepository.class);controller=new GlobalSearchController(platform,search);role("msp");}
    void role(String role){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/search");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");r.addParameter("q","literal");return r;}
    Access access(){return new Access(ACTOR,TENANT,7);}
    Snapshot result(){return new Snapshot("literal",TENANT,Instant.EPOCH,"CURRENT_TENANT_INTERNAL",List.of(new AssetHit("60000000-0000-4000-8000-000000000021",TENANT,"literal","router","manual",1)),false,List.of(new TicketHit("60000000-0000-4000-8000-000000000022",TENANT,"literal","synthetic-receipt","customer_report","REGISTERED",1)),false,true,List.of(),false);}
    Map<?,?> data(MockHttpServletRequest r){return (Map<?,?>)((Map<?,?>)controller.get(r)).get("data");}
    @Test void exactFourGroupsAndContractCapsPreservePartialSources(){when(search.search(access(),new GlobalSearchQuery("literal"))).thenReturn(result());var data=data(request());assertEquals("PARTIAL",data.get("coverage"));assertEquals("NOT_IMPLEMENTED",data.get("notification_bell_status"));var groups=(List<?>)data.get("groups");assertEquals(List.of("assets","events","tickets","automation_scripts"),groups.stream().map(v->((Map<?,?>)v).get("kind")).toList());assertEquals(List.of(5,4,3,3),groups.stream().map(v->((Map<?,?>)v).get("limit")).toList());for(int i:new int[]{3}){var group=(Map<?,?>)groups.get(i);assertEquals("NOT_IMPLEMENTED",group.get("status"));for(String key:List.of("items","returned","has_more","source_scope","matched_fields"))assertNull(group.get(key));}}
    @Test void minimalDtoDoesNotExposeContactIpOrDescription(){when(search.search(any(),any())).thenReturn(result());var groups=(List<?>)data(request()).get("groups");var asset=(Map<?,?>)((List<?>)((Map<?,?>)groups.get(0)).get("items")).get(0);var ticket=(Map<?,?>)((List<?>)((Map<?,?>)groups.get(2)).get("items")).get(0);assertEquals(Set.of("id","tenant_id","name","type","record_source","version","resource_kind"),asset.keySet());assertEquals(Set.of("id","tenant_id","title","receipt_no","record_source","registration_status","version","resource_kind"),ticket.keySet());assertEquals("CUSTOMER_REPORT_REGISTRATION",ticket.get("resource_kind"));}
    @Test void supportedEmptyGroupsAreEmptyButUnsupportedRemainNull(){when(search.search(any(),any())).thenReturn(new Snapshot("literal",TENANT,Instant.EPOCH,"OWN_REPORT_REGISTRATIONS",List.of(),false,List.of(),false,false,List.of(),false));var groups=(List<?>)data(request()).get("groups");var supported=(Map<?,?>)groups.get(2);assertEquals(0,supported.get("returned"));assertEquals(List.of(),supported.get("items"));assertEquals("OWN_REPORT_REGISTRATIONS",supported.get("source_scope"));assertNull(((Map<?,?>)groups.get(1)).get("items"));}
    @Test void onlyExactQueryAcceptedNoHiddenBrowseAllOrScopeOverride(){for(String key:List.of("tenant_id","actor_id","limit","source","page_size")){var r=request();r.addParameter(key,"forged");assertEquals(400,assertThrows(DomainException.class,()->data(r)).status);}var duplicate=request();duplicate.addParameter("q","second");assertEquals(400,assertThrows(DomainException.class,()->data(duplicate)).status);var missing=request();missing.removeParameter("q");assertEquals(400,assertThrows(DomainException.class,()->data(missing)).status);verifyNoInteractions(search);}
    @Test void invalidTextAndOversizeQueryRejectBeforeRepository(){for(String q:List.of(""," \ufeff","x\n","😀".repeat(101))){var r=request();r.setParameter("q",q);assertEquals(400,assertThrows(DomainException.class,()->data(r)).status);}var r=request();r.setQueryString("q="+"%20".repeat(2000));assertEquals(400,assertThrows(DomainException.class,()->data(r)).status);verifyNoInteractions(search);}
    @Test void allKnownRolesAllowedButUnknownAndInactiveDenied(){when(search.search(any(),any())).thenReturn(result());for(String role:List.of("msp","padmin","viewer","tadmin")){role(role);assertEquals(TENANT,data(request()).get("tenant_id"));}role("unknown");assertEquals(403,assertThrows(DomainException.class,()->data(request())).status);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));assertEquals(401,assertThrows(DomainException.class,()->data(request())).status);}
    @Test void repositoryRoleSnapshotAndScopeMustMatchCurrentRequest(){var r=request();r.addHeader("X-Tenant-ID","forged");when(search.search(access(),new GlobalSearchQuery("literal"))).thenReturn(result());data(r);verify(search).search(access(),new GlobalSearchQuery("literal"));when(search.search(any(),any())).thenReturn(new Snapshot("different",TENANT,Instant.EPOCH,"CURRENT_TENANT_INTERNAL",List.of(),false,List.of(),false,true,List.of(),false));assertThrows(IllegalStateException.class,()->data(request()));}
    @Test void missingSessionAndRepositoryRevocationNeverBecomeEmptySuccess(){var r=request();r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->data(r)).status);when(search.search(any(),any())).thenThrow(new DomainException(401,"SESSION_EXPIRED","revoked"));assertEquals(401,assertThrows(DomainException.class,()->data(request())).status);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"inactive","removed","version","role","capabilities","tenant","session","actor"})
    void rechecksFullCurrentMembershipAfterSearchBeforeReturning(String change){
        var request=request();when(search.search(any(),any())).thenAnswer(call->{switch(change){
            case "inactive"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));
            case "removed"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());
            case "version"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",8,true)));
            case "role"->role("padmin");case "capabilities"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,List.of("search:read"))));
            case "tenant"->request.getSession().setAttribute("mop.tenant_id",ACTOR);case "session"->request.getSession().invalidate();case "actor"->request.setUserPrincipal(()->TENANT);
        }return result();});assertEquals(401,assertThrows(DomainException.class,()->data(request)).status);
    }
    @Test void mismatchedInitialMembershipAndIncompleteReadGrantsRejectBeforeSearch(){
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(TENANT,ACTOR,"msp",7,true)));
        assertEquals(401,assertThrows(DomainException.class,()->data(request())).status);
        for(var grants:List.of(List.of("search:read"),List.of("search:read","assets:read"),List.of("search:read","tickets:read"))){
            when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,grants)));
            assertEquals(403,assertThrows(DomainException.class,()->data(request())).status);
        }verifyNoInteractions(search);
    }
}
