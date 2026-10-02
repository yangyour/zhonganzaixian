package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManualAssetQueryControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011",ID="55000000-0000-4000-8000-000000000021";
    PlatformRepository platform;ManualAssetMetadataRepository metadata;ManualAssetQueryController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);metadata=mock(ManualAssetMetadataRepository.class);controller=new ManualAssetQueryController(platform,metadata);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true)));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/assets/manual-metadata");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setAttribute("mop.trace_id","synthetic-trace");return r;}
    Access access(){return new Access(ACTOR,TENANT,7,"synthetic-trace");}
    Current current(){return new Current(new Snapshot(new Asset(ID,TENANT,"synthetic","router","192.0.2.1","site-a","unknown","DRAFT","manual",null,"NOT_ONBOARDED",1,Instant.EPOCH,Instant.EPOCH,"UNKNOWN","PENDING_ONBOARDING"),null,null),null,null,List.of());}
    Map<?,?> data(Object response){return (Map<?,?>)((Map<?,?>)response).get("data");}
    @Test void queriesTrustedTenantWithExplicitFiltersAndBoundedPairedCursor(){
        var r=request();r.addHeader("X-Tenant-ID","forged");r.addParameter("name","literal%_");r.addParameter("unknown_fields","brand,owner_id");r.addParameter("page_size","10");var filters=ManualAssetFilter.fromQuery(Map.of("name","literal%_","unknown_fields","brand,owner_id"));when(metadata.search(access(),filters,11,null,null)).thenReturn(Collections.nCopies(11,current()));var data=data(controller.search(r));assertEquals(10,((List<?>)data.get("items")).size());assertEquals(true,data.get("has_more"));assertEquals(ID,data.get("next_before_id"));assertEquals(Instant.EPOCH,data.get("next_before_created_at"));assertEquals("CURRENT_REQUEST_SNAPSHOT_ONLY",data.get("pagination_consistency"));verify(metadata).search(access(),filters,11,null,null);verify(platform,never()).idempotent(any(),any(),any(),any(),any(),anyLong(),any());
    }
    @Test void acceptsExplicitEmptyResultAndNormalizesOnlyTechnicalCursor(){var r=request();r.addParameter("before_created_at","1970-01-01T08:00:00+08:00");r.addParameter("before_id",ID);when(metadata.search(access(),ManualAssetFilter.fromQuery(Map.of()),16,Instant.EPOCH,ID)).thenReturn(List.of());var data=data(controller.search(r));assertEquals(List.of(),data.get("items"));assertEquals(false,data.get("has_more"));assertNull(data.get("next_before_id"));assertNull(data.get("next_before_created_at"));}
    @Test void deniesCustomerUnknownInactiveAndAnonymousContexts(){for(String role:List.of("tadmin","viewer","unknown")){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.search(request())).status);}when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.search(request())).status);var anonymous=request();anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.search(anonymous)).status);verifyNoInteractions(metadata);}
    @Test void rejectsDuplicateCrossTenantExtraFieldsAndUnpairedOrUnsafeBounds(){for(var params:List.of(Map.of("tenant_id",TENANT),Map.of("before_id",ID),Map.of("page_size","1000"),Map.of("warranty_status","expired"),Map.of("purchase_date","2026-02-30"),Map.of("unknown_fields","brand","brand","provided"))){var r=request();params.forEach(r::addParameter);assertEquals(400,assertThrows(DomainException.class,()->controller.search(r)).status);}var dup=request();dup.addParameter("name",new String[]{"a","b"});assertEquals(400,assertThrows(DomainException.class,()->controller.search(dup)).status);var oversized=request();oversized.setQueryString("a".repeat(4097));assertEquals(400,assertThrows(DomainException.class,()->controller.search(oversized)).status);verifyNoInteractions(metadata);}
}
