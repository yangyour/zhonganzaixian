package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupPlanDraft;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BackupPlanDraftTest {
    UUID tenant=UUID.fromString("6a000000-0000-4000-8000-000000000001"),asset=UUID.fromString("6a000000-0000-4000-8000-000000000002");
    BackupPlanDraft draft(String time){return new BackupPlanDraft(tenant,"合成计划",List.of(asset),time,null,true,null);}
    @Test void requestedEnableNeverActivatesDraftOrGrantsDeviceAccess(){var d=draft("02:00");assertTrue(d.requestedEnabled());assertFalse(d.enabled());assertFalse(d.effective());assertFalse(d.deviceExecutionAllowed());assertNull(d.nextExecutionAt());assertEquals("DRAFT",d.state());assertEquals("SSH_RUNNING_CONFIG",d.collectionMethod());assertEquals("NOT_VERIFIED_BY_THIS_MODEL",d.targetVerification());}
    @Test void explicitDisabledRequestIsRetained(){assertFalse(new BackupPlanDraft(tenant,"合成",List.of(asset),"02:00",null,false,null).requestedEnabled());}
    @Test void noImplicitDefaultFillsMissingTime(){assertEquals("02:00",BackupPlanDraft.CONTRACT_DEFAULT_TIME);assertThrows(DomainException.class,()->draft(null));}
    @Test void timeRequiresExactAsciiHourAndMinute(){for(String s:List.of("2:00","24:00","02:60","02:00:00"," 02:00","02:00 ","０２:００","0 2:00","02:00Z"))assertThrows(DomainException.class,()->draft(s));for(String s:List.of("00:00","02:00","23:59"))assertEquals(s,draft(s).dailyLocalTime());}
    @Test void defaultRetryProposalIsNotEffectiveConfiguration(){var d=draft("02:00");assertEquals(2,d.proposedRetryCount());assertEquals(10,d.proposedRetryIntervalMinutes());assertEquals("CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE",d.retryPolicySource());}
    @Test void targetsRequireExplicitNonEmptyUniqueReferences(){assertThrows(DomainException.class,()->new BackupPlanDraft(null,"合成",List.of(asset),"02:00",null,false,null));for(List<UUID> refs:Arrays.asList(null,List.<UUID>of(),Arrays.asList((UUID)null),List.of(asset,asset)))assertThrows(DomainException.class,()->new BackupPlanDraft(tenant,"合成",refs,"02:00",null,false,null));}
    @Test void targetLimitRejectsInsteadOfTruncating(){assertEquals(413,assertThrows(DomainException.class,()->new BackupPlanDraft(tenant,"合成",Collections.nCopies(1001,asset),"02:00",null,false,null)).status);var refs=new ArrayList<UUID>();for(int n=1;n<=1000;n++)refs.add(new UUID(1,n));assertEquals(1000,new BackupPlanDraft(tenant,"合成",refs,"02:00",null,false,null).targetAssetIds().size());}
    @Test void targetsAreCopiedWithoutSortingOrSilentlyRebinding(){var other=new UUID(1,3);var refs=new ArrayList<>(List.of(other,asset));var d=new BackupPlanDraft(tenant,"合成",refs,"02:00",null,false,null);refs.clear();assertEquals(List.of(other,asset),d.targetAssetIds());assertThrows(UnsupportedOperationException.class,()->d.targetAssetIds().clear());}
    @Test void credentialReferenceDoesNotVerifyOrConfigureCredentials(){assertEquals("NOT_SUPPLIED",draft("02:00").credentialVerification());var ref=new UUID(2,3);var d=new BackupPlanDraft(tenant,"合成",List.of(asset),"02:00",ref,true,null);assertEquals(ref,d.credentialRef());assertEquals("NOT_VERIFIED_BY_THIS_MODEL",d.credentialVerification());assertFalse(d.deviceExecutionAllowed());}
    @Test void nameRequiresNonblankValidUnicodeWithoutControlCharacters(){for(String name:Arrays.asList(null,""," \t","\u00a0","\ufeff","bad\nname","\ud800","😀".repeat(101)))assertThrows(DomainException.class,()->new BackupPlanDraft(tenant,name,List.of(asset),"02:00",null,false,null));assertDoesNotThrow(()->new BackupPlanDraft(tenant,"😀".repeat(100),List.of(asset),"02:00",null,false,null));}
    @Test void optionalNotePreservesNullEmptyAndWhitespace(){for(String note:Arrays.asList(null,""," \n\t ","😀".repeat(4000)))assertEquals(note,new BackupPlanDraft(tenant,"合成",List.of(asset),"02:00",null,false,note).note());for(String note:List.of("x\u0000","\udc00","😀".repeat(4001)))assertThrows(DomainException.class,()->new BackupPlanDraft(tenant,"合成",List.of(asset),"02:00",null,false,note));}
    @Test void calendarPreviewUsesFixedShanghaiAndExplicitDate(){var p=draft("02:00").previewDate(LocalDate.of(2026,10,5));assertEquals(Instant.parse("2026-10-04T18:00:00Z"),p.candidateInstant());assertEquals("Asia/Shanghai",p.timezone());assertEquals("UNPUBLISHED_CALENDAR_CANDIDATE",p.resolution());assertFalse(p.createsRun());assertFalse(p.executionAuthorized());assertNull(p.missedRunPolicy());}
    @Test void midnightAndLastMinuteDoNotDependOnJvmZone(){assertEquals(Instant.parse("2026-12-31T16:00:00Z"),draft("00:00").previewDate(LocalDate.of(2027,1,1)).candidateInstant());assertEquals(Instant.parse("2024-02-29T15:59:00Z"),draft("23:59").previewDate(LocalDate.of(2024,2,29)).candidateInstant());}
    @Test void historicalShanghaiGapAndOverlapDoNotSilentlyChooseOffset(){var gap=draft("02:30").previewDate(LocalDate.of(1991,4,14));assertEquals("LOCAL_TIME_GAP",gap.resolution());assertNull(gap.candidateInstant());var overlap=draft("01:30").previewDate(LocalDate.of(1991,9,15));assertEquals("LOCAL_TIME_OVERLAP",overlap.resolution());assertNull(overlap.candidateInstant());}
    @Test void invalidDatesAndUtcRangeUnderflowFailClosed(){for(LocalDate date:Arrays.asList(null,LocalDate.of(0,1,1),LocalDate.of(10000,1,1),LocalDate.of(1,1,1)))assertThrows(DomainException.class,()->draft("02:00").previewDate(date));assertDoesNotThrow(()->draft("23:59").previewDate(LocalDate.of(9999,12,31)));}
    @Test void everyMinuteRoundTripsThroughTheContractBusinessZone(){
        LocalDate date=LocalDate.of(2026,10,5);
        for(int minute=0;minute<1440;minute++){
            String time=String.format(java.util.Locale.ROOT,"%02d:%02d",minute/60,minute%60);
            var p=draft(time).previewDate(date);
            assertEquals(LocalDateTime.of(date,LocalTime.of(minute/60,minute%60)),p.candidateInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
            assertFalse(p.createsRun());
        }
    }
    @Test void validationErrorsNeverEchoSuppliedText(){
        var error=assertThrows(DomainException.class,()->new BackupPlanDraft(tenant,"SYNTHETIC_SECRET\u0000",List.of(asset),"02:00",null,false,null));
        assertFalse(error.getMessage().contains("SYNTHETIC_SECRET"));
    }
    @Test void previewIsReadOnlyRepeatableAndNeverMakesAPlanDue(){var d=draft("02:00");assertEquals(d.previewDate(LocalDate.of(2026,10,5)),d.previewDate(LocalDate.of(2026,10,5)));assertNull(d.nextExecutionAt());assertFalse(d.enabled());}
}
