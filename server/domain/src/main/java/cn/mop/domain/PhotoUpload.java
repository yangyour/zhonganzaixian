package cn.mop.domain;
public record PhotoUpload(String originalName,String mediaType,String sha256,byte[] content) {}
