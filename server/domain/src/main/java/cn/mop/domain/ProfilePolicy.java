package cn.mop.domain;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
public final class ProfilePolicy {
    private ProfilePolicy() {}
    public static void requireSupportedRuntime(String... profiles) {
        Set<String> active = Arrays.stream(profiles).collect(Collectors.toSet());
        if (active.size()!=1 || !(active.contains("remote_test") || active.contains("local_fixture")))
            throw new IllegalStateException("FAIL_CLOSED: select exactly remote_test, or explicit local_fixture for isolated unit fixtures; production/integration and mixed profiles require approved implementations.");
    }
    public static void requireLocalFixture(String... profiles) {
        Set<String> active = Arrays.stream(profiles).collect(Collectors.toSet());
        if (!active.contains("local_fixture") || active.contains("production") || active.contains("integration")) {
            throw new IllegalStateException("FAIL_CLOSED: only explicit local_fixture is available; real identity and durable tenant repositories are not implemented (Q02/Q17). local_fixture cannot be combined with integration/production.");
        }
    }
}
