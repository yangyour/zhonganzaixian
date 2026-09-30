package cn.mop.worker;

import cn.mop.domain.ProfilePolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
    @Bean Object requireApprovedRuntime(Environment environment) {
        ProfilePolicy.requireSupportedRuntime(environment.getActiveProfiles());
        if(!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local_fixture"))||!environment.getProperty("mop.fixture.unit-test",Boolean.class,false))
            throw new IllegalStateException("FAIL_CLOSED: durable outbox execution is not implemented; no worker may claim test-environment jobs");
        return new Object();
    }
}
