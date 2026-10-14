package cn.mop.domain.assetexport;

import cn.mop.domain.DomainException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Session-bound signed links. Signing secret is operator configuration, never returned or logged. */
public final class AssetExportDownloadGrant {
    public static final long TTL_SECONDS=900;
    public record Binding(String jobId,String tenantId,String actorId,long permissionVersion,long publishedVersion,String sha256) {
        public Binding {
            for(String id:List.of(jobId,tenantId,actorId))if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Canonical export scope required");
            if(permissionVersion<1||publishedVersion<1||sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid export grant binding");
        }
    }
    public record Grant(String token,Instant expiresAt) {}
    private final byte[] secret;
    public AssetExportDownloadGrant(byte[] secret){if(secret==null||secret.length<32||secret.length>64)throw new IllegalArgumentException("Export signing key must contain 32 to 64 bytes");this.secret=secret.clone();}
    public Grant issue(Binding binding,Instant databaseNow){Instant until=databaseNow.plusSeconds(TTL_SECONDS);String expiry=Long.toString(until.toEpochMilli());return new Grant(expiry+"."+signature(binding,expiry),until);}
    public void verify(Binding binding,String token,Instant databaseNow){
        try{
            if(token==null||!token.matches("[1-9][0-9]{0,15}\\.[A-Za-z0-9_-]{43}"))throw denied();
            String[] parts=token.split("\\.");long expiry=Long.parseLong(parts[0]);
            if(!MessageDigest.isEqual(signature(binding,parts[0]).getBytes(StandardCharsets.US_ASCII),parts[1].getBytes(StandardCharsets.US_ASCII)))throw denied();
            Instant until=Instant.ofEpochMilli(expiry);if(!databaseNow.isBefore(until)||until.isAfter(databaseNow.plusSeconds(TTL_SECONDS)))throw denied();
        }catch(DomainException safe){throw safe;}catch(RuntimeException malformed){throw denied();}
    }
    private String signature(Binding b,String expiry){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            String text=String.join("\n","MOP_EXPORT_DOWNLOAD_V1",b.jobId(),b.tenantId(),b.actorId(),Long.toString(b.permissionVersion()),Long.toString(b.publishedVersion()),b.sha256(),expiry);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception failure){throw new IllegalStateException("Export link signing unavailable");}
    }
    private static DomainException denied(){return new DomainException(404,"OBJECT_NOT_FOUND","下载链接不存在、已过期或不属于当前授权范围");}
    @Override public String toString(){return "AssetExportDownloadGrant[secret=REDACTED]";}
}
