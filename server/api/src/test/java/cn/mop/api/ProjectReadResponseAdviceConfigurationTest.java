package cn.mop.api;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
class ProjectReadResponseAdviceConfigurationTest {
 ApplicationContextRunner runner(String profile){return new ApplicationContextRunner().withInitializer(c->c.getEnvironment().setActiveProfiles(profile)).withUserConfiguration(ProjectReadResponseAdvice.class);}
 @Test void remoteProjectLocalDefaultInstallsGuard(){runner("remote_test").run(c->assertThat(c).hasSingleBean(ProjectReadResponseAdvice.class));}
 @Test void legacyOidcDoesNotInventProjectRevisionAuthority(){runner("remote_test").withPropertyValues("MOP_AUTH_MODE=legacy_oidc").run(c->assertThat(c).doesNotHaveBean(ProjectReadResponseAdvice.class));}
 @Test void fixtureProfileDoesNotRegisterProductionGuard(){runner("local_fixture").run(c->assertThat(c).doesNotHaveBean(ProjectReadResponseAdvice.class));}
}
