package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.search.*;
import cn.mop.domain.search.GlobalSearchRepository.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Actual production-controller/filter-chain JSON with synthetic repository projections, not PG evidence.
 * Normal tests compare committed fixtures; explicit -Dmop.globalSearchFixtures=write exports them. */
@ExtendWith(SpringExtension.class) @WebAppConfiguration
@ActiveProfiles({"remote_test","isolated_global_search_http"})
@ContextConfiguration(classes=GlobalSearchHttpContractTest.Configuration.class)
class GlobalSearchHttpContractTest {
    static final String TENANT=id(1),ACTOR=id(2),TRACE=id(3),QUERY="合成 Ä%_' ";
    static final Instant NOW=Instant.parse("2026-10-19T11:00:00.123Z");
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @org.springframework.boot.test.context.TestConfiguration @Profile("isolated_global_search_http")
    @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,ApiExceptionHandler.class,GlobalSearchController.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository platform(){return mock(PlatformRepository.class);}
        @Bean GlobalSearchRepository search(){return mock(GlobalSearchRepository.class);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean ObjectMapper mapper(){return JSON;}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){converters.add(new MappingJackson2HttpMessageConverter(mapper()));}
    }
    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository platform;
    @Autowired GlobalSearchRepository search;
    MockMvc mvc;
    @BeforeEach void setup(){reset(platform,search);mvc=webAppContextSetup(context).apply(springSecurity()).build();role("msp");}
    static String id(int suffix){return "65000000-0000-4000-8000-"+String.format(Locale.ROOT,"%012d",suffix);}
    void role(String role){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,true)));}
    static EventHit event(int n){return new EventHit(id(100+n),TENANT,"SYNTHETIC_"+n,"合成 Ä%_' 规则 "+n,n+1,Severity.values()[n%Severity.values().length],MonitorState.values()[n%2],HandlingState.values()[n%HandlingState.values().length],n+1,NOW.minusSeconds(n));}
    static Snapshot snapshot(String role,int count,boolean more,int start){boolean internal=Capabilities.internal(role);return new Snapshot(QUERY,TENANT,NOW,internal?"CURRENT_TENANT_INTERNAL":"OWN_REPORT_REGISTRATIONS",List.of(new AssetHit(id(10),TENANT,"合成资产","router","manual",1)),false,List.of(new TicketHit(id(11),TENANT,"合成报障登记","SYNTHETIC-RECEIPT","customer_report","REGISTERED",1)),false,internal,internal?IntStream.range(start,start+count).mapToObj(GlobalSearchHttpContractTest::event).toList():List.of(),internal&&more);}
    ResultActions request(String query)throws Exception {var session=new MockHttpSession(context.getServletContext());session.setAttribute("mop.tenant_id",TENANT);return mvc.perform(get("/api/v1/search").param("q",query).session(session).with(user(ACTOR)));}
    JsonNode response(String role,int count,boolean more,int start)throws Exception{return response(role,snapshot(role,count,more,start));}
    JsonNode response(String role,Snapshot sample)throws Exception{
        role(role);when(search.search(new Access(ACTOR,TENANT,7),new GlobalSearchQuery(sample.query()))).thenReturn(sample);
        var http=request(sample.query()).andExpect(status().isOk()).andExpect(handler().handlerType(GlobalSearchController.class)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        var output=(ObjectNode)JSON.readTree(http.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(http.getHeader("X-Trace-Id"),output.path("trace_id").asText());assertDoesNotThrow(()->UUID.fromString(output.path("trace_id").asText()));output.put("trace_id",TRACE);
        verify(platform,never()).idempotentAuthorized(any(),any(),any(),any(),any(),anyLong(),any(),any());
        verify(platform,never()).audit(any(),any(),any(),any(),any(),any(),any(),any());
        return output;
    }
    @ParameterizedTest @ValueSource(strings={"msp","padmin","viewer","tadmin"})
    void exactGroupVisibilityAndMinimalProjection(String role)throws Exception{
        var data=response(role,1,false,0).path("data");var group=data.path("groups").get(1);
        assertEquals(List.of("assets","events","tickets","automation_scripts"),IntStream.range(0,4).mapToObj(i->data.path("groups").get(i).path("kind").asText()).toList());
        assertEquals(4,group.path("limit").asInt());assertEquals("NOT_IMPLEMENTED",data.path("notification_bell_status").asText());
        if(Capabilities.internal(role)){
            assertEquals("AVAILABLE",group.path("status").asText());assertEquals("AUTOMATION_SOURCE_NOT_IMPLEMENTED",data.path("coverage_reason").asText());
            var item=group.path("items").get(0);assertEquals(Set.of("id","tenant_id","rule_code","rule_name","version","severity","monitor_state","handling_state","occurrence_count","last_occurred_at","resource_kind"),keys(item));
            assertEquals("MONITORING_EVENT",item.path("resource_kind").asText());assertEquals(NOW.toString(),item.path("last_occurred_at").asText());
        }else{
            assertEquals("NOT_AUTHORIZED",group.path("status").asText());assertEquals("INTERNAL_EVENT_READ_REQUIRED",group.path("reason").asText());
            assertEquals("AUTOMATION_SOURCE_NOT_IMPLEMENTED_AND_EVENT_NOT_AUTHORIZED",data.path("coverage_reason").asText());
            for(String field:List.of("items","returned","has_more","source_scope","matched_fields"))assertTrue(group.path(field).isNull(),field);
            assertEquals(1,data.path("groups").get(0).path("returned").asInt());assertEquals(1,data.path("groups").get(2).path("returned").asInt());
        }
        assertTrue(data.path("groups").get(3).path("items").isNull());
    }
    @ParameterizedTest @CsvSource({"0,false","1,false","4,false","4,true"})
    void boundedCountsAndOverflow(int count,boolean more)throws Exception{
        var group=response("msp",count,more,0).path("data").path("groups").get(1);
        assertEquals(count,group.path("returned").asInt());assertEquals(count,group.path("items").size());assertEquals(more,group.path("has_more").asBoolean());
    }
    @ParameterizedTest @ValueSource(ints={401,403,503})
    void repositoryRevocationOrOutageNeverBecomesAnEmptyResult(int status)throws Exception{
        when(search.search(any(),any())).thenThrow(new DomainException(status,status==401?"SESSION_EXPIRED":status==403?"ACTION_FORBIDDEN":"UPSTREAM_UNAVAILABLE","synthetic denial"));
        request(QUERY).andExpect(status().is(status)).andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void effectiveSearchRevocationDeniedBeforeRepository()throws Exception{
        when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,List.of("assets:read","tickets:read"))));
        request(QUERY).andExpect(status().isForbidden());verifyNoInteractions(search);
    }
    @Test void actualControllerGoldenCoversAllStatesCountsAndRoles()throws Exception{
        var root=JSON.createObjectNode();root.put("fixture_kind","SYNTHETIC_PRODUCTION_CONTROLLER_JSON");root.put("database_evidence",false);root.put("query",QUERY);
        var exchanges=root.putArray("exchanges");
        for(String role:List.of("msp","padmin","viewer","tadmin"))exchange(exchanges,"role_"+role,response(role,1,false,0));
        for(int count:new int[]{0,1,4})exchange(exchanges,"events_"+count,response("msp",count,false,0));
        exchange(exchanges,"events_overflow",response("msp",4,true,0));
        var storedText = new EventHit(id(160),TENANT,"LINK\tRULE","Link\nDown",1,Severity.WARNING,MonitorState.PROBLEM,HandlingState.NEW,1,NOW);
        exchange(exchanges,"stored_rule_control_text",response("msp",new Snapshot("Link",TENANT,NOW,"CURRENT_TENANT_INTERNAL",List.of(),false,List.of(),false,true,List.of(storedText),false)));
        for(int state=0;state<HandlingState.values().length;state++)exchange(exchanges,"state_"+HandlingState.values()[state].name().toLowerCase(Locale.ROOT),response("msp",1,false,state));
        Path file=fixturePath();if("write".equals(System.getProperty("mop.globalSearchFixtures")))Files.writeString(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root)+"\n",StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(file),"Export controller fixture with -Dmop.globalSearchFixtures=write");assertEquals(root,JSON.readTree(Files.readString(file)),"Controller fixture drift; review before explicit regeneration");
    }
    static void exchange(ArrayNode output,String name,JsonNode response){var node=output.addObject();node.put("name",name);node.put("method","GET");node.put("path","/api/v1/search");node.put("response_schema","GroupedSearchResult");node.set("response",response);}
    static Set<String> keys(JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    static Path fixturePath(){for(Path p=Path.of(System.getProperty("user.dir")).toAbsolutePath();p!=null;p=p.getParent())if(Files.isDirectory(p.resolve("server/api"))&&Files.isDirectory(p.resolve("fixtures")))return p.resolve("fixtures/global-search-controller.json");throw new IllegalStateException("Repository root not found");}
    @ParameterizedTest @ValueSource(strings={"inactive","removed","version","role","capabilities"})
    void finalAuthorizationFailureReturnsNoStoreErrorWithoutAnyPartialGroups(String change)throws Exception{
        when(search.search(any(),any())).thenAnswer(call->{switch(change){
            case "inactive"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,false)));
            case "removed"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.empty());
            case "version"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",8,true)));
            case "role"->role("padmin");
            case "capabilities"->when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",7,true,List.of("search:read"))));
        }return snapshot("msp",1,false,0);});
        request(QUERY).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
    }
}
