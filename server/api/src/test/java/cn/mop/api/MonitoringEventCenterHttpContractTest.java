package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.*;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static cn.mop.api.MonitoringEventCenterContractSamples.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Actual MVC route, JSON serialization, and production remote_test filter-chain checks.
 * Repository/session dependencies are mocked: these tests do not claim PG, SSO, or collector integration.
 * Normal runs compare with the committed cross-language golden. Export is explicit:
 * -Dmop.eventCenterFixtures=write (only this test writes fixtures/monitoring-event-center-controller.json).
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({"remote_test", "isolated_event_center_http"})
@ContextConfiguration(classes = MonitoringEventCenterHttpContractTest.Configuration.class)
class MonitoringEventCenterHttpContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRACE = "00000000-0000-4000-8000-000000000015";
    private static final String BASE = "/api/v1";
    private static final String EVENT_PATH = BASE + "/events/" + EVENT;
    private static final String WINDOW_PATH = BASE + "/maintenance-windows/" + WINDOW + "/actions";
    private static final String QUARANTINE_PATH = BASE + "/monitoring/quarantine/" + QUARANTINE + "/decisions";
    private static final String ATTEMPT_PATH = BASE + "/autoticket-attempts/" + ATTEMPT + "/manual-create";

    @org.springframework.boot.test.context.TestConfiguration
    @Profile("isolated_event_center_http")
    @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class, MonitoringEventCenterController.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean PlatformRepository platform() { return mock(PlatformRepository.class); }
        @Bean MonitoringEventCenterRepository reader() { return mock(MonitoringEventCenterRepository.class); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(mapper()));
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired PlatformRepository platform;
    @Autowired MonitoringEventCenterRepository reader;
    private MockMvc mvc;

    @BeforeEach void setup() {
        reset(platform, reader);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, true)));
        when(platform.membership(ADMIN, TENANT)).thenReturn(Optional.of(new Membership(ADMIN, TENANT, "padmin", 9, true)));
        when(platform.membership(VIEWER, TENANT)).thenReturn(Optional.of(new Membership(VIEWER, TENANT, "viewer", 3, true)));
        when(platform.idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(6)).run();
            return ((Supplier<?>) call.getArgument(7)).get();
        });
        when(reader.workspace(any(), anyString(), anyInt())).thenAnswer(call -> workspace(call.getArgument(1)));
        when(reader.noiseFunnel(any(), anyString())).thenAnswer(call -> funnel(call.getArgument(1)));
        when(reader.eventDetail(any(), anyString())).thenReturn(detail(true));
        when(reader.ingestEvent(any(), any())).thenReturn(event(0));
        when(reader.transitionEvent(any(), any())).thenAnswer(call -> transitioned(call.getArgument(1)));
        when(reader.createMaintenanceWindow(any(), any())).thenAnswer(call -> createdWindow(call.getArgument(1)));
        when(reader.updateMaintenanceWindow(any(), any())).thenAnswer(call -> updatedWindow(call.getArgument(0), call.getArgument(1)));
        when(reader.resolveQuarantine(any(), any())).thenAnswer(call -> decided(call.getArgument(1)));
        when(reader.manualCreateAutoTicket(any(), any())).thenAnswer(call -> manualAttempt(call.getArgument(1)));
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder, String actor) {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute("mop.tenant_id", TENANT);
        return builder.session(session).with(user(actor));
    }
    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder) { return scoped(builder, ACTOR); }
    private MockHttpServletRequestBuilder write(WriteCase example) {
        return scoped(post(example.path()), example.actor()).with(csrf().asHeader()).contentType("application/json")
            .header("Idempotency-Key", "synthetic-" + example.name()).content(example.request().toString());
    }
    private ResultActions goodGet(String path, String method) throws Exception {
        return mvc.perform(scoped(get(path))).andExpect(status().isOk())
            .andExpect(handler().handlerType(MonitoringEventCenterController.class)).andExpect(handler().methodName(method))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
    private ResultActions goodWrite(WriteCase example) throws Exception {
        return mvc.perform(write(example)).andExpect(status().isOk())
            .andExpect(handler().handlerType(MonitoringEventCenterController.class)).andExpect(handler().methodName(example.handler()))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test void allGetRoutesResolveAndParseTheirQueries() throws Exception {
        goodGet(BASE + "/events", "workspace").andExpect(jsonPath("$.data.events.length()").value(6));
        verify(reader).workspace(accessFor(ACTOR, 7), eq("24h"), eq(25));
        goodGet(BASE + "/events?window=7d&page_size=50", "workspace");
        verify(reader).workspace(accessFor(ACTOR, 7), eq("7d"), eq(50));
        goodGet(BASE + "/events/noise-funnel?window=30d", "noiseFunnel")
            .andExpect(jsonPath("$.data.raw_intake_count").value(23));
        verify(reader).noiseFunnel(accessFor(ACTOR, 7), eq("30d"));
        goodGet(EVENT_PATH, "eventDetail").andExpect(jsonPath("$.data.event.version").value(1))
            .andExpect(jsonPath("$.data.members[0].raw_event_id").value(nullValue()));
        verify(reader).eventDetail(accessFor(ACTOR, 7), eq(EVENT));
    }

    @ParameterizedTest(name = "{0}") @MethodSource("writes")
    void validWritesUseExactRoutesAndTypedCommands(WriteCase example) throws Exception {
        goodWrite(example);
        var command = example.command();
        if (command instanceof IngestEventCommand c) verify(reader).ingestEvent(accessFor(example.actor(), 7), eq(c));
        else if (command instanceof TransitionEventCommand c) verify(reader).transitionEvent(accessFor(example.actor(), 7), eq(c));
        else if (command instanceof CreateMaintenanceWindowCommand c) verify(reader).createMaintenanceWindow(accessFor(example.actor(), 7), eq(c));
        else if (command instanceof UpdateMaintenanceWindowCommand c) verify(reader).updateMaintenanceWindow(accessFor(example.actor(), example.actor().equals(ADMIN) ? 9 : 7), eq(c));
        else if (command instanceof QuarantineDecisionCommand c) verify(reader).resolveQuarantine(accessFor(example.actor(), 7), eq(c));
        else if (command instanceof ManualAutoTicketCommand c) verify(reader).manualCreateAutoTicket(accessFor(example.actor(), 7), eq(c));
        else fail("Missing command assertion");
        if (example.actor().equals(ADMIN)) { verify(reader).authorizeAdminWrite(accessFor(ADMIN, 9)); verify(reader, never()).authorizeWrite(any()); }
        else { verify(reader).authorizeWrite(accessFor(ACTOR, 7)); verify(reader, never()).authorizeAdminWrite(any()); }
        verify(platform).idempotentAuthorized(eq(example.actor()), eq(TENANT), eq("POST " + example.path()),
            eq("synthetic-" + example.name()), matches("[0-9a-f]{64}"), eq(example.actor().equals(ADMIN) ? 9L : 7L), any(), any());
    }

    @ParameterizedTest @MethodSource("writes")
    void everyWriteRequiresProductionCsrfProtection(WriteCase example) throws Exception {
        mvc.perform(scoped(post(example.path()), example.actor()).contentType("application/json")
            .header("Idempotency-Key", "synthetic-csrf").content(example.request().toString()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        verifyNoInteractions(reader);
        verify(platform, never()).idempotentAuthorized(any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @ParameterizedTest @MethodSource("writes")
    void everyWriteRechecksInternalMembership(WriteCase example) throws Exception {
        mvc.perform(scoped(post(example.path()), VIEWER).with(csrf().asHeader()).contentType("application/json")
            .header("Idempotency-Key", "synthetic-role").content(example.request().toString()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));
        verifyNoInteractions(reader);
    }

    @ParameterizedTest @ValueSource(strings = {"/events", "/events/noise-funnel", "/events/55000000-0000-4000-8000-000000000100"})
    void readsRequireAuthenticatedCurrentInternalMembership(String path) throws Exception {
        mvc.perform(get(BASE + path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"));
        mvc.perform(scoped(get(BASE + path), VIEWER).with(user(VIEWER).roles("PADMIN")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTION_FORBIDDEN"));
        verifyNoInteractions(reader);
    }

    @Test void sessionAndCurrentMembershipCannotBeReplacedBySecurityRoles() throws Exception {
        mvc.perform(get(BASE + "/events").with(user(ACTOR))).andExpect(status().isUnauthorized());
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
        mvc.perform(scoped(get(BASE + "/events"))).andExpect(status().isUnauthorized());
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, false)));
        mvc.perform(scoped(get(BASE + "/events"))).andExpect(status().isUnauthorized());
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "msp", 7, true, List.of())));
        mvc.perform(scoped(get(BASE + "/events")).with(user(ACTOR).roles("PADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(reader);
    }

    @Test void slaConfirmationRequiresCurrentAdminCapabilityAndAdminAuthorizationCallback() throws Exception {
        var example = writes().filter(w -> w.name().equals("confirm_sla_exclusion")).findFirst().orElseThrow();
        mvc.perform(scoped(post(WINDOW_PATH)).with(csrf().asHeader()).contentType("application/json")
            .header("Idempotency-Key", "synthetic-admin").content(example.request().toString()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(reader);
        when(platform.membership(ADMIN, TENANT)).thenReturn(Optional.of(new Membership(ADMIN, TENANT, "padmin", 9, true, List.of("ticket_notes:write_internal"))));
        mvc.perform(write(example)).andExpect(status().isForbidden());
        verifyNoInteractions(reader);
        when(platform.membership(ADMIN, TENANT)).thenReturn(Optional.of(new Membership(ADMIN, TENANT, "padmin", 9, true, List.of("configurations:write"))));
        goodWrite(example).andExpect(jsonPath("$.data.sla_exclusion_confirmed").value(true));
        verify(reader).authorizeAdminWrite(accessFor(ADMIN, 9));
        verify(reader, never()).authorizeWrite(any());
    }

    @Test void rejectedAuthorizationCallbackCannotRunMutation() throws Exception {
        doThrow(new DomainException(403, "ACTION_FORBIDDEN", "合成权限已撤销")).when(reader).authorizeWrite(any());
        mvc.perform(write(writes().findFirst().orElseThrow())).andExpect(status().isForbidden());
        verify(reader, never()).ingestEvent(any(), any());
    }

    @Test void obsoleteAliasesAreNotAccepted() throws Exception {
        var input = writes().findFirst().orElseThrow().request().toString();
        mvc.perform(scoped(post(BASE + "/events")).with(csrf().asHeader()).contentType("application/json").content(input))
            .andExpect(status().isMethodNotAllowed());
        for (String path : List.of(EVENT_PATH + "/actions", BASE + "/monitoring-quarantine/" + QUARANTINE + "/decisions"))
            mvc.perform(scoped(post(path)).with(csrf().asHeader()).contentType("application/json").content(input)).andExpect(status().isNotFound());
        verifyNoInteractions(reader);
    }

    @ParameterizedTest @ValueSource(strings = {"", "page_size=0", "page_size=51", "page_size=01", "page_size=1.0", "page_size=+1", "page_size=1&page_size=2", "window=24h&window=7d", "window=24h&%77indow=7d", "unknown=x", "window=", "window=24h&", "window=24h=7d", "window", "window=%", "window=%GG"})
    void malformedWorkspaceQueriesFailBeforeRepository(String query) throws Exception {
        mvc.perform(scoped(get(BASE + "/events").with(r -> { r.setQueryString(query); return r; })))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(reader);
    }

    @Test void detailAndMutationsRejectAnyQueryAndFunnelRejectsPageSize() throws Exception {
        mvc.perform(scoped(get(EVENT_PATH + "?window=24h"))).andExpect(status().isBadRequest());
        mvc.perform(scoped(get(BASE + "/events/noise-funnel?page_size=25"))).andExpect(status().isBadRequest());
        for (var example : writes().toList())
            mvc.perform(write(example).with(r -> { r.setQueryString("unexpected=1"); return r; })).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @ParameterizedTest @MethodSource("writes")
    void everyBodyRequiresItsExactFieldSet(WriteCase example) throws Exception {
        var extra = example.request().deepCopy().put("tenant_id", "SYNTHETIC_FORGED_TENANT");
        mvc.perform(write(example).content(extra.toString())).andExpect(status().isBadRequest())
            .andExpect(content().string(not(containsString("SYNTHETIC_FORGED_TENANT"))));
        for (String field : fieldNames(example.request())) {
            var missing = example.request().deepCopy(); missing.remove(field);
            mvc.perform(write(example).content(missing.toString())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reader);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.0", "1.5", "\"1\"", "null", "true", "9007199254740992", "9223372036854775808"})
    void versionsArePositiveJsonIntegersWithinJavascriptSafeRange(String value) throws Exception {
        for (var example : writes().filter(w -> w.request().has("expected_version")).toList()) {
            var input = example.request().deepCopy(); input.set("expected_version", JSON.readTree(value));
            mvc.perform(write(example).content(input.toString())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reader);
    }

    @Test void maximumSafeVersionIsPassedExactlyWithoutRounding() throws Exception {
        var example = writes().filter(w -> w.name().equals("acknowledge")).findFirst().orElseThrow();
        var input = example.request().deepCopy().put("expected_version", 9_007_199_254_740_991L);
        mvc.perform(write(example).content(input.toString())).andExpect(status().isOk());
        verify(reader).transitionEvent(any(), eq(new TransitionEventCommand(EVENT, 9_007_199_254_740_991L, "ACKNOWLEDGE", null)));
    }

    @ParameterizedTest @ValueSource(strings = {"{}{}", "{\"expected_version\":1,\"expected_version\":2}", "[]", "null", "{broken"})
    void malformedJsonIsRejectedBeforeRepository(String body) throws Exception {
        mvc.perform(write(writes().findFirst().orElseThrow()).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void requestTypesEnumsDatesUuidAndMediaTypeAreChecked() throws Exception {
        var ingest = writes().findFirst().orElseThrow();
        for (ObjectNode body : List.of(ingest.request().deepCopy().put("severity", "CRITICAL"),
            ingest.request().deepCopy().put("source_phase", "OK"), ingest.request().deepCopy().put("simulate_autoticket_failure", "false"),
            ingest.request().deepCopy().put("asset_id", "not-a-uuid"), ingest.request().deepCopy().put("occurred_at", "yesterday"),
            ingest.request().deepCopy().put("rule_name", " "), ingest.request().deepCopy().put("rule_code", "x".repeat(65))))
            mvc.perform(write(ingest).content(body.toString())).andExpect(status().isBadRequest());
        mvc.perform(write(ingest).contentType("text/plain")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(scoped(get(BASE + "/events/not-a-uuid"))).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void quarantineDecisionsUseCommandVocabularyAndNullableBoundAsset() throws Exception {
        var example = writes().filter(w -> w.name().equals("discard_quarantine")).findFirst().orElseThrow();
        for (ObjectNode input : List.of(example.request().deepCopy().put("decision", "BOUND"),
            example.request().deepCopy().put("decision", "DISCARDED"), example.request().deepCopy().put("decision", "BIND_ASSET"),
            example.request().deepCopy().put("bound_asset_id", ASSET)))
            mvc.perform(write(example).content(input.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void textBoundariesPreserveFullSourceIdsAndRootCauseNotes() throws Exception {
        var ingest = writes().findFirst().orElseThrow();
        String source = "s".repeat(256), ruleName = "名".repeat(128);
        var input = ingest.request().deepCopy().put("source_event_id", source).put("rule_name", ruleName);
        mvc.perform(write(ingest).content(input.toString())).andExpect(status().isOk());
        verify(reader).ingestEvent(any(), eq(new IngestEventCommand(ASSET, "SYNTHETIC_RULE_0", ruleName, Severity.DISASTER, MonitorState.PROBLEM, source, NOW, false)));
        clearInvocations(reader);
        for (ObjectNode invalid : List.of(input.deepCopy().put("source_event_id", source + "s"), input.deepCopy().put("rule_name", ruleName + "名")))
            mvc.perform(write(ingest).content(invalid.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
        var resolve = writes().filter(w -> w.name().equals("resolve")).findFirst().orElseThrow();
        String note = "根".repeat(500);
        mvc.perform(write(resolve).content(resolve.request().deepCopy().put("root_cause_note", note).toString())).andExpect(status().isOk());
        verify(reader).transitionEvent(any(), eq(new TransitionEventCommand(EVENT, 1, "RESOLVE", note)));
        clearInvocations(reader);
        mvc.perform(write(resolve).content(resolve.request().deepCopy().put("root_cause_note", note + "根").toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void idempotencyHeaderMustBeExactlyOneValidBoundedValue() throws Exception {
        var example = writes().findFirst().orElseThrow();
        for (String value : List.of("short", "contains space", "x".repeat(129)))
            mvc.perform(scoped(post(example.path())).with(csrf().asHeader()).contentType("application/json")
                .header("Idempotency-Key", value).content(example.request().toString())).andExpect(status().isBadRequest());
        mvc.perform(scoped(post(example.path())).with(csrf().asHeader()).contentType("application/json")
            .content(example.request().toString())).andExpect(status().isBadRequest());
        mvc.perform(write(example).header("Idempotency-Key", "second-valid-key")).andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
    }

    @Test void nullableReadFieldsAreSerializedExplicitlyAndNotRenamed() throws Exception {
        var result = response(goodGet(BASE + "/events", "workspace"));
        var first = result.path("data").path("events").get(0);
        for (String key : List.of("flap_silenced_until", "suppression_reason", "recovered_at", "first_ack_at", "resolved_at", "closed_at", "root_cause_note"))
            assertTrue(first.has(key) && first.get(key).isNull(), key);
        assertFalse(first.has("merged_count")); assertFalse(first.has("flap_suppressed_until"));
        assertFalse(result.path("data").has("review_policy_status"));
        assertFalse(result.path("data").has("mapping_policy_status"));
        when(reader.eventDetail(any(), anyString())).thenReturn(detail(false));
        goodGet(EVENT_PATH, "eventDetail").andExpect(jsonPath("$.data.autoticket_attempt").value(nullValue()));
    }

    @Test void crossTenantProjectionAndRepositoryFailureNeverLeakData() throws Exception {
        var sample = workspace("24h");
        when(reader.workspace(any(), anyString(), anyInt())).thenReturn(new EventCenterWorkspace(id(999), sample.events(), sample.noiseFunnel(), sample.maintenanceWindows(), sample.quarantineItems(), sample.autoTicketAttempts(), NOW));
        mvc.perform(scoped(get(BASE + "/events"))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE")).andExpect(jsonPath("$.data").doesNotExist());
        when(reader.eventDetail(any(), anyString())).thenThrow(new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该监控事件"));
        mvc.perform(scoped(get(EVENT_PATH))).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        when(reader.noiseFunnel(any(), anyString())).thenThrow(new IllegalStateException("SYNTHETIC_SECRET_JDBC"));
        mvc.perform(scoped(get(BASE + "/events/noise-funnel"))).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR")).andExpect(content().string(not(containsString("SYNTHETIC_SECRET_JDBC"))));
    }

    @Test void committedCrossLanguageGoldenEqualsActualHttpSerialization() throws Exception {
        var output = JSON.createObjectNode();
        output.put("generator", "MonitoringEventCenterHttpContractTest: actual Spring MVC + production remote_test security; synthetic repository projections; trace_id normalized only");
        var workspaceResponse = response(goodGet(BASE + "/events", "workspace"));
        var detailResponse = response(goodGet(EVENT_PATH, "eventDetail"));
        var funnelResponse = response(goodGet(BASE + "/events/noise-funnel", "noiseFunnel"));
        output.set("workspace", workspaceResponse); output.set("detail", detailResponse); output.set("noise_funnel", funnelResponse);
        var exchanges = output.putArray("exchanges");
        exchanges.add(exchange("workspace", "GET", BASE + "/events", null, "MonitoringEventWorkspaceResult", NullNode.instance, workspaceResponse));
        exchanges.add(exchange("detail", "GET", EVENT_PATH, null, "MonitoringEventDetailResult", NullNode.instance, detailResponse));
        exchanges.add(exchange("noise_funnel", "GET", BASE + "/events/noise-funnel", null, "MonitoringNoiseFunnelResult", NullNode.instance, funnelResponse));
        when(reader.eventDetail(any(), anyString())).thenReturn(detail(false));
        exchanges.add(exchange("detail_without_attempt", "GET", EVENT_PATH, null, "MonitoringEventDetailResult", NullNode.instance, response(goodGet(EVENT_PATH, "eventDetail"))));
        for (var example : writes().toList()) exchanges.add(exchange(example.name(), "POST", example.path(), example.requestSchema(), example.responseSchema(), example.request(), response(goodWrite(example))));
        var enums = output.putObject("enums");
        enumNames(enums, "severity", Severity.values()); enumNames(enums, "monitor_state", MonitorState.values());
        enumNames(enums, "handling_state", HandlingState.values()); enumNames(enums, "suppression_state", SuppressionState.values());
        enumNames(enums, "notification_empty_state", NotificationEmptyState.values()); enumNames(enums, "noise_layer", NoiseLayer.values());
        enumNames(enums, "autoticket_status", AutoTicketStatus.values());
        var canonical = canonical(output);
        Path file = fixturePath();
        if ("write".equals(System.getProperty("mop.eventCenterFixtures"))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(canonical) + "\n", StandardCharsets.UTF_8);
        }
        assertTrue(Files.isRegularFile(file), "Missing fixture: explicitly export with -Dmop.eventCenterFixtures=write");
        assertEquals(canonical, JSON.readTree(Files.readString(file)), "HTTP JSON golden drift: review then explicitly re-export; normal tests never overwrite the fixture");
    }

    private static Access accessFor(String actor, long version) {
        return argThat(a -> a != null && TENANT.equals(a.tenantId()) && actor.equals(a.actorId()) && version == a.permissionVersion()
            && a.traceId() != null && a.traceId().matches("[0-9a-f-]{36}"));
    }
    private static Set<String> fieldNames(JsonNode object) { var keys = new LinkedHashSet<String>(); object.fieldNames().forEachRemaining(keys::add); return keys; }
    private static JsonNode response(ResultActions result) throws Exception {
        var http = result.andReturn().getResponse();
        var node = (ObjectNode) JSON.readTree(http.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(Set.of("data", "trace_id"), fieldNames(node));
        assertDoesNotThrow(() -> UUID.fromString(node.path("trace_id").asText()));
        assertEquals(http.getHeader("X-Trace-Id"), node.path("trace_id").asText());
        node.put("trace_id", TRACE);
        return node;
    }
    private static ObjectNode exchange(String name, String method, String path, String requestSchema, String responseSchema, JsonNode request, JsonNode response) {
        var node = JSON.createObjectNode(); node.put("name", name); node.put("method", method); node.put("path", path);
        node.put("request_schema", requestSchema); node.put("response_schema", responseSchema); node.set("request", request); node.set("response", response); return node;
    }
    private static void enumNames(ObjectNode root, String field, Enum<?>[] values) { var list = root.putArray(field); for (var value : values) list.add(value.name()); }
    private static JsonNode canonical(JsonNode input) {
        if (input.isArray()) { var a = JSON.createArrayNode(); input.forEach(value -> a.add(canonical(value))); return a; }
        if (!input.isObject()) return input;
        var result = JSON.createObjectNode(); new TreeSet<>(fieldNames(input)).forEach(key -> result.set(key, canonical(input.get(key)))); return result;
    }
    private static Path fixturePath() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null) {
            if (Files.isDirectory(path.resolve("server/api")) && Files.isDirectory(path.resolve("fixtures")))
                return path.resolve("fixtures/monitoring-event-center-controller.json");
            path = path.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
    private static ObjectNode object(Object... pairs) {
        var node = JSON.createObjectNode(); for (int i = 0; i < pairs.length; i += 2) node.set((String) pairs[i], JSON.valueToTree(pairs[i + 1])); return node;
    }
    record WriteCase(String name, String path, String handler, String actor, String requestSchema, String responseSchema, ObjectNode request, Object command) {
        @Override public String toString() { return name; }
    }
    static Stream<WriteCase> writes() {
        var examples = new ArrayList<WriteCase>();
        examples.add(new WriteCase("ingest", BASE + "/events/ingest", "ingest", ACTOR, "MonitoringIngestEventInput", "MonitoringEventSummaryResult",
            object("asset_id", ASSET, "rule_code", "SYNTHETIC_RULE_0", "rule_name", "合成监控规则 0", "severity", "DISASTER", "source_phase", "PROBLEM", "source_event_id", "synthetic-source-1", "occurred_at", NOW.toString(), "simulate_autoticket_failure", false),
            new IngestEventCommand(ASSET, "SYNTHETIC_RULE_0", "合成监控规则 0", Severity.DISASTER, MonitorState.PROBLEM, "synthetic-source-1", NOW, false)));
        examples.add(new WriteCase("ingest_nullable_time", BASE + "/events/ingest", "ingest", ACTOR, "MonitoringIngestEventInput", "MonitoringEventSummaryResult",
            object("asset_id", ASSET, "rule_code", "SYNTHETIC_RULE_0", "rule_name", "合成监控规则 0", "severity", "DISASTER", "source_phase", "PROBLEM", "source_event_id", "synthetic-source-2", "occurred_at", null, "simulate_autoticket_failure", true),
            new IngestEventCommand(ASSET, "SYNTHETIC_RULE_0", "合成监控规则 0", Severity.DISASTER, MonitorState.PROBLEM, "synthetic-source-2", null, true)));
        for (String action : List.of("ACKNOWLEDGE", "START", "MARK_PENDING_REVIEW", "RESOLVE", "CLOSE")) {
            String note = action.equals("RESOLVE") ? "合成测试人工确认设备故障根因并完成修复，复测网络连接稳定" : null;
            examples.add(new WriteCase(action.toLowerCase(Locale.ROOT), EVENT_PATH + "/transitions", "transition", ACTOR, "MonitoringTransitionEventInput", "MonitoringEventSummaryResult",
                object("expected_version", 1, "action", action, "root_cause_note", note), new TransitionEventCommand(EVENT, 1, action, note)));
        }
        examples.add(new WriteCase("create_maintenance", BASE + "/maintenance-windows", "createMaintenanceWindow", ACTOR, "CreateMaintenanceWindowInput", "MaintenanceWindowResult",
            object("name", "合成维护窗口", "asset_id", ASSET, "starts_at", NOW.toString(), "ends_at", NOW.plusSeconds(3600).toString(), "silence_mode", "SUPPRESS_NOTIFY", "sla_exclusion_requested", true, "reason", "合成测试维护原因"),
            new CreateMaintenanceWindowCommand("合成维护窗口", ASSET, NOW, NOW.plusSeconds(3600), "SUPPRESS_NOTIFY", true, "合成测试维护原因")));
        for (String action : List.of("EXTEND", "END", "CONFIRM_SLA_EXCLUSION")) {
            Instant end = action.equals("EXTEND") ? NOW.plusSeconds(10800) : null;
            examples.add(new WriteCase(action.toLowerCase(Locale.ROOT), WINDOW_PATH, "updateMaintenanceWindow", action.equals("CONFIRM_SLA_EXCLUSION") ? ADMIN : ACTOR,
                "UpdateMaintenanceWindowInput", "MaintenanceWindowResult", object("expected_version", 1, "action", action, "new_ends_at", end == null ? null : end.toString()),
                new UpdateMaintenanceWindowCommand(WINDOW, 1, action, end)));
        }
        for (String decision : List.of("BIND_ASSET", "DISCARD")) {
            String asset = decision.equals("BIND_ASSET") ? ASSET : null;
            examples.add(new WriteCase(decision.equals("BIND_ASSET") ? "bind_quarantine" : "discard_quarantine", QUARANTINE_PATH, "decideQuarantine", ACTOR,
                "MonitoringQuarantineDecisionInput", "MonitoringQuarantineDecisionResult", object("decision", decision, "bound_asset_id", asset, "reason", "合成测试人工核验原因"),
                new QuarantineDecisionCommand(QUARANTINE, decision, asset, "合成测试人工核验原因")));
        }
        examples.add(new WriteCase("manual_create", ATTEMPT_PATH, "manualCreateAutoTicket", ACTOR, "ManualAutoTicketInput", "AutoTicketAttemptResult",
            object("expected_version", 3, "reason", "合成测试人工补建原因"), new ManualAutoTicketCommand(ATTEMPT, 3, "合成测试人工补建原因")));
        return examples.stream();
    }

    private static EventSummary transitioned(TransitionEventCommand command) {
        var e = event(0);
        var state = switch (command.action()) {
            case "ACKNOWLEDGE" -> HandlingState.ACKNOWLEDGED;
            case "START" -> HandlingState.IN_PROGRESS;
            case "MARK_PENDING_REVIEW" -> HandlingState.PENDING_REVIEW;
            case "RESOLVE" -> HandlingState.RESOLVED;
            case "CLOSE" -> HandlingState.CLOSED;
            default -> throw new AssertionError("Unexpected synthetic command");
        };
        return new EventSummary(command.eventId(), e.tenantId(), e.assetId(), command.expectedVersion() + 1,
            e.ruleCode(), e.ruleName(), e.severity(), e.severityRank(), e.fingerprint(), e.occurrenceCount(), e.flapFlipCount(), e.flapSilencedUntil(),
            state.ordinal() >= HandlingState.PENDING_REVIEW.ordinal() ? MonitorState.RECOVERED : e.monitorState(), state,
            e.suppressionState(), e.suppressionReason(), e.notificationEmptyState(), e.notificationEmptyStateLabel(), e.slaUnknownMarked(), e.reviewPolicyStatus(),
            e.problemAt(), e.lastOccurredAt(), state.ordinal() >= 3 ? NOW : null, NOW, state.ordinal() >= 4 ? NOW : null,
            state == HandlingState.CLOSED ? NOW : null, state == HandlingState.CLOSED ? "合成测试人工确认根因" : command.rootCauseNote(), e.ticketId(), ACTOR, NOW);
    }
    private static MaintenanceWindow createdWindow(CreateMaintenanceWindowCommand c) {
        return new MaintenanceWindow(WINDOW, TENANT, 1, c.name(), c.assetId(), c.startsAt(), c.endsAt(), c.silenceMode(), "ACTIVE",
            c.slaExclusionRequested(), false, null, null, c.reason(), ACTOR, ACTOR, NOW);
    }
    private static MaintenanceWindow updatedWindow(Access access, UpdateMaintenanceWindowCommand c) {
        var w = window(0); boolean confirmed = c.action().equals("CONFIRM_SLA_EXCLUSION");
        return new MaintenanceWindow(c.windowId(), TENANT, c.expectedVersion() + 1, w.name(), w.assetId(), w.startsAt(), c.newEndsAt() == null ? w.endsAt() : c.newEndsAt(),
            w.silenceMode(), c.action().equals("END") ? "ENDED" : w.status(), true, confirmed, confirmed ? access.actorId() : null,
            confirmed ? NOW : null, w.reason(), w.createdBy(), access.actorId(), NOW);
    }
    private static QuarantineItem decided(QuarantineDecisionCommand c) {
        var q = quarantine(0);
        return new QuarantineItem(c.quarantineId(), q.poolId(), q.sourceEventId(), q.sourcePhase(), q.claimedTenantId(), q.claimedAssetId(), q.quarantineReason(), q.occurredAt(),
            q.receivedAt(), q.quarantineStatus(), c.decision(), c.boundAssetId(), c.decision().equals("BIND_ASSET") ? EVENT : null, c.reason(), ACTOR, NOW);
    }
    private static AutoTicketAttempt manualAttempt(ManualAutoTicketCommand c) {
        var a = attempt(2);
        return new AutoTicketAttempt(c.attemptId(), TENANT, a.eventId(), c.expectedVersion() + 1, a.attemptCount() + 1, a.maxRetries(), a.backoffScheduleSec(),
            AutoTicketStatus.MANUAL_CREATED, a.mappedPriority(), a.mappingPolicyStatus(), id(599), null, "人工补建成功: " + c.reason(), ACTOR, NOW);
    }
}
