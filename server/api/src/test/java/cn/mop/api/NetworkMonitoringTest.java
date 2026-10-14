package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.adapters.monitoring.ZabbixReadClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated protocol/authorization cases; real Zabbix/PG tests have separate HTTP evidence. */
class NetworkMonitoringTest {
    static final String TENANT="55000000-0000-4000-8000-000000000001",ACTOR="55000000-0000-4000-8000-000000000002",ASSET="55000000-0000-4000-8000-000000000003";
    static final Instant NOW=Instant.parse("2026-10-12T08:00:00Z");
    NetworkMonitoringService.Binding binding;NetworkMonitoringService service;
    List<Map<String,Object>> items;List<Map<String,Object>> history;boolean hostEnabled;boolean wrongHost;
    @BeforeEach void setup(){
        Map<String,String> ids=new LinkedHashMap<>();int n=100;for(String m:NetworkMonitoringService.METRICS)ids.put(m,""+(++n));
        binding=new NetworkMonitoringService.Binding(TENANT,ASSET,"192.168.1.2","测试品牌","测试型号","12",ids);
        items=new ArrayList<>();
        for(String metric:NetworkMonitoringService.METRICS)items.add(new LinkedHashMap<>(Map.ofEntries(
            Map.entry("itemid",ids.get(metric)),Map.entry("hostid","12"),Map.entry("key_",NetworkMonitoringService.key(binding,metric)),Map.entry("type","3"),
            Map.entry("value_type",NetworkMonitoringService.floating(metric)?"0":"3"),Map.entry("delay","60s"),Map.entry("history","7d"),
            Map.entry("units",NetworkMonitoringService.unit(metric)),Map.entry("status","0"),Map.entry("state","0"),Map.entry("lastclock",""+(NOW.getEpochSecond()-30)),
            Map.entry("lastvalue",metric.equals("loss")?"0":metric.equals("latency")?"0.002":"1"))));
        history=new ArrayList<>(List.of(point(ASSET,60,"0.002"),point(ASSET,0,"0.003")));hostEnabled=true;wrongHost=false;
        service=new NetworkMonitoringService(List.of(binding),(method,params)->switch(method){
            case "host.get"->json(List.of(Map.of("hostid",wrongHost?"99":"12","status",hostEnabled?"0":"1")));
            case "item.get"->json(items);case "history.get"->json(history);default->throw new AssertionError();
        },Clock.fixed(NOW,ZoneOffset.UTC),true);
    }
    static JsonNode json(Object value){return ZabbixReadClient.JSON.valueToTree(value);}
    Map<String,Object> point(String unused,int ago,String value){return new LinkedHashMap<>(Map.of("itemid","103","clock",""+(NOW.getEpochSecond()-ago),"ns","123","value",value));}
    @SuppressWarnings("unchecked") Map<String,Object> sample(Map<String,Object> summary,String name){return (Map<String,Object>)((Map<?,?>)summary.get("metrics")).get(name);}
    static void status(int expected,org.junit.jupiter.api.function.Executable f){assertEquals(expected,assertThrows(DomainException.class,f).status);}
    @Test void realShapedCurrentValuesDoNotClaimVendorHealth(){var data=service.summary(binding,"测试");assertEquals("REACHABLE",data.get("network_state"));assertEquals("0.002",sample(data,"latency").get("value"));assertEquals(false,data.get("device_identity_verified"));assertEquals(true,service.readiness().get("ready"));}
    @Test void realProviderAveragePrecisionIsPreservedWithoutRounding(){String value="0.00011333333333333333";items.get(2).put("lastvalue",value);assertEquals(value,sample(service.summary(binding,"测试"),"latency").get("value"));history.get(0).put("value",value);assertEquals(value,((Map<?,?>)((List<?>)service.history(binding,"latency","HOUR_1").get("points")).get(0)).get("value"));}
    @Test void noResponseIsRecordedNetworkFactAndCollectorRemainsReady(){items.get(0).put("lastvalue","0");items.get(1).put("lastvalue","100");items.get(2).put("lastvalue","0");assertEquals("NO_RESPONSE",service.summary(binding,"测试").get("network_state"));assertEquals(true,service.readiness().get("ready"));}
    @Test void staleValuesAreNullAndReadinessFails(){items.get(0).put("lastclock",""+(NOW.getEpochSecond()-181));var result=service.summary(binding,"测试");assertEquals("UNKNOWN",result.get("network_state"));assertNull(sample(result,"icmp").get("value"));assertEquals("STALE",sample(result,"icmp").get("state"));assertEquals(false,service.readiness().get("ready"));}
    @Test void futureClockNoDataAndUnsupportedAreDistinct(){for(var pair:List.of(new String[]{"lastclock","0","NO_DATA"},new String[]{"lastclock",""+(NOW.getEpochSecond()+1),"CLOCK_SKEW"},new String[]{"state","1","COLLECTOR_ERROR"},new String[]{"status","1","DISABLED"})){setup();items.get(0).put(pair[0],pair[1]);var item=sample(service.summary(binding,"测试"),"icmp");assertEquals(pair[2],item.get("state"));assertNull(item.get("value"));}}
    @Test void providerHostMismatchAndDisabledHostFailClosed(){wrongHost=true;status(503,()->service.summary(binding,"测试"));wrongHost=false;hostEnabled=false;status(503,()->service.summary(binding,"测试"));}
    @Test void mappingTypeUnitPeriodAndDuplicateItemDriftFailClosed(){for(String field:List.of("hostid","key_","value_type","delay","history","units")){setup();items.get(0).put(field,"changed");status(503,()->service.summary(binding,"测试"));}setup();items.set(1,items.get(0));status(503,()->service.summary(binding,"测试"));}
    @Test void historicalSamplesPreserveValuesAndDoNotClaimCompleteWindow(){var data=service.history(binding,"latency","HOUR_1");assertEquals(false,data.get("complete_window_claimed"));assertEquals(2,((List<?>)data.get("points")).size());assertEquals("s",data.get("unit"));}
    @Test void emptyHistoryIsExplicitAndDoesNotInventZero(){history.clear();assertEquals(List.of(),service.history(binding,"latency","HOURS_24").get("points"));}
    @Test void wrongItemDuplicateTimeAndOutOfWindowHistoryRejected(){history.get(0).put("itemid","999");status(503,()->service.history(binding,"latency","HOUR_1"));setup();history.set(1,history.get(0));status(503,()->service.history(binding,"latency","HOUR_1"));setup();history.get(0).put("clock","1");status(503,()->service.history(binding,"latency","HOUR_1"));}
    @Test void tooManyPointsAreNotTruncated(){history=new ArrayList<>(Collections.nCopies(2001,history.get(0)));status(422,()->service.history(binding,"latency","HOURS_24"));}
    @Test void metricAndWindowAreAllowlists(){status(400,()->service.history(binding,"cpu","HOUR_1"));status(400,()->service.history(binding,"icmp","YEAR"));}
    @Test void impossibleNumericValuesAreRejected(){for(String value:List.of("NaN","-1","1e3","0.5","2")){items.get(0).put("lastvalue",value);status(503,()->service.summary(binding,"测试"));}}
    @Test void configurationDoesNotAcceptPrivateFieldsOrDuplicateBinding(){var value=Map.of("tenant_id",TENANT,"asset_id",ASSET,"ip",binding.ip(),"brand","测试","model","测试","host_id","12","items",binding.items());String raw=json(List.of(value)).toString();assertEquals(1,NetworkMonitoringService.parseBindings(raw).size());status(503,()->NetworkMonitoringService.parseBindings(json(List.of(value,value)).toString()));status(503,()->NetworkMonitoringService.parseBindings(raw.replace("192.168.1.2","8.8.8.8")));status(503,()->NetworkMonitoringService.parseBindings(raw.replace("\"ip\":","\"password\":\"secret\",\"ip\":")));}
    @Test void configurationDisabledIsNotReadyAndEnabledMissingIsRejected(){var config=new NetworkMonitoringConfiguration();assertEquals(false,config.networkMonitoringService(new MockEnvironment()).readiness().get("ready"));assertThrows(IllegalStateException.class,()->config.networkMonitoringService(new MockEnvironment().withProperty("MOP_NETWORK_MONITORING_ENABLED","true")));}
    @Test void correctedFirewallHasOneExplicitSourceAddressException(){assertFalse(NetworkMonitoringService.privateIp("192.169.11.1"));assertTrue(NetworkMonitoringService.approvedTarget("192.169.11.1","深信服","AF-1000-B1800"));assertFalse(NetworkMonitoringService.approvedTarget("192.169.11.2","深信服","AF-1000-B1800"));assertFalse(NetworkMonitoringService.approvedTarget("192.169.11.1","unknown","AF-1000-B1800"));assertFalse(NetworkMonitoringService.approvedTarget("192.169.11.1","深信服","other-model"));}
    @Test void correctedFirewallBindingRetainsTenantAndMetricValidation(){var row=new LinkedHashMap<String,Object>(Map.of("tenant_id",TENANT,"asset_id",ASSET,"ip","192.169.11.1","brand","深信服","model","AF-1000-B1800","host_id","12","items",binding.items()));assertEquals(1,NetworkMonitoringService.parseBindings(json(List.of(row)).toString()).size());row.put("ip","192.169.11.2");status(503,()->NetworkMonitoringService.parseBindings(json(List.of(row)).toString()));row.put("ip","192.169.11.1");row.put("tenant_id","../other");status(503,()->NetworkMonitoringService.parseBindings(json(List.of(row)).toString()));}
    @Test void transportRejectsRedirectErrorWrongIdDuplicatesAndOversize(){for(String body:List.of("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":[]}","{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"data\":\"secret\"}}","{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":1,\"result\":[]}"))assertThrows(IllegalStateException.class,()->ZabbixReadClient.decode(200,"application/json",body.getBytes(),1));assertThrows(IllegalStateException.class,()->ZabbixReadClient.decode(302,"application/json",new byte[]{1},1));assertThrows(IllegalStateException.class,()->ZabbixReadClient.decode(200,"application/json",new byte[ZabbixReadClient.MAX_BYTES+1],1));}
    @Test void transportOnlyAcceptsExplicitLoopbackEndpointAndReadMethods(){assertThrows(IllegalArgumentException.class,()->new ZabbixReadClient("http://example.com/api_jsonrpc.php","a".repeat(64)));var client=new ZabbixReadClient("http://127.0.0.1:10020/api_jsonrpc.php","a".repeat(64));assertThrows(IllegalArgumentException.class,()->client.call("item.update",Map.of()));}
    PlatformRepository repository(){var platform=mock(PlatformRepository.class);var member=new Membership(ACTOR,TENANT,"padmin",3,true);when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(member));when(platform.authorizedAsset(TENANT,ACTOR,3,ASSET)).thenReturn(Optional.of(new Asset(ASSET,TENANT,"测试","switch",binding.ip(),"test","unknown","draft","manual",null,"pending",1,NOW,NOW,"UNKNOWN","PENDING")));return platform;}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/network-monitoring");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
    @Test void controllerRechecksScopedAssetAndMembershipAfterSourceRead(){var repo=repository();new NetworkMonitoringController(repo,service).list(request());verify(repo,times(2)).authorizedAsset(TENANT,ACTOR,3,ASSET);verify(repo,times(2)).membership(ACTOR,TENANT);}
    @Test void crossTenantAssetAndUnknownBindingReturn404(){var repo=repository();var ctl=new NetworkMonitoringController(repo,service);status(404,()->ctl.history("55000000-0000-4000-8000-000000000099",request()));when(repo.authorizedAsset(TENANT,ACTOR,3,ASSET)).thenReturn(Optional.empty());status(404,()->ctl.history(ASSET,request()));}
    @Test void revokedPermissionBeforeReadAndDuringReadCannotReturnSamples(){var repo=repository();var ctl=new NetworkMonitoringController(repo,service);when(repo.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",4,true,List.of())));status(403,()->ctl.list(request()));repo=repository();when(repo.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"padmin",3,true)),Optional.of(new Membership(ACTOR,TENANT,"padmin",4,true)));var changed=new NetworkMonitoringController(repo,service);status(401,()->changed.list(request()));}
    @Test void queryCannotSelectTenantIpOrProviderItem(){var ctl=new NetworkMonitoringController(repository(),service);for(String key:List.of("tenant_id","ip","itemid")){var req=request();req.addParameter(key,"anything");status(400,()->ctl.list(req));}var req=request();req.addParameter("metric","icmp","loss");status(400,()->ctl.history(ASSET,req));}
    @Test void readyEndpointFailsForEnabledUnavailableNetworkDependency(){var platform=mock(PlatformController.class,org.mockito.Answers.CALLS_REAL_METHODS);var network=mock(NetworkMonitoringService.class);when(network.enabled()).thenReturn(true);when(network.readiness()).thenReturn(Map.of("ready",false));ReflectionTestUtils.setField(platform,"networkMonitoring",network);status(503,()->platform.ready(request()));}
}
