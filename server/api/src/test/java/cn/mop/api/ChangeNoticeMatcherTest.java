package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.change.ChangeNoticeDraft;
import cn.mop.domain.change.ChangeNoticeMatcher;
import cn.mop.domain.change.ChangeNoticeMatcher.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChangeNoticeMatcherTest {
    static final UUID TENANT=id(1), ASSET=id(2), AUTHOR=id(3), APPROVER=id(4), BACKUP=id(5);
    static final Instant START=Instant.parse("2026-10-19T01:00:00Z"), END=START.plusSeconds(3600);
    static UUID id(int n) { return new UUID(0,n); }
    static Policy policy(Duration before,Duration after,boolean start,boolean end,LateApproval late) {
        return new Policy(TENANT,id(6),1,"a".repeat(64),before,after,start,end,late);
    }
    static Policy policy() { return policy(Duration.ZERO,Duration.ZERO,true,false,LateApproval.REJECT); }
    static ChangeNoticeDraft draft(Instant end) { return new ChangeNoticeDraft(TENANT,AUTHOR,null,"合成配置变更",START,end,List.of(ASSET)); }
    static ApprovedNotice notice(int n,Instant end,Instant approvedAt) {
        var d=draft(end);return new ApprovedNotice(id(n),1,d,d.fingerprint(),APPROVER,approvedAt);
    }
    static ApprovedNotice notice() { return notice(7,END,START.minusSeconds(60)); }
    static Detection detection(Instant at) { return new Detection(TENANT,ASSET,BACKUP,at); }
    static Result evaluate(Instant at,Policy p,List<ApprovedNotice> notices,boolean complete) {
        return ChangeNoticeMatcher.evaluate(TENANT,detection(at),p,notices,complete);
    }
    @Test void exactDeviceAndWindowMatchReturnsRevisionEvidence() {
        var result=evaluate(START,policy(),List.of(notice()),true);
        assertEquals(Status.MATCHED,result.status());assertEquals(1,result.matches().size());
        assertEquals(notice().approvedFingerprint(),result.matches().get(0).approvedFingerprint());
        assertEquals(id(6),result.policyReleaseId());assertTrue(result.reasons().isEmpty());
    }
    @Test void endpointRulesAreExplicitAtMillisecondBoundaries() {
        for(boolean start:List.of(false,true))for(boolean end:List.of(false,true)) {
            var p=policy(Duration.ZERO,Duration.ZERO,start,end,LateApproval.REJECT);
            assertEquals(start?Status.MATCHED:Status.NO_MATCH,evaluate(START,p,List.of(notice()),true).status());
            assertEquals(end?Status.MATCHED:Status.NO_MATCH,evaluate(END,p,List.of(notice()),true).status());
            assertEquals(Status.MATCHED,evaluate(START.plusMillis(1),p,List.of(notice()),true).status());
            assertEquals(Status.NO_MATCH,evaluate(END.plusMillis(1),p,List.of(notice()),true).status());
        }
    }
    @Test void explicitToleranceDoesNotChangeFrozenDraft() {
        var n=notice();var p=policy(Duration.ofSeconds(30),Duration.ofMinutes(2),true,false,LateApproval.REJECT);
        assertEquals(Status.MATCHED,evaluate(START.minusSeconds(30),p,List.of(n),true).status());
        assertEquals(Status.NO_MATCH,evaluate(START.minusSeconds(30).minusMillis(1),p,List.of(n),true).status());
        assertEquals(Status.MATCHED,evaluate(END.plusSeconds(119),p,List.of(n),true).status());
        assertEquals(Status.NO_MATCH,evaluate(END.plusSeconds(120),p,List.of(n),true).status());
        assertEquals(START,n.draft().plannedStartAt());assertEquals(END,n.draft().plannedEndAt());
    }
    @Test void absentPolicyNeverBecomesNoMatchOrDefaultWindow() {
        var r=evaluate(START,null,List.of(),true);assertEquals(Status.CONFIG_PENDING,r.status());
        assertEquals(List.of(Reason.POLICY_UNAVAILABLE),r.reasons());assertNull(r.policyReleaseId());
    }
    @Test void incompleteQueryCannotProveAbsence() {
        var r=evaluate(START,policy(),List.of(),false);assertEquals(Status.EVIDENCE_INCOMPLETE,r.status());
        assertEquals(List.of(Reason.NOTICE_SET_INCOMPLETE),r.reasons());
        assertEquals(Status.NO_MATCH,evaluate(START,policy(),List.of(),true).status());
    }
    @Test void positiveMatchDoesNotRequireNegativeProofButRetainsWarning() {
        var r=evaluate(START,policy(),List.of(notice()),false);assertEquals(Status.MATCHED,r.status());
        assertEquals(List.of(Reason.NOTICE_SET_INCOMPLETE),r.reasons());
    }
    @Test void unknownEndIsNotAnInfiniteWindowOrAnUnreportedChange() {
        var n=notice(7,null,START.minusSeconds(60));var r=evaluate(START,policy(),List.of(n),true);
        assertEquals(Status.EVIDENCE_INCOMPLETE,r.status());assertEquals(List.of(Reason.WINDOW_END_UNKNOWN),r.reasons());
        assertEquals(Status.NO_MATCH,evaluate(START.minusMillis(1),policy(),List.of(n),true).status());
    }
    @Test void otherDeviceCannotCoverDetection() {
        var r=ChangeNoticeMatcher.evaluate(TENANT,new Detection(TENANT,id(100),BACKUP,START),policy(),List.of(notice()),true);
        assertEquals(Status.NO_MATCH,r.status());
    }
    @Test void lateApprovalDependsOnExplicitPublishedPolicy() {
        var late=notice(7,END,START.plusMillis(1));
        assertEquals(Status.NO_MATCH,evaluate(START,policy(),List.of(late),true).status());
        var p=policy(Duration.ZERO,Duration.ZERO,true,false,LateApproval.ALLOW);
        assertEquals(Status.MATCHED,evaluate(START,p,List.of(late),true).status());
        assertEquals(Status.MATCHED,evaluate(START,policy(),List.of(notice(7,END,START)),true).status());
    }
    @Test void selfApprovalAndApprovalOfAnotherRevisionAreRejected() {
        var d=draft(END);
        assertThrows(DomainException.class,()->new ApprovedNotice(id(7),1,d,d.fingerprint(),AUTHOR,START));
        assertThrows(DomainException.class,()->new ApprovedNotice(id(7),1,d,draft(null).fingerprint(),APPROVER,START));
    }
    @Test void foreignTenantIs404EvenWhenConfigurationIsMissing() {
        assertEquals(404,assertThrows(DomainException.class,()->ChangeNoticeMatcher.evaluate(id(99),detection(START),null,List.of(),true)).status);
        var foreign=new ChangeNoticeDraft(id(99),AUTHOR,null,"合成",START,END,List.of(ASSET));
        var n=new ApprovedNotice(id(7),1,foreign,foreign.fingerprint(),APPROVER,START);
        assertEquals(404,assertThrows(DomainException.class,()->evaluate(START,null,List.of(n),true)).status);
        var p=new Policy(id(99),id(6),1,"a".repeat(64),Duration.ZERO,Duration.ZERO,true,false,LateApproval.REJECT);
        assertEquals(404,assertThrows(DomainException.class,()->evaluate(START,p,List.of(),true)).status);
    }
    @Test void ambiguousEffectiveRevisionsAreRejected() {
        assertThrows(DomainException.class,()->evaluate(START,policy(),List.of(notice(),notice()),true));
    }
    @Test void multipleMatchesAreCanonicalAndImmutable() {
        var n=notice(8,END,START);var a=evaluate(START,policy(),List.of(n,notice()),true);
        assertEquals(a,evaluate(START,policy(),List.of(notice(),n),true));
        assertEquals(List.of(id(7),id(8)),a.matches().stream().map(Match::noticeId).toList());
        assertThrows(UnsupportedOperationException.class,()->a.matches().clear());
        assertThrows(UnsupportedOperationException.class,()->a.reasons().clear());
    }
    @Test void deduplicationBindsTenantAssetAndBackupRatherThanDetectionTime() {
        String key=detection(START).deduplicationKey();assertEquals(key,detection(END).deduplicationKey());
        assertNotEquals(key,new Detection(id(99),ASSET,BACKUP,START).deduplicationKey());
        assertNotEquals(key,new Detection(TENANT,id(99),BACKUP,START).deduplicationKey());
        assertNotEquals(key,new Detection(TENANT,ASSET,id(99),START).deduplicationKey());
    }
    @Test void precisionNegativeToleranceAndOverflowAreRejected() {
        assertThrows(DomainException.class,()->detection(START.plusNanos(1)));
        assertThrows(DomainException.class,()->policy(Duration.ofMillis(-1),Duration.ZERO,true,false,LateApproval.REJECT));
        assertThrows(DomainException.class,()->policy(Duration.ofNanos(1),Duration.ZERO,true,false,LateApproval.REJECT));
        assertThrows(DomainException.class,()->policy(Duration.ofSeconds(Long.MAX_VALUE),Duration.ZERO,true,false,LateApproval.REJECT));
        var p=policy(Duration.ofMillis(Long.MAX_VALUE),Duration.ZERO,true,false,LateApproval.REJECT);
        assertThrows(DomainException.class,()->evaluate(START,p,List.of(notice()),true));
    }
    @Test void missingAndMalformedInputsAreValidationErrors() {
        assertThrows(DomainException.class,()->new Detection(null,ASSET,BACKUP,START));
        assertThrows(DomainException.class,()->new Policy(TENANT,id(6),0,"a".repeat(64),Duration.ZERO,Duration.ZERO,true,false,LateApproval.REJECT));
        assertThrows(DomainException.class,()->new Policy(TENANT,id(6),1,"bad",Duration.ZERO,Duration.ZERO,true,false,LateApproval.REJECT));
        assertThrows(DomainException.class,()->evaluate(START,policy(),Arrays.asList((ApprovedNotice)null),true));
    }
}
