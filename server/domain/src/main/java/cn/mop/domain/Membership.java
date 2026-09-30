package cn.mop.domain;
public record Membership(String userId, String tenantId, String role, long permissionVersion, boolean active) {}
