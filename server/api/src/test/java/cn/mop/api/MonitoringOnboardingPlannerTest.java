package cn.mop.api;

import cn.mop.domain.Asset;
import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringOnboardingPlannerTest {
    static final String TENANT="79000000-0000-4000-8000-000000000001", OTHER="79000000-0000-4000-8000-000000000002";
    static JsonNode example() throws Exception {return MonitoringPresetRegistry.JSON.readTree(Files.readString(Path.of("../../deploy/examples/monitoring/inventory.disabled.json")));}
    static Asset asset(String id,String tenant,String ip){return new Asset(id,tenant,"Synthetic","other",ip,"site","unknown","DRAFT","test_seed",null,null,1,Instant.EPOCH,Instant.EPOCH,null,null);}
    static Map<String,Asset> assets(JsonNode inventory){Map<String,Asset> result=new HashMap<>();for(JsonNode row:inventory.get("assets"))result.put(row.get("asset_id").asText(),asset(row.get("asset_id").asText(),TENANT,row.get("address").asText()));return result;}
    static ObjectNode request(JsonNode inventory){ObjectNode result=inventory.deepCopy();result.remove(List.of("tenant_id","collectors"));for(JsonNode row:result.get("assets"))((ObjectNode)row).remove("address");return result;}
    static String collectors(JsonNode inventory,String tenant){ObjectNode root=MonitoringPresetRegistry.JSON.createObjectNode().put("version",1);var rows=root.putArray("collectors");inventory.get("collectors").fields().forEachRemaining(entry->{ObjectNode row=entry.getValue().deepCopy();row.put("tenant_id",tenant).put("name",entry.getKey());if(!row.has("auth_profile"))row.putNull("auth_profile");rows.add(row);});return root.toString();}
    static MonitoringOnboardingPlanner empty(){return new MonitoringOnboardingPlanner("{\"version\":1,\"collectors\":[]}");}
    static JsonNode tree(Object value){return MonitoringPresetRegistry.JSON.valueToTree(value);}

    @Test void allThirteenPresetsAndFifteenTargetsMatchPythonGolden()throws Exception{
        var source=example();var planner=new MonitoringOnboardingPlanner(collectors(source,TENANT));var result=planner.plan(TENANT,request(source),assets(source)::get);
        var golden=MonitoringPresetRegistry.JSON.readTree(Files.readString(Path.of("../../fixtures/monitoring-inventory-golden.json")));
        assertEquals(true,result.get("valid"));assertEquals(15,result.get("target_count"));assertEquals(golden.get("configuration"),tree(result.get("configuration")));assertEquals(golden.get("configuration_sha256").asText(),result.get("configuration_sha256"));assertEquals(false,result.get("applied"));assertEquals(false,result.get("network_accessed"));
    }
    @Test void catalogHasThirteenDetachedDefinitionsAndValidUnavailableRules()throws Exception{
        var planner=empty();var catalog=tree(planner.catalog(TENANT));assertEquals(13,catalog.get("presets").size());assertEquals(MonitoringOnboardingPlanner.sha(Files.readAllBytes(Path.of("../../config/monitoring/presets.v1.json"))),catalog.get("catalog_sha256").asText());
        for(JsonNode p:catalog.get("presets")){assertFalse(p.get("device_setup_verified").asBoolean());assertFalse(p.get("profile_schema").get("additionalProperties").asBoolean());if(p.get("server_collector_required").asBoolean()){assertFalse(p.get("server_collector_configured").asBoolean());assertTrue(p.get("profile_schema").get("properties").get("collector").has("not"));}}
        ((ObjectNode)catalog.get("presets").get(0)).put("title","mutated");assertFalse(tree(planner.catalog(TENANT)).toString().contains("mutated"));
    }
    @Test void collectorNamesAndReferencesAreTenantScoped()throws Exception{
        var source=example();ObjectNode raw=(ObjectNode)MonitoringPresetRegistry.JSON.readTree(collectors(source,OTHER));((ObjectNode)raw.get("collectors").get(0)).put("name","private_collector");var planner=new MonitoringOnboardingPlanner(raw.toString());
        String visible=tree(planner.catalog(TENANT)).toString();assertFalse(visible.contains("private_collector"));assertFalse(visible.contains("198.51.100"));assertFalse(visible.contains("replace_with_configured"));
        var result=tree(planner.plan(TENANT,request(source),assets(source)::get));assertFalse(result.get("valid").asBoolean());assertTrue(result.get("configuration").isNull());assertTrue(result.toString().contains("COLLECTOR_REQUIRED"));assertFalse(result.toString().contains("198.51.100"));
    }
    @Test void catalogExposesOnlyScopedNamesNotCollectorAddressesOrCredentialReferences()throws Exception{
        var planner=new MonitoringOnboardingPlanner(collectors(example(),TENANT));String visible=tree(planner.catalog(TENANT)).toString();assertFalse(visible.contains("198.51.100"));assertFalse(visible.contains("replace_with_configured"));assertTrue(visible.contains("server_collector_configured\":true"));
    }
    @Test void startupRejectsUnsafeMalformedDuplicateAndOverflowCollectors()throws Exception{
        String good=collectors(example(),TENANT);
        for(String bad:List.of("{}",good.replace("\"version\":1","\"version\":4294967297"),good.replace("198.51.100.10","localhost"),good.replace("198.51.100.10","169.254.169.254"),good.replace("\"auth_profile\":null","\"auth_profile\":\"secret\""),good+" {}"))assertThrows(IllegalArgumentException.class,()->new MonitoringOnboardingPlanner(bad));
        ObjectNode duplicated=(ObjectNode)MonitoringPresetRegistry.JSON.readTree(good);duplicated.withArray("collectors").add(duplicated.get("collectors").get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->new MonitoringOnboardingPlanner(duplicated.toString()));
    }
    @Test void requestCannotSupplyTenantAddressCollectorDefinitionsOrCredentials()throws Exception{
        var source=example();AtomicInteger lookups=new AtomicInteger();
        for(String name:List.of("tenant_id","collectors","password","endpoint","enabled")){var request=request(source);request.put(name,"never-echo");DomainException e=assertThrows(DomainException.class,()->empty().plan(TENANT,request,id->{lookups.incrementAndGet();return null;}));assertEquals(400,e.status);assertFalse(e.getMessage().contains("never-echo"));}
        var address=request(source);((ObjectNode)address.get("assets").get(0)).put("address","192.0.2.99");assertThrows(DomainException.class,()->empty().plan(TENANT,address,id->{lookups.incrementAndGet();return null;}));
        var secret=request(source);var profiles=((ObjectNode)secret.get("assets").get(0)).putArray("profiles");profiles.addObject().put("preset_id","network-snmp").put("auth_profile","unapproved_reference");assertThrows(DomainException.class,()->empty().plan(TENANT,secret,id->{lookups.incrementAndGet();return null;}));assertEquals(0,lookups.get());
    }
    @Test void invalidProfilesReturnPositionedValueFreeDiagnosticsAndNoPartialPlan()throws Exception{
        var source=example();var request=request(source);((ObjectNode)request.get("assets").get(0)).putArray("profiles").add("never-echo").addObject().put("preset_id","http-service").put("scheme","bad-secret");
        var result=tree(new MonitoringOnboardingPlanner(collectors(source,TENANT)).plan(TENANT,request,assets(source)::get));assertFalse(result.get("valid").asBoolean());assertTrue(result.get("configuration").isNull());assertTrue(result.get("configuration_sha256").isNull());assertEquals(0,result.get("target_count").asInt());assertEquals(2,result.get("diagnostics").size());assertEquals(1,result.get("diagnostics").get(0).get("asset_index").asInt());assertEquals(2,result.get("diagnostics").get(1).get("profile_index").asInt());assertFalse(result.toString().contains("never-echo"));assertFalse(result.toString().contains("bad-secret"));
    }
    @Test void reorderKeepsStableIdentityAndConfigurationHash()throws Exception{
        var source=example();var planner=new MonitoringOnboardingPlanner(collectors(source,TENANT));var original=request(source);var first=planner.plan(TENANT,original,assets(source)::get);
        ObjectNode reversed=original.deepCopy();var list=reversed.putArray("assets");for(int i=original.get("assets").size()-1;i>=0;i--)list.add(original.get("assets").get(i));assertEquals(first,planner.plan(TENANT,reversed,assets(source)::get));
    }
    @Test void addressChangesRetainLogicalIdsButChangeConfigurationHash()throws Exception{
        var source=example();var planner=new MonitoringOnboardingPlanner(collectors(source,TENANT));var records=assets(source);var first=tree(planner.plan(TENANT,request(source),records::get));String id=source.get("assets").get(0).get("asset_id").asText();records.put(id,asset(id,TENANT,"192.0.2.99"));var next=tree(planner.plan(TENANT,request(source),records::get));assertNotEquals(first.get("configuration_sha256"),next.get("configuration_sha256"));for(int i=0;i<15;i++)assertEquals(first.get("configuration").get("targets").get(i).get("id"),next.get("configuration").get("targets").get(i).get("id"));
    }
    @Test void duplicateLogicalKeysAndExactDestinationsCannotCreatePartialPlans()throws Exception{
        var source=example();var planner=new MonitoringOnboardingPlanner(collectors(source,TENANT));
        for(boolean sameKey:List.of(true,false)){var request=request(source);var profiles=((ObjectNode)request.get("assets").get(0)).putArray("profiles");profiles.addObject().put("preset_id","tcp-service").put("key","ssh").put("port",22);profiles.addObject().put("preset_id","tcp-service").put("key",sameKey?"ssh":"ssh-copy").put("port",sameKey?23:22);var result=tree(planner.plan(TENANT,request,assets(source)::get));assertFalse(result.get("valid").asBoolean());assertTrue(result.get("configuration").isNull());}
    }
    @Test void explicitDefaultsAreValidatedAndNeverEnableTargets()throws Exception{
        var source=example();var request=request(source);request.putObject("defaults").put("interval_seconds",60).put("timeout_seconds",7).put("exporter_scheme","https");var result=tree(new MonitoringOnboardingPlanner(collectors(source,TENANT)).plan(TENANT,request,assets(source)::get));assertTrue(result.get("valid").asBoolean());for(JsonNode row:result.get("configuration").get("targets")){assertFalse(row.get("enabled").asBoolean());assertEquals(60,row.get("interval_seconds").asInt());assertEquals(7,row.get("timeout_seconds").asInt());}
        request.putObject("defaults").put("interval_seconds",5).put("timeout_seconds",5);AtomicInteger lookups=new AtomicInteger();result=tree(empty().plan(TENANT,request,id->{lookups.incrementAndGet();return null;}));assertFalse(result.get("valid").asBoolean());assertEquals(0,lookups.get());
    }
    @Test void unsupportedAssetAddressStaysUnconfigured()throws Exception{
        var source=example();var request=request(source);((ObjectNode)request.get("assets").get(0)).putArray("profiles").add("linux-node");var records=assets(source);String id=request.get("assets").get(0).get("asset_id").asText();records.put(id,asset(id,TENANT,"::1"));var result=tree(empty().plan(TENANT,request,records::get));assertFalse(result.get("valid").asBoolean());assertTrue(result.toString().contains("ASSET_ADDRESS_UNSUPPORTED"));
    }
    @Test void totalProfileAndDuplicateAssetLimitsAreEnforcedBeforeLookup()throws Exception{
        var source=example();var request=request(source);request.withArray("assets").add(request.get("assets").get(0).deepCopy());assertThrows(DomainException.class,()->empty().plan(TENANT,request,id->{throw new AssertionError();}));
        var many=request(source);var list=many.putArray("assets");for(int i=0;i<5;i++){var item=list.addObject().put("asset_id",String.format("79000000-0000-4000-8000-%012d",100+i));var profiles=item.putArray("profiles");for(int j=0;j<16;j++)profiles.add("linux-node");}assertThrows(DomainException.class,()->empty().plan(TENANT,many,id->{throw new AssertionError();}));
    }
    @Test void returnedPlansCannotMutateFutureCompilation()throws Exception{
        var source=example();var planner=new MonitoringOnboardingPlanner(collectors(source,TENANT));var first=planner.plan(TENANT,request(source),assets(source)::get);String expected=tree(first).toString();((Map<?,?>)first.get("configuration")).clear();assertEquals(expected,tree(planner.plan(TENANT,request(source),assets(source)::get)).toString());
    }

    @Test void plannerDefensivelyRejectsIncorrectResolverIdentity()throws Exception{
        var request=request(example());String id=request.get("assets").get(0).get("asset_id").asText();
        for(Asset wrong:List.of(asset(id,OTHER,"192.0.2.10"),asset(OTHER,TENANT,"192.0.2.10")))assertEquals(404,assertThrows(DomainException.class,()->empty().plan(TENANT,request,ignored->wrong)).status);
        assertEquals(404,assertThrows(DomainException.class,()->empty().plan(TENANT,request,ignored->null)).status);
    }
    @Test void defaultPlannerExistsWithoutEnablingProbeService(){
        var config=new MonitoringPresetConfiguration();var env=new org.springframework.mock.env.MockEnvironment();assertFalse(config.monitoringPresetProbeService(env).enabled());assertEquals(13,tree(config.monitoringOnboardingPlanner(env).catalog(TENANT)).get("presets").size());
    }
}
