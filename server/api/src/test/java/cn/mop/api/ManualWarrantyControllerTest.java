package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualWarrantyControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011",ID="55000000-0000-4000-8000-000000000021";
    ObjectMapper mapper=new ObjectMapper();PlatformRepository platform;ManualAssetMetadataRepository metadata;ManualWarrantyController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);metadata=mock(ManualAssetMetadataRepository.class);controller=new ManualWarrantyController(platform,metadata,mapper);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true)));}
    MockHttpServletRequest request(String method){var r=new MockHttpServletRequest(method,"/api/v1/assets/"+ID+"/manual-metadata/warranty");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");r.addHeader("Idempotency-Key","synthetic-request");return r;}
    Access access(){return new Access(ACTOR,TENANT,7,"synthetic-trace");}
    ObjectNode body(){return mapper.createObjectNode().put("expected_version",1).put("warranty_start","2026-01-01").put("warranty_expiry","2027-01-01").put("warranty_supplier","明确供应商").putNull("overdue_service_date").put("note","明确维护原因");}
    ManualAssetMetadata metadataFacts(){return new ManualAssetMetadata("明确草稿名称","router","site-a",null,null,null,null,null,null,null,LocalDate.of(2027,1,1),null,null,"明确维护原因");}
    ManualWarrantyUpdate facts(){return new ManualWarrantyUpdate(new ManualWarrantyDetails(LocalDate.of(2026,1,1),"明确供应商"),LocalDate.of(2027,1,1),null,"明确维护原因");}
    Asset asset(long version){return new Asset(ID,TENANT,"明确草稿名称","router","192.0.2.1","site-a","unknown","DRAFT","manual",null,"NOT_ONBOARDED",version,Instant.EPOCH,Instant.EPOCH,"UNKNOWN","PENDING_ONBOARDING");}
    Current current(){return new Current(new Snapshot(asset(2),metadataFacts(),2L,facts().details()),ACTOR,Instant.EPOCH,List.of());}
    Map<?,?> data(Object response){return (Map<?,?>)((Map<?,?>)response).get("data");}
    @Test void writeUsesTrustedScopeVersionAndIdempotentTransactionWithoutIpMutation(){
        when(platform.idempotentAuthorized(eq(ACTOR),eq(TENANT),eq("PATCH /api/v1/assets/"+ID+"/manual-metadata/warranty"),eq("synthetic-request"),anyString(),eq(7L),any(),any())).thenAnswer(c->{((Runnable)c.getArgument(6)).run();return ((Supplier<?>)c.getArgument(7)).get();});when(metadata.updateWarranty(access(),ID,1,facts())).thenReturn(current());
        var request=request("PATCH");request.addHeader("X-Tenant-ID","forged");Map<?,?> value=data(controller.write(ID,body(),request));assertEquals(2L,value.get("metadata_version"));assertEquals("SELF_REPORTED",value.get("verification_status"));assertEquals(false,value.get("monitoring_applied"));assertEquals(false,value.get("cmdb_applied"));assertEquals("CONFIG_PENDING",value.get("management_ip_change_status"));assertEquals("192.0.2.1",((Asset)value.get("asset")).managementIp());verify(metadata).updateWarranty(access(),ID,1,facts());
    }
    @Test void internalRolesOnlyWithActiveMembership(){
        when(metadata.current(access(),ID)).thenReturn(current());for(String role:List.of("msp","padmin")){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(true,data(controller.get(ID,request("GET"))).get("can_edit"));}
        for(String role:List.of("viewer","tadmin","unknown")){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.get(ID,request("GET"))).status);assertEquals(403,assertThrows(DomainException.class,()->controller.write(ID,body(),request("PATCH"))).status);}
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.get(ID,request("GET"))).status);var anonymous=request("GET");anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.get(ID,anonymous)).status);
    }
    @Test void noMetadataIsNullAndIneligibleAssetsHaveExplicitBlockers(){
        when(metadata.current(access(),ID)).thenReturn(new Current(new Snapshot(asset(1),null,null),null,null,List.of("MANUAL_DRAFT_REQUIRED")));Map<?,?> value=data(controller.get(ID,request("GET")));for(String key:List.of("metadata","metadata_version","recorded_by","recorded_at","verification_status"))assertNull(value.get(key));assertEquals(false,value.get("can_edit"));assertEquals(List.of("MANUAL_DRAFT_REQUIRED"),value.get("edit_blockers"));verify(platform,never()).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void historyPaginationIsReadOnlyBoundedAndIncludesActualBefore(){
        Revision revision=new Revision(2,null,ACTOR,Instant.EPOCH,new Snapshot(asset(1),null,null),metadataFacts(),"192.0.2.1",facts().details());when(metadata.history(access(),ID,11,null)).thenReturn(Collections.nCopies(11,revision));var request=request("GET");request.addParameter("page_size","10");Map<?,?> value=data(controller.history(ID,request));assertEquals(10,((List<?>)value.get("items")).size());assertEquals(true,value.get("has_more"));assertEquals(2L,value.get("next_before_version"));
        when(metadata.history(access(),ID,16,2L)).thenReturn(List.of());var next=request("GET");next.addParameter("before_version","2");assertNull(data(controller.history(ID,next)).get("next_before_version"));verify(platform,never()).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test void rejectsUnknownMutationFieldsMissingExplicitNullsAndUnsafeVersions(){
        for(String key:List.of("management_ip","tenant_id","source","status","actor_id","sampled_at","lifecycle_status","monitoring_applied"))assertEquals(400,assertThrows(DomainException.class,()->controller.write(ID,body().put(key,"forged"),request("PATCH"))).status);
        List<JsonNode> invalid=new ArrayList<>();invalid.add(body().put("expected_version",0));invalid.add(body().put("expected_version",1.5));invalid.add(body().put("expected_version",Long.MAX_VALUE));invalid.add(body().put("warranty_supplier"," "));invalid.add(body().put("warranty_start","2026-02-30"));invalid.add(body().put("warranty_start",123));invalid.add(body().put("note",false));invalid.add(body().putNull("note"));var missing=body();missing.remove("warranty_supplier");invalid.add(missing);for(var b:invalid)assertEquals(400,assertThrows(DomainException.class,()->controller.write(ID,b,request("PATCH"))).status);verifyNoInteractions(metadata);
    }
    @Test void rejectsQueryInjectionDuplicatesAndUnboundedHistory(){
        for(String key:List.of("tenant_id","owner_id","q")){var r=request("GET");r.addParameter(key,ID);assertEquals(400,assertThrows(DomainException.class,()->controller.get(ID,r)).status);}
        for(String version:List.of("0","1","-1","1.5","9007199254740991")){var r=request("GET");r.addParameter("before_version",version);assertEquals(400,assertThrows(DomainException.class,()->controller.history(ID,r)).status);}
        var dup=request("GET");dup.addParameter("page_size",new String[]{"10","15"});assertEquals(400,assertThrows(DomainException.class,()->controller.history(ID,dup)).status);verifyNoInteractions(metadata);
    }
    @Test void boundedParserRejectsDuplicateTrailingNestedAndOversizedJson()throws Exception{
        for(String value:List.of("{\"name\":\"a\",\"name\":\"b\"}","{} {}","[[[1]]]"," ".repeat(ManualWarrantyController.MAX_BODY_BYTES+1),"["+"0,".repeat(50)+"0]")){var request=request("PATCH");request.setContent(value.getBytes(StandardCharsets.UTF_8));assertEquals(400,assertThrows(DomainException.class,()->ManualWarrantyController.body(request)).status);}
        var value=body().put("note","😀".repeat(2000));var request=request("PATCH");request.setContent(mapper.writeValueAsBytes(value));assertEquals(value,ManualWarrantyController.body(request));
    }
    @Test void semanticOrderHasSameHashAndChangesOrMissingIdempotencyAreRejected(){
        List<String> hashes=new ArrayList<>();when(platform.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(c->{hashes.add(c.getArgument(4));return Map.of("replayed",true);});var first=body();var reverse=mapper.createObjectNode();List<String> names=new ArrayList<>();first.fieldNames().forEachRemaining(names::add);Collections.reverse(names);names.forEach(k->reverse.set(k,first.get(k)));controller.write(ID,first,request("PATCH"));controller.write(ID,reverse,request("PATCH"));controller.write(ID,reverse.put("note","明确更改"),request("PATCH"));assertEquals(hashes.get(0),hashes.get(1));assertNotEquals(hashes.get(0),hashes.get(2));var missing=request("PATCH");missing.removeHeader("Idempotency-Key");assertEquals(400,assertThrows(DomainException.class,()->controller.write(ID,body(),missing)).status);verifyNoInteractions(metadata);
    }
    @Test void supplementalsRemainUnverifiedAndBeforeSnapshotContainsActualWrapper(){
        when(metadata.current(access(),ID)).thenReturn(current());var data=data(controller.get(ID,request("GET")));assertEquals(false,data.get("warranty_coverage_verified"));assertEquals("UNVERIFIED",data.get("warranty_supplier_verification"));assertEquals("明确供应商",((Map<?,?>)data.get("warranty_details")).get("warranty_supplier"));
        var absent=new Current(new Snapshot(asset(1),null,null),null,null,List.of());assertNull(ManualWarrantyController.dto(absent).get("warranty_details"));var clear=new Current(new Snapshot(asset(2),metadataFacts(),2L,new ManualWarrantyDetails(null,null)),ACTOR,Instant.EPOCH,List.of());assertNotNull(ManualWarrantyController.dto(clear).get("warranty_details"));assertNull(((Map<?,?>)ManualWarrantyController.dto(clear).get("warranty_details")).get("warranty_start"));
        var revision=new Revision(3,2L,ACTOR,Instant.EPOCH,current().snapshot(),metadataFacts(),"192.0.2.1",new ManualWarrantyDetails(null,null));var projected=ManualWarrantyController.revisionDto(revision);assertEquals("明确供应商",((Map<?,?>)((Map<?,?>)projected.get("before")).get("warranty_details")).get("warranty_supplier"));assertNull(((Map<?,?>)projected.get("warranty_details")).get("warranty_supplier"));
    }
    @Test void authorizationFailurePreventsCommandAndLegacyDtoCannotAccidentallyExposeSupplementals(){
        when(platform.idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any())).thenAnswer(c->{((Runnable)c.getArgument(6)).run();return ((Supplier<?>)c.getArgument(7)).get();});
        doThrow(new DomainException(404,"OBJECT_NOT_FOUND","hidden")).when(metadata).authorizeCurrentAsset(access(),ID);assertEquals(404,assertThrows(DomainException.class,()->controller.write(ID,body(),request("PATCH"))).status);verify(metadata,never()).updateWarranty(any(),any(),anyLong(),any());assertFalse(ManualAssetMetadataController.dto(current()).containsKey("warranty_details"));
    }
}
