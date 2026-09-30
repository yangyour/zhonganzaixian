package cn.mop.domain;

import java.time.Instant;

/** Tenant ownership is part of every asset repository key. */
public record Asset(String id, String tenantId, String name, String type, String managementIp,
                    String siteId, String status, String lifecycleStatus, String source,
                    Instant sampledAt, String unknownReason, long version, Instant createdAt,
                    Instant updatedAt, String availability, String syncState) {}
