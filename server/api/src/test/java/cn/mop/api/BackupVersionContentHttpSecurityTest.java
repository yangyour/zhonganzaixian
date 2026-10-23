package cn.mop.api;

import cn.mop.adapters.backup.RedactedBackupDiff;
import cn.mop.domain.Asset;
import cn.mop.domain.Membership;
import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ActiveProfiles("local_fixture")
@ContextConfiguration(classes = BackupVersionContentHttpSecurityTest.Configuration.class)
class BackupVersionContentHttpSecurityTest {
    static final String TENANT = UUID.randomUUID().toString(), ACTOR = UUID.randomUUID().toString();
    static final String ASSET = UUID.randomUUID().toString(), VERSION = UUID.randomUUID().toString();
    static final String ROOT = "/api/v1/assets/" + ASSET + "/backup-versions";

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    static class Configuration implements WebMvcConfigurer {
        @Bean
        PlatformRepository platform() { return mock(PlatformRepository.class); }
        @Bean
        BackupVersionContentService service() { return mock(BackupVersionContentService.class); }
        @Bean
        ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean
        BackupVersionContentController controller(PlatformRepository platform, BackupVersionContentService service) {
            return new BackupVersionContentController(platform, service);
        }
        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(mapper()));
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    PlatformRepository platform;
    @Autowired
    BackupVersionContentService service;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        reset(platform, service);
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member("padmin", 7)));
        when(platform.authorizedAsset(eq(TENANT), eq(ACTOR), anyLong(), eq(ASSET))).thenReturn(Optional.of(mock(Asset.class)));
    }

    @Test
    void binaryReadIsPrivateAttachmentAndCustomerRolesAreDenied() throws Exception {
        when(service.download(any(), any(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(new BackupVersionContentService.Content(new byte[]{1, 2, 3}, UUID.fromString(VERSION), false));
        mvc.perform(scoped(get(ROOT + "/" + VERSION + "/content")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"backup-" + VERSION + ".envelope\""));
        for (String role : List.of("tadmin", "viewer")) {
            reset(service);
            when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member(role, 7)));
            mvc.perform(scoped(get(ROOT + "/" + VERSION + "/content?format=plaintext"))).andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }

    @Test
    void sessionChangeSuppressesAndDisposesPendingPlaintext() throws Exception {
        byte[] bytes = "SYNTHETIC_PRIVATE_CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.download(any(), any(), any(), anyBoolean(), anyString(), any()))
                .thenReturn(new BackupVersionContentService.Content(bytes, UUID.fromString(VERSION), true));
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(member("padmin", 7)), Optional.of(member("padmin", 8)));
        String body = mvc.perform(scoped(get(ROOT + "/" + VERSION + "/content?format=plaintext")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SYNTHETIC_PRIVATE_CONTENT"));
        assertArrayEquals(new byte[bytes.length], bytes);
    }

    @Test
    void diffReadsReturnOnlyRedactedSummaryAndWritesRemainProtectedAndUnsupported() throws Exception {
        String json = "{\"before_version_id\":\"" + VERSION + "\",\"after_version_id\":\"" + VERSION + "\"}";
        mvc.perform(scoped(post(ROOT + "/diff").contentType("application/json").content(json))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        mvc.perform(scoped(post(ROOT + "/diff").contentType("application/json").content(json)).with(csrf().asHeader()))
                .andExpect(status().isMethodNotAllowed());
        when(service.compare(any(), any(), any(), any(), anyString(), any()))
                .thenReturn(new RedactedBackupDiff.Summary(RedactedBackupDiff.Status.IDENTICAL, 1, 1, List.of()));
        mvc.perform(scoped(get(ROOT + "/diff?before_version_id=" + VERSION + "&after_version_id=" + VERSION)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.redaction").value("ALL_TEXT_WITHHELD"))
                .andExpect(jsonPath("$.data.automatic_ticket_allowed").value(false)).andExpect(jsonPath("$.data.content").doesNotExist());
    }

    @Test
    void anonymousForeignAssetAndInjectedQueryNeverReachContentProvider() throws Exception {
        mvc.perform(get(ROOT + "/" + VERSION + "/content")).andExpect(status().isUnauthorized());
        mvc.perform(scoped(get(ROOT + "/" + VERSION + "/content?format=envelope&tenant_id=" + TENANT)))
                .andExpect(status().isBadRequest());
        when(platform.authorizedAsset(eq(TENANT), eq(ACTOR), anyLong(), eq(ASSET))).thenReturn(Optional.empty());
        mvc.perform(scoped(get(ROOT + "/" + VERSION + "/content"))).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void boundedDiffQueryRejectsDuplicateFieldsAndOversizeBeforeService() throws Exception {
        mvc.perform(scoped(get(ROOT + "/diff?before_version_id=" + VERSION + "&before_version_id=" + VERSION + "&after_version_id=" + VERSION)))
                .andExpect(status().isBadRequest());
        mvc.perform(scoped(get(ROOT + "/diff?before_version_id=" + "a".repeat(2049) + "&after_version_id=" + VERSION)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private Membership member(String role, long version) {
        return new Membership(ACTOR, TENANT, role, version, true, List.of("assets:read"));
    }

    private MockHttpServletRequestBuilder scoped(MockHttpServletRequestBuilder request) {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute("mop.tenant_id", TENANT);
        return request.session(session).with(user(ACTOR));
    }
}
