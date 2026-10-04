package cn.mop.domain.change;
import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
/** Unreviewed change proposal only. A supplied time window and applicant UUID are not an approved notice. */
public record ChangeNoticeDraft(UUID tenantId,UUID applicantId,UUID ticketId,String changeSummary,
                                Instant plannedStartAt,Instant plannedEndAt,List<UUID> assetIds) {
    public static final String FORMAT="MOP_CHANGE_NOTICE_DRAFT_V1";
    public static final int MAX_ASSETS=1000,MAX_SUMMARY_CODE_POINTS=2000;
    public ChangeNoticeDraft {
        if(tenantId==null||applicantId==null||changeSummary==null||changeSummary.codePointCount(0,changeSummary.length())>MAX_SUMMARY_CODE_POINTS||changeSummary.isEmpty()
            ||changeSummary.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)
            ||changeSummary.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t'))throw invalid();
        instant(plannedStartAt);if(plannedEndAt!=null){instant(plannedEndAt);if(!plannedEndAt.isAfter(plannedStartAt))throw invalid();}
        if(assetIds==null||assetIds.isEmpty())throw invalid();if(assetIds.size()>MAX_ASSETS)throw new DomainException(413,"CHANGE_NOTICE_TARGET_LIMIT","报备草稿设备超过1000项技术保护，未截断保存");
        var copy=new ArrayList<>(assetIds);if(copy.isEmpty()||copy.size()>MAX_ASSETS||copy.stream().anyMatch(Objects::isNull)||new HashSet<>(copy).size()!=copy.size())throw invalid();assetIds=List.copyOf(copy);
    }
    /** Expected scope still comes from an independently authorized caller. */
    public void requireTenant(UUID expectedTenant){if(expectedTenant==null)throw invalid();if(!tenantId.equals(expectedTenant))throw new DomainException(404,"OBJECT_NOT_FOUND","报备草稿不存在或不可见");}
    public String timezone(){return BusinessDates.ZONE.getId();}
    public LocalDate plannedBusinessStartDate(){return BusinessDates.localDate(plannedStartAt);}
    public LocalDate plannedBusinessEndDate(){return plannedEndAt==null?null:BusinessDates.localDate(plannedEndAt);}
    public String lifecycleStatus(){return "DRAFT";}
    public String recordSource(){return "UNREVIEWED_CHANGE_NOTICE_DRAFT";}
    public String referenceVerification(){return "NOT_VERIFIED_BY_THIS_MODEL";}
    public String approvalWorkflowStatus(){return "CONFIG_PENDING";}
    public String approvalStatus(){return null;}
    public boolean approvalEvidenceVerified(){return false;}
    public boolean effective(){return false;}
    public boolean deviceExecutionAllowed(){return false;}
    public boolean alertSuppressionAllowed(){return false;}
    public String automaticP3Decision(){return null;}
    public String matchingPolicyStatus(){return "CONFIG_PENDING";}
    /** No system clock, endpoint-inclusion rule, tolerance, emergency approval or event matching is invented. */
    public byte[] canonicalBytes(){
        try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
            field(out,FORMAT);field(out,tenantId.toString());field(out,applicantId.toString());field(out,ticketId==null?null:ticketId.toString());field(out,changeSummary);out.writeLong(plannedStartAt.toEpochMilli());if(plannedEndAt==null)out.writeInt(-1);else{out.writeInt(8);out.writeLong(plannedEndAt.toEpochMilli());}out.writeInt(assetIds.size());for(var id:assetIds)field(out,id.toString());out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException("Change notice encoding unavailable",impossible);}
    }
    public String fingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    private static void field(DataOutputStream out,String value)throws IOException{if(value==null){out.writeInt(-1);return;}byte[] data=value.getBytes(StandardCharsets.UTF_8);out.writeInt(data.length);out.write(data);}
    private static void instant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();BusinessDates.localDate(value);}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","报备草稿需明确租户、申请人、摘要、设备和计划开始时刻；结束可未知，不自动补容差或批准状态");}
}
