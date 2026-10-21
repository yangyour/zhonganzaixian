package cn.mop.api;

import cn.mop.adapters.wecom.*;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

@Configuration @Profile("remote_test")
@ConditionalOnProperty(name="MOP_WECOM_ENABLED",havingValue="true")
public class WeComConfiguration {
    @Bean WeComClient weComClient(Environment env,ObjectMapper mapper) {
        var credentials=WeComCredentials.load(Path.of(env.getRequiredProperty("MOP_WECOM_CONFIG_FILE")),mapper);
        String proxy=env.getProperty("MOP_WECOM_PROXY_URL","");
        return new WeComClient(credentials,mapper,proxy.isBlank()?null:URI.create(proxy));
    }
    @Bean PostgresWeComRepository weComRepository(DataSource source,PlatformRepository platform) {
        if(!(platform instanceof PostgresPlatformRepository pg))throw new IllegalStateException("WeCom requires designated PostgreSQL");
        return new PostgresWeComRepository(source,pg);
    }
    @Bean WeComService weComService(WeComClient client,PostgresWeComRepository repository,Environment env) {
        String tenant=env.getRequiredProperty("MOP_WECOM_TENANT_ID");
        if(!UUID.fromString(tenant).toString().equals(tenant))throw new IllegalArgumentException("An explicit WeCom project tenant is required");
        String testUser=env.getProperty("MOP_WECOM_TEST_USER_ID","");if(!testUser.isBlank())WeComClient.userId(testUser);
        String callback=env.getProperty("MOP_WECOM_CALLBACK_URL","");
        boolean login=env.getProperty("MOP_WECOM_LOGIN_ENABLED",Boolean.class,false);
        if(login)WeComService.validateCallback(callback,env.getProperty("MOP_WECOM_ALLOW_HTTP_CALLBACK",Boolean.class,false));
        return new WeComService(client,repository,tenant,testUser,login,callback,env.getProperty("MOP_WECOM_DISPATCH_ENABLED",Boolean.class,false));
    }
}
