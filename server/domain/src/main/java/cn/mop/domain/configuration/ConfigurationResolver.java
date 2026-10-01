package cn.mop.domain.configuration;

import cn.mop.domain.configuration.ConfigurationScope.Context;
import cn.mop.domain.configuration.ConfigurationScope.Environment;
import cn.mop.domain.configuration.ConfigurationScope.Kind;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure resolution of explicitly supplied runtime records, not a registry importer or evidence verifier.
 * Persistence must establish the truth of publication, authorization and customer verification records.
 * Definitions declare low-to-high precedence. No hierarchy, business range or approval is inferred.
 */
public final class ConfigurationResolver {
    private ConfigurationResolver() {}

    public enum ValueType { BOOLEAN, INTEGER, DECIMAL, STRING, OBJECT, ARRAY, SECRET_REFERENCE }
    public enum Origin { DESIGN_REGISTRY, INTERNAL_REVISION, RUNTIME_PUBLICATION }
    public enum Lifecycle { DRAFT, IN_REVIEW, APPROVED, PUBLISHED, REJECTED, WITHDRAWN, INVALIDATED }
    public enum Status { RESOLVED, CONFIG_PENDING }
    public enum ReasonCode {
        MISSING_CONFIGURATION, FORBIDDEN_OVERRIDE, AMBIGUOUS_CONFIGURATION,
        MISSING_EFFECTIVE_AT, NOT_YET_EFFECTIVE, EXPIRED,
        DESIGN_REGISTRY_NOT_RUNTIME_CONFIG, UNPUBLISHED,
        PUBLICATION_MISSING, PUBLICATION_UNVERIFIED, PUBLICATION_BINDING_MISMATCH,
        PUBLICATION_WRONG_ENVIRONMENT, PRODUCTION_APPROVAL_PENDING, INVALID_PUBLICATION_TIME,
        CUSTOMER_CONFIRMATION_MISSING, CUSTOMER_CONFIRMATION_UNVERIFIED,
        CUSTOMER_CONFIRMATION_BINDING_MISMATCH, INVALID_CONFIRMATION_TIME,
        VALUE_MISSING, TYPE_MISMATCH, OUT_OF_RANGE
    }

    /** Numeric bounds are inclusive and must be supplied explicitly; null means no declared bound. */
    public record Definition(String key, long schemaVersion, ValueType valueType, String unit,
                             String sourceRef, boolean sensitive, boolean customerConfirmationRequired,
                             List<Kind> precedence, List<String> questionIds,
                             BigDecimal minimum, BigDecimal maximum) {
        public Definition {
            ConfigurationScope.requireText(key, "configuration key");
            if (schemaVersion < 1) throw new IllegalArgumentException("Schema version must be positive");
            Objects.requireNonNull(valueType, "value type");
            ConfigurationScope.requireText(sourceRef, "schema source reference");
            precedence = List.copyOf(Objects.requireNonNull(precedence, "precedence"));
            if (precedence.isEmpty() || new HashSet<>(precedence).size() != precedence.size()) throw new IllegalArgumentException("Precedence must contain unique declared scopes");
            questionIds = questions(questionIds);
            if (customerConfirmationRequired && questionIds.isEmpty()) throw new IllegalArgumentException("Customer-confirmed definitions require affected question identifiers");
            if (sensitive && valueType != ValueType.SECRET_REFERENCE) throw new IllegalArgumentException("Sensitive definitions accept secret references only");
            if ((minimum != null && minimum.getClass() != BigDecimal.class) || (maximum != null && maximum.getClass() != BigDecimal.class)) throw new IllegalArgumentException("Bounds must use immutable base decimal values");
            if ((minimum != null || maximum != null) && valueType != ValueType.INTEGER && valueType != ValueType.DECIMAL) throw new IllegalArgumentException("Numeric bounds require a numeric value type");
            if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw new IllegalArgumentException("Minimum exceeds maximum");
        }
    }

    /** These are verified provenance inputs, not results of internal review or document-reference entry. */
    public record CustomerVerification(String key, String revisionId, long revisionVersion,
                                       ConfigurationScope scope, String decisionRef,
                                       String documentRef, String documentSha256, String documentVersion,
                                       String confirmedBy, Instant confirmedAt, String verifiedBy, Instant verifiedAt,
                                       Boolean authorityVerified, Boolean authenticityVerified) {}

    /** Explicit nullable Booleans distinguish verified approval from unknown approval. */
    public record Publication(String key, String revisionId, long revisionVersion,
                              ConfigurationScope scope, Environment environment, String recordRef,
                              String publishedBy, Instant publishedAt,
                              Boolean authorizationVerified, Boolean productionApprovalVerified) {}

    /**
     * Supply selected runtime scope slots and their dated versions, not every revision in review history.
     * An explicit pending override occupies its layer and blocks inheritance from a lower layer.
     * Validity is [effectiveAt, expiresAt); a null start remains a pending input, never 'now'.
     */
    public record Revision(String key, String revisionId, long version, ConfigurationScope scope,
                           Origin origin, Lifecycle lifecycle, Object value,
                           Instant effectiveAt, Instant expiresAt, List<String> questionIds,
                           CustomerVerification customerVerification, Publication publication) {
        public Revision {
            ConfigurationScope.requireText(key, "configuration key");
            ConfigurationScope.requireText(revisionId, "revision identifier");
            if (version < 1) throw new IllegalArgumentException("Revision version must be positive");
            Objects.requireNonNull(scope, "revision scope");
            Objects.requireNonNull(origin, "revision origin");
            Objects.requireNonNull(lifecycle, "revision lifecycle");
            if (effectiveAt != null && expiresAt != null && !expiresAt.isAfter(effectiveAt)) throw new IllegalArgumentException("Expiry must follow the effective instant");
            value = ImmutableConfigurationValue.freeze(value);
            questionIds = questions(questionIds);
        }
    }

    public record Reason(ReasonCode code, ConfigurationScope scope, String revisionId, Long version) {}

    /**
     * Selected metadata and reasons survive a pending value. Mismatched provenance is omitted,
     * and rejected sensitive input is redacted rather than copied into a diagnostic snapshot.
     */
    public record Resolution(Definition definition, Context context, Status status, Object effectiveValue,
                             List<Revision> selectedInputs, List<Reason> reasons, List<String> questionIds) {
        public Resolution {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(status, "status");
            effectiveValue = ImmutableConfigurationValue.freeze(effectiveValue);
            selectedInputs = selectedInputs.stream().map(input -> diagnosticInput(definition, input)).toList();
            reasons = List.copyOf(reasons);
            questionIds = questions(questionIds);
            if (status == Status.RESOLVED && (effectiveValue == null || selectedInputs.size() != 1 || !reasons.isEmpty())) throw new IllegalArgumentException("A resolved value requires one eligible input and no blockers");
            if (status == Status.CONFIG_PENDING && (effectiveValue != null || reasons.isEmpty())) throw new IllegalArgumentException("Pending resolution must preserve a null effective value and blockers");
        }
    }

    public static Resolution resolve(Definition definition, Context context, List<Revision> revisions) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(revisions, "revisions");
        List<Revision> matching = revisions.stream().map(revision -> Objects.requireNonNull(revision, "revision"))
                .filter(revision -> definition.key().equals(revision.key()) && revision.scope().matches(context))
                .sorted(Comparator.comparing((Revision revision) -> revision.scope().kind().name())
                        .thenComparing(Revision::revisionId).thenComparingLong(Revision::version)).toList();
        List<Revision> forbidden = matching.stream().filter(revision -> !definition.precedence().contains(revision.scope().kind())).toList();
        if (!forbidden.isEmpty()) return pending(definition, context, forbidden, forbidden.stream().map(revision -> reason(ReasonCode.FORBIDDEN_OVERRIDE, revision)).toList());
        if (matching.isEmpty()) return pending(definition, context, List.of(), List.of(new Reason(ReasonCode.MISSING_CONFIGURATION, null, null, null)));

        int highest = matching.stream().mapToInt(revision -> definition.precedence().indexOf(revision.scope().kind())).max().orElseThrow();
        List<Revision> layer = matching.stream().filter(revision -> definition.precedence().indexOf(revision.scope().kind()) == highest).toList();
        // Undated selected overrides cannot be silently bypassed by a dated revision in the same slot.
        List<Revision> undated = layer.stream().filter(revision -> revision.effectiveAt() == null).toList();
        if (!undated.isEmpty()) return pending(definition, context, layer, undated.stream().map(revision -> reason(ReasonCode.MISSING_EFFECTIVE_AT, revision)).toList());
        List<Revision> active = layer.stream().filter(revision -> !context.at().isBefore(revision.effectiveAt())
                && (revision.expiresAt() == null || context.at().isBefore(revision.expiresAt()))).toList();
        if (active.size() > 1) return pending(definition, context, active, active.stream().map(revision -> reason(ReasonCode.AMBIGUOUS_CONFIGURATION, revision)).toList());
        if (active.isEmpty()) return pending(definition, context, layer, layer.stream().map(revision -> reason(context.at().isBefore(revision.effectiveAt()) ? ReasonCode.NOT_YET_EFFECTIVE : ReasonCode.EXPIRED, revision)).toList());

        Revision selected = active.get(0);
        List<Reason> reasons = eligibility(definition, context, selected);
        return reasons.isEmpty() ? new Resolution(definition, context, Status.RESOLVED, selected.value(), active, List.of(), combinedQuestions(definition, active))
                : pending(definition, context, active, reasons);
    }

    private static List<Reason> eligibility(Definition definition, Context context, Revision revision) {
        List<Reason> reasons = new ArrayList<>();
        if (revision.origin() == Origin.DESIGN_REGISTRY) reasons.add(reason(ReasonCode.DESIGN_REGISTRY_NOT_RUNTIME_CONFIG, revision));
        else if (revision.origin() != Origin.RUNTIME_PUBLICATION) reasons.add(reason(ReasonCode.UNPUBLISHED, revision));
        if (revision.lifecycle() != Lifecycle.PUBLISHED) reasons.add(reason(ReasonCode.UNPUBLISHED, revision));
        Publication publication = revision.publication();
        if (publication == null) reasons.add(reason(ReasonCode.PUBLICATION_MISSING, revision));
        else {
            if (!bound(revision, publication.key(), publication.revisionId(), publication.revisionVersion(), publication.scope())) reasons.add(reason(ReasonCode.PUBLICATION_BINDING_MISMATCH, revision));
            if (!Boolean.TRUE.equals(publication.authorizationVerified()) || !text(publication.recordRef()) || !text(publication.publishedBy())) reasons.add(reason(ReasonCode.PUBLICATION_UNVERIFIED, revision));
            if (publication.environment() != context.environment()) reasons.add(reason(ReasonCode.PUBLICATION_WRONG_ENVIRONMENT, revision));
            if (context.environment() == Environment.PRODUCTION && !Boolean.TRUE.equals(publication.productionApprovalVerified())) reasons.add(reason(ReasonCode.PRODUCTION_APPROVAL_PENDING, revision));
            if (publication.publishedAt() == null || publication.publishedAt().isAfter(context.at()) || publication.publishedAt().isAfter(revision.effectiveAt())) reasons.add(reason(ReasonCode.INVALID_PUBLICATION_TIME, revision));
        }
        if (definition.customerConfirmationRequired()) {
            CustomerVerification verification = revision.customerVerification();
            if (verification == null) reasons.add(reason(ReasonCode.CUSTOMER_CONFIRMATION_MISSING, revision));
            else {
                if (!bound(revision, verification.key(), verification.revisionId(), verification.revisionVersion(), verification.scope())) reasons.add(reason(ReasonCode.CUSTOMER_CONFIRMATION_BINDING_MISMATCH, revision));
                if (!Boolean.TRUE.equals(verification.authorityVerified()) || !Boolean.TRUE.equals(verification.authenticityVerified())
                        || !text(verification.decisionRef()) || !text(verification.documentRef()) || !text(verification.documentVersion())
                        || !text(verification.confirmedBy()) || !text(verification.verifiedBy())
                        || verification.documentSha256() == null || !verification.documentSha256().matches("[a-fA-F0-9]{64}")) reasons.add(reason(ReasonCode.CUSTOMER_CONFIRMATION_UNVERIFIED, revision));
                if (verification.confirmedAt() == null || verification.verifiedAt() == null
                        || verification.confirmedAt().isAfter(verification.verifiedAt()) || verification.verifiedAt().isAfter(context.at())
                        || (publication != null && publication.publishedAt() != null && verification.verifiedAt().isAfter(publication.publishedAt()))) reasons.add(reason(ReasonCode.INVALID_CONFIRMATION_TIME, revision));
            }
        }
        if (revision.value() == null) reasons.add(reason(ReasonCode.VALUE_MISSING, revision));
        else if (!validType(definition.valueType(), revision.value())) reasons.add(reason(ReasonCode.TYPE_MISMATCH, revision));
        else if ((definition.minimum() != null || definition.maximum() != null) && outsideRange(definition, (Number) revision.value())) reasons.add(reason(ReasonCode.OUT_OF_RANGE, revision));
        return reasons.stream().distinct().toList();
    }

    private static boolean validType(ValueType type, Object value) {
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger;
            case DECIMAL -> value instanceof Number;
            case STRING -> value instanceof String;
            case OBJECT -> value instanceof Map<?, ?>;
            case ARRAY -> value instanceof List<?>;
            case SECRET_REFERENCE -> value instanceof Map<?, ?> map && map.size() == 1 && map.get("secret_ref") instanceof String reference && !reference.isBlank();
        };
    }

    private static boolean outsideRange(Definition definition, Number value) {
        BigDecimal decimal = new BigDecimal(value.toString());
        return (definition.minimum() != null && decimal.compareTo(definition.minimum()) < 0)
                || (definition.maximum() != null && decimal.compareTo(definition.maximum()) > 0);
    }

    private static boolean bound(Revision revision, String key, String id, long version, ConfigurationScope scope) {
        return revision.key().equals(key) && revision.revisionId().equals(id) && revision.version() == version && revision.scope().equals(scope);
    }

    private static Revision diagnosticInput(Definition definition, Revision revision) {
        CustomerVerification verification = revision.customerVerification();
        if (verification != null && !bound(revision, verification.key(), verification.revisionId(), verification.revisionVersion(), verification.scope())) verification = null;
        Publication publication = revision.publication();
        if (publication != null && !bound(revision, publication.key(), publication.revisionId(), publication.revisionVersion(), publication.scope())) publication = null;
        Object value = revision.value();
        if (definition.sensitive() && value != null && !validType(ValueType.SECRET_REFERENCE, value)) value = null;
        return new Revision(revision.key(), revision.revisionId(), revision.version(), revision.scope(), revision.origin(),
                revision.lifecycle(), value, revision.effectiveAt(), revision.expiresAt(), revision.questionIds(), verification, publication);
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static Reason reason(ReasonCode code, Revision revision) { return new Reason(code, revision.scope(), revision.revisionId(), revision.version()); }
    private static Resolution pending(Definition definition, Context context, List<Revision> inputs, List<Reason> reasons) {
        return new Resolution(definition, context, Status.CONFIG_PENDING, null, inputs, reasons, combinedQuestions(definition, inputs));
    }

    private static List<String> combinedQuestions(Definition definition, List<Revision> inputs) {
        Set<String> result = new TreeSet<>(definition.questionIds());
        inputs.forEach(input -> result.addAll(input.questionIds()));
        return List.copyOf(result);
    }

    private static List<String> questions(List<String> questions) {
        Objects.requireNonNull(questions, "question identifiers");
        Set<String> result = new TreeSet<>();
        for (String question : questions) {
            if (question == null || !question.matches("Q[0-9]{2}")) throw new IllegalArgumentException("Question identifier must use Q plus two digits");
            result.add(question);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
}
