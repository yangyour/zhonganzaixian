package cn.mop.domain.monitoring;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** Immutable, parseable but unverified source candidate. Claims never grant tenant access. */
public final class QuarantinedMonitoringEvent {
    public enum Reason { UNKNOWN_MAPPING, CROSS_TENANT_CLAIM, SOURCE_KEY_CONFLICT }
    public record Claims(UUID tenantId, UUID assetId) {
        @Override public String toString(){return "UnverifiedClaims[redacted]";}
    }
    private final RawMonitoringEvent.Key key;
    private final Claims claims;
    private final Reason reason;
    private final Instant occurredAt, receivedAt;
    private final byte[] payload;
    private final String payloadSha256, candidateFingerprint;
    public QuarantinedMonitoringEvent(RawMonitoringEvent.Key key, Claims claims, Reason reason, Instant occurredAt, Instant receivedAt, byte[] payload){
        if(key==null||claims==null||reason==null||occurredAt==null||receivedAt==null||payload==null||payload.length==0||payload.length>RawMonitoringEvent.MAX_PAYLOAD_BYTES)throw new IllegalArgumentException("Invalid monitoring quarantine candidate");
        this.key=key;this.claims=claims;this.reason=reason;this.occurredAt=occurredAt;this.receivedAt=receivedAt;this.payload=payload.clone();payloadSha256=hash(this.payload);candidateFingerprint=fingerprint();
    }
    public RawMonitoringEvent.Key key(){return key;}
    public Claims claims(){return claims;}
    public Reason reason(){return reason;}
    public Instant occurredAt(){return occurredAt;}
    public Instant receivedAt(){return receivedAt;}
    public byte[] payload(){return payload.clone();}
    public String payloadSha256(){return payloadSha256;}
    public String candidateFingerprint(){return candidateFingerprint;}
    public boolean dispatchAllowed(){return false;}
    public boolean sourceAuthorized(){return false;}
    private String fingerprint(){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            field(out,"MOP_MONITORING_QUARANTINE_V1");field(out,key.poolId().toString());field(out,key.sourceEventId());field(out,key.phase().name());
            field(out,claims.tenantId()==null?null:claims.tenantId().toString());field(out,claims.assetId()==null?null:claims.assetId().toString());field(out,reason.name());
            out.writeLong(occurredAt.getEpochSecond());out.writeInt(occurredAt.getNano());field(out,payloadSha256);out.flush();return hash(bytes.toByteArray());
        }catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
    private static void field(DataOutputStream out,String value)throws java.io.IOException{if(value==null){out.writeInt(-1);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    @Override public String toString(){return "QuarantinedMonitoringEvent[held unverified; not durably stored; content redacted]";}
}
