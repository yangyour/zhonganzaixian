package cn.mop.domain;

import java.security.MessageDigest;
import java.util.*;

/** Pure technical guards; production scan, retention and customer policy approval stay pending. */
public final class PhotoRules {
    private PhotoRules() {}
    public static void validateUpload(PhotoUpload upload) {
        if(upload==null)throw invalid("照片内容缺失");
        String name=upload.originalName();
        if(name==null||name.isBlank()||name.codePointCount(0,name.length())>200||name.contains("/")||name.contains("\\")||name.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff)))throw invalid("文件名无效");
        if(upload.mediaType()==null||!Set.of("image/jpeg","image/png").contains(upload.mediaType()))throw invalid("照片类型不受支持");
        validateStoredContent(upload.content(),upload.sha256());
    }
    public static void validateStoredContent(byte[] content,String sha) {
        if(content==null||content.length<1||content.length>5_000_000||sha==null||!sha.matches("[0-9a-f]{64}"))throw invalid("照片大小或摘要无效");
        try{if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(sha))throw new DomainException(409,"FILE_INTEGRITY_CONFLICT","照片受理内容与原图摘要不一致");}
        catch(DomainException error){throw error;}catch(Exception error){throw new IllegalStateException("SHA-256 is unavailable");}
    }
    public static List<String> attachmentIds(List<String> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>3)throw invalid("照片引用必须包含1至3张");
        TreeSet<String> unique=new TreeSet<>();
        for(String id:ids){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("照片引用必须为规范UUID");unique.add(id);}
        return List.copyOf(unique);
    }
    public static int retrySeconds(int attempts){return (int)Math.min(300L,5L<<Math.min(6,attempts<=1?0:attempts-1));}
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
}
