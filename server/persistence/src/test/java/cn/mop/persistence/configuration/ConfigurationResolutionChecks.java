package cn.mop.persistence.configuration;

import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.ConfigurationResolver;
import cn.mop.domain.configuration.ConfigurationResolver.CustomerVerification;
import cn.mop.domain.configuration.ConfigurationResolver.Definition;
import cn.mop.domain.configuration.ConfigurationResolver.Lifecycle;
import cn.mop.domain.configuration.ConfigurationResolver.Origin;
import cn.mop.domain.configuration.ConfigurationResolver.Publication;
import cn.mop.domain.configuration.ConfigurationResolver.ReasonCode;
import cn.mop.domain.configuration.ConfigurationResolver.Resolution;
import cn.mop.domain.configuration.ConfigurationResolver.Revision;
import cn.mop.domain.configuration.ConfigurationResolver.Status;
import cn.mop.domain.configuration.ConfigurationResolver.ValueType;
import cn.mop.domain.configuration.ConfigurationScope;
import cn.mop.domain.configuration.ConfigurationScope.Context;
import cn.mop.domain.configuration.ConfigurationScope.Environment;
import cn.mop.domain.configuration.ConfigurationScope.Kind;
import cn.mop.domain.configuration.ConfigurationSnapshot;
import cn.mop.domain.configuration.ConfigurationSnapshot.ObjectKind;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Runnable without JUnit: synthetic provenance exercises the pure contract, never external verification. */
public final class ConfigurationResolutionChecks {
    private static final String KEY = "test.configuration";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant SWITCH = Instant.parse("2026-10-01T00:00:00Z");
    private static final Context A = context("test-tenant-a", SWITCH);
    private static final ConfigurationScope PLATFORM = new ConfigurationScope(Kind.PLATFORM, null, null);
    private static final ConfigurationScope TENANT_A = new ConfigurationScope(Kind.TENANT, "test-tenant-a", "test-tenant-a");
    private static final ConfigurationScope TENANT_B = new ConfigurationScope(Kind.TENANT, "test-tenant-b", "test-tenant-b");
    private static final List<Kind> LAYERS = List.of(Kind.PLATFORM, Kind.TENANT);
    private static int assertions;

    private ConfigurationResolutionChecks() {}

    public static void main(String[] arguments) {
        missingAndPendingPreserveQuestions();
        tenantIsolationPrecedesDiagnostics();
        overridesRequireAnExplicitLayer();
        suppliedPrecedenceControlsResolution();
        pendingOverridesNeverFallBack();
        activeConflictsNeverChooseTheNewestOrFirst();
        timeBoundariesSelectDisjointVersions();
        futureExpiredAndUndatedOverridesBlock();
        internalApprovalAndRegistryValuesStayPending();
        customerEvidenceMustBeVerifiedAndBound();
        publicationMustBeAuthorizedBoundAndDated();
        productionApprovalCannotDefaultToTrue();
        nullZeroFalseAndDeclaredRangesRemainDistinct();
        diagnosticAndCreateTimeSnapshotsAreDetached();
        sensitiveAndMalformedValuesFailClosed();
        System.out.println("{\"status\":\"PASS\",\"groups\":15,\"assertions\":" + assertions
                + ",\"scope\":\"pure_jdk_configuration_resolution\",\"provenance\":\"synthetic_contract_inputs_only\",\"junit\":\"NOT_RUN\",\"integration\":\"NOT_RUN\"}");
    }

    public static void missingAndPendingPreserveQuestions() {
        Resolution missing = resolve(definition(ValueType.INTEGER, true), List.of());
        pending(missing, ReasonCode.MISSING_CONFIGURATION);
        equal(List.of("Q08", "Q26"), missing.questionIds(), "Missing values retain affected questions");
        Revision pending = revision("test-pending", 1, TENANT_A, null, START, null, Origin.INTERNAL_REVISION, Lifecycle.APPROVED, List.of("Q09"), null, null);
        Resolution result = resolve(definition(ValueType.INTEGER, true), List.of(pending));
        pending(result, ReasonCode.VALUE_MISSING);
        equal(List.of("Q08", "Q09", "Q26"), result.questionIds(), "Selected-input questions are included once");
        equal(null, result.selectedInputs().get(0).value(), "Diagnostic input preserves explicit null");
    }

    public static void tenantIsolationPrecedesDiagnostics() {
        Revision foreign = revision("test-foreign-secret-marker", 1, TENANT_B, 99, START, null, Origin.INTERNAL_REVISION, Lifecycle.APPROVED, List.of("Q31"), null, null);
        Resolution result = resolve(definition(ValueType.INTEGER, true), List.of(foreign));
        pending(result, ReasonCode.MISSING_CONFIGURATION);
        equal(List.of(), result.selectedInputs(), "Other tenant inputs are excluded from snapshots");
        equal(List.of("Q08", "Q26"), result.questionIds(), "Other tenant questions are excluded");
        require(result.reasons().stream().allMatch(reason -> reason.scope() == null && reason.revisionId() == null), "Other tenant identifiers are not diagnostic output");
        Revision local = published("test-local", 1, TENANT_A, 0, START, null, true);
        equal(0, resolve(definition(ValueType.INTEGER, true), List.of(foreign, local)).effectiveValue(), "Foreign input cannot override local value");
        ConfigurationScope foreignDevice = new ConfigurationScope(Kind.DEVICE, "test-tenant-b", "same-device-id");
        Context deviceContext = new Context("test-tenant-a", Environment.REMOTE_TEST, SWITCH, Map.of(Kind.DEVICE, "same-device-id"));
        require(!foreignDevice.matches(deviceContext), "Shared device identifier does not erase tenant ownership");
        for (Kind kind : List.of(Kind.SITE, Kind.DEVICE, Kind.RULE, Kind.SERVICE_TIER, Kind.INTERFACE_INSTANCE)) {
            Context scoped = new Context("test-tenant-a", Environment.REMOTE_TEST, SWITCH, Map.of(kind, "test-matching-scope"));
            require(new ConfigurationScope(kind, "test-tenant-a", "test-matching-scope").matches(scoped), "Declared local scope matches: " + kind);
            require(!new ConfigurationScope(kind, "test-tenant-b", "test-matching-scope").matches(scoped), "Foreign ownership never matches: " + kind);
            require(!new ConfigurationScope(kind, "test-tenant-a", "test-other-scope").matches(scoped), "Unrelated local identifier never matches: " + kind);
        }
    }

    public static void overridesRequireAnExplicitLayer() {
        Definition platformOnly = definition(ValueType.INTEGER, false, List.of(Kind.PLATFORM), null, null);
        Revision validBase = published("test-base", 1, PLATFORM, 1, START, null, false);
        Revision forbidden = published("test-forbidden", 2, TENANT_A, 2, START, null, false);
        Resolution result = resolve(platformOnly, List.of(validBase, forbidden));
        pending(result, ReasonCode.FORBIDDEN_OVERRIDE);
        equal(List.of(forbidden), result.selectedInputs(), "Forbidden input is explained rather than silently applied");
        equal(1, resolve(platformOnly, List.of(validBase)).effectiveValue(), "Explicitly declared platform layer can resolve");
        Context noPool = new Context("test-tenant-a", Environment.REMOTE_TEST, SWITCH, Map.of());
        require(!new ConfigurationScope(Kind.RESOURCE_POOL, null, "test-pool").matches(noPool), "Shared pool membership must be explicit");
        Context pool = new Context("test-tenant-a", Environment.REMOTE_TEST, SWITCH, Map.of(Kind.RESOURCE_POOL, "test-pool"));
        require(new ConfigurationScope(Kind.RESOURCE_POOL, null, "test-pool").matches(pool), "Trusted matching shared pool is supported");
    }

    public static void suppliedPrecedenceControlsResolution() {
        Revision base = published("test-base", 10, PLATFORM, 10, START, null, false);
        Revision tenant = published("test-tenant", 1, TENANT_A, 20, START, null, false);
        Definition usual = definition(ValueType.INTEGER, false);
        equal(20, resolve(usual, List.of(base, tenant)).effectiveValue(), "Declared higher layer wins even with smaller revision number");
        equal(resolve(usual, List.of(base, tenant)), resolve(usual, List.of(tenant, base)), "Caller input order does not choose a layer");
        Definition reversed = definition(ValueType.INTEGER, false, List.of(Kind.TENANT, Kind.PLATFORM), null, null);
        equal(10, resolve(reversed, List.of(tenant, base)).effectiveValue(), "No hierarchy is inferred over supplied schema");
    }

    public static void pendingOverridesNeverFallBack() {
        Revision base = published("test-base", 1, PLATFORM, 4, START, null, false);
        Revision explicitNull = published("test-null-override", 1, TENANT_A, null, START, null, false);
        pending(resolve(definition(ValueType.INTEGER, false), List.of(base, explicitNull)), ReasonCode.VALUE_MISSING);
        Revision unverified = published("test-unverified-override", 1, TENANT_A, 8, START, null, false);
        pending(resolve(definition(ValueType.INTEGER, true), List.of(base, unverified)), ReasonCode.CUSTOMER_CONFIRMATION_MISSING);
    }

    public static void activeConflictsNeverChooseTheNewestOrFirst() {
        Revision old = published("test-old", 1, TENANT_A, 10, START, null, true);
        Revision newer = published("test-newer", 100, TENANT_A, 20, START, null, true);
        Definition definition = definition(ValueType.INTEGER, true);
        Resolution conflict = resolve(definition, List.of(old, newer));
        pending(conflict, ReasonCode.AMBIGUOUS_CONFIGURATION);
        equal(2, conflict.selectedInputs().size(), "Overlapping inputs remain visible for conflict diagnosis");
        equal(conflict, resolve(definition, List.of(newer, old)), "Conflict output does not depend on input order");
        pending(resolve(definition, List.of(old, old)), ReasonCode.AMBIGUOUS_CONFIGURATION);
    }

    public static void timeBoundariesSelectDisjointVersions() {
        Revision old = published("test-version-one", 1, TENANT_A, 10, START, SWITCH, true);
        Revision next = published("test-version-two", 2, TENANT_A, 20, SWITCH, null, true);
        Definition definition = definition(ValueType.INTEGER, true);
        equal(10, ConfigurationResolver.resolve(definition, context("test-tenant-a", SWITCH.minusNanos(1)), List.of(next, old)).effectiveValue(), "Instant before boundary uses old version");
        Resolution boundary = resolve(definition, List.of(old, next));
        equal(20, boundary.effectiveValue(), "Exact activation boundary uses new version");
        equal(2L, boundary.selectedInputs().get(0).version(), "Chosen version is retained");
        pending(ConfigurationResolver.resolve(definition, context("test-tenant-a", START.minusNanos(1)), List.of(old)), ReasonCode.NOT_YET_EFFECTIVE);
        pending(resolve(definition, List.of(old)), ReasonCode.EXPIRED);
        expect(IllegalArgumentException.class, () -> published("test-invalid-window", 1, TENANT_A, 1, SWITCH, SWITCH, true));
    }

    public static void futureExpiredAndUndatedOverridesBlock() {
        Revision base = published("test-base", 1, PLATFORM, 1, START, null, false);
        Revision future = published("test-future", 1, TENANT_A, 2, SWITCH.plusSeconds(1), null, false);
        pending(resolve(definition(ValueType.INTEGER, false), List.of(base, future)), ReasonCode.NOT_YET_EFFECTIVE);
        Revision expired = published("test-expired", 1, TENANT_A, 2, START, SWITCH, false);
        pending(resolve(definition(ValueType.INTEGER, false), List.of(base, expired)), ReasonCode.EXPIRED);
        Revision undated = revision("test-undated", 1, TENANT_A, 2, null, null, Origin.INTERNAL_REVISION, Lifecycle.DRAFT, List.of(), null, null);
        pending(resolve(definition(ValueType.INTEGER, false), List.of(base, undated)), ReasonCode.MISSING_EFFECTIVE_AT);
        pending(resolve(definition(ValueType.INTEGER, false), List.of(published("test-active", 1, TENANT_A, 3, START, null, false), undated)), ReasonCode.MISSING_EFFECTIVE_AT);
    }

    public static void internalApprovalAndRegistryValuesStayPending() {
        Revision runtime = published("test-origin", 1, TENANT_A, 4, START, null, true);
        Revision registry = revision(runtime.revisionId(), 1, TENANT_A, 4, START, null, Origin.DESIGN_REGISTRY, Lifecycle.PUBLISHED, List.of(), runtime.customerVerification(), runtime.publication());
        pending(resolve(definition(ValueType.INTEGER, true), List.of(registry)), ReasonCode.DESIGN_REGISTRY_NOT_RUNTIME_CONFIG);
        Revision approved = revision(runtime.revisionId(), 1, TENANT_A, 4, START, null, Origin.INTERNAL_REVISION, Lifecycle.APPROVED, List.of(), runtime.customerVerification(), runtime.publication());
        pending(resolve(definition(ValueType.INTEGER, true), List.of(approved)), ReasonCode.UNPUBLISHED);
        for (Lifecycle lifecycle : List.of(Lifecycle.DRAFT, Lifecycle.IN_REVIEW, Lifecycle.APPROVED, Lifecycle.REJECTED, Lifecycle.WITHDRAWN, Lifecycle.INVALIDATED)) {
            Revision inactive = revision(runtime.revisionId(), 1, TENANT_A, 4, START, null, Origin.RUNTIME_PUBLICATION, lifecycle, List.of(), runtime.customerVerification(), runtime.publication());
            pending(resolve(definition(ValueType.INTEGER, true), List.of(inactive)), ReasonCode.UNPUBLISHED);
        }
    }

    public static void customerEvidenceMustBeVerifiedAndBound() {
        Revision runtime = published("test-customer", 1, TENANT_A, 4, START, null, true);
        CustomerVerification verification = runtime.customerVerification();
        CustomerVerification foreign = verification(runtime.key(), runtime.revisionId(), 1, TENANT_B, START.minusSeconds(2), START.minusSeconds(1), true, true, "b".repeat(64));
        Resolution foreignEvidence = resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, foreign, runtime.publication())));
        pending(foreignEvidence, ReasonCode.CUSTOMER_CONFIRMATION_BINDING_MISMATCH);
        equal(null, foreignEvidence.selectedInputs().get(0).customerVerification(), "Foreign-bound evidence never appears in diagnostic inputs");
        CustomerVerification wrongVersion = verification(runtime.key(), runtime.revisionId(), 2, TENANT_A, START.minusSeconds(2), START.minusSeconds(1), true, true, "b".repeat(64));
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, wrongVersion, runtime.publication()))), ReasonCode.CUSTOMER_CONFIRMATION_BINDING_MISMATCH);
        for (Boolean flag : Arrays.asList(null, false)) {
            CustomerVerification unknownAuthority = verification(runtime.key(), runtime.revisionId(), 1, TENANT_A, START.minusSeconds(2), START.minusSeconds(1), flag, true, "b".repeat(64));
            pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, unknownAuthority, runtime.publication()))), ReasonCode.CUSTOMER_CONFIRMATION_UNVERIFIED);
            CustomerVerification unverifiedDocument = verification(runtime.key(), runtime.revisionId(), 1, TENANT_A, START.minusSeconds(2), START.minusSeconds(1), true, flag, "b".repeat(64));
            pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, unverifiedDocument, runtime.publication()))), ReasonCode.CUSTOMER_CONFIRMATION_UNVERIFIED);
        }
        CustomerVerification invalidHash = verification(runtime.key(), runtime.revisionId(), 1, TENANT_A, START.minusSeconds(2), START.minusSeconds(1), true, true, "document-reference-is-not-verification");
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, invalidHash, runtime.publication()))), ReasonCode.CUSTOMER_CONFIRMATION_UNVERIFIED);
        CustomerVerification late = verification(runtime.key(), runtime.revisionId(), 1, TENANT_A, SWITCH, SWITCH, true, true, "b".repeat(64));
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, late, runtime.publication()))), ReasonCode.INVALID_CONFIRMATION_TIME);
        CustomerVerification reversed = verification(runtime.key(), runtime.revisionId(), 1, TENANT_A, START, START.minusSeconds(1), true, true, "b".repeat(64));
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, reversed, runtime.publication()))), ReasonCode.INVALID_CONFIRMATION_TIME);
        equal(verification, resolve(definition(ValueType.INTEGER, true), List.of(runtime)).selectedInputs().get(0).customerVerification(), "Verified customer provenance is preserved");
        Revision platform = published("test-platform-confirmation", 1, PLATFORM, 4, START, null, true);
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(platform, verification, platform.publication()))), ReasonCode.CUSTOMER_CONFIRMATION_BINDING_MISMATCH);
    }

    public static void publicationMustBeAuthorizedBoundAndDated() {
        Revision runtime = published("test-publication", 1, TENANT_A, 4, START, null, true);
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, runtime.customerVerification(), null))), ReasonCode.PUBLICATION_MISSING);
        Publication foreign = publication(runtime, TENANT_B, Environment.REMOTE_TEST, START, true, false);
        Resolution foreignPublication = resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, runtime.customerVerification(), foreign)));
        pending(foreignPublication, ReasonCode.PUBLICATION_BINDING_MISMATCH);
        equal(null, foreignPublication.selectedInputs().get(0).publication(), "Foreign-bound publication never appears in diagnostic inputs");
        for (Publication mismatched : List.of(
                new Publication("test-other-key", runtime.revisionId(), 1, TENANT_A, Environment.REMOTE_TEST, "test-record", "test-publisher", START, true, false),
                new Publication(runtime.key(), "test-other-revision", 1, TENANT_A, Environment.REMOTE_TEST, "test-record", "test-publisher", START, true, false),
                new Publication(runtime.key(), runtime.revisionId(), 2, TENANT_A, Environment.REMOTE_TEST, "test-record", "test-publisher", START, true, false))) {
            Resolution result = resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, runtime.customerVerification(), mismatched)));
            pending(result, ReasonCode.PUBLICATION_BINDING_MISMATCH);
            equal(null, result.selectedInputs().get(0).publication(), "Mismatched publication references are removed from diagnostic output");
        }
        for (Boolean flag : Arrays.asList(null, false)) {
            Publication unverified = publication(runtime, TENANT_A, Environment.REMOTE_TEST, START, flag, false);
            pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, runtime.customerVerification(), unverified))), ReasonCode.PUBLICATION_UNVERIFIED);
        }
        for (Instant date : Arrays.asList(null, START.plusSeconds(1), SWITCH.plusSeconds(1))) {
            Publication invalid = publication(runtime, TENANT_A, Environment.REMOTE_TEST, date, true, false);
            pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, runtime.customerVerification(), invalid))), ReasonCode.INVALID_PUBLICATION_TIME);
        }
        Publication wrongEnvironment = publication(runtime, TENANT_A, Environment.PRODUCTION, START, true, true);
        pending(resolve(definition(ValueType.INTEGER, true), List.of(withEvidence(runtime, runtime.customerVerification(), wrongEnvironment))), ReasonCode.PUBLICATION_WRONG_ENVIRONMENT);
    }

    public static void productionApprovalCannotDefaultToTrue() {
        Revision runtime = published("test-production", 1, TENANT_A, 0, START, null, true);
        Context production = new Context("test-tenant-a", Environment.PRODUCTION, SWITCH, Map.of());
        for (Boolean approval : Arrays.asList(null, false)) {
            Publication publication = publication(runtime, TENANT_A, Environment.PRODUCTION, START, true, approval);
            pending(ConfigurationResolver.resolve(definition(ValueType.INTEGER, true), production, List.of(withEvidence(runtime, runtime.customerVerification(), publication))), ReasonCode.PRODUCTION_APPROVAL_PENDING);
        }
        Publication approved = publication(runtime, TENANT_A, Environment.PRODUCTION, START, true, true);
        equal(0, ConfigurationResolver.resolve(definition(ValueType.INTEGER, true), production, List.of(withEvidence(runtime, runtime.customerVerification(), approved))).effectiveValue(), "Explicit production approval is accepted as an input contract");
        pending(ConfigurationResolver.resolve(definition(ValueType.INTEGER, true), production, List.of(runtime)), ReasonCode.PUBLICATION_WRONG_ENVIRONMENT);
    }

    public static void nullZeroFalseAndDeclaredRangesRemainDistinct() {
        equal(0, resolve(definition(ValueType.INTEGER, false), List.of(published("test-zero", 1, TENANT_A, 0, START, null, false))).effectiveValue(), "Legitimate integer zero is configured");
        equal(false, resolve(definition(ValueType.BOOLEAN, false), List.of(published("test-false", 1, TENANT_A, false, START, null, false))).effectiveValue(), "Legitimate false is configured");
        pending(resolve(definition(ValueType.BOOLEAN, false), List.of(published("test-null", 1, TENANT_A, null, START, null, false))), ReasonCode.VALUE_MISSING);
        pending(resolve(definition(ValueType.INTEGER, false), List.of(published("test-fraction-as-int", 1, TENANT_A, new BigDecimal("0.0"), START, null, false))), ReasonCode.TYPE_MISMATCH);
        Definition bounded = definition(ValueType.DECIMAL, false, LAYERS, BigDecimal.ZERO, BigDecimal.ONE);
        for (BigDecimal valid : List.of(BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("0.00001"))) equal(valid, resolve(bounded, List.of(published("test-bounds", 1, TENANT_A, valid, START, null, false))).effectiveValue(), "Declared inclusive range preserves numeric representation");
        for (BigDecimal invalid : List.of(new BigDecimal("-0.00001"), new BigDecimal("1.00001"))) pending(resolve(bounded, List.of(published("test-bounds", 1, TENANT_A, invalid, START, null, false))), ReasonCode.OUT_OF_RANGE);
        equal(-100, resolve(definition(ValueType.INTEGER, false), List.of(published("test-no-invented-range", 1, TENANT_A, -100, START, null, false))).effectiveValue(), "No range is invented when schema declares none");
    }

    @SuppressWarnings("unchecked")
    public static void diagnosticAndCreateTimeSnapshotsAreDetached() {
        List<Object> nested = new ArrayList<>(Arrays.asList(null, 0, false));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("unknown", null);
        source.put("nested", nested);
        Revision old = published("test-frozen-one", 1, TENANT_A, source, START, SWITCH, true);
        source.put("unknown", "caller changed source");
        nested.set(1, 99);
        List<Revision> revisions = new ArrayList<>(List.of(old));
        List<Definition> definitions = new ArrayList<>(List.of(definition(ValueType.OBJECT, true)));
        Context before = context("test-tenant-a", SWITCH.minusNanos(1));
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.forCreation(ObjectKind.TICKET, "test-ticket-one", before, definitions, revisions);
        Map<String, Object> value = (Map<String, Object>) snapshot.entries().get(KEY).effectiveValue();
        equal(null, value.get("unknown"), "Source map mutations cannot change frozen null");
        equal(Arrays.asList(null, 0, false), value.get("nested"), "Source list mutations cannot change frozen elements");
        equal(null, snapshot.entries().get(KEY).definition().unit(), "Nullable units are retained");
        equal(before, snapshot.context(), "Create-time instant and tenant are retained");
        equal(ObjectKind.TICKET, snapshot.objectKind(), "Object kind is retained");
        equal("test-ticket-one", snapshot.objectId(), "Object identifier is retained");
        expect(UnsupportedOperationException.class, () -> value.put("unknown", 1));
        expect(UnsupportedOperationException.class, () -> ((List<Object>) value.get("nested")).set(0, 1));
        expect(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        expect(UnsupportedOperationException.class, () -> snapshot.entries().get(KEY).selectedInputs().clear());
        revisions.clear();
        definitions.clear();
        Revision next = published("test-frozen-two", 2, TENANT_A, Map.of("nested", List.of(10, true)), SWITCH, null, true);
        ConfigurationSnapshot fresh = ConfigurationSnapshot.forCreation(ObjectKind.REPORT, "test-report-two", A, List.of(definition(ValueType.OBJECT, true)), List.of(next, old));
        equal(2L, fresh.entries().get(KEY).selectedInputs().get(0).version(), "New object captures new revision");
        equal(1L, snapshot.entries().get(KEY).selectedInputs().get(0).version(), "Historical object keeps original revision");
        equal(Arrays.asList(null, 0, false), value.get("nested"), "Historical value stays unchanged after new resolution");
        ConfigurationSnapshot diagnostic = ConfigurationSnapshot.diagnostic(A, List.of(definition(ValueType.INTEGER, true)), List.of());
        require(!diagnostic.resolved(), "Diagnostic snapshot can describe pending inputs");
        equal(null, diagnostic.entries().get(KEY).effectiveValue(), "Pending diagnostic retains null effective value");
        equal(ConfigurationSnapshot.Purpose.DIAGNOSTIC, diagnostic.purpose(), "Diagnostic purpose is explicit");
        DomainException blocked = expect(DomainException.class, () -> ConfigurationSnapshot.forCreation(ObjectKind.TICKET, "test-blocked", A, List.of(definition(ValueType.INTEGER, true)), List.of()));
        equal(422, blocked.status, "Creation fails with the documented HTTP status");
        equal("CONFIG_PENDING", blocked.code, "Creation gate uses documented domain error");
        Map<String, Object> detail = (Map<String, Object>) blocked.details.get(0);
        equal(List.of("Q08", "Q26"), detail.get("question_ids"), "Creation gate exposes affected questions");
        require(!detail.containsKey("value") && !detail.containsKey("selected_inputs"), "Creation error details omit configuration values");
        Map<Kind, String> mutableScopes = new HashMap<>();
        mutableScopes.put(Kind.DEVICE, "test-device-one");
        Context detached = new Context("test-tenant-a", Environment.REMOTE_TEST, SWITCH, mutableScopes);
        mutableScopes.put(Kind.DEVICE, "test-device-two");
        equal("test-device-one", detached.authorizedScopeIds().get(Kind.DEVICE), "Context freezes authorized scope identifiers");
        expect(UnsupportedOperationException.class, () -> detached.authorizedScopeIds().clear());
    }

    public static void sensitiveAndMalformedValuesFailClosed() {
        Definition sensitive = new Definition(KEY, 1, ValueType.SECRET_REFERENCE, null, "test-schema-ref", true, false, LAYERS, List.of("Q28"), null, null);
        Map<String, Object> reference = Map.of("secret_ref", "test-private-reference");
        equal(reference, resolve(sensitive, List.of(published("test-secret-ref", 1, TENANT_A, reference, START, null, false))).effectiveValue(), "Sensitive input can carry a reference");
        Resolution cleartext = resolve(sensitive, List.of(published("test-cleartext", 1, TENANT_A, Map.of("secret_ref", "test-ref", "password", "synthetic-marker"), START, null, false)));
        pending(cleartext, ReasonCode.TYPE_MISMATCH);
        equal(null, cleartext.selectedInputs().get(0).value(), "Rejected sensitive cleartext is redacted from diagnostics");
        pending(resolve(sensitive, List.of(published("test-empty-ref", 1, TENANT_A, Map.of("secret_ref", ""), START, null, false))), ReasonCode.TYPE_MISMATCH);
        expect(IllegalArgumentException.class, () -> new Definition(KEY, 1, ValueType.STRING, null, "test-schema-ref", true, false, LAYERS, List.of(), null, null));
        for (Object unsupported : List.of(new AtomicInteger(1), Double.NaN, Double.POSITIVE_INFINITY, new int[]{1}, new Object())) expect(IllegalArgumentException.class, () -> published("test-invalid-json", 1, TENANT_A, unsupported, START, null, false));
        for (Object extensibleNumber : List.of(new MutableDecimal(), new MutableInteger())) expect(IllegalArgumentException.class, () -> published("test-extensible-number", 1, TENANT_A, extensibleNumber, START, null, false));
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        expect(IllegalArgumentException.class, () -> published("test-cycle", 1, TENANT_A, cyclic, START, null, false));
        expect(IllegalArgumentException.class, () -> definition(ValueType.INTEGER, false, List.of(Kind.TENANT, Kind.TENANT), null, null));
        expect(IllegalArgumentException.class, () -> definition(ValueType.INTEGER, false, List.of(), null, null));
        expect(IllegalArgumentException.class, () -> definition(ValueType.INTEGER, false, LAYERS, BigDecimal.ONE, BigDecimal.ZERO));
        MutableDecimal mutableBound = new MutableDecimal();
        mutableBound.representation = "999";
        expect(IllegalArgumentException.class, () -> definition(ValueType.INTEGER, false, LAYERS, mutableBound, null));
        expect(IllegalArgumentException.class, () -> definition(ValueType.INTEGER, false, LAYERS, null, mutableBound));
        expect(IllegalArgumentException.class, () -> new Definition(KEY, 1, ValueType.INTEGER, null, "test-schema-ref", false, true, LAYERS, List.of(), null, null));
        expect(IllegalArgumentException.class, () -> new ConfigurationScope(Kind.TENANT, "test-tenant-a", "test-tenant-b"));
        expect(IllegalArgumentException.class, () -> new ConfigurationScope(Kind.DEVICE, null, "test-device"));
        expect(IllegalArgumentException.class, () -> new Context("test-tenant-a", Environment.REMOTE_TEST, SWITCH, Map.of(Kind.TENANT, "test-tenant-b")));
        expect(IllegalArgumentException.class, () -> ConfigurationSnapshot.diagnostic(A, List.of(), List.of()));
        expect(IllegalArgumentException.class, () -> ConfigurationSnapshot.diagnostic(A, List.of(definition(ValueType.INTEGER, false), definition(ValueType.INTEGER, false)), List.of()));
    }

    private static Context context(String tenant, Instant at) { return new Context(tenant, Environment.REMOTE_TEST, at, Map.of()); }
    private static final class MutableDecimal extends BigDecimal {
        private String representation = "1";
        private MutableDecimal() { super("1"); }
        @Override public String toString() { return representation; }
    }
    private static final class MutableInteger extends BigInteger {
        private String representation = "1";
        private MutableInteger() { super("1"); }
        @Override public String toString() { return representation; }
    }
    private static Definition definition(ValueType type, boolean confirmation) { return definition(type, confirmation, LAYERS, null, null); }
    private static Definition definition(ValueType type, boolean confirmation, List<Kind> layers, BigDecimal minimum, BigDecimal maximum) {
        return new Definition(KEY, 1, type, null, "test-schema-ref", false, confirmation, layers, List.of("Q26", "Q08", "Q08"), minimum, maximum);
    }
    private static Resolution resolve(Definition definition, List<Revision> revisions) { return ConfigurationResolver.resolve(definition, A, revisions); }
    private static Revision published(String id, long version, ConfigurationScope scope, Object value, Instant starts, Instant expires, boolean confirmed) {
        CustomerVerification verification = confirmed ? verification(KEY, id, version, scope, starts.minusSeconds(2), starts.minusSeconds(1), true, true, "b".repeat(64)) : null;
        Publication publication = new Publication(KEY, id, version, scope, Environment.REMOTE_TEST, "test-publication-record", "test-publisher", starts, true, false);
        return revision(id, version, scope, value, starts, expires, Origin.RUNTIME_PUBLICATION, Lifecycle.PUBLISHED, List.of(), verification, publication);
    }
    private static Revision revision(String id, long version, ConfigurationScope scope, Object value, Instant starts, Instant expires, Origin origin, Lifecycle lifecycle, List<String> questions, CustomerVerification verification, Publication publication) {
        return new Revision(KEY, id, version, scope, origin, lifecycle, value, starts, expires, questions, verification, publication);
    }
    private static CustomerVerification verification(String key, String id, long version, ConfigurationScope scope, Instant confirmedAt, Instant verifiedAt, Boolean authority, Boolean authenticity, String hash) {
        return new CustomerVerification(key, id, version, scope, "test-decision-record", "document-ref:test-synthetic", hash, "test-document-v1", "test-customer", confirmedAt, "test-verifier", verifiedAt, authority, authenticity);
    }
    private static Publication publication(Revision revision, ConfigurationScope scope, Environment environment, Instant at, Boolean authorized, Boolean productionApproved) {
        return new Publication(revision.key(), revision.revisionId(), revision.version(), scope, environment, "test-publication-record", "test-publisher", at, authorized, productionApproved);
    }
    private static Revision withEvidence(Revision revision, CustomerVerification verification, Publication publication) {
        return revision(revision.revisionId(), revision.version(), revision.scope(), revision.value(), revision.effectiveAt(), revision.expiresAt(), revision.origin(), revision.lifecycle(), revision.questionIds(), verification, publication);
    }
    private static void pending(Resolution result, ReasonCode reason) {
        equal(Status.CONFIG_PENDING, result.status(), "Input must remain pending");
        equal(null, result.effectiveValue(), "Pending must never expose an effective fallback value");
        require(result.reasons().stream().anyMatch(entry -> entry.code() == reason), "Expected pending reason: " + reason);
    }
    private static void equal(Object expected, Object actual, String message) { require(Objects.equals(expected, actual), message + ": expected=" + expected + ", actual=" + actual); }
    private static void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static <T extends Throwable> T expect(Class<T> type, Runnable action) {
        assertions++;
        try { action.run(); } catch (Throwable thrown) { if (type.isInstance(thrown)) return type.cast(thrown); throw new AssertionError("Expected " + type.getSimpleName() + ", got " + thrown.getClass().getSimpleName(), thrown); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
