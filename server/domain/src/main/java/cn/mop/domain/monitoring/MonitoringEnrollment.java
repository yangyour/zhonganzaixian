package cn.mop.domain.monitoring;
import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.*;

/** Durable registration is not deployment, device identity, continuous collection or health. */
public final class MonitoringEnrollment {
 private MonitoringEnrollment(){}
 public enum Phase { REQUESTED, APPROVED, REGISTERED_DISABLED, CANCELLED }
 public record AssetSnapshot(UUID id,long version,String managementIp){public AssetSnapshot{Objects.requireNonNull(id);if(version<1||version>9007199254740990L||managementIp==null||managementIp.length()>45)throw invalid();}}
 public record Document(UUID id,UUID tenantId,UUID createdBy,Instant createdAt,String catalogSha256,String configurationSha256,String configuration,List<AssetSnapshot> assets){
  public Document{Objects.requireNonNull(id);Objects.requireNonNull(tenantId);Objects.requireNonNull(createdBy);time(createdAt);hash(catalogSha256);hash(configurationSha256);
   if(configuration==null||configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw invalid();assets=List.copyOf(assets);if(assets.isEmpty()||assets.size()>64||assets.stream().map(AssetSnapshot::id).distinct().count()!=assets.size())throw invalid();}
 }
 public record Policy(String reference,List<UUID> reviewers,Boolean selfApprovalAllowed){public Policy{if(reference!=null&&(reference.isBlank()||reference.length()>200))throw invalid();if(reviewers!=null){reviewers=List.copyOf(reviewers);if(reviewers.size()>100||reviewers.stream().distinct().count()!=reviewers.size())throw invalid();}}}
 public record Probe(UUID targetId,String configurationSha256,String status,Integer sampleCount,List<String> matchedMetrics,Instant observedAt,String reason){
  public Probe{Objects.requireNonNull(targetId);hash(configurationSha256);time(observedAt);if(!Set.of("NOT_RUN","FAILED","TCP_REACHABLE_PROTOCOL_UNVERIFIED","SAMPLE_VERIFIED_ONCE").contains(status))throw invalid();
   matchedMetrics=List.copyOf(matchedMetrics);if(matchedMetrics.size()>100||matchedMetrics.stream().anyMatch(x->x==null||!x.matches("[a-zA-Z_:][a-zA-Z0-9_:]{0,199}")))throw invalid();
   if(status.equals("SAMPLE_VERIFIED_ONCE")){if(sampleCount==null||sampleCount<1||sampleCount>100000||matchedMetrics.isEmpty())throw invalid();}
   else if(sampleCount!=null||!matchedMetrics.isEmpty())throw invalid();if(reason!=null&&!reason.matches("[A-Z0-9_]{1,100}"))throw invalid();}
 }
 public record State(Document document,long version,Phase phase,UUID reviewer,String approvalPolicy,Instant updatedAt,String reason,Probe lastProbe){
  public State{Objects.requireNonNull(document);Objects.requireNonNull(phase);time(updatedAt);if(version<1||version>9007199254740990L||updatedAt.isBefore(document.createdAt()))throw invalid();
   if(Set.of(Phase.APPROVED,Phase.REGISTERED_DISABLED).contains(phase)){if(reviewer==null||approvalPolicy==null||approvalPolicy.isBlank())throw invalid();}
   else if(reviewer!=null||approvalPolicy!=null)throw invalid();
   if(lastProbe!=null&&(phase!=Phase.REGISTERED_DISABLED||lastProbe.observedAt().isBefore(document.createdAt())||lastProbe.observedAt().isAfter(updatedAt)))throw invalid();
   if(phase==Phase.CANCELLED&&(reason==null||reason.isBlank()||reason.length()>1000))throw invalid();}
 }
 public static State create(Document d){return new State(d,1,Phase.REQUESTED,null,null,d.createdAt(),null,null);}
 public static State approve(State before,UUID actor,long expected,Policy policy,Instant now){check(before,expected,now);if(before.phase()!=Phase.REQUESTED)throw conflict();
  if(policy==null||policy.reference()==null||policy.reviewers()==null)throw pending("APPROVAL_POLICY_MISSING");
  if(!policy.reviewers().contains(actor))throw forbidden();
  if(before.document().createdBy().equals(actor)){if(policy.selfApprovalAllowed()==null)throw pending("SELF_APPROVAL_POLICY_MISSING");if(!policy.selfApprovalAllowed())throw forbidden();}
  return new State(before.document(),before.version()+1,Phase.APPROVED,actor,policy.reference(),now,null,null);
 }
 public static State register(State before,long expected,Policy policy,Instant now){check(before,expected,now);if(before.phase()!=Phase.APPROVED)throw conflict();
  if(policy==null||!Objects.equals(policy.reference(),before.approvalPolicy())||policy.reviewers()==null||!policy.reviewers().contains(before.reviewer()))throw pending("APPROVAL_POLICY_CHANGED");
  return new State(before.document(),before.version()+1,Phase.REGISTERED_DISABLED,before.reviewer(),before.approvalPolicy(),now,null,null);
 }
 public static State cancel(State before,long expected,String reason,Instant now){check(before,expected,now);if(before.phase()==Phase.CANCELLED)throw conflict();return new State(before.document(),before.version()+1,Phase.CANCELLED,null,null,now,reason,null);}
 public static State probe(State before,long expected,Probe result,Instant now){check(before,expected,now);if(before.phase()!=Phase.REGISTERED_DISABLED)throw conflict();return new State(before.document(),before.version()+1,before.phase(),before.reviewer(),before.approvalPolicy(),now,null,Objects.requireNonNull(result));}
 private static void check(State s,long expected,Instant now){time(now);if(s.version()!=expected||s.version()>=9007199254740990L||now.isBefore(s.updatedAt()))throw conflict();}
 private static void hash(String s){if(s==null||!s.matches("[a-f0-9]{64}"))throw invalid();}
 private static void time(Instant t){if(t==null||t.getNano()%1000000!=0||t.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||t.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","监控登记记录或证据字段无效");}
 private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","监控登记版本或阶段已变化");}
 private static DomainException pending(String reason){return new DomainException(422,"CONFIG_PENDING","监控登记审批配置尚未就绪",List.of(reason));}
 private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前用户不在明确监控登记审批范围内");}
}
