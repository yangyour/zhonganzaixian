package cn.mop.domain.configuration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata-only diagnostic of real registry/proposal reads. No value, review text or document body
 * can be represented here. An internal APPROVED proposal is never a runtime publication.
 * Runtime definitions, precedence and customer verification remain explicitly unavailable.
 */
public record ConfigurationDiagnostic(String key, Context context, Instant observedAt,
                                      Registry registry, RecentProposals recentProposals,
                                      List<Reason> reasons) {
    public enum SourceKind { DESIGN_REGISTRY, INTERNAL_REVISION, RUNTIME_DEFINITION, RUNTIME_PUBLICATION, CUSTOMER_VERIFICATION }
    public enum ReasonCode {
        DESIGN_REGISTRY_NOT_RUNTIME_CONFIG, RUNTIME_DEFINITION_SOURCE_UNAVAILABLE,
        PUBLICATION_SOURCE_UNAVAILABLE, CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE,
        INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION, PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE,
        REGISTRY_QUESTIONS_UNAVAILABLE
    }

    /** Tenant context is an authorization dimension, not an inferred configuration override scope. */
    public record Context(String tenantId) {
        public Context { requireUuid(tenantId); }
    }

    /** Original descriptive labels are preserved; none is converted into a runtime scope or rule. */
    public record Registry(long version, String typeLabel, String unitLabel, String scopeLabel,
                           String sourceRef, String decisionStatusLabel, String classificationLabel,
                           List<String> questionIds) {
        public Registry {
            if(version<1)throw new IllegalArgumentException("Invalid registry version");
            questionIds=questions(questionIds);
        }
    }

    public record Proposal(String id, long version, String workflowStatus, Integer evidenceReferenceCount) {
        public Proposal {
            requireUuid(id);
            if(version<1||!List.of("DRAFT","IN_REVIEW","APPROVED","REJECTED","WITHDRAWN").contains(workflowStatus))
                throw new IllegalArgumentException("Invalid internal proposal metadata");
            if(evidenceReferenceCount!=null&&evidenceReferenceCount<0)throw new IllegalArgumentException("Invalid reference count");
        }
    }

    /** Recency is for display only; it does not select an active version or establish precedence. */
    public record RecentProposals(List<Proposal> items, boolean truncated) {
        public RecentProposals {
            items=List.copyOf(Objects.requireNonNull(items,"items"));
            if(items.size()>5||(truncated&&items.size()!=5))throw new IllegalArgumentException("Invalid proposal page");
        }
    }

    public record Reason(ReasonCode code, SourceKind sourceKind, String sourceId, Long sourceVersion) {
        public Reason {
            Objects.requireNonNull(code,"code");Objects.requireNonNull(sourceKind,"sourceKind");
            if(sourceVersion!=null&&sourceVersion<1)throw new IllegalArgumentException("Invalid source version");
        }
    }

    public ConfigurationDiagnostic {
        requireKey(key);Objects.requireNonNull(context,"context");Objects.requireNonNull(observedAt,"observedAt");
        Objects.requireNonNull(registry,"registry");Objects.requireNonNull(recentProposals,"recentProposals");
        reasons=List.copyOf(Objects.requireNonNull(reasons,"reasons"));
        if(reasons.isEmpty())throw new IllegalArgumentException("A diagnostic requires explicit blockers");
    }

    public static ConfigurationDiagnostic blocked(String key,Context context,Instant observedAt,Registry registry,RecentProposals proposals) {
        List<Reason> reasons=new ArrayList<>();
        reasons.add(new Reason(ReasonCode.DESIGN_REGISTRY_NOT_RUNTIME_CONFIG,SourceKind.DESIGN_REGISTRY,key,registry.version()));
        reasons.add(new Reason(ReasonCode.RUNTIME_DEFINITION_SOURCE_UNAVAILABLE,SourceKind.RUNTIME_DEFINITION,null,null));
        reasons.add(new Reason(ReasonCode.PUBLICATION_SOURCE_UNAVAILABLE,SourceKind.RUNTIME_PUBLICATION,null,null));
        // This describes unavailable verification capability; whether this key requires it is still unknown.
        reasons.add(new Reason(ReasonCode.CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE,SourceKind.CUSTOMER_VERIFICATION,null,null));
        if(registry.questionIds()==null)reasons.add(new Reason(ReasonCode.REGISTRY_QUESTIONS_UNAVAILABLE,SourceKind.DESIGN_REGISTRY,key,registry.version()));
        for(Proposal proposal:proposals.items()) {
            reasons.add(new Reason(ReasonCode.INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION,SourceKind.INTERNAL_REVISION,proposal.id(),proposal.version()));
            if(proposal.evidenceReferenceCount()==null)reasons.add(new Reason(ReasonCode.PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE,SourceKind.INTERNAL_REVISION,proposal.id(),proposal.version()));
        }
        return new ConfigurationDiagnostic(key,context,observedAt,registry,proposals,reasons);
    }

    public String status(){return "CONFIG_PENDING";}
    public Object effectiveValue(){return null;}

    /** Stable API-ready shape with explicit nulls. All maps and lists are detached and immutable. */
    public Map<String,Object> dto() {
        List<Map<String,Object>> proposals=recentProposals.items().stream().map(item->fields(
            "id",item.id(),"version",item.version(),"workflow_status",item.workflowStatus(),
            "origin",SourceKind.INTERNAL_REVISION.name(),"confirmation_status","UNVERIFIED",
            "evidence_input_status","REFERENCE_ONLY","evidence_reference_count",item.evidenceReferenceCount())).toList();
        List<Map<String,Object>> diagnostics=reasons.stream().map(reason->fields(
            "code",reason.code().name(),"source_kind",reason.sourceKind().name(),
            "source_id",reason.sourceId(),"source_version",reason.sourceVersion())).toList();
        return fields("key",key,"status",status(),"effective_value",null,
            "context",fields("tenant_id",context.tenantId(),"environment","remote_test"),"observed_at",observedAt.toString(),
            "registry",fields("origin",SourceKind.DESIGN_REGISTRY.name(),"version",registry.version(),
                "type_label",registry.typeLabel(),"unit_label",registry.unitLabel(),"scope_label",registry.scopeLabel(),
                "source_ref",registry.sourceRef(),"decision_status_label",registry.decisionStatusLabel(),
                "classification_label",registry.classificationLabel(),"question_ids",registry.questionIds()),
            "recent_proposals",fields("items",proposals,"truncated",recentProposals.truncated()),
            "runtime_definition",fields("availability","NOT_IMPLEMENTED","schema_version",null,
                "allowed_layers",null,"precedence",null,"resolved_scope",null),
            "publication",fields("availability","NOT_IMPLEMENTED","record_ref",null,"revision_id",null,
                "revision_version",null,"effective_at",null,"authorization_verified",null,"production_approval_verified",null),
            "customer_verification",fields("availability","NOT_IMPLEMENTED","required",null,"decision_ref",null,
                "authority_verified",null,"authenticity_verified",null),
            "reasons",diagnostics,"question_ids",registry.questionIds());
    }

    private static Map<String,Object> fields(Object...pairs) {
        if(pairs.length%2!=0)throw new IllegalArgumentException("Invalid metadata fields");
        Map<String,Object> values=new LinkedHashMap<>();
        for(int index=0;index<pairs.length;index+=2)values.put((String)pairs[index],pairs[index+1]);
        return Collections.unmodifiableMap(values);
    }
    private static List<String> questions(List<String> questionIds) {
        if(questionIds==null)return null;
        List<String> copy=List.copyOf(questionIds);
        for(String question:copy)if(!question.matches("Q[0-9]{2}"))throw new IllegalArgumentException("Invalid question reference");
        return copy;
    }
    static void requireKey(String key) {
        if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException("Invalid configuration key");
    }
    static void requireUuid(String value) {
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid trusted identifier");
    }
}
