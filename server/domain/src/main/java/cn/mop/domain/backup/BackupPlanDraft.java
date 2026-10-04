package cn.mop.domain.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.time.BusinessDates;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Proposed daily plan only. Neither a credential reference nor a target UUID authorizes device access. */
public record BackupPlanDraft(UUID tenantId,String name,List<UUID> targetAssetIds,
                              String dailyLocalTime,UUID credentialRef,boolean requestedEnabled,String note) {
    public static final int MAX_TARGETS=1000;
    public static final String CONTRACT_DEFAULT_TIME="02:00";
    public BackupPlanDraft {
        if(tenantId==null)throw invalid();
        text(name,100,false);text(note,4000,true);
        if(targetAssetIds==null||targetAssetIds.isEmpty())throw invalid();
        if(targetAssetIds.size()>MAX_TARGETS)throw new DomainException(413,"BACKUP_PLAN_TARGET_LIMIT","目标设备超过1000项技术保护，未截断保存");
        var copy=new ArrayList<>(targetAssetIds);
        if(copy.isEmpty()||copy.size()>MAX_TARGETS||copy.stream().anyMatch(Objects::isNull)||new HashSet<>(copy).size()!=copy.size())throw invalid();
        targetAssetIds=List.copyOf(copy);
        if(dailyLocalTime==null||!dailyLocalTime.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw invalid();
    }
    public String timezone(){return BusinessDates.ZONE.getId();}
    public String collectionMethod(){return "SSH_RUNNING_CONFIG";}
    public String state(){return "DRAFT";}
    public boolean effective(){return false;}
    public boolean enabled(){return false;}
    public String targetVerification(){return "NOT_VERIFIED_BY_THIS_MODEL";}
    public String credentialVerification(){return credentialRef==null?"NOT_SUPPLIED":"NOT_VERIFIED_BY_THIS_MODEL";}
    public boolean deviceExecutionAllowed(){return false;}
    public Instant nextExecutionAt(){return null;}
    public int proposedRetryCount(){return 2;}
    public int proposedRetryIntervalMinutes(){return 10;}
    public String retryPolicySource(){return "CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE";}
    /** Explicit calendar date only; gaps/overlaps remain unresolved instead of choosing an offset. */
    public Occurrence previewDate(LocalDate date){
        if(date==null||date.getYear()<1||date.getYear()>9999)throw invalid();
        LocalDateTime local=LocalDateTime.of(date,LocalTime.parse(dailyLocalTime,DateTimeFormatter.ofPattern("HH:mm")));
        var offsets=BusinessDates.ZONE.getRules().getValidOffsets(local);
        if(offsets.size()!=1)return new Occurrence(local,null,offsets.isEmpty()?"LOCAL_TIME_GAP":"LOCAL_TIME_OVERLAP");
        Instant candidate=local.toInstant(offsets.get(0));
        // The project's wire timestamps use UTC years0001..9999; never wrap or pick a different date.
        if(candidate.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||candidate.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();
        return new Occurrence(local,candidate,"UNPUBLISHED_CALENDAR_CANDIDATE");
    }
    public record Occurrence(LocalDateTime requestedLocalDateTime,Instant candidateInstant,String resolution){
        public String timezone(){return BusinessDates.ZONE.getId();}
        public boolean createsRun(){return false;}
        public boolean executionAuthorized(){return false;}
        public String missedRunPolicy(){return null;}
    }
    private static void text(String value,int max,boolean nullable){
        if(value==null){if(nullable)return;throw invalid();}
        if(value.codePointCount(0,value.length())>max||!nullable&&(value.isEmpty()||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff))||value.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&(!nullable||cp!='\n'&&cp!='\r'&&cp!='\t')))throw invalid();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","备份计划草稿需明确名称、唯一目标、HH:mm时间和有效日期；不会启用计划或授权SSH");}
}
