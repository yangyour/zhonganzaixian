package cn.mop.api;

import cn.mop.domain.AttachmentSecurityRepository;
import cn.mop.domain.DomainException;
import cn.mop.domain.Membership;
import cn.mop.domain.PhotoObjectStore;
import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real MVC/CSRF chain with isolated repository doubles, separate from designated PostgreSQL evidence. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles({"local_fixture","isolated_attachment_security_http"})
@ContextConfiguration(classes = AttachmentSecurityHttpTest.Configuration.class)
class AttachmentSecurityHttpTest {
    private static final String TENANT = "11000000-0000-4000-8000-000000000001";
    private static final String ACTOR = "11000000-0000-4000-8000-000000000002";
    private static final String FILE = "11000000-0000-4000-8000-000000000003";
    private static final String BASE = "/api/v1/files/" + FILE + "/security";

    @org.springframework.context.annotation.Profile("isolated_attachment_security_http")
    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean
        PlatformRepository repository() {
            return mock(PlatformRepository.class);
        }
        @Bean
        AttachmentSecurityRepository security() {
            return mock(AttachmentSecurityRepository.class);
        }
        @Bean
        PhotoObjectStore store() {
            return mock(PhotoObjectStore.class);
        }
        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        }
        @Bean
        AttachmentSecurityController controller(PlatformRepository platform, AttachmentSecurityRepository security) {
            return new AttachmentSecurityController(platform, security);
        }
        @Bean
        FileController files(PlatformRepository platform, PhotoObjectStore store, ObjectMapper mapper) {
            return new FileController(platform, new PhotoPolicy("image/jpeg,image/png"), store, mapper);
        }
        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(mapper()));
            converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformRepository platform;
    @Autowired
    private AttachmentSecurityRepository security;
    @Autowired
    private PhotoObjectStore store;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, security, store);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "padmin", 8, true)));
        when(security.current(any(), eq(FILE))).thenReturn(snapshot("QUARANTINED", 0, true));
        when(platform.idempotentAuthorized(anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), any(), any()))
            .thenAnswer(call -> {
                call.getArgument(6, Runnable.class).run();
                return call.getArgument(7, Supplier.class).get();
            });
    }

    private AttachmentSecurityRepository.Snapshot snapshot(String state, long version, boolean manager) {
        return new AttachmentSecurityRepository.Snapshot(FILE, TENANT, version, state, null, "a".repeat(64),
            null, null, null, false, false, manager, true, "NOT_CONFIGURED");
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder builder) {
        var session = new MockHttpSession();
        session.setAttribute("mop.tenant_id", TENANT);
        return builder.session(session).with(user(ACTOR)).header("Idempotency-Key", "unit-attachment-security");
    }

    @Test
    void unauthenticatedReadsAndCsrfFreeWritesAreRejected() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(scoped(post(BASE + "/actions").contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"REQUEST_SCAN\",\"expected_version\":0}"))).andExpect(status().isForbidden());
        verify(security, never()).change(any(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void readsKeepUnconfiguredAndQuarantinedStateVisible() throws Exception {
        mvc.perform(scoped(get(BASE))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("QUARANTINED"))
            .andExpect(jsonPath("$.data.scanner_status").value("NOT_CONFIGURED"))
            .andExpect(jsonPath("$.data.content_allowed").value(false));
    }

    @Test
    void requestIsBoundToSessionVersionAndDurableIdempotencyBoundary() throws Exception {
        when(security.change(any(), eq(FILE), eq(0L), eq(AttachmentSecurityRepository.Action.REQUEST_SCAN), anyString()))
            .thenReturn(snapshot("QUEUED", 1, true));
        mvc.perform(scoped(post(BASE + "/actions").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"REQUEST_SCAN\",\"expected_version\":0}"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value("QUEUED"));
        verify(platform).idempotentAuthorized(eq(ACTOR), eq(TENANT), eq(BASE + "/actions"), eq("unit-attachment-security"),
            matches("[a-f0-9]{64}"), eq(8L), any(), any());
    }

    @Test
    void publicRequestsCannotForgeScannerEvidenceOrIdentity() throws Exception {
        for (String body : List.of("{\"action\":\"SCAN_RESULT\",\"expected_version\":0}",
            "{\"action\":\"RELEASE\",\"expected_version\":0,\"scan_status\":\"CLEAN\"}",
            "{\"action\":\"REQUEST_SCAN\",\"expected_version\":0,\"actor_id\":\"forged\"}",
            "{\"action\":\"REQUEST_SCAN\",\"expected_version\":0.1}",
            "{\"action\":\"REQUEST_SCAN\",\"expected_version\":0,\"expected_version\":1}")) {
            mvc.perform(scoped(post(BASE + "/actions").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(body))).andExpect(status().isBadRequest());
        }
        verify(security, never()).recordScan(any(), anyString(), anyLong(), any(), anyString());
        verify(security, never()).change(any(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void visibilityAndReleaseAuthorizationAreRecheckedBeforeCommandReplay() throws Exception {
        when(security.current(any(), eq(FILE))).thenReturn(snapshot("CLEAN", 2, false));
        mvc.perform(scoped(post(BASE + "/actions").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"RELEASE\",\"expected_version\":2}"))).andExpect(status().isForbidden());
        when(security.current(any(), eq(FILE))).thenThrow(new DomainException(404, "OBJECT_NOT_FOUND", "不可见"));
        mvc.perform(scoped(get(BASE))).andExpect(status().isNotFound());
        mvc.perform(scoped(post(BASE + "/actions").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"REQUEST_SCAN\",\"expected_version\":0}"))).andExpect(status().isNotFound());
        verify(security, never()).change(any(), anyString(), anyLong(), any(), anyString());
    }

    @Test
    void quarantinedContentCannotCreateGrantOrReadObjectStorage() throws Exception {
        when(security.requireReleased(any(), eq(FILE))).thenThrow(new DomainException(422, "ATTACHMENT_QUARANTINED", "隔离"));
        mvc.perform(scoped(post("/api/v1/files/" + FILE + "/content-grants").with(csrf().asHeader())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isUnprocessableEntity());
        verify(platform, never()).contentGrant(anyString(), anyString(), anyString());
        verifyNoInteractions(store);
    }

    @Test
    void releaseRevokedDuringStorageReadNeverReturnsBytes() throws Exception {
        String grant = "11000000-0000-4000-8000-000000000004";
        Map<String, Object> location = Map.of("object_key", "private-unit", "byte_size", 1L, "sha256", "a".repeat(64),
            "media_type", "image/png", "original_name", "unit.png");
        when(platform.photoContent(TENANT, ACTOR, FILE, grant)).thenReturn(location);
        when(store.read("private-unit", 1L, "a".repeat(64))).thenReturn(new byte[]{1});
        when(security.requireReleased(any(), eq(FILE))).thenReturn(3L)
            .thenThrow(new DomainException(422, "ATTACHMENT_QUARANTINED", "已撤销"));
        mvc.perform(scoped(get("/api/v1/files/" + FILE + "/content").param("grant", grant)))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("ATTACHMENT_QUARANTINED"));
    }

    @Test
    void stateConflictRemainsExplicitAndDoesNotBecomeSuccess() throws Exception {
        when(security.change(any(), eq(FILE), anyLong(), any(), anyString()))
            .thenThrow(new DomainException(409, "VERSION_CONFLICT", "状态变化"));
        mvc.perform(scoped(post(BASE + "/actions").with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"CANCEL_SCAN\",\"expected_version\":2}"))).andExpect(status().isConflict());
    }
}
