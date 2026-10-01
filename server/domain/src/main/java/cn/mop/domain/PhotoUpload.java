package cn.mop.domain;

/** Exact original bytes. A caller cannot mutate durable acceptance after validation. */
public record PhotoUpload(String originalName,String mediaType,String sha256,byte[] content) {
    public PhotoUpload { content=content==null?null:content.clone(); }
    @Override public byte[] content(){return content==null?null:content.clone();}
}
