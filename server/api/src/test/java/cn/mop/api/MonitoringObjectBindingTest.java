package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import cn.mop.domain.monitoring.MonitoringObjectMapping.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class MonitoringObjectBindingTest {
 final UUID pool=UUID.randomUUID(),tenant=UUID.randomUUID(),asset=UUID.randomUUID(),author=UUID.randomUUID(),reviewer=UUID.randomUUID();
 final Instant time=Instant.parse("2026-10-23T00:00:00Z");final Key key=new Key(pool,ObjectType.HOST,"123");final String hash="a".repeat(64);
 Policy policy(){return new Policy("configured:"+hash,Set.of(pool),Set.of(reviewer),false,60);}
 State draft(){return MonitoringObjectBinding.create(new Document(UUID.randomUUID(),tenant,key,"ZABBIX_7",author,time),asset,1,null);}
 SourceMetadata source(UUID claimed){return new SourceMetadata(key,"ZABBIX_7","123",pool,tenant,claimed,hash,MonitoringObjectBinding.metadataHash(key,"ZABBIX_7","123",pool,tenant,claimed));}
 State activate(State d){return MonitoringObjectBinding.activate(d,d.version(),reviewer,policy(),policy().reference(),hash,source(asset),time.plusSeconds(1),null);}
 @Test void pendingPolicyCannotBeGuessed(){var p=new Policy(null,null,null,null,null);assertFalse(p.configured());assertEquals(503,assertThrows(DomainException.class,()->p.requirePool(pool)).status);assertEquals(503,assertThrows(DomainException.class,()->MonitoringObjectBinding.activate(draft(),1,reviewer,p,null,hash,source(asset),time.plusSeconds(1),null)).status);}
 @Test void noSelfApprovalWithoutExplicitPermission(){var p=new Policy(policy().reference(),Set.of(pool),Set.of(author),false,60);assertEquals(403,assertThrows(DomainException.class,()->MonitoringObjectBinding.activate(draft(),1,author,p,p.reference(),hash,source(asset),time.plusSeconds(1),null)).status);}
 @Test void activePublicationPreservedWhileDraftEditedOrCancelled(){var active=activate(draft());var edited=MonitoringObjectBinding.submit(active,2,author,UUID.randomUUID(),4,time.plusSeconds(2),"new draft");assertEquals(active.active(),edited.active());assertNotEquals(asset,edited.draft().assetId());var cancelled=MonitoringObjectBinding.cancelDraft(edited,3,time.plusSeconds(3),null);assertNull(cancelled.draft());assertEquals(active.active(),cancelled.active());}
 @Test void revokePreservesNewDraftAndRequiresReason(){var edited=MonitoringObjectBinding.submit(activate(draft()),2,author,asset,2,time.plusSeconds(2),null);assertThrows(DomainException.class,()->MonitoringObjectBinding.revoke(edited,3,time.plusSeconds(3),null));var revoked=MonitoringObjectBinding.revoke(edited,3,time.plusSeconds(3),"operator revoke");assertNull(revoked.active());assertEquals(edited.draft(),revoked.draft());}
 @Test void foreignAssetClaimIs404NotImplicitRebind(){assertEquals(404,assertThrows(DomainException.class,()->MonitoringObjectBinding.activate(draft(),1,reviewer,policy(),policy().reference(),hash,source(UUID.randomUUID()),time.plusSeconds(1),null)).status);}
 @Test void expectedPolicyAndDefinitionAreRequired(){assertEquals(409,assertThrows(DomainException.class,()->MonitoringObjectBinding.activate(draft(),1,reviewer,policy(),"old",hash,source(asset),time.plusSeconds(1),null)).status);assertEquals(409,assertThrows(DomainException.class,()->MonitoringObjectBinding.activate(draft(),1,reviewer,policy(),policy().reference(),"b".repeat(64),source(asset),time.plusSeconds(1),null)).status);}
 @Test void ttlBoundaryAndFutureClockAreNotCurrent(){var s=activate(draft());assertEquals("CURRENT_RECORDED_MAPPING",availability(s,1,true,time.plusSeconds(60)));assertEquals("PROOF_EXPIRED",availability(s,1,true,time.plusSeconds(61)));assertEquals("PROOF_FROM_FUTURE",availability(s,1,true,time));}
 @Test void currentAssetAndReviewerAreRequired(){var s=activate(draft());assertEquals("ASSET_CHANGED",availability(s,2,true,time.plusSeconds(2)));assertEquals("REVIEWER_REVOKED",availability(s,1,false,time.plusSeconds(2)));assertEquals("SOURCE_CONFIGURATION_CHANGED",MonitoringObjectBinding.availability(s,policy(),null,1,true,time.plusSeconds(2)));}
 @Test void nullOrChangedPolicyInvalidatesPublication(){var s=activate(draft());assertEquals("POLICY_UNCONFIGURED",MonitoringObjectBinding.availability(s,new Policy(null,null,null,null,null),hash,1,true,time.plusSeconds(2)));var p=new Policy("changed:"+hash,Set.of(pool),Set.of(reviewer),false,60);assertEquals("POLICY_CHANGED",MonitoringObjectBinding.availability(s,p,hash,1,true,time.plusSeconds(2)));}
 @Test void wrongVersionAndClockRegressionFail(){assertThrows(DomainException.class,()->MonitoringObjectBinding.cancelDraft(draft(),2,time,null));assertThrows(DomainException.class,()->MonitoringObjectBinding.submit(draft(),1,author,asset,1,time.minusSeconds(1),null));}
 @Test void sourceHashCannotBeAltered(){assertThrows(DomainException.class,()->new SourceMetadata(key,"ZABBIX_7","123",pool,tenant,asset,hash,hash));}
 @Test void receiptIsNotCurrentActivationOrDispatch(){var r=new MonitoringObjectBindingRepository.Receipt(UUID.randomUUID(),2,Action.ACTIVATE);assertTrue(r.acknowledgementOnly());assertFalse(r.currentMappingClaimed());}
 @Test void effectiveStateNeverClaimsDeviceOrDispatch(){var s=activate(draft());var v=new MonitoringObjectBindingRepository.View(s,availability(s,1,true,time.plusSeconds(2)),false,false,policy().reference(),hash);assertFalse(v.dispatchAllowed());assertFalse(v.monitoringApplied());assertFalse(v.deviceIdentityVerified());}
 String availability(State s,long version,boolean reviewer,Instant at){return MonitoringObjectBinding.availability(s,policy(),hash,version,reviewer,at);}
}
