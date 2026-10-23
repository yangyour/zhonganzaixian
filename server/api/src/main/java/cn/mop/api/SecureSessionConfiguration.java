package cn.mop.api;

import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/** Opt-in direct TLS session boundary; it does not enable a production runtime or trust proxy headers. */
@Configuration
@ConditionalOnProperty(name = "mop.security.require-https", havingValue = "true")
public class SecureSessionConfiguration {
    /** Validate effective settings before registering the transport gate, without reading key material. */
    @Bean
    FilterRegistrationBean<SecureSessionTransportFilter> secureSessionTransport(Environment environment) {
        requireSafeSettings(environment);
        var registration = new FilterRegistrationBean<>(new SecureSessionTransportFilter());
        registration.setName("mopSecureSessionTransport");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    static void requireSafeSettings(Environment environment) {
        boolean safe = environment.getProperty("server.ssl.enabled", Boolean.class, false)
            && environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)
            && environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class, false)
            && "strict".equalsIgnoreCase(environment.getProperty("server.servlet.session.cookie.same-site"))
            && "MOP_SESSION".equals(environment.getProperty("server.servlet.session.cookie.name"))
            && "/".equals(environment.getProperty("server.servlet.session.cookie.path"))
            && environment.getProperty("server.servlet.session.cookie.domain", "").isBlank()
            && environment.getProperty("server.servlet.session.cookie.max-age") == null
            && "cookie".equalsIgnoreCase(environment.getProperty("server.servlet.session.tracking-modes"))
            && "none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy"));
        String[] protocols = environment.getProperty("server.ssl.enabled-protocols", String[].class, new String[0]);
        safe = safe && protocols.length > 0;
        for (String protocol : protocols) {
            safe = safe && Set.of("TLSv1.2", "TLSv1.3").contains(protocol);
        }
        if (!safe) {
            throw new IllegalStateException("FAIL_CLOSED: secure sessions require direct TLS1.2+, host-only secure strict session cookies, cookie-only tracking and no forwarded-header trust");
        }
    }
}
