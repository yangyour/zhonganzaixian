package cn.mop.api;
import cn.mop.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MonitoringPresetControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000002",TENANT="55000000-0000-4000-8000-000000000001";
    PlatformRepository repository;MonitoringPresetRegistry registry;MonitoringPresetRegistry.Target target;MonitoringPresetController controller;Membership member;Asset asset;AtomicInteger calls;
    @BeforeEach void setup(){repository=mock(PlatformRepository.class);var c=MonitoringPresetTest.config("linux-node");registry=MonitoringPresetTest.registry(c);target=MonitoringPresetTest.first(registry,c);member=new Membership(ACTOR,TENANT,"padmin",7,true);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(member));asset=new Asset(target.assetId(),TENANT,"Synthetic","other","192.0.2.10","site","unknown","DRAFT","test_seed",null,null,1,Instant.EPOCH,Instant.EPOCH,null,null);when(repository.authorizedAsset(TENANT,ACTOR,7,target.assetId())).thenReturn(Optional.of(asset));calls=new AtomicInteger();service(t->{calls.incrementAndGet();return MonitoringPresetProbeService.outcome(t,"FAILED","CONNECTION_FAILED");});}
    void service(MonitoringPresetProbeService.Runner runner){controller=new MonitoringPresetController(repository,new MonitoringPresetProbeService(registry,runner));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/api/v1/monitoring-onboarding/registered-targets/"+target.id()+"/test");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);r.setContentType("application/json");r.setContent(("{\"configuration_sha256\":\""+target.hash()+"\"}").getBytes(StandardCharsets.UTF_8));return r;}
    @Test void listingHasNoProbeIoAndOnlyCurrentTenantTargets(){Map<?,?> data=(Map<?,?>)((Map<?,?>)controller.list(request())).get("data");assertEquals(TENANT,data.get("tenant_id"));assertEquals(1,((List<?>)data.get("items")).size());assertEquals(0,calls.get());}
    @Test void testsExactConfiguredTargetAndRechecksAfterIo(){Map<?,?> data=(Map<?,?>)((Map<?,?>)controller.test(target.id(),request())).get("data");assertEquals("SERVER_ONE_SHOT_PROBE",data.get("source"));assertEquals(1,calls.get());verify(repository,times(2)).authorizedAsset(TENANT,ACTOR,7,target.assetId());verify(repository,times(2)).membership(ACTOR,TENANT);}
    @Test void foreignOrMissingTargetNeverCallsNetwork(){assertEquals(404,assertThrows(DomainException.class,()->controller.test(ACTOR,request())).status);assertEquals(0,calls.get());}
    @Test void mismatchedAddressCannotTestOldBinding(){var changed=new Asset(asset.id(),asset.tenantId(),asset.name(),asset.type(),"192.0.2.11",asset.siteId(),asset.status(),asset.lifecycleStatus(),asset.source(),null,null,2,Instant.EPOCH,Instant.EPOCH,null,null);when(repository.authorizedAsset(TENANT,ACTOR,7,target.assetId())).thenReturn(Optional.of(changed));assertEquals(404,assertThrows(DomainException.class,()->controller.test(target.id(),request())).status);assertEquals(0,calls.get());}
    @Test void staleConfigurationHashIsRejectedBeforeIo(){var r=request();r.setContent(("{\"configuration_sha256\":\""+"0".repeat(64)+"\"}").getBytes(StandardCharsets.UTF_8));assertEquals(409,assertThrows(DomainException.class,()->controller.test(target.id(),r)).status);assertEquals(0,calls.get());}
    @Test void overridesDuplicatesTrailingBytesAndQueriesAreRejected(){for(String body:List.of("{}","{\"endpoint\":\"http://127.0.0.1/\"}","{\"configuration_sha256\":\""+target.hash()+"\",\"configuration_sha256\":\""+target.hash()+"\"}","{} {}"," ".repeat(257))){var r=request();r.setContent(body.getBytes(StandardCharsets.UTF_8));assertEquals(400,assertThrows(DomainException.class,()->controller.test(target.id(),r)).status);}var r=request();r.setQueryString("tenant_id=other");assertEquals(400,assertThrows(DomainException.class,()->controller.test(target.id(),r)).status);assertEquals(0,calls.get());}
    @Test void readOnlyConfiguredMemberCannotInvokeProbe(){when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,true,List.of("assets:read","configurations:read"))));assertDoesNotThrow(()->controller.list(request()));assertEquals(403,assertThrows(DomainException.class,()->controller.test(target.id(),request())).status);assertEquals(0,calls.get());}
    @Test void revokedMembershipAfterIoCannotReleaseResult(){service(t->{when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());return MonitoringPresetProbeService.base(t);});assertEquals(401,assertThrows(DomainException.class,()->controller.test(target.id(),request())).status);}
    @Test void changedAssetAfterIoCannotReleaseResult(){service(t->{when(repository.authorizedAsset(TENANT,ACTOR,7,target.assetId())).thenReturn(Optional.empty());return MonitoringPresetProbeService.base(t);});assertEquals(404,assertThrows(DomainException.class,()->controller.test(target.id(),request())).status);}
    @Test void defaultDisabledServiceMakesNoNetworkCall(){controller=new MonitoringPresetController(repository,new MonitoringPresetProbeService(null,t->{throw new AssertionError();}));assertEquals(503,assertThrows(DomainException.class,()->controller.list(request())).status);}
    @Test void noSessionOrInactiveIdentityCannotRead(){var r=request();r.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.list(r)).status);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",7,false)));assertEquals(401,assertThrows(DomainException.class,()->controller.list(request())).status);}
    @Test void cacheAndReferrerHeadersRemainPrivate(){var r=new MockHttpServletResponse();controller.privateHeaders(r);assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("no-referrer",r.getHeader("Referrer-Policy"));}

    @Test void expandedViewPreservesAllServiceTargetsButLegacyViewFailsExplicitly(){
        var c=MonitoringPresetTest.config("linux-node");var second=MonitoringPresetTest.row(c).deepCopy();
        second.put("id","79000000-0000-4000-8000-000000000099").put("endpoint","http://192.0.2.10:19100/metrics");c.withArray("targets").add(second);
        registry=new MonitoringPresetRegistry(c.toString(),"[]","[]");service(t->{throw new AssertionError("read performed probe");});
        var error=assertThrows(DomainException.class,()->controller.list(request()));assertEquals(409,error.status);assertEquals("CONFIGURATION_VIEW_REQUIRED",error.code);
        var r=request();r.addParameter("view","expanded");Map<?,?> data=(Map<?,?>)((Map<?,?>)controller.list(r)).get("data");assertEquals(2,((List<?>)data.get("items")).size());
    }
    @Test void expandedViewDoesNotPermitExtraDuplicateOrUnsupportedQueryParameters(){
        for(String value:List.of("all","","EXPANDED")){var r=request();r.addParameter("view",value);assertEquals(400,assertThrows(DomainException.class,()->controller.list(r)).status);}
        var duplicate=request();duplicate.addParameter("view","expanded","expanded");assertEquals(400,assertThrows(DomainException.class,()->controller.list(duplicate)).status);
        var override=request();override.addParameter("view","expanded");override.addParameter("tenant_id","other");assertEquals(400,assertThrows(DomainException.class,()->controller.list(override)).status);
        var post=request();post.addParameter("view","expanded");assertEquals(400,assertThrows(DomainException.class,()->controller.test(target.id(),post)).status);assertEquals(0,calls.get());
    }
    @Test void expandedViewStillRequiresCurrentMembershipAndAssetScope(){
        var r=request();r.addParameter("view","expanded");when(repository.authorizedAsset(TENANT,ACTOR,7,target.assetId())).thenReturn(Optional.empty());
        assertEquals(404,assertThrows(DomainException.class,()->controller.list(r)).status);assertEquals(0,calls.get());
    }
}
