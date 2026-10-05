package cn.mop.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TestOidcEndpointPolicyTest {
    @Test void keepsExistingTunnelConfiguration() {
        assertThatCode(() -> TestOidcEndpointPolicy.validate("http://127.0.0.1:18110/realms/mop-test", "mop-web", "unit-secret", "")).doesNotThrowAnyException();
    }
    @Test void allowsOnlyExplicitAcceptanceIssuerAndClient() {
        for (String origin : new String[]{"http://192.0.2.10:10010", "https://mop.example.test"}) {
            assertThatCode(() -> TestOidcEndpointPolicy.validate(origin + "/realms/mop-acceptance", "mop-web-acceptance", "unit-secret", origin)).doesNotThrowAnyException();
            assertThatThrownBy(() -> TestOidcEndpointPolicy.validate(origin + "/realms/master", "mop-web-acceptance", "unit-secret", origin)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> TestOidcEndpointPolicy.validate(origin + "/realms/mop-acceptance", "mop-web", "unit-secret", origin)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> TestOidcEndpointPolicy.validate(origin + "/realms/mop-acceptance", "mop-web-acceptance", "unit-secret", "https://different.example.test")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void rejectsAmbiguousOriginsBeforeAnyDiscovery() {
        for (String origin : new String[]{"http://mop.example.test", "http://256.1.2.3:10010", "http://192.000.2.1:10010", "http://0.0.0.0:10010", "http://192.0.2.1:0", "http://192.0.2.1:65536", "https://user:secret@example.test", "https://example.test/", "https://example.test/path", "https://example.test?q=1", "https://example.test#x", "https://example.test\n", "file:///tmp", "//example.test", " https://example.test"}) {
            assertThat(TestOidcEndpointPolicy.validOrigin(origin)).as(origin).isFalse();
        }
    }
    @Test void refusesMissingSecretOrUnpinnedPublicIssuer() {
        assertThatThrownBy(() -> TestOidcEndpointPolicy.validate("http://192.0.2.10:10010/realms/mop-acceptance", "mop-web-acceptance", "unit-secret", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestOidcEndpointPolicy.validate("https://mop.example.test/realms/mop-acceptance", "mop-web-acceptance", " ", "https://mop.example.test")).isInstanceOf(IllegalStateException.class);
    }
}
