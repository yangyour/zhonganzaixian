package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetdependency.AssetDependencyRepository;
import cn.mop.domain.assetdependency.AssetDependencyRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated validation/DTO checks; separate real HTTP/PG evidence is required. */
class AssetDependencyControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011",ASSET="55000000-0000-4000-8000-000000000021",OTHER="55000000-0000-4000-8000-000000000022";
    ObjectMapper mapper=new ObjectMapper();PlatformRepository platform;AssetDependencyRepository dependencies;AssetDependencyController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);dependencies=mock(AssetDependencyRepository.class);controller=new AssetDependencyController(platform,dependencies,mapper);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true)));when(platform.find(TENANT,ASSET)).thenReturn(Optional.of(mock(Asset.class)));when(platform.authorizedAsset(TENANT,ACTOR,7,ASSET)).thenReturn(Optional.of(readAsset()));}
    MockHttpServletRequest request(String method){var r=new MockHttpServletRequest(method,"/api/v1/assets/"+ASSET+"/dependencies");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");r.addHeader("Idempotency-Key","synthetic-request");return r;}
    ObjectNode body(){var body=mapper.createObjectNode().put("expected_version",0);body.putArray("additions").addObject().put("parent_id",ASSET).put("child_id",OTHER).putNull("proposed_type").putNull("proposed_suppression_scope").putNull("proposed_enabled");return body;}
    @Test void readReturnsCompletePendingDraftNotEffectiveRules(){
        when(dependencies.current(TENANT,ACTOR)).thenReturn(new Draft(TENANT,0,null,List.of()));var request=request("GET");request.addHeader("X-Tenant-ID","forged");
        Map<?,?> data=(Map<?,?>)((Map<?,?>)controller.current(ASSET,request)).get("data");assertEquals(TENANT,data.get("tenant_id"));assertEquals(true,data.get("complete"));assertEquals("DRAFT",data.get("lifecycle_status"));assertEquals("CONFIG_PENDING",data.get("configuration_status"));assertNull(data.get("effective_rules"));assertEquals(false,data.get("monitoring_applied"));verify(dependencies).current(TENANT,ACTOR);verifyNoMoreInteractions(dependencies);
    }
    @Test void writesUseTrustedScopePermissionVersionAndProtectedCallback(){
        when(platform.idempotent(eq(ACTOR),eq(TENANT),anyString(),eq("synthetic-request"),anyString(),eq(7L),any())).thenAnswer(call->((Supplier<?>)call.getArgument(6)).get());
        var edge=new ProposedEdge(ASSET,OTHER,null,null,null);when(dependencies.append(TENANT,ACTOR,0,List.of(edge),"synthetic-trace")).thenReturn(new Draft(TENANT,1,null,List.of(edge)));
        controller.append(ASSET,body(),request("POST"));verify(dependencies).append(TENANT,ACTOR,0,List.of(edge),"synthetic-trace");
    }
    @Test void viewerCannotWriteAndInactiveOrMissingMembershipCannotRead(){
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.append(ASSET,body(),request("POST"))).status);
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
        var anonymous=request("GET");anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,anonymous)).status);verifyNoInteractions(dependencies);
    }
    @Test void unknownPathAssetAndQuerySpoofingNeverReadGraph(){
        assertEquals(404,assertThrows(DomainException.class,()->controller.current(OTHER,request("GET"))).status);
        var query=request("GET");query.addParameter("tenant_id",TENANT);assertEquals(400,assertThrows(DomainException.class,()->controller.current(ASSET,query)).status);verifyNoInteractions(dependencies);
    }
    @Test void versionIdentityExtraFieldsUnanchoredEdgesAndMissingNullChoicesAreRejected(){
        List<JsonNode> invalid=new ArrayList<>();invalid.add(body().put("expected_version",-1));invalid.add(body().put("expected_version",1.5));invalid.add(body().put("expected_version",Long.MAX_VALUE));invalid.add(body().put("tenant_id",TENANT));
        var missing=body();((ObjectNode)missing.get("additions").get(0)).remove("proposed_enabled");invalid.add(missing);
        var unanchored=body();((ObjectNode)unanchored.get("additions").get(0)).put("parent_id",OTHER);invalid.add(unanchored);
        var bool=body();((ObjectNode)bool.get("additions").get(0)).put("proposed_enabled","true");invalid.add(bool);
        for(JsonNode b:invalid)assertEquals(400,assertThrows(DomainException.class,()->controller.append(ASSET,b,request("POST"))).status);verifyNoInteractions(dependencies);
    }
    @Test void boundedStrictParserRejectsDuplicateTrailingDeepAndOversizedInput()throws Exception{
        for(String json:List.of("{\"expected_version\":0,\"expected_version\":1}","{} {}","[[[[[1]]]]]"," ".repeat(AssetDependencyController.MAX_BODY_BYTES+1),"["+"0,".repeat(2050)+"0]")){
            var r=request("POST");r.setContent(json.getBytes(StandardCharsets.UTF_8));assertEquals(400,assertThrows(DomainException.class,()->AssetDependencyController.body(r)).status);
        }
        var value=body();((ObjectNode)value.get("additions").get(0)).put("proposed_suppression_scope","😀".repeat(128));var r=request("POST");r.setContent(mapper.writeValueAsBytes(value));assertEquals(value,AssetDependencyController.body(r));
    }
    @Test void nullableMetadataDoesNotAcceptBlankControlOrOverlongValues(){
        for(String value:List.of("","\u00a0\ufeff","x\u0000","x".repeat(65))){var b=body();((ObjectNode)b.get("additions").get(0)).put("proposed_type",value);assertEquals(400,assertThrows(DomainException.class,()->controller.append(ASSET,b,request("POST"))).status);}verifyNoInteractions(dependencies);
    }
    Asset readAsset(){return new Asset(ASSET,TENANT,"Synthetic dependency anchor","switch","192.0.2.1","synthetic-site","unknown","DRAFT","manual",null,null,1,null,null,null,null);}
    @Test void revokedMembershipDuringReadPreventsResponse(){
        when(dependencies.current(TENANT,ACTOR)).thenAnswer(call->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());return new Draft(TENANT,0,null,List.of());});
        assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
    }
    @Test void changedPermissionVersionDuringReadPreventsResponse(){
        when(dependencies.current(TENANT,ACTOR)).thenAnswer(call->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",8,true)));return new Draft(TENANT,0,null,List.of());});
        assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
    }
    @Test void removedCapabilityAtSameVersionStillPreventsResponse(){
        when(dependencies.current(TENANT,ACTOR)).thenAnswer(call->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,List.of())));return new Draft(TENANT,0,null,List.of());});
        assertEquals(403,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
    }
    @Test void disappearingAnchorFailsClosedAfterGraphRead(){
        when(dependencies.current(TENANT,ACTOR)).thenReturn(new Draft(TENANT,0,null,List.of()));
        when(platform.authorizedAsset(TENANT,ACTOR,7,ASSET)).thenReturn(Optional.of(readAsset()),Optional.empty());
        assertEquals(404,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
    }
    @Test void sessionTenantChangeDuringReadDoesNotReturnOriginalGraph(){
        var request=request("GET");when(dependencies.current(TENANT,ACTOR)).thenAnswer(call->{request.getSession().setAttribute("mop.tenant_id",OTHER);return new Draft(TENANT,0,null,List.of());});
        when(platform.membership(ACTOR,OTHER)).thenReturn(Optional.of(new Membership(ACTOR,OTHER,"msp",7,true)));
        assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,request)).status);
    }
    @Test void graphSourceTenantCannotOverrideAuthorizedScope(){
        when(dependencies.current(TENANT,ACTOR)).thenReturn(new Draft(OTHER,0,null,List.of()));
        assertEquals(502,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
    }
    @Test void readUsesAuthorizationSnapshotsAndNeverUnprotectedFind(){
        when(dependencies.current(TENANT,ACTOR)).thenReturn(new Draft(TENANT,0,null,List.of()));controller.current(ASSET,request("GET"));
        verify(platform,times(2)).authorizedAsset(TENANT,ACTOR,7,ASSET);verify(platform,never()).find(anyString(),anyString());
    }
    @Test void membershipIdentityMismatchAndZeroVersionFailBeforeReading(){
        for(var member:List.of(new Membership(OTHER,TENANT,"msp",7,true),new Membership(ACTOR,OTHER,"msp",7,true),new Membership(ACTOR,TENANT,"msp",0,true))){
            when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member));assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
        }verifyNoInteractions(dependencies);
    }
    @Test void roleChangeAtSameVersionStillInvalidatesTheOriginalRead(){
        when(dependencies.current(TENANT,ACTOR)).thenAnswer(call->{when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",7,true)));return new Draft(TENANT,0,null,List.of());});
        assertEquals(401,assertThrows(DomainException.class,()->controller.current(ASSET,request("GET"))).status);
    }
}