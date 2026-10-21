package cn.mop.api;

import cn.mop.adapters.email.*;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

@Configuration @Profile("remote_test")
@ConditionalOnProperty(name="MOP_EMAIL_ENABLED",havingValue="true")
public class EmailConfiguration {
    @Bean SmtpMailClient smtpMailClient(Environment env,ObjectMapper mapper){return new SmtpMailClient(SmtpSettings.load(Path.of(env.getRequiredProperty("MOP_EMAIL_CONFIG_FILE")),mapper,env.getProperty("MOP_EMAIL_PASSWORD")));}
    @Bean PostgresEmailRepository emailRepository(DataSource source,PlatformRepository platform){if(!(platform instanceof PostgresPlatformRepository pg))throw new IllegalStateException("Email requires designated PostgreSQL");return new PostgresEmailRepository(source,pg);}
    @Bean EmailService emailService(SmtpMailClient client,PostgresEmailRepository repository,Environment env){
        String tenant=env.getRequiredProperty("MOP_EMAIL_TENANT_ID");if(!UUID.fromString(tenant).toString().equals(tenant))throw new IllegalArgumentException("An explicit email project tenant is required");
        return new EmailService(client,repository,tenant,env.getProperty("MOP_EMAIL_DISPATCH_ENABLED",Boolean.class,false));
    }
}
