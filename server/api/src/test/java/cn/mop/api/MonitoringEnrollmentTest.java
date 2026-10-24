package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringEnrollment;
import cn.mop.domain.monitoring.MonitoringEnrollment.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringEnrollmentTest {
 final UUID creator=UUID.randomUUID(),reviewer=UUID.randomUUID(),tenant=UUID.randomUUID();final Instant now=Instant.parse("2026-10-23T20:00:00Z");
 State state(){return MonitoringEnrollment.create(new Document(UUID.randomUUID(),tenant,creator,now,"a".repeat(64),"b".repeat(64),"{}",List.of(new AssetSnapshot(UUID.randomUUID(),1,"192.0.2.1"))));}
 Policy policy(){return new Policy("explicit-unit-policy",List.of(reviewer),false);}
 @Test void missingApprovalPolicyCannotApprove(){assertEquals(422,assertThrows(DomainException.class,()->MonitoringEnrollment.approve(state(),reviewer,1,null,now)).status);}
 @Test void creatorCannotSelfApproveWithoutExplicitPolicy(){assertThrows(DomainException.class,()->MonitoringEnrollment.approve(state(),creator,1,new Policy("v1",List.of(creator),null),now));assertThrows(DomainException.class,()->MonitoringEnrollment.approve(state(),creator,1,new Policy("v1",List.of(creator),false),now));}
 @Test void namedReviewerIsRequired(){assertThrows(DomainException.class,()->MonitoringEnrollment.approve(state(),UUID.randomUUID(),1,policy(),now));}
 @Test void registrationNeverEnablesCollection(){var approved=MonitoringEnrollment.approve(state(),reviewer,1,policy(),now);var registered=MonitoringEnrollment.register(approved,2,policy(),now);assertEquals(Phase.REGISTERED_DISABLED,registered.phase());assertEquals(3,registered.version());assertNull(registered.lastProbe());}
 @Test void changedPolicyInvalidatesApproval(){var approved=MonitoringEnrollment.approve(state(),reviewer,1,policy(),now);assertThrows(DomainException.class,()->MonitoringEnrollment.register(approved,2,new Policy("changed",List.of(reviewer),false),now));}
 @Test void staleVersionAndCancellationCannotRegister(){assertThrows(DomainException.class,()->MonitoringEnrollment.approve(state(),reviewer,2,policy(),now));var cancelled=MonitoringEnrollment.cancel(state(),1,"operator cancelled",now);assertThrows(DomainException.class,()->MonitoringEnrollment.register(cancelled,2,policy(),now));}
 @Test void onceSampleDoesNotAdvanceToActiveOrHealthy(){var approved=MonitoringEnrollment.approve(state(),reviewer,1,policy(),now);var registered=MonitoringEnrollment.register(approved,2,policy(),now);var p=new Probe(UUID.randomUUID(),"c".repeat(64),"SAMPLE_VERIFIED_ONCE",1,List.of("node_uname_info"),now,null);var after=MonitoringEnrollment.probe(registered,3,p,now);assertEquals(Phase.REGISTERED_DISABLED,after.phase());assertEquals(p,after.lastProbe());}
 @Test void nonSampleCannotCarryMadeUpCounts(){assertThrows(DomainException.class,()->new Probe(UUID.randomUUID(),"c".repeat(64),"FAILED",1,List.of("node_uname_info"),now,"CONNECTION_FAILED"));}
 @Test void missingSignatureOrFutureEvidenceRejected(){assertThrows(DomainException.class,()->new Probe(UUID.randomUUID(),"wrong","SAMPLE_VERIFIED_ONCE",1,List.of("up"),now,null));var approved=MonitoringEnrollment.approve(state(),reviewer,1,policy(),now);var registered=MonitoringEnrollment.register(approved,2,policy(),now);assertThrows(DomainException.class,()->MonitoringEnrollment.probe(registered,3,new Probe(UUID.randomUUID(),"c".repeat(64),"SAMPLE_VERIFIED_ONCE",1,List.of("up"),now.plusSeconds(1),null),now));}
 @Test void approvalDefaultsRemainUnconfigured(){var p=MonitoringEnrollmentConfiguration.policy(null).apply(tenant.toString());assertNull(p.reference());assertNull(p.reviewers());assertNull(p.selfApprovalAllowed());}
}
