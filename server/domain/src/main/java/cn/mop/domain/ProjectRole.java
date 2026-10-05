package cn.mop.domain;

import java.util.List;
import java.util.Set;

/** Configured role grants stay within the contract's four business categories. */
public record ProjectRole(String name, String description, String baseRole, List<String> permissions, boolean enabled) {
    public ProjectRole {
        name = text(name, 100, false);
        description = text(description, 2000, true);
        if (baseRole == null || !Set.of("viewer", "tadmin", "msp", "padmin").contains(baseRole)) throw invalid("角色类别无效");
        if (permissions == null || permissions.size() > 256 || permissions.stream().anyMatch(value -> value == null)) throw invalid("权限列表无效");
        var selected = Set.copyOf(permissions);
        if (selected.size() != permissions.size() || !Capabilities.forRole(baseRole).containsAll(selected)) throw invalid("所选权限超出角色类别范围或包含重复项");
        permissions = selected.stream().sorted().toList();
    }
    private static String text(String value, int maximum, boolean nullable) {
        if (value == null && nullable) return null;
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum ||
            value.codePoints().anyMatch(Character::isISOControl) || value.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) throw invalid("角色名称或说明无效");
        return value;
    }
    private static DomainException invalid(String message) { return new DomainException(400, "VALIDATION_ERROR", message); }
}
