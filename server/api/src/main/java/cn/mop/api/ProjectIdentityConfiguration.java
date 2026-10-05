package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration @Profile("remote_test")
@ConditionalOnProperty(name="MOP_AUTH_MODE",havingValue="project_local",matchIfMissing=true)
public class ProjectIdentityConfiguration {
    @Bean ProjectIdentityRepository projectIdentities(DataSource source,PlatformRepository platform,ObjectMapper mapper){
        if(!(platform instanceof PostgresPlatformRepository pg))throw new IllegalStateException("Project identity requires real PostgreSQL");
        return new ProjectIdentityRepository(source,pg,new ProjectPasswordCodec(),mapper);
    }
    @Bean ProjectIdentityService projectIdentityService(ProjectIdentityRepository identities,Environment env,ObjectMapper mapper){
        String key=env.getProperty("MOP_AUTH_COMMAND_KEY");
        if(key==null||!key.matches("[0-9a-f]{64}"))throw new IllegalStateException("FAIL_CLOSED: private project identity command key is required");
        return new ProjectIdentityService(identities,mapper,key,env.getProperty("MOP_OIDC_ENABLED",Boolean.class,false));
    }
    @Bean ProjectSessionValidationFilter projectSessionValidationFilter(ProjectIdentityService identities){return new ProjectSessionValidationFilter(identities);}
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<ProjectSessionValidationFilter> projectFilterRegistration(ProjectSessionValidationFilter filter){
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }
}
