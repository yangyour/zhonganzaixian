package cn.mop.api;

import cn.mop.domain.AttachmentSecurityRepository;
import cn.mop.domain.Membership;
import cn.mop.domain.PhotoObjectStore;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real identity filter and binary converter with isolated storage; no real accounts, files or component writes. */
class FileContentReturnAuthorizationTest {
    private static final String ACTOR = "7b000000-0000-4000-8000-000000000001";
    private static final String TENANT = "7b000000-0000-4000-8000-000000000002";
    private static final String FILE = "7b000000-0000-4000-8000-000000000003";
    private static final String GRANT = "7b000000-0000-4000-8000-000000000004";
    private static final String PRIVATE_CONTENT = "SYNTHETIC_PRIVATE_IMAGE_BYTES";
    private static final String PATH = "/api/v1/files/" + FILE + "/content";

    private final PlatformRepository platform = mock(PlatformRepository.class);
    private final PhotoObjectStore storage = mock(PhotoObjectStore.class);
    private final AttachmentSecurityRepository security = mock(AttachmentSecurityRepository.class);
    private final ProjectIdentityRepository accounts = mock(ProjectIdentityRepository.class);
    private final MockHttpSession session = new MockHttpSession();
    private final byte[] bytes = PRIVATE_CONTENT.getBytes(StandardCharsets.UTF_8);
    private Runnable duringStorageRead = () -> {};
    private MockMvc mvc;

    @BeforeEach
    void prepare() throws Exception {
        session.setAttribute("mop.tenant_id", TENANT);
        when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.of(new Membership(ACTOR, TENANT, "viewer", 7, true)));
        when(accounts.sessionState(ACTOR)).thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(5, false)));
        when(security.requireReleased(any(), eq(FILE))).thenReturn(4L);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Map<String, Object> location = Map.of("object_key", "photos/" + TENANT + "/" + FILE,
                "byte_size", (long) bytes.length, "sha256", sha, "media_type", "image/png", "original_name", "synthetic.png");
        when(platform.photoContent(TENANT, ACTOR, FILE, GRANT)).thenReturn(location);
        when(storage.read(anyString(), anyLong(), anyString())).thenAnswer(call -> {
            duringStorageRead.run();
            return bytes.clone();
        });
        var mapper = new ObjectMapper();
        var controller = new FileController(platform, new PhotoPolicy("image/jpeg,image/png"), storage, mapper);
        ReflectionTestUtils.setField(controller, "attachmentSecurity", security);
        var filter = new ProjectSessionValidationFilter(new ProjectIdentityService(accounts, mapper, "01".repeat(32), false));
        authenticate();
        // No JSON return advice is installed: the file controller must protect its byte response itself.
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(platform))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(filter)
                .build();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new ProjectIdentityService.LocalPrincipal(ACTOR, 5), null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void stableDownloadChecksIdentityAndReleaseAgainBeforeSuccessfulAudit() throws Exception {
        mvc.perform(get(PATH).session(session).principal(() -> ACTOR).param("grant", GRANT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(accounts, times(2)).sessionState(ACTOR);
        verify(platform, times(2)).photoContent(TENANT, ACTOR, FILE, GRANT);
        verify(security, times(2)).requireReleased(any(), eq(FILE));
        verify(platform).audit(eq(ACTOR), eq(TENANT), eq("file.content.read"), eq("file"), eq(FILE),
                eq("SUCCEEDED"), eq("ACTIVE_SCAN_RELEASE_AND_ORIGINAL_BYTES_VERIFIED"), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"account_revision", "account_removed", "session_id", "session_invalidated", "authentication", "tenant", "membership"})
    void revokedReadReturnsNoBytesOrSuccessfulReadAudit(String change) throws Exception {
        duringStorageRead = () -> {
            switch (change) {
                case "account_revision" -> when(accounts.sessionState(ACTOR))
                        .thenReturn(Optional.of(new ProjectIdentityRepository.SessionState(6, false)));
                case "account_removed" -> when(accounts.sessionState(ACTOR)).thenReturn(Optional.empty());
                case "session_id" -> session.changeSessionId();
                case "session_invalidated" -> session.invalidate();
                case "authentication" -> authenticate();
                case "tenant" -> session.setAttribute("mop.tenant_id", GRANT);
                case "membership" -> when(platform.membership(ACTOR, TENANT)).thenReturn(Optional.empty());
                default -> throw new AssertionError(change);
            }
        };
        var result = mvc.perform(get(PATH).session(session).principal(() -> ACTOR).param("grant", GRANT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_EXPIRED"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_CONTENT));
        assertNoSuccessfulReadAudit();
    }

    @Test
    void unavailableAccountRecheckDoesNotReleaseBytesOrLogSuccess() throws Exception {
        duringStorageRead = () -> when(accounts.sessionState(ACTOR)).thenThrow(new IllegalStateException("SYNTHETIC_PRIVATE_DEPENDENCY"));
        var result = mvc.perform(get(PATH).session(session).principal(() -> ACTOR).param("grant", GRANT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_UNAVAILABLE"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_CONTENT));
        assertFalse(result.getResponse().getContentAsString().contains("SYNTHETIC_PRIVATE_DEPENDENCY"));
        assertNoSuccessfulReadAudit();
    }

    @Test
    void changedReleaseStillRejectsBeforeAccountGuardAndSuccessfulAudit() throws Exception {
        duringStorageRead = () -> when(security.requireReleased(any(), eq(FILE))).thenReturn(5L);
        mvc.perform(get(PATH).session(session).principal(() -> ACTOR).param("grant", GRANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        verify(accounts).sessionState(ACTOR);
        assertNoSuccessfulReadAudit();
    }

    private void assertNoSuccessfulReadAudit() {
        verify(platform, never()).audit(anyString(), anyString(), eq("file.content.read"), anyString(), anyString(),
                eq("SUCCEEDED"), anyString(), anyString());
    }
}
