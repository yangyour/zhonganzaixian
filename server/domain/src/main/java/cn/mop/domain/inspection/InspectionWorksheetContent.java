package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Canonical manual worksheet content. Self-reported performance time is not the server recording time. */
public record InspectionWorksheetContent(InspectionWorksheet worksheet, Instant reportedPerformedAt) {
    public static final String FORMAT="MOP_INSPECTION_WORKSHEET_V1";
    public static final int MAX_CANONICAL_BYTES=2_097_152; // Technical bound, not a business inspection quota.
    private static final Instant FIRST=Instant.parse("0001-01-01T00:00:00Z"),LAST=Instant.parse("9999-12-31T23:59:59.999Z");
    public InspectionWorksheetContent {
        if(worksheet==null)throw invalid();
        if(reportedPerformedAt!=null)millisecondInstant(reportedPerformedAt);
    }
    /** The repository must supply its actual transaction clock; this pure check alone is not a server-clock proof. */
    public void requireNotFuture(Instant recordedAt) {
        millisecondInstant(recordedAt);
        if(reportedPerformedAt!=null&&reportedPerformedAt.isAfter(recordedAt))throw new DomainException(400,"INSPECTION_TIME_IN_FUTURE","自述已执行时间不能晚于实际记录时间；未执行或未知时间应保持未填写");
    }
    public String timeSource(){return reportedPerformedAt==null?"NOT_PROVIDED":"SELF_REPORTED_TIME";}
    public String verificationStatus(){return "SELF_REPORTED_UNVERIFIED";}
    public boolean reportPublishable(){return false;}
    /** Missing observations are absent, never encoded as NORMAL. Ordering follows the frozen template, not Map order. */
    public byte[] canonicalBytes() {
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {
                field(out,FORMAT);field(out,worksheet.tenantId().toString());field(out,worksheet.assetId().toString());field(out,worksheet.assetName());
                var template=worksheet.template();field(out,template.templateId().toString());out.writeLong(template.version());field(out,template.contentSha256());
                if(reportedPerformedAt==null)out.writeInt(-1);else{out.writeInt(8);out.writeLong(reportedPerformedAt.toEpochMilli());}
                out.writeInt(worksheet.observations().size());
                for(var item:template.definition().items()) {
                    var observation=worksheet.observations().get(item.key());if(observation==null)continue;
                    field(out,item.key());field(out,observation.result().name());field(out,observation.note());out.writeInt(observation.evidenceIds().size());
                    for(var id:observation.evidenceIds())field(out,id.toString());
                }
            }
            byte[] result=bytes.toByteArray();if(result.length>MAX_CANONICAL_BYTES)throw invalid();return result;
        }catch(IOException impossible){throw new IllegalStateException("Inspection content encoding unavailable",impossible);}
    }
    public String fingerprint() {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("Inspection content digest unavailable",impossible);}
    }
    private static void field(DataOutputStream out,String value)throws IOException {
        if(value==null){out.writeInt(-1);return;}byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static void millisecondInstant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(FIRST)||value.isAfter(LAST))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","巡检执行单内容或自述时间无效；记录时间不能代替未知巡检时间");}
}
