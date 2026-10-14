package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.adapters.monitoring.ZabbixReadClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirewallResourceTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",ACTOR="55000000-0000-4000-8000-000000000002",ASSET="55000000-0000-4000-8000-000000000003";
    static final Instant NOW=Instant.parse("2026-10-14T08:00:00Z");
    FirewallResourceService service;FirewallResourceService.Binding binding;NetworkMonitoringService basic;NetworkMonitoringController ctl;PlatformRepository repo;
    List<Map<String,Object>> items,history;int reads;
    Map<String,Object> config(){return new LinkedHashMap<>(Map.of("tenant_id",TENANT,"asset_id",ASSET,"ip","192.169.11.1","host_id","50","items",Map.of("cpu","51","memory","52","state","53"),"allowed_hosts","127.0.0.1","tls_mode","TEST_INSECURE"));}
    String json(Object value){return ZabbixReadClient.JSON.valueToTree(value).toString();}
    static void status(int code,org.junit.jupiter.api.function.Executable f){assertEquals(code,assertThrows(DomainException.class,f).status);}
    @BeforeEach void setup(){
        binding=FirewallResourceService.parse(json(config()));items=new ArrayList<>();
        for(String m:List.of("cpu","memory","state"))items.add(new LinkedHashMap<>(Map.ofEntries(Map.entry("itemid",binding.items().get(m)),Map.entry("hostid","50"),Map.entry("key_","mop.af."+m+"[192.169.11.1]"),Map.entry("type","2"),Map.entry("value_type",m.equals("state")?"1":"0"),Map.entry("history","7d"),Map.entry("trends","0"),Map.entry("units",m.equals("state")?"":"%"),Map.entry("trapper_hosts","127.0.0.1"),Map.entry("status","0"),Map.entry("state","0"),Map.entry("lastclock",""+(NOW.getEpochSecond()-10)),Map.entry("lastvalue",m.equals("state")?"OK":m.equals("cpu")?"12.50":"58"))));
        history=new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("itemid","51","clock",""+(NOW.getEpochSecond()-30),"ns","123","value","12.50"))));reads=0;
        service=new FirewallResourceService(binding,(method,p)->{reads++;return ZabbixReadClient.JSON.valueToTree(switch(method){case "host.get"->List.of(Map.of("hostid","50","host","mop-lan-af-api-192169111","status","0"));case "item.get"->items;case "history.get"->history;default->throw new AssertionError();});},Clock.fixed(NOW,ZoneOffset.UTC));
        var b=new NetworkMonitoringService.Binding(TENANT,ASSET,"192.169.11.1","深信服","AF-1000-B1800","12",Map.of());basic=new NetworkMonitoringService(List.of(b),null,Clock.fixed(NOW,ZoneOffset.UTC),true);
        repo=mock(PlatformRepository.class);when(repo.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",3,true)));
        when(repo.authorizedAsset(TENANT,ACTOR,3,ASSET)).thenReturn(Optional.of(new Asset(ASSET,TENANT,"测试","firewall",b.ip(),"test","unknown","draft","manual",null,"pending",1,NOW,NOW,"UNKNOWN","PENDING")));
        ctl=new NetworkMonitoringController(repo,basic);ctl.resources=service;
    }
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/network-monitoring/"+ASSET+"/resources");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
    Object value(Map<String,Object> snapshot,String m){return ((Map<?,?>)((Map<?,?>)snapshot.get("metrics")).get(m)).get("value");}
    @Test void freshValuesKeepUnitsAndReceiptTimeWithoutPretendingDeviceTime(){var v=service.snapshot();assertEquals("FRESH",v.get("state"));assertEquals("12.50",value(v,"cpu"));assertNull(v.get("source_sampled_at"));assertEquals(false,v.get("tls_trust_verified"));assertEquals(true,service.readiness().get("ready"));}
    @Test void sourceFailureMasksRecentOldMetrics(){items.get(2).put("lastvalue","PASSWORD_EXPIRED");var v=service.snapshot();assertEquals("COLLECTOR_ERROR",v.get("state"));assertEquals("PASSWORD_EXPIRED",v.get("reason"));assertNull(value(v,"cpu"));assertNull(value(v,"memory"));assertEquals(false,service.readiness().get("ready"));}
    @Test void staleNoDataAndFutureTimestampsAreUnknown(){for(var pair:List.of(new String[]{"0","NO_DATA"},new String[]{""+(NOW.getEpochSecond()-181),"STALE"},new String[]{""+(NOW.getEpochSecond()+1),"CLOCK_SKEW"})){items.get(2).put("lastclock",pair[0]);var v=service.snapshot();assertEquals(pair[1],v.get("state"));assertNull(value(v,"cpu"));}}
    @Test void partialBatchCannotShowFreshMetrics(){items.get(0).put("lastclock",""+(NOW.getEpochSecond()-70));var v=service.snapshot();assertEquals("SAMPLE_INCOMPLETE",v.get("reason"));assertNull(value(v,"memory"));}
    @Test void disabledAndUnsupportedItemsDoNotBecomeFresh(){items.get(0).put("status","1");assertEquals("COLLECTOR_ERROR",service.snapshot().get("state"));status(503,()->service.history("cpu","HOUR_1"));}
    @Test void providerMappingAndUnitsDriftFailClosed(){for(String field:List.of("hostid","key_","type","value_type","units","history","trapper_hosts")){setup();items.get(0).put(field,"changed");status(503,service::snapshot);}}
    @Test void duplicatesOrUnknownStatusFailClosed(){items.set(1,items.get(0));status(503,service::snapshot);setup();items.get(2).put("lastvalue","UNKNOWN_SECRET");status(503,service::snapshot);}
    @Test void invalidPercentCannotEscapeAsAValue(){for(String v:List.of("-1","101","NaN","1e2")){items.get(0).put("lastvalue",v);status(503,service::snapshot);}}
    @Test void explicitMappingRejectsOtherTargetsInlineCredentialsAndDuplicateIds(){var c=config();c.put("ip","192.169.11.2");status(503,()->FirewallResourceService.parse(json(c)));c.put("ip","192.169.11.1");c.put("password","PRIVATE");status(503,()->FirewallResourceService.parse(json(c)));c.remove("password");c.put("items",Map.of("cpu","51","memory","51","state","53"));status(503,()->FirewallResourceService.parse(json(c)));}
    @Test void historyPreservesPrecisionAndIsNotACompleteWindow(){var v=service.history("cpu","HOURS_24");assertEquals(false,v.get("complete_window_claimed"));assertEquals("COLLECTOR_RECEIPT_TIME",v.get("timestamp_meaning"));assertEquals("12.50",((Map<?,?>)((List<?>)v.get("points")).get(0)).get("value"));history.clear();assertEquals(List.of(),service.history("cpu","HOUR_1").get("points"));}
    @Test void historyDuplicatesWrongItemAndLargeWindowsFailClosed(){history.add(history.get(0));status(503,()->service.history("cpu","HOUR_1"));setup();history.get(0).put("itemid","52");status(503,()->service.history("cpu","HOUR_1"));setup();history=new ArrayList<>(Collections.nCopies(2001,history.get(0)));status(422,()->service.history("cpu","HOUR_1"));}
    @Test void resourceEndpointRechecksMembershipAndAsset(){ctl.resources(ASSET,request());verify(repo,times(2)).membership(ACTOR,TENANT);verify(repo,times(2)).authorizedAsset(TENANT,ACTOR,3,ASSET);}
    @Test void wrongTenantAndChangedIpNeverReadProvider(){var r=request();r.getSession().setAttribute("mop.tenant_id","55000000-0000-4000-8000-000000000009");when(repo.membership(ACTOR,"55000000-0000-4000-8000-000000000009")).thenReturn(Optional.of(new Membership(ACTOR,"55000000-0000-4000-8000-000000000009","padmin",3,true)));status(404,()->ctl.resources(ASSET,r));assertEquals(0,reads);when(repo.authorizedAsset(TENANT,ACTOR,3,ASSET)).thenReturn(Optional.empty());status(404,()->ctl.resources(ASSET,request()));assertEquals(0,reads);}
    @Test void revokedPermissionOrMembershipChangeBlocksData(){when(repo.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",3,true,List.of())));status(403,()->ctl.resources(ASSET,request()));assertEquals(0,reads);when(repo.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",3,true)),Optional.of(new Membership(ACTOR,TENANT,"padmin",4,true)));status(401,()->ctl.resources(ASSET,request()));}
    @Test void requestCannotOverrideSourceOrSelectStateHistory(){var r=request();r.addParameter("itemid","51");status(400,()->ctl.resources(ASSET,r));var h=request();h.addParameter("metric","state");status(400,()->ctl.resourceHistory(ASSET,h));assertEquals(0,reads);}
    @Test void enabledDependencyFailureMakesReady503(){var p=mock(PlatformController.class,org.mockito.Answers.CALLS_REAL_METHODS);items.get(2).put("lastvalue","ACCESS_DENIED");ReflectionTestUtils.setField(p,"firewallResources",service);status(503,()->p.ready(request()));}
}
