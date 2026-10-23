package cn.mop.api;

import cn.mop.domain.AttachmentSecurityRepository;
import cn.mop.adapters.ClamdAttachmentScanner;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.PostgresAttachmentSecurityRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Actual quarantine persistence; an absent schema or scanner never becomes an in-memory success. */
@Configuration
@Profile("remote_test")
public class AttachmentSecurityConfiguration {
    @Bean
    AttachmentSecurityRepository attachmentSecurityRepository(PlatformRepository platform, Environment environment) {
        if (!(platform instanceof PostgresPlatformRepository postgres)) {
            throw new IllegalStateException("Attachment security requires the designated PostgreSQL repository");
        }
        var policy = ClamdAttachmentScanner.Policy.configured(environment::getProperty);
        return new PostgresAttachmentSecurityRepository(postgres,
            environment.getProperty("MOP_ATTACHMENT_SECURITY_SCHEMA", "public"),
            policy.isEmpty() ? "NOT_CONFIGURED" : "CONFIGURED_NOT_VERIFIED",
            policy.map(ClamdAttachmentScanner.Policy::sha256).orElse(null));
    }
}
