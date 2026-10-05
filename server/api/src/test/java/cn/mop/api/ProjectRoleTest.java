package cn.mop.api;

import cn.mop.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProjectRoleTest {
    @Test void configuredRolesKeepOnlyExplicitCapabilitiesWithinTheirBusinessCategory() {
        var role = new ProjectRole("只读工程师", null, "msp", List.of("tickets:read", "assets:read"), true);
        assertThat(role.permissions()).containsExactly("assets:read", "tickets:read");
        var member = new Membership("user", "tenant", "msp", 3, true, role.permissions());
        assertThat(member.capabilities()).containsExactly("assets:read", "tickets:read");
        assertThat(member.capabilities()).doesNotContain("assets:write", "worklogs:write_own");
    }
    @Test void noImplicitGrantsAreAddedToAnEmptyConfiguredRole() {
        var member = new Membership("user", "tenant", "padmin", 4, true, List.of());
        assertThat(member.capabilities()).isEmpty();
    }
    @Test void existingExplicitTestConstructorKeepsLegacyCategoryGrants() {
        assertThat(new Membership("u", "t", "viewer", 1, true).capabilities()).isEqualTo(Capabilities.forRole("viewer"));
        assertThat(new Membership("u", "t", "unknown", 1, true).capabilities()).isEmpty();
    }
    @Test void revokedMembershipHasNoEffectiveCapabilities() {
        assertThat(new Membership("u", "t", "padmin", 2, false, Capabilities.forRole("padmin")).capabilities()).isEmpty();
    }
    @Test void permissionInputsCannotEscalateAcrossBusinessCategories() {
        for (String grant : List.of("configurations:write", "audit:read", "invented:superuser")) {
            assertThatThrownBy(() -> new ProjectRole("客户角色", null, "viewer", List.of(grant), true)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> new Membership("u", "t", "viewer", 1, true, List.of(grant))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void invalidAndDuplicateInputsFailWithoutRepairingThem() {
        assertThatThrownBy(() -> new ProjectRole("角色", null, null, List.of(), true)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProjectRole(" ", null, "msp", List.of(), true)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProjectRole("角色\n", null, "msp", List.of(), true)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProjectRole("角色", null, "msp", List.of("assets:read", "assets:read"), true)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Membership("u", "t", "msp", 1, true, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void callerMutationCannotChangeAcceptedGrants() {
        var input = new ArrayList<>(List.of("assets:read"));
        var role = new ProjectRole("角色", null, "msp", input, true);
        var member = new Membership("u", "t", "msp", 1, true, input);
        input.add("assets:write");
        assertThat(role.permissions()).containsExactly("assets:read");
        assertThat(member.capabilities()).containsExactly("assets:read");
        assertThatThrownBy(() -> role.permissions().add("assets:write")).isInstanceOf(UnsupportedOperationException.class);
    }
}
