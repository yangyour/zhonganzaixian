package cn.mop.api;

import cn.mop.domain.Asset;
import cn.mop.domain.FixtureUser;
import cn.mop.persistence.FixtureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="mop.fixture.unit-test",matches="true")
@SpringBootTest(properties="mop.fixture.unit-test=true") @AutoConfigureMockMvc @ActiveProfiles("local_fixture")
class FixtureSecurityAndWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired FixtureRepository repository;
    @Autowired ObjectMapper mapper;
    record Browser(MockHttpSession session,String csrf,String userId){}
    Browser login(String role,String tenant)throws Exception {
        FixtureUser user=repository.users().stream().filter(u->u.role().equals(role)&&repository.membership(u.id(),tenant).isPresent()).findFirst().orElseThrow();
        MvcResult login=mvc.perform(post("/api/v1/fixture/session").contentType("application/json").header("Idempotency-Key",UUID.randomUUID().toString()).content(mapper.writeValueAsString(Map.of("user_id",user.id())))).andExpect(status().isOk()).andReturn();
        JsonNode data=json(login).path("data");Browser browser=new Browser((MockHttpSession)login.getRequest().getSession(false),data.path("csrf_token").asText(),user.id());
        if(!data.path("tenant").path("id").asText().equals(tenant))mvc.perform(command(post("/api/v1/session/tenant"),browser,Map.of("tenant_id",tenant),UUID.randomUUID().toString())).andExpect(status().isOk());
        return browser;
    }
    JsonNode json(MvcResult result)throws Exception{return mapper.readTree(result.getResponse().getContentAsString());}
    MockHttpServletRequestBuilder command(MockHttpServletRequestBuilder request,Browser browser,Object body,String key)throws Exception{return request.session(browser.session).header("X-CSRF-TOKEN",browser.csrf).header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(body));}
    Map<String,Object> assetBody(String name,String site){return Map.of("name",name,"type","router","management_ip","192.0.2.77","site_id",site);}

    @Test void unauthenticatedAndHealthHaveClearEnvelopes()throws Exception {
        mvc.perform(get("/api/v1/assets")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED")).andExpect(jsonPath("$.trace_id").isNotEmpty());
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("local_fixture")).andExpect(jsonPath("$.data.production_ready").value(false)).andExpect(jsonPath("$.data.durable").value(false));
    }
    @Test void loginRejectsCrossOriginAndMissingIdempotencyAndRenewsCsrf()throws Exception {
        String user=repository.users().get(0).id();
        mvc.perform(post("/api/v1/fixture/session").contentType("application/json").content(mapper.writeValueAsString(Map.of("user_id",user)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/fixture/session").header("Origin","https://attacker.example").header("Idempotency-Key","cross-origin").contentType("application/json").content(mapper.writeValueAsString(Map.of("user_id",user)))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        Browser browser=login("padmin",FixtureRepository.TENANT_A);
        mvc.perform(get("/api/v1/session").session(browser.session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.csrf_token").value(browser.csrf));
    }
    @Test void csrfAndViewerPermissionAreServerEnforced()throws Exception {
        Browser admin=login("padmin",FixtureRepository.TENANT_A);
        mvc.perform(post("/api/v1/assets").session(admin.session).header("Idempotency-Key","no-csrf").contentType("application/json").content(mapper.writeValueAsString(assetBody("CSRF denied","site-a-main")))).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        Browser viewer=login("viewer",FixtureRepository.TENANT_A);
        mvc.perform(command(post("/api/v1/assets"),viewer,assetBody("viewer forbidden","site-a-main"),"viewer-forbidden")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));
        mvc.perform(get("/api/v1/configurations").session(viewer.session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").session(viewer.session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").session(admin.session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].result").value("DENIED"));
    }
    @Test void tenantIsolationIncludesAssetReadsSitesAndTenantSwitch()throws Exception {
        Browser a=login("viewer",FixtureRepository.TENANT_A),b=login("viewer",FixtureRepository.TENANT_B);
        String assetB=repository.list(FixtureRepository.TENANT_B).get(0).id();
        mvc.perform(get("/api/v1/assets/"+assetB).session(a.session)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
        mvc.perform(get("/api/v1/assets/"+assetB).session(b.session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.tenant_id").value(FixtureRepository.TENANT_B));
        mvc.perform(command(post("/api/v1/session/tenant"),a,Map.of("tenant_id",FixtureRepository.TENANT_B),"tenant-denied")).andExpect(status().isNotFound());
        Browser admin=login("tadmin",FixtureRepository.TENANT_A);
        mvc.perform(command(post("/api/v1/assets"),admin,assetBody("foreign site","site-b-main"),"foreign-site")).andExpect(status().isNotFound());
    }
    @Test void unknownAndTenantFieldsCannotForgeOwnership()throws Exception {
        Browser a=login("padmin",FixtureRepository.TENANT_A);Map<String,Object> payload=new HashMap<>(assetBody("forged","site-a-main"));payload.put("tenant_id",FixtureRepository.TENANT_B);
        mvc.perform(command(post("/api/v1/assets"),a,payload,"forged-tenant")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        payload.remove("tenant_id");payload.put("actor_id",a.userId);
        mvc.perform(command(post("/api/v1/assets"),a,payload,"forged-actor")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/assets").session(a.session).param("tenant_id",FixtureRepository.TENANT_B)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/assets").session(a.session).param("page_size","15","100")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/assets").session(a.session).param("page_size","500")).andExpect(status().isBadRequest());
    }
    @Test void idempotencySamePayloadReplaysAndDifferentPayloadConflicts()throws Exception {
        Browser a=login("tadmin",FixtureRepository.TENANT_A);String key=UUID.randomUUID().toString();Map<String,Object> payload=assetBody("幂等 "+key,"site-a-main");
        MvcResult first=mvc.perform(command(post("/api/v1/assets"),a,payload,key)).andExpect(status().isOk()).andReturn();
        MvcResult second=mvc.perform(command(post("/api/v1/assets"),a,payload,key)).andExpect(status().isOk()).andReturn();
        assertThat(json(second).path("data")).isEqualTo(json(first).path("data"));
        mvc.perform(command(post("/api/v1/assets"),a,assetBody("异体 "+key,"site-a-main"),key)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(command(post("/api/v1/assets"),a,payload,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_NAME_CONFLICT"));
    }
    @Test @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void revokedMembershipCannotReadOrReplayPreviouslyCommittedWrite()throws Exception {
        Browser a=login("tadmin",FixtureRepository.TENANT_A);Map<String,Object> body=assetBody("撤权前草稿","site-a-main");
        mvc.perform(command(post("/api/v1/assets"),a,body,"revoke-write")).andExpect(status().isOk());repository.revoke(a.userId,FixtureRepository.TENANT_A);
        mvc.perform(get("/api/v1/assets").session(a.session)).andExpect(status().isUnauthorized());
        mvc.perform(command(post("/api/v1/assets"),a,body,"revoke-write")).andExpect(status().isUnauthorized());
    }
    @Test void cursorIsBoundToTenantFilterAndStableOrder()throws Exception {
        Browser admin=login("padmin",FixtureRepository.TENANT_A);
        MvcResult first=mvc.perform(get("/api/v1/assets").session(admin.session).param("page_size","10")).andExpect(status().isOk()).andReturn();
        JsonNode data=json(first).path("data");String cursor=data.path("next_cursor").asText();assertThat(data.path("has_more").asBoolean()).isTrue();
        MvcResult second=mvc.perform(get("/api/v1/assets").session(admin.session).param("page_size","10").param("cursor",cursor)).andExpect(status().isOk()).andReturn();
        Set<String> ids=new HashSet<>();data.path("items").forEach(item->ids.add(item.path("id").asText()));json(second).path("data").path("items").forEach(item->assertThat(ids).doesNotContain(item.path("id").asText()));
        mvc.perform(get("/api/v1/assets").session(admin.session).param("cursor",cursor).param("q","different")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CURSOR_INVALID"));
        mvc.perform(command(post("/api/v1/session/tenant"),admin,Map.of("tenant_id",FixtureRepository.TENANT_B),UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assets").session(admin.session).param("cursor",cursor)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CURSOR_INVALID"));
        mvc.perform(get("/api/v1/assets").session(admin.session).param("status","ok")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.has_more").value(false));
    }
    @Test void newDraftHasNoInventedMonitoringAndOnboardingNeverCreatesTask()throws Exception {
        Browser a=login("msp",FixtureRepository.TENANT_A);String name="未知 "+UUID.randomUUID();
        MvcResult created=mvc.perform(command(post("/api/v1/assets"),a,assetBody(name,"site-a-main"),UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("unknown")).andExpect(jsonPath("$.data.availability").value("UNKNOWN")).andExpect(jsonPath("$.data.sampled_at").doesNotExist()).andExpect(jsonPath("$.data.lifecycle_status").value("DRAFT")).andReturn();
        String id=json(created).path("data").path("id").asText();
        mvc.perform(command(post("/api/v1/assets/"+id+"/onboarding"),a,Map.of("expected_version",1),"onboard-"+id)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("CONFIG_PENDING")).andExpect(jsonPath("$.error.details[0].question_ids[0]").value("Q02")).andExpect(jsonPath("$.data.operation_id").doesNotExist());
        mvc.perform(command(post("/api/v1/assets"),a,Map.of("name","invalid-ip","type","router","management_ip","host.internal","site_id","site-a-main"),"invalid-ip")).andExpect(status().isBadRequest());
        mvc.perform(command(post("/api/v1/assets"),a,Map.of("name","IPv6 "+UUID.randomUUID(),"type","other","management_ip","2001:db8::1","site_id","site-a-main"),UUID.randomUUID().toString())).andExpect(status().isOk());
    }
    @Test void pendingConfigurationStaysNullAfterDraftAndPublishingIsBlocked()throws Exception {
        Browser a=login("padmin",FixtureRepository.TENANT_A);String key="database.isolation_mode";
        Map<String,Object> item=repository.configurations().stream().filter(m->m.get("decision_status").equals("pending")&&m.get("type").equals("object")).findFirst().orElseThrow();key=item.get("key").toString();long version=((Number)item.get("version")).longValue();
        mvc.perform(command(post("/api/v1/configurations/"+key+"/draft"),a,Map.of("expected_version",version,"proposed_value",Map.of("candidate","review-only")),UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.decision_status").value("pending")).andExpect(jsonPath("$.data.value").doesNotExist()).andExpect(jsonPath("$.data.effective_value").doesNotExist()).andExpect(jsonPath("$.data.draft_value.candidate").value("review-only")).andExpect(jsonPath("$.data.lifecycle_status").value("DRAFT"));
        mvc.perform(command(post("/api/v1/configurations/"+key+"/publish"),a,Map.of("expected_version",version+1),UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("CONFIG_PENDING"));
        Map<String,Object> after=repository.configuration(key);assertThat(after.get("value")).isNull();assertThat(after.get("effective_value")).isNull();assertThat(after.get("version")).isEqualTo(version+1);
        mvc.perform(command(post("/api/v1/configurations/business.timezone/draft"),a,Map.of("expected_version",1,"proposed_value","UTC"),UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("STATE_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/configurations").session(a.session).param("decision_status","pending")).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].decision_status").value("pending"));
        mvc.perform(get("/api/v1/configurations").session(a.session).param("tenant_id",FixtureRepository.TENANT_B)).andExpect(status().isBadRequest());
    }
    @Test void concurrentConfigDraftsHaveSingleCasWinnerAndConcurrentReplayCreatesOneAsset()throws Exception {
        Browser a=login("padmin",FixtureRepository.TENANT_A);String key="monitor.page_refresh_sec";long version=((Number)repository.configuration(key).get("version")).longValue();
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Integer>> futures=new ArrayList<>();for(int candidate:List.of(31,32))futures.add(pool.submit(()->{start.await();return mvc.perform(command(post("/api/v1/configurations/"+key+"/draft"),a,Map.of("expected_version",version,"proposed_value",candidate),UUID.randomUUID().toString())).andReturn().getResponse().getStatus();}));start.countDown();
            List<Integer> status=new ArrayList<>();for(Future<Integer> future:futures)status.add(future.get(10,TimeUnit.SECONDS));assertThat(status).containsExactlyInAnyOrder(200,409);
            String idempotency=UUID.randomUUID().toString();Map<String,Object> body=assetBody("并发 "+idempotency,"site-a-main");CountDownLatch assetsStart=new CountDownLatch(1);List<Future<String>> ids=new ArrayList<>();for(int i=0;i<2;i++)ids.add(pool.submit(()->{assetsStart.await();MvcResult result=mvc.perform(command(post("/api/v1/assets"),a,body,idempotency)).andExpect(status().isOk()).andReturn();return json(result).path("data").path("id").asText();}));assetsStart.countDown();assertThat(ids.get(0).get(10,TimeUnit.SECONDS)).isEqualTo(ids.get(1).get(10,TimeUnit.SECONDS));
            assertThat(repository.list(FixtureRepository.TENANT_A).stream().filter(asset->asset.name().equals(body.get("name"))).count()).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void unknownPathsAndUnsupportedMethodsHaveUnifiedEnvelope()throws Exception {
        Browser a=login("padmin",FixtureRepository.TENANT_A);
        mvc.perform(get("/api/v1/not-an-endpoint").session(a.session)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("OBJECT_NOT_FOUND"));
        mvc.perform(command(put("/api/v1/assets"),a,Map.of(),"wrong-method")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }
    @Test void tenantSwitchReplayInNewBrowserReturnsCurrentCsrfAndAuthorization()throws Exception {
        Browser first=login("padmin",FixtureRepository.TENANT_A);String key=UUID.randomUUID().toString();
        mvc.perform(command(post("/api/v1/session/tenant"),first,Map.of("tenant_id",FixtureRepository.TENANT_B),key)).andExpect(status().isOk());
        Browser fresh=login("padmin",FixtureRepository.TENANT_A);assertThat(fresh.csrf).isNotEqualTo(first.csrf);
        mvc.perform(command(post("/api/v1/session/tenant"),fresh,Map.of("tenant_id",FixtureRepository.TENANT_B),key)).andExpect(status().isOk()).andExpect(jsonPath("$.data.csrf_token").value(fresh.csrf)).andExpect(jsonPath("$.data.tenant.id").value(FixtureRepository.TENANT_B));
        mvc.perform(command(post("/api/v1/assets"),fresh,assetBody("重放后可写 "+UUID.randomUUID(),"site-b-main"),UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.tenant_id").value(FixtureRepository.TENANT_B));
    }
}
