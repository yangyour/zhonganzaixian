package cn.mop.domain;

/** Runtime facts, distinct from production approval and from monitoring data provenance. */
public record RepositoryMetadata(String mode, String source, boolean durable, String identitySource) {}
