package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.change.ChangeNoticeDraft;
import cn.mop.domain.change.ChangeNoticeApproval;
import cn.mop.domain.change.ChangeNoticeApproval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChangeNoticeApprovalTest {
    static final UUID TENANT=id(1),AUTHOR=id(2),REVIEWER=id(3),POLICY=id(4);
    static final Instant T=Instant.parse("2026-10-19T01:00:00Z");
    static UUID id(int n) { return new UUID(0,n); }
    static Actor author() { return new Actor(TENANT,AUTHOR,2,true,null,false); }
    static Actor reviewer() { return new Actor(TENANT,REVIEWER,3,true,POLICY,true); }
    static Snapshot draft() {
        return ChangeNoticeApproval.create(id(8),7,new ChangeNoticeDraft(TENANT,AUTHOR,null,"合成报备",T,T.plusSeconds(3600),List.of(id(9))),T);
    }
    static Snapshot submitted() { return apply(draft(),Action.SUBMIT,author(),null).snapshot(); }
    static Transition apply(Snapshot s,Action a,Actor who,String note) {
        return ChangeNoticeApproval.apply(s,s.version(),a,who,note,s.updatedAt().plusMillis(1));
    }
    @Test void approvalBindsExactRevisionAndDoesNotActivateOrExecute() {
        var original=draft();var reviewed=apply(submitted(),Action.APPROVE,reviewer(),null);
        var s=reviewed.snapshot();assertEquals(State.APPROVED,s.state());assertEquals(3,s.version());
        assertEquals(7,s.draftRevision());assertEquals(original.fingerprint(),s.fingerprint());assertEquals(original.draft(),s.draft());
        assertEquals(REVIEWER,s.reviewerId());assertEquals(POLICY,s.reviewPolicyReleaseId());
        assertEquals(State.IN_REVIEW,reviewed.event().from());assertEquals(State.APPROVED,reviewed.event().to());
        assertEquals(3,reviewed.event().permissionVersion());assertEquals(s.version(),reviewed.event().version());
        assertFalse(s.effective());assertFalse(s.deviceExecutionAllowed());assertFalse(s.alertSuppressionAllowed());
        assertEquals(State.DRAFT,original.state());
    }
    @Test void fullTransitionMatrixRejectsIllegalStateActions() {
        var d=draft();var pending=submitted();var approved=apply(pending,Action.APPROVE,reviewer(),null).snapshot();
        var rejected=apply(pending,Action.REJECT,reviewer(),"请补充设备范围").snapshot();
        var withdrawn=apply(pending,Action.WITHDRAW,author(),null).snapshot();
        for(var s:List.of(d,pending,approved,rejected,withdrawn))for(var action:Action.values()) {
            boolean allowed=s.state()==State.DRAFT&&(action==Action.SUBMIT||action==Action.WITHDRAW)
                    ||s.state()==State.IN_REVIEW&&action!=Action.SUBMIT;
            Actor who=action==Action.APPROVE||action==Action.REJECT?reviewer():author();
            if(allowed)assertDoesNotThrow(()->apply(s,action,who,"明确说明"));
            else assertEquals(422,assertThrows(DomainException.class,()->apply(s,action,who,"明确说明")).status);
        }
    }
    @ParameterizedTest @EnumSource(value=Action.class,names={"APPROVE","REJECT"})
    void selfReviewRejectedEvenWithReviewPermission(Action action) {
        var self=new Actor(TENANT,AUTHOR,10,true,POLICY,true);
        assertEquals(403,assertThrows(DomainException.class,()->apply(submitted(),action,self,"说明")).status);
    }
    @ParameterizedTest @EnumSource(value=Action.class,names={"SUBMIT","WITHDRAW"})
    void onlyApplicantCanSubmitOrWithdraw(Action action) {
        assertEquals(403,assertThrows(DomainException.class,()->apply(draft(),action,reviewer(),null)).status);
    }
    @Test void missingApprovalPolicyAndDeniedRoleAreDistinct() {
        var missing=new Actor(TENANT,REVIEWER,1,true,null,false);
        var denied=new Actor(TENANT,REVIEWER,1,true,POLICY,false);
        assertEquals("CONFIGURATION_PENDING",assertThrows(DomainException.class,()->apply(submitted(),Action.APPROVE,missing,null)).code);
        assertEquals(403,assertThrows(DomainException.class,()->apply(submitted(),Action.APPROVE,denied,null)).status);
        assertThrows(DomainException.class,()->new Actor(TENANT,REVIEWER,1,true,null,true));
    }
    @Test void tenantAndActiveMembershipCheckedBeforeVersionsAndStates() {
        var foreign=new Actor(id(99),REVIEWER,1,true,null,false);
        assertEquals(404,assertThrows(DomainException.class,()->ChangeNoticeApproval.apply(draft(),99,Action.APPROVE,foreign,null,T)).status);
        var inactive=new Actor(TENANT,REVIEWER,1,false,POLICY,true);
        assertEquals(401,assertThrows(DomainException.class,()->apply(submitted(),Action.APPROVE,inactive,null)).status);
    }
    @Test void staleVersionCannotReviewChangedSnapshot() {
        var s=submitted();assertEquals(409,assertThrows(DomainException.class,()->ChangeNoticeApproval.apply(s,1,Action.APPROVE,reviewer(),null,T.plusSeconds(1))).status);
        assertEquals(State.IN_REVIEW,s.state());assertEquals(2,s.version());
    }
    @Test void rejectRequiresValidUnicodeReasonButApproveDoesNotInventRequiredComment() {
        assertNull(apply(submitted(),Action.APPROVE,reviewer(),null).snapshot().decisionNote());
        for(String bad:Arrays.asList(null,"","\u00a0","\ufeff","\uD800","\u0000","x".repeat(2001)))
            assertEquals(400,assertThrows(DomainException.class,()->apply(submitted(),Action.REJECT,reviewer(),bad)).status);
        String original="  请补全范围\n保留原文 😀  ";var result=apply(submitted(),Action.REJECT,reviewer(),original);
        assertEquals(original,result.snapshot().decisionNote());assertEquals(original,result.event().note());
        assertDoesNotThrow(()->apply(submitted(),Action.REJECT,reviewer(),"😀".repeat(2000)));
    }
    @Test void withdrawalPreservesSubmissionFactWithoutReviewEvidence() {
        var early=apply(draft(),Action.WITHDRAW,author(),null).snapshot();assertNull(early.submittedAt());
        var pending=submitted();var withdrawn=apply(pending,Action.WITHDRAW,author(),"取消本次申请");
        assertEquals(pending.submittedAt(),withdrawn.snapshot().submittedAt());assertNull(withdrawn.snapshot().reviewerId());
        assertNull(withdrawn.snapshot().reviewPolicyReleaseId());assertEquals("取消本次申请",withdrawn.event().note());
    }
    @Test void backwardsClockAndSubmillisecondInputAreRejected() {
        assertEquals(503,assertThrows(DomainException.class,()->ChangeNoticeApproval.apply(submitted(),2,Action.APPROVE,reviewer(),null,T)).status);
        assertEquals(400,assertThrows(DomainException.class,()->ChangeNoticeApproval.apply(submitted(),2,Action.APPROVE,reviewer(),null,T.plusNanos(1))).status);
    }
    @Test void impossibleSnapshotsCannotMasqueradeAsApproval() {
        var d=draft();
        assertThrows(DomainException.class,()->new Snapshot(d.noticeId(),7,d.draft(),"b".repeat(64),1,State.DRAFT,T,null,null,null,null,null,T));
        assertThrows(DomainException.class,()->new Snapshot(d.noticeId(),7,d.draft(),d.fingerprint(),1,State.APPROVED,T,null,REVIEWER,T,POLICY,null,T));
        assertThrows(DomainException.class,()->new Snapshot(d.noticeId(),7,d.draft(),d.fingerprint(),1,State.APPROVED,T,T,AUTHOR,T,POLICY,null,T));
        assertThrows(DomainException.class,()->new Snapshot(d.noticeId(),7,d.draft(),d.fingerprint(),1,State.DRAFT,T,null,REVIEWER,T,POLICY,null,T));
    }
    @Test void exhaustedVersionNeverOverflows() {
        var d=draft();var max=new Snapshot(d.noticeId(),7,d.draft(),d.fingerprint(),9_007_199_254_740_990L,State.DRAFT,T,null,null,null,null,null,T);
        assertEquals(422,assertThrows(DomainException.class,()->apply(max,Action.SUBMIT,author(),null)).status);
    }
}
