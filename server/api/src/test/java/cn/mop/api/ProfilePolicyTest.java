package cn.mop.api;
import cn.mop.domain.ProfilePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class ProfilePolicyTest {
    @Test void onlyExplicitLocalFixtureIsAccepted(){
        assertThatCode(()->ProfilePolicy.requireLocalFixture("local_fixture")).doesNotThrowAnyException();
        for(String[] profiles:new String[][]{ {},{"production"},{"integration"},{"local_fixture","production"},{"local_fixture","integration"}})
            assertThatThrownBy(()->ProfilePolicy.requireLocalFixture(profiles)).isInstanceOf(IllegalStateException.class).hasMessageContaining("FAIL_CLOSED");
    }
    @Test void supportedRuntimeAcceptsOnlyOneTestProfile(){
        assertThatCode(()->ProfilePolicy.requireSupportedRuntime("remote_test")).doesNotThrowAnyException();
        assertThatCode(()->ProfilePolicy.requireSupportedRuntime("local_fixture")).doesNotThrowAnyException();
        for(String[] profiles:new String[][]{ {},{"production"},{"integration"},{"remote_test","local_fixture"},{"remote_test","production"}})
            assertThatThrownBy(()->ProfilePolicy.requireSupportedRuntime(profiles)).isInstanceOf(IllegalStateException.class).hasMessageContaining("FAIL_CLOSED");
    }
    @Test void productionAndMixedProfilesActuallyFailApplicationStartup(){
        for(String profile:new String[]{"production","integration","local_fixture,production"})
            new WebApplicationContextRunner().withUserConfiguration(MopApplication.class).withPropertyValues("spring.profiles.active="+profile).run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("FAIL_CLOSED");});
    }
    @Test void fixtureCannotBeExposedOnAllNetworkInterfaces(){
        new WebApplicationContextRunner().withUserConfiguration(MopApplication.class).withPropertyValues("spring.profiles.active=local_fixture","mop.fixture.unit-test=true","server.address=0.0.0.0").run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("must bind to 127.0.0.1");});
    }
    @Test void fixtureCannotBeNormalDevelopmentRuntime(){
        new WebApplicationContextRunner().withUserConfiguration(MopApplication.class).withPropertyValues("spring.profiles.active=local_fixture","mop.fixture.unit-test=false").run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("reserved");});
    }
}
