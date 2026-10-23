package cn.mop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/** Isolated configuration/request tests, with no database, login or external TLS server. */
class SecureSessionTransportTest {
    private MockEnvironment safeSettings() {
        return new MockEnvironment()
            .withProperty("server.ssl.enabled", "true")
            .withProperty("server.ssl.enabled-protocols", "TLSv1.2,TLSv1.3")
            .withProperty("server.servlet.session.cookie.secure", "true")
            .withProperty("server.servlet.session.cookie.http-only", "true")
            .withProperty("server.servlet.session.cookie.same-site", "strict")
            .withProperty("server.servlet.session.cookie.name", "MOP_SESSION")
            .withProperty("server.servlet.session.cookie.path", "/")
            .withProperty("server.servlet.session.tracking-modes", "cookie")
            .withProperty("server.forward-headers-strategy", "none");
    }

    @Test
    void directTlsSettingsAreAcceptedWithoutReadingCertificateOrPassword() {
        assertThatCode(() -> SecureSessionConfiguration.requireSafeSettings(safeSettings())).doesNotThrowAnyException();
    }

    @Test
    void weakeningAnySessionOrTransportSettingFailsClosed() {
        String[][] invalid = {
            {"server.ssl.enabled", "false"}, {"server.ssl.enabled-protocols", "TLSv1.1,TLSv1.2"},
            {"server.servlet.session.cookie.secure", "false"}, {"server.servlet.session.cookie.http-only", "false"},
            {"server.servlet.session.cookie.same-site", "lax"}, {"server.servlet.session.cookie.path", "/api"},
            {"server.servlet.session.cookie.name", "OTHER"}, {"server.servlet.session.cookie.domain", ".example.test"},
            {"server.servlet.session.cookie.max-age", "30m"}, {"server.servlet.session.tracking-modes", "cookie,url"},
            {"server.forward-headers-strategy", "framework"}, {"server.forward-headers-strategy", "native"}
        };
        for (String[] item : invalid) {
            assertThatThrownBy(() -> SecureSessionConfiguration.requireSafeSettings(safeSettings().withProperty(item[0], item[1])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FAIL_CLOSED");
        }
    }

    @Test
    void missingSettingsCannotSilentlyEnableThePolicy() {
        assertThatThrownBy(() -> SecureSessionConfiguration.requireSafeSettings(new MockEnvironment()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registrationRequiresExplicitEnablementAndValidSettings() {
        var runner = new ApplicationContextRunner().withUserConfiguration(SecureSessionConfiguration.class);
        runner.run(context -> assertThat(context).doesNotHaveBean("secureSessionTransport"));
        runner.withPropertyValues("mop.security.require-https=true").run(context -> assertThat(context).hasFailed());
        runner.withInitializer(context -> context.setEnvironment(safeSettings().withProperty("mop.security.require-https", "true")))
            .run(context -> {
                assertThat(context).hasNotFailed().hasBean("secureSessionTransport");
                var registration = context.getBean("secureSessionTransport", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                assertThat(registration.getOrder()).isEqualTo(Integer.MIN_VALUE);
            });
    }

    @Test
    void plaintextLoginAndCsrfAreRejectedBeforeSessionCreation() throws Exception {
        for (String path : new String[]{"/api/v1/auth/local/login", "/api/v1/auth/csrf", "/api/v1/account"}) {
            var request = new MockHttpServletRequest("GET", path);
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            new SecureSessionTransportFilter().doFilter(request, response, (a, b) -> called.set(true));
            assertThat(called).isFalse();
            assertThat(request.getSession(false)).isNull();
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("HTTPS_REQUIRED");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getCookies()).isEmpty();
        }
    }

    @Test
    void forwardedHeadersNeverAuthenticateTheTransport() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/local/login");
        request.addHeader("Forwarded", "proto=https;host=mop.example.test");
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        new SecureSessionTransportFilter().doFilter(request, response, (a, b) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void secureRequestsContinueToExistingCsrfAndAuthorizationChainWithHsts() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/assets");
        request.setSecure(true);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new SecureSessionTransportFilter().doFilter(request, response, (a, b) -> called.set(true));
        assertThat(called).isTrue();
        assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void onlyCredentialFreeLoopbackReadHealthProbesBypassTls() throws Exception {
        for (String method : new String[]{"GET", "HEAD"}) {
            var request = new MockHttpServletRequest(method, "/api/v1/health/ready");
            request.setRemoteAddr("127.0.0.1");
            var called = new AtomicBoolean();
            new SecureSessionTransportFilter().doFilter(request, new MockHttpServletResponse(), (a, b) -> called.set(true));
            assertThat(called).isTrue();
        }
        for (String variant : new String[]{"remote", "cookie", "authorization", "session", "post", "path"}) {
            var request = new MockHttpServletRequest("GET", "/api/v1/health/ready");
            request.setRemoteAddr("127.0.0.1");
            switch (variant) {
                case "remote" -> request.setRemoteAddr("192.0.2.1");
                case "cookie" -> request.addHeader("Cookie", "MOP_SESSION=SYNTHETIC");
                case "authorization" -> request.addHeader("Authorization", "Bearer SYNTHETIC");
                case "session" -> request.setSession(new MockHttpSession());
                case "post" -> request.setMethod("POST");
                case "path" -> request.setRequestURI("/api/v1/health/ready;jsessionid=SYNTHETIC");
                default -> throw new AssertionError();
            }
            var response = new MockHttpServletResponse();
            new SecureSessionTransportFilter().doFilter(request, response, (a, b) -> { throw new AssertionError(); });
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    void expirationCookiePreservesSecureScopeOnTlsWithoutTrustingHeaders() {
        for (boolean secure : new boolean[]{true, false}) {
            var request = new MockHttpServletRequest();
            request.setSecure(secure);
            request.addHeader("X-Forwarded-Proto", "https");
            var response = new MockHttpServletResponse();
            ProjectSessionCookie.expire(request, response);
            var cookie = response.getCookie("MOP_SESSION");
            assertThat(cookie).isNotNull();
            assertThat(cookie.getSecure()).isEqualTo(secure);
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getDomain()).isNull();
            assertThat(cookie.getMaxAge()).isZero();
        }
    }
}
