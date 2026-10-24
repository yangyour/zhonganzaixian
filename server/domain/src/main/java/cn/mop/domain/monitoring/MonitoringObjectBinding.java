package cn.mop.domain.monitoring;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MonitoringObjectMapping.Key;
import java.time.*;
import java.util.*;

/** Immutable internal mapping publication. Neither metadata verification nor an active record enables collection/dispatch. */
public final class MonitoringObjectBinding {
 public static final long MAX_VERSION=9007199254740990L;
 private MonitoringObjectBinding(){}
 public enum Action { CREATE, SUBMIT_DRAFT, CANCEL_DRAFT, ACTIVATE, REVOKE }
 public record Document(UUID id,UUID tenantId,Key key,String provider,UUID createdBy,Instant createdAt){
  public Document{Objects.requireNonNull(id);Objects.requireNonNull(tenantId);Objects.requireNonNull(key);Objects.requireNonNull(createdBy);instant(createdAt);if(provider==null||!provider.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();}
  @Override public String toString(){return "MonitoringMappingDocument[identifiers redacted]";}
 }
 public record Draft(UUID assetId,long assetVersion,UUID submittedBy,Instant submittedAt){
  public Draft{Objects.requireNonNull(assetId);Objects.requireNonNull(submittedBy);if(assetVersion<1||assetVersion>9007199254740991L)throw invalid();instant(submittedAt);}
  @Override public String toString(){return "MonitoringMappingDraft[not effective; identifiers redacted]";}
 }
 /** Nullable values are intentional configuration gates; no approval/TTL is inferred. */
 public record Policy(String reference,Set<UUID> pools,Set<UUID> reviewers,Boolean selfApprovalAllowed,Integer verificationTtlSeconds){
  public Policy{if(reference!=null&&!reference.matches("[A-Za-z0-9._:-]{1,256}"))throw invalid();pools=pools==null?null:Set.copyOf(pools);reviewers=reviewers==null?null:Set.copyOf(reviewers);if(pools!=null&&pools.size()>100||reviewers!=null&&reviewers.size()>100||verificationTtlSeconds!=null&&(verificationTtlSeconds<1||verificationTtlSeconds>86400))throw invalid();}
  public boolean configured(){return reference!=null&&pools!=null&&!pools.isEmpty()&&reviewers!=null&&!reviewers.isEmpty()&&selfApprovalAllowed!=null&&verificationTtlSeconds!=null;}
  public void requirePool(UUID pool){if(pools==null)throw pending();if(!pools.contains(pool))throw missing();}
  public void requireReviewer(UUID actor,Draft draft){if(!configured())throw pending();if(!reviewers.contains(actor)||!selfApprovalAllowed&&actor.equals(draft.submittedBy()))throw new DomainException(403,"MAPPING_REVIEW_FORBIDDEN","当前复核人或双主体条件不满足");}
  @Override public String toString(){return "MonitoringMappingPolicy[configured="+configured()+"]";}
 }
 /** Source facts are obtained by the bound server adapter, never accepted in the public action body. */
 public record SourceMetadata(Key key,String provider,String sourceHostId,UUID claimedPoolId,UUID claimedTenantId,UUID claimedAssetId,String definitionSha256,String metadataSha256){
  public SourceMetadata{Objects.requireNonNull(key);Objects.requireNonNull(claimedPoolId);Objects.requireNonNull(claimedTenantId);Objects.requireNonNull(claimedAssetId);if(provider==null||!provider.matches("[A-Z][A-Z0-9_]{0,63}")||sourceHostId==null||!sourceHostId.matches("[1-9][0-9]{0,19}"))throw invalid();hash(definitionSha256);hash(metadataSha256);if(!metadataSha256.equals(metadataHash(key,provider,sourceHostId,claimedPoolId,claimedTenantId,claimedAssetId)))throw invalid();}
  @Override public String toString(){return "MonitoringMappingSourceMetadata[allowlisted facts only; redacted]";}
 }
 public record Active(Draft approvedDraft,UUID activatedBy,Instant activatedAt,Instant expiresAt,String policyReference,SourceMetadata source){
  public Active{Objects.requireNonNull(approvedDraft);Objects.requireNonNull(activatedBy);Objects.requireNonNull(source);instant(activatedAt);instant(expiresAt);if(!expiresAt.isAfter(activatedAt)||activatedAt.isBefore(approvedDraft.submittedAt())||policyReference==null||!policyReference.matches("[A-Za-z0-9._:-]{1,256}"))throw invalid();}
  @Override public String toString(){return "MonitoringMappingActivation[not collection or dispatch authority]";}
 }
 public record State(Document document,long version,Draft draft,Active active,Instant updatedAt,String reason){
  public State{Objects.requireNonNull(document);MonitoringObjectBinding.version(version);instant(updatedAt);if(updatedAt.isBefore(document.createdAt())||draft!=null&&(draft.submittedAt().isBefore(document.createdAt())||draft.submittedAt().isAfter(updatedAt))||active!=null&&(active.activatedAt().isAfter(updatedAt)||!active.source().key().equals(document.key())||!active.source().provider().equals(document.provider())||!active.source().claimedPoolId().equals(document.key().poolId())||!active.source().claimedTenantId().equals(document.tenantId())||!active.source().claimedAssetId().equals(active.approvedDraft().assetId())))throw invalid();if(reason!=null&&(reason.isBlank()||reason.length()>500))throw invalid();}
  @Override public String toString(){return "MonitoringMappingState[version="+version+", draft="+(draft!=null)+", activeRecord="+(active!=null)+"]";}
 }
 public static State create(Document document,UUID assetId,long assetVersion,String reason){return new State(document,1,new Draft(assetId,assetVersion,document.createdBy(),document.createdAt()),null,document.createdAt(),reason);}
 public static State submit(State state,long expected,UUID actor,UUID asset,long assetVersion,Instant now,String reason){current(state,expected,now);return new State(state.document(),state.version()+1,new Draft(asset,assetVersion,actor,now),state.active(),now,reason);}
 public static State cancelDraft(State state,long expected,Instant now,String reason){current(state,expected,now);if(state.draft()==null)throw conflict();return new State(state.document(),state.version()+1,null,state.active(),now,reason);}
 public static State revoke(State state,long expected,Instant now,String reason){current(state,expected,now);if(state.active()==null||reason==null||reason.isBlank())throw conflict();return new State(state.document(),state.version()+1,state.draft(),null,now,reason);}
 public static State activate(State state,long expected,UUID actor,Policy policy,String expectedPolicy,String expectedDefinition,SourceMetadata source,Instant observed,String reason){
  current(state,expected,observed);if(state.draft()==null)throw conflict();policy.requirePool(state.document().key().poolId());policy.requireReviewer(actor,state.draft());
  if(!Objects.equals(expectedPolicy,policy.reference())||!Objects.equals(expectedDefinition,source.definitionSha256()))throw new DomainException(409,"MAPPING_SOURCE_POLICY_CHANGED","来源或审批配置已变化，请重新读取");
  if(!source.key().equals(state.document().key())||!source.provider().equals(state.document().provider())||!source.claimedPoolId().equals(state.document().key().poolId())||!source.claimedTenantId().equals(state.document().tenantId())||!source.claimedAssetId().equals(state.draft().assetId()))throw missing();
  Instant expiry;try{expiry=observed.plusSeconds(policy.verificationTtlSeconds());}catch(DateTimeException|ArithmeticException e){throw invalid();}
  return new State(state.document(),state.version()+1,null,new Active(state.draft(),actor,observed,expiry,policy.reference(),source),observed,reason);
 }
 public static String availability(State state,Policy policy,String currentDefinition,long currentAssetVersion,boolean reviewerAuthorized,Instant now){
  if(state.active()==null)return "NO_ACTIVE_MAPPING";var active=state.active();if(!policy.configured())return "POLICY_UNCONFIGURED";
  if(!policy.pools().contains(state.document().key().poolId())||!active.policyReference().equals(policy.reference()))return "POLICY_CHANGED";
  if(!active.source().definitionSha256().equals(currentDefinition))return "SOURCE_CONFIGURATION_CHANGED";
  if(!reviewerAuthorized||!policy.reviewers().contains(active.activatedBy()))return "REVIEWER_REVOKED";
  if(active.approvedDraft().assetVersion()!=currentAssetVersion)return "ASSET_CHANGED";
  if(now.isBefore(active.activatedAt()))return "PROOF_FROM_FUTURE";
  if(!now.isBefore(active.expiresAt()))return "PROOF_EXPIRED";
  return "CURRENT_RECORDED_MAPPING";
 }
 public static String metadataHash(Key key,String provider,String host,UUID pool,UUID tenant,UUID asset){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n","MOP_MAPPING_SOURCE_METADATA_V1",key.poolId().toString(),key.objectType().name(),key.externalId(),provider,host,pool.toString(),tenant.toString(),asset.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static void current(State state,long expected,Instant now){Objects.requireNonNull(state);instant(now);if(expected!=state.version()||expected>=MAX_VERSION||now.isBefore(state.updatedAt()))throw conflict();}
 private static void version(long value){if(value<1||value>MAX_VERSION)throw invalid();}
 private static void hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw invalid();}
 private static void instant(Instant value){if(value==null||value.getNano()%1000000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
 private static DomainException invalid(){return new DomainException(400,"INVALID_MONITORING_MAPPING","监控对象映射或明确配置无效");}
 private static DomainException conflict(){return new DomainException(409,"MAPPING_VERSION_CONFLICT","映射版本、草稿或已发布记录发生变化");}
 private static DomainException missing(){return new DomainException(404,"MONITORING_MAPPING_NOT_FOUND","当前范围内没有该监控对象映射");}
 private static DomainException pending(){return new DomainException(503,"MONITORING_MAPPING_POLICY_PENDING","映射来源范围、复核人或明确有效期尚未配置");}
}
