package cn.mop.domain.assetdependency;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetdependency.AssetDependencyValidator.AssetFact;
import cn.mop.domain.assetdependency.AssetDependencyValidator.AssetReference;
import cn.mop.domain.assetdependency.AssetDependencyValidator.AuthorizationFacts;
import cn.mop.domain.assetdependency.AssetDependencyValidator.Dependency;
import cn.mop.domain.assetdependency.AssetDependencyValidator.GraphSnapshot;
import cn.mop.domain.assetdependency.AssetDependencyValidator.ValidatedAddition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Executable pure-JDK behavioral checks against real production sources. Synthetic facts here
 * exercise input validation only; they are not a repository, component fixture or integration.
 * Exhaustive four-node graphs use a transitive-closure oracle independent of the production
 * indegree algorithm. Java assertions do not need to be enabled for any check to execute.
 */
public final class AssetDependencyChecks {
    private static final String TENANT = "test-tenant-a";
    private static final AssetReference A = reference("a");
    private static final AssetReference B = reference("b");
    private static final AssetReference C = reference("c");
    private static final AssetReference D = reference("d");
    private static final AuthorizationFacts AUTH = authorized(A, B, C, D);
    private static int assertions;
    private static int groups;
    private static int exhaustiveDags;
    private static int exhaustiveCycles;

    private AssetDependencyChecks() {}

    public static void main(String[] args) {
        group(AssetDependencyChecks::explicitAuthorizationFactsAreRequired);
        group(AssetDependencyChecks::authorizationPrecedesStructuralDiagnostics);
        group(AssetDependencyChecks::selfReferencesAreRejected);
        group(AssetDependencyChecks::directedCyclesAndBatchCyclesAreRejected);
        group(AssetDependencyChecks::completeSnapshotIsRequired);
        group(AssetDependencyChecks::validDirectedShapesAreAccepted);
        group(AssetDependencyChecks::parallelEdgesDoNotInventUniquenessOrCycles);
        group(AssetDependencyChecks::nullMetadataAndInputSnapshotsArePreserved);
        group(AssetDependencyChecks::malformedEvidenceIsRejected);
        group(AssetDependencyChecks::orderingDoesNotChangeValidation);
        group(AssetDependencyChecks::deepChainsUseNoRecursion);
        group(AssetDependencyChecks::exhaustiveFourNodeGraphsMatchIndependentOracle);
        System.out.println("{\"status\":\"PASS\",\"groups\":" + groups
            + ",\"assertions\":" + assertions + ",\"exhaustive_graphs\":4096"
            + ",\"exhaustive_dags\":" + exhaustiveDags
            + ",\"exhaustive_cycles\":" + exhaustiveCycles
            + ",\"deep_chain_edges\":25000}");
    }

    public static void explicitAuthorizationFactsAreRequired() {
        List<Dependency> edge = List.of(edge(A, B));
        DomainException missingTenant = missing(new AuthorizationFacts(Set.of(), AUTH.assetFacts()), empty(), edge);
        missing(new AuthorizationFacts(Set.of("test-tenant-b"), AUTH.assetFacts()), empty(), edge);
        missing(new AuthorizationFacts(Set.of(TENANT), List.of()), empty(), edge);
        missing(authorized(A), empty(), edge);
        missing(authorized(B), empty(), edge);
        missing(new AuthorizationFacts(Set.of(TENANT), List.of(new AssetFact(A, false), new AssetFact(B, true))), empty(), edge);
        DomainException deniedChild = missing(new AuthorizationFacts(Set.of(TENANT), List.of(new AssetFact(A, true), new AssetFact(B, false))), empty(), edge);
        AssetReference foreignA = new AssetReference("test-tenant-b", A.assetId());
        AuthorizationFacts bothTenants = new AuthorizationFacts(Set.of(TENANT, foreignA.tenantId()),
            List.of(new AssetFact(A, true), new AssetFact(B, true), new AssetFact(foreignA, true)));
        DomainException foreign = missing(bothTenants, empty(), List.of(edge(foreignA, B)));
        missing(bothTenants, empty(), List.of(edge(A, foreignA)));
        missing(new AuthorizationFacts(Set.of(TENANT), List.of(new AssetFact(foreignA, true), new AssetFact(B, true))), empty(), edge);
        missing(authorized(A), graph(edge(A, B)), List.of());
        missing(bothTenants, graph(edge(A, foreignA)), List.of());
        DomainException unknown = missing(AUTH, empty(), List.of(edge(A, reference("not-known"))));
        for (DomainException failure : List.of(deniedChild, foreign, unknown)) {
            equal(missingTenant.getMessage(), failure.getMessage(), "Existence and ownership cannot be distinguished");
            equal(missingTenant.details, failure.details, "No inaccessible object data appears in details");
        }
        ValidatedAddition sameAssetIds = AssetDependencyValidator.validateAddition(
            new AuthorizationFacts(Set.of(TENANT, "test-tenant-b"), List.of(new AssetFact(A, true), new AssetFact(B, true), new AssetFact(foreignA, false))),
            empty(), edge);
        equal(edge, sameAssetIds.dependencies(), "Foreign same-ID facts cannot shadow authorized local facts");
    }

    public static void authorizationPrecedesStructuralDiagnostics() {
        Dependency hidden = edge(A, reference("not-known"));
        missing(AUTH, empty(), List.of(edge(A, A), hidden));
        missing(AUTH, empty(), List.of(hidden, edge(A, A)));
        missing(AUTH, graph(edge(A, B), edge(B, A)), List.of(hidden));
        missing(AUTH, graph(edge(A, A), hidden), List.of());
        missing(new AuthorizationFacts(Set.of(), AUTH.assetFacts()), graph(edge(A, A)), List.of());
        missing(AUTH, new GraphSnapshot(TENANT, 4, false, List.of(hidden)), List.of(edge(A, A)));
    }

    public static void selfReferencesAreRejected() {
        structural("ASSET_DEPENDENCY_SELF_REFERENCE", AUTH, empty(), List.of(edge(A, A)));
        structural("ASSET_DEPENDENCY_SELF_REFERENCE", AUTH, graph(edge(A, A)), List.of());
        structural("ASSET_DEPENDENCY_SELF_REFERENCE", AUTH, empty(),
            List.of(new Dependency(A, A, null, null, false)));
        structural("ASSET_DEPENDENCY_SELF_REFERENCE", AUTH, graph(edge(A, B)), List.of(edge(C, C)));
    }

    public static void directedCyclesAndBatchCyclesAreRejected() {
        GraphSnapshot chain = graph(edge(A, B), edge(B, C));
        structural("ASSET_DEPENDENCY_CYCLE", AUTH, chain, List.of(edge(C, A)));
        equal(3, AssetDependencyValidator.validateAddition(AUTH, chain, List.of(edge(A, C))).dependencies().size(),
            "A forward transitive edge remains acyclic");
        structural("ASSET_DEPENDENCY_CYCLE", AUTH, graph(edge(A, B)), List.of(edge(B, A)));
        structural("ASSET_DEPENDENCY_CYCLE", AUTH, empty(), List.of(edge(A, B), edge(B, C), edge(C, A)));
        structural("ASSET_DEPENDENCY_CYCLE", AUTH, graph(edge(A, B), edge(B, A)), List.of(edge(C, D)));
        structural("ASSET_DEPENDENCY_CYCLE", AUTH, graph(edge(A, B), edge(B, A)), List.of());
        structural("ASSET_DEPENDENCY_CYCLE", AUTH,
            graph(new Dependency(A, B, "网络级联", "抑制通知", true)),
            List.of(new Dependency(B, A, "供电", "抑制自动建单", false)));
        structural("ASSET_DEPENDENCY_CYCLE", AUTH,
            graph(new Dependency(A, B, null, null, null)),
            List.of(new Dependency(B, A, "服务依赖", "抑制通知", null)));
    }

    public static void completeSnapshotIsRequired() {
        DomainException noEvidence = expect(DomainException.class, () -> AssetDependencyValidator.validateAddition(
            AUTH, new GraphSnapshot(TENANT, 5, false, List.of()), List.of()));
        equal(409, noEvidence.status, "Empty dependencies do not prove an incomplete graph is empty");
        equal("ASSET_DEPENDENCY_GRAPH_INCOMPLETE", noEvidence.code, "Completeness failure is explicit");
        DomainException partial = expect(DomainException.class, () -> AssetDependencyValidator.validateAddition(
            AUTH, new GraphSnapshot(TENANT, 5, false, List.of(edge(A, B))), List.of(edge(B, C))));
        equal(noEvidence.code, partial.code, "A partial neighborhood cannot prove global acyclicity");
        equal(List.of(), partial.details, "Incomplete graph diagnostics do not expose asset data");
    }

    public static void validDirectedShapesAreAccepted() {
        AuthorizationFacts noAssets = new AuthorizationFacts(Set.of(TENANT), List.of());
        equal(List.of(), AssetDependencyValidator.validateAddition(noAssets, empty(), List.of()).dependencies(),
            "Explicitly complete empty graph can be valid");
        equal(List.of(), AssetDependencyValidator.validateAddition(AUTH, empty(), List.of()).dependencies(),
            "Known isolated assets do not invent graph edges");
        List<List<Dependency>> valid = List.of(
            List.of(edge(A, B), edge(B, C), edge(C, D)),
            List.of(edge(A, B), edge(A, C), edge(A, D)),
            List.of(edge(A, D), edge(B, D), edge(C, D)),
            List.of(edge(A, B), edge(A, C), edge(B, D), edge(C, D)),
            List.of(edge(B, A), edge(C, B), edge(D, C)),
            List.of(edge(A, B), edge(C, D)));
        for (List<Dependency> edges : valid) {
            equal(edges, AssetDependencyValidator.validateAddition(AUTH, empty(), edges).dependencies(),
                "Acyclic chain, branching, diamond and disconnected components are accepted");
            equal(edges, AssetDependencyValidator.validateAddition(AUTH,
                new GraphSnapshot(TENANT, 8, true, edges), List.of()).dependencies(),
                "The same existing DAG remains valid");
        }
    }

    public static void parallelEdgesDoNotInventUniquenessOrCycles() {
        Dependency network = new Dependency(A, B, "网络级联", "抑制通知", true);
        Dependency power = new Dependency(A, B, "供电", "抑制自动建单", null);
        List<Dependency> existing = List.of(network, network, power);
        List<Dependency> added = List.of(edge(B, C), network);
        ValidatedAddition checked = AssetDependencyValidator.validateAddition(AUTH,
            new GraphSnapshot(TENANT, 9, true, existing), added);
        equal(5, checked.dependencies().size(), "Structural validation does not invent a relationship unique key");
        equal(List.of(network, network, power, edge(B, C), network), checked.dependencies(),
            "Metadata and caller ordering are retained while graph traversal merges parallel arcs");
        structural("ASSET_DEPENDENCY_CYCLE", AUTH,
            new GraphSnapshot(TENANT, 9, true, existing), List.of(edge(B, A), edge(B, A)));
    }

    public static void nullMetadataAndInputSnapshotsArePreserved() {
        Set<String> tenants = new HashSet<>(Set.of(TENANT));
        List<AssetFact> facts = new ArrayList<>(AUTH.assetFacts());
        AuthorizationFacts authorization = new AuthorizationFacts(tenants, facts);
        List<Dependency> stored = new ArrayList<>(List.of(edge(A, B)));
        GraphSnapshot snapshot = new GraphSnapshot(TENANT, 17, true, stored);
        Dependency draft = new Dependency(B, C, null, null, null);
        List<Dependency> additions = new ArrayList<>(List.of(draft));
        tenants.clear();
        facts.clear();
        stored.clear();
        ValidatedAddition result = AssetDependencyValidator.validateAddition(authorization, snapshot, additions);
        additions.clear();
        equal(TENANT, result.tenantId(), "Result retains trusted graph tenant");
        equal(17L, result.baselineRevision(), "Validation does not increment a persisted revision");
        equal(List.of(edge(A, B), draft), result.dependencies(), "Result is detached from all caller lists");
        equal(null, result.dependencies().get(1).type(), "Pending dependency type stays null");
        equal(null, result.dependencies().get(1).suppressionScope(), "Pending suppression scope stays null");
        equal(null, result.dependencies().get(1).enabled(), "Unknown activation stays null");
        equal(List.of(edge(A, B)), snapshot.dependencies(), "The original snapshot has not been modified");
        expect(UnsupportedOperationException.class, () -> authorization.authorizedTenantIds().clear());
        expect(UnsupportedOperationException.class, () -> authorization.assetFacts().clear());
        expect(UnsupportedOperationException.class, () -> snapshot.dependencies().clear());
        expect(UnsupportedOperationException.class, () -> result.dependencies().clear());
        List<Dependency> failedBatch = new ArrayList<>(List.of(edge(C, A)));
        structural("ASSET_DEPENDENCY_CYCLE", authorization,
            new GraphSnapshot(TENANT, 17, true, result.dependencies()), failedBatch);
        equal(List.of(edge(C, A)), failedBatch, "Rejected additions are not removed or rewritten");
    }

    public static void malformedEvidenceIsRejected() {
        for (String missing : Arrays.asList(null, "", " ", "\n\t")) {
            expect(IllegalArgumentException.class, () -> new AssetReference(missing, "a"));
            expect(IllegalArgumentException.class, () -> new AssetReference(TENANT, missing));
            expect(IllegalArgumentException.class, () -> new GraphSnapshot(missing, 1, true, List.of()));
        }
        expect(NullPointerException.class, () -> new AssetFact(null, true));
        expect(NullPointerException.class, () -> new Dependency(null, B, null, null, null));
        expect(NullPointerException.class, () -> new Dependency(A, null, null, null, null));
        expect(NullPointerException.class, () -> new AuthorizationFacts(null, List.of()));
        expect(NullPointerException.class, () -> new AuthorizationFacts(Set.of(TENANT), null));
        expect(NullPointerException.class, () -> new AuthorizationFacts(Set.of(TENANT), Arrays.asList((AssetFact) null)));
        expect(IllegalArgumentException.class, () -> new AuthorizationFacts(Set.of(" "), List.of()));
        expect(IllegalArgumentException.class, () -> new AuthorizationFacts(Set.of(TENANT), List.of(new AssetFact(A, true), new AssetFact(A, false))));
        expect(IllegalArgumentException.class, () -> new AuthorizationFacts(Set.of(TENANT), List.of(new AssetFact(A, true), new AssetFact(A, true))));
        expect(IllegalArgumentException.class, () -> new GraphSnapshot(TENANT, -1, true, List.of()));
        expect(NullPointerException.class, () -> new GraphSnapshot(TENANT, 1, true, null));
        expect(NullPointerException.class, () -> new GraphSnapshot(TENANT, 1, true, Arrays.asList((Dependency) null)));
        expect(NullPointerException.class, () -> AssetDependencyValidator.validateAddition(null, empty(), List.of()));
        expect(NullPointerException.class, () -> AssetDependencyValidator.validateAddition(AUTH, null, List.of()));
        expect(NullPointerException.class, () -> AssetDependencyValidator.validateAddition(AUTH, empty(), null));
        expect(NullPointerException.class, () -> AssetDependencyValidator.validateAddition(AUTH, empty(), Arrays.asList((Dependency) null)));
        expect(IllegalArgumentException.class, () -> new ValidatedAddition(TENANT, -1, List.of()));
        equal("non-uuid:test", new AssetReference(TENANT, "non-uuid:test").assetId(),
            "The pure core does not invent UUID or source identifier restrictions");
    }

    public static void orderingDoesNotChangeValidation() {
        List<Dependency> diamond = List.of(edge(A, B), edge(A, C), edge(B, D), edge(C, D));
        List<Dependency> cycle = List.of(edge(A, B), edge(B, C), edge(C, A));
        for (int shift = 0; shift < diamond.size(); shift++) {
            List<Dependency> reordered = new ArrayList<>(diamond);
            Collections.rotate(reordered, shift);
            equal(reordered, AssetDependencyValidator.validateAddition(AUTH, empty(), reordered).dependencies(),
                "Input ordering cannot make a diamond fail");
            Collections.reverse(reordered);
            equal(reordered, AssetDependencyValidator.validateAddition(AUTH, empty(), reordered).dependencies(),
                "Reverse iteration cannot make a diamond fail");
        }
        for (int split = 0; split <= cycle.size(); split++) {
            structural("ASSET_DEPENDENCY_CYCLE", AUTH,
                new GraphSnapshot(TENANT, 2, true, cycle.subList(0, split)), cycle.subList(split, cycle.size()));
        }
        List<AssetFact> reorderedFacts = new ArrayList<>(AUTH.assetFacts());
        Collections.reverse(reorderedFacts);
        equal(diamond, AssetDependencyValidator.validateAddition(new AuthorizationFacts(Set.of(TENANT), reorderedFacts),
            empty(), diamond).dependencies(), "Authorization fact iteration order cannot alter validation");
    }

    public static void deepChainsUseNoRecursion() {
        int edgeCount = 25_000;
        List<AssetFact> facts = new ArrayList<>();
        List<Dependency> edges = new ArrayList<>();
        AssetReference previous = reference("deep-0");
        facts.add(new AssetFact(previous, true));
        for (int index = 1; index <= edgeCount; index++) {
            AssetReference next = reference("deep-" + index);
            facts.add(new AssetFact(next, true));
            edges.add(edge(previous, next));
            previous = next;
        }
        AuthorizationFacts authorization = new AuthorizationFacts(Set.of(TENANT), facts);
        GraphSnapshot chain = new GraphSnapshot(TENANT, 3, true, edges);
        equal(edgeCount, AssetDependencyValidator.validateAddition(authorization, chain, List.of()).dependencies().size(),
            "A deep acyclic chain is accepted without recursion");
        structural("ASSET_DEPENDENCY_CYCLE", authorization, chain,
            List.of(edge(previous, facts.get(0).reference())));
    }

    public static void exhaustiveFourNodeGraphsMatchIndependentOracle() {
        List<AssetReference> vertices = List.of(A, B, C, D);
        List<int[]> pairs = new ArrayList<>();
        for (int parent = 0; parent < 4; parent++) {
            for (int child = 0; child < 4; child++) if (parent != child) pairs.add(new int[]{parent, child});
        }
        for (int mask = 0; mask < (1 << pairs.size()); mask++) {
            boolean[][] reachable = new boolean[4][4];
            List<Dependency> existing = new ArrayList<>();
            List<Dependency> additions = new ArrayList<>();
            for (int index = 0; index < pairs.size(); index++) {
                if ((mask & (1 << index)) == 0) continue;
                int[] pair = pairs.get(index);
                reachable[pair[0]][pair[1]] = true;
                Dependency dependency = edge(vertices.get(pair[0]), vertices.get(pair[1]));
                ((index & 1) == 0 ? existing : additions).add(dependency);
            }
            // Floyd-Warshall reachability is an independent oracle, not a copy of Kahn traversal.
            for (int via = 0; via < 4; via++) {
                for (int from = 0; from < 4; from++) {
                    for (int to = 0; to < 4; to++) reachable[from][to] |= reachable[from][via] && reachable[via][to];
                }
            }
            boolean hasCycle = false;
            for (int vertex = 0; vertex < 4; vertex++) hasCycle |= reachable[vertex][vertex];
            GraphSnapshot snapshot = new GraphSnapshot(TENANT, 4, true, existing);
            if (hasCycle) {
                exhaustiveCycles++;
                structural("ASSET_DEPENDENCY_CYCLE", AUTH, snapshot, additions);
            } else {
                exhaustiveDags++;
                List<Dependency> expected = new ArrayList<>(existing);
                expected.addAll(additions);
                equal(expected, AssetDependencyValidator.validateAddition(AUTH, snapshot, additions).dependencies(),
                    "Independent closure oracle expects DAG for mask " + mask);
            }
        }
        equal(543, exhaustiveDags, "All labeled four-node DAGs are covered");
        equal(3553, exhaustiveCycles, "Every remaining directed graph contains a cycle");
    }

    private static AssetReference reference(String id) { return new AssetReference(TENANT, "test-asset-" + id); }
    private static Dependency edge(AssetReference parent, AssetReference child) { return new Dependency(parent, child, null, null, null); }
    private static AuthorizationFacts authorized(AssetReference... references) {
        List<AssetFact> facts = new ArrayList<>();
        for (AssetReference reference : references) facts.add(new AssetFact(reference, true));
        return new AuthorizationFacts(Set.of(TENANT), facts);
    }
    private static GraphSnapshot empty() { return graph(); }
    private static GraphSnapshot graph(Dependency... dependencies) { return new GraphSnapshot(TENANT, 1, true, List.of(dependencies)); }
    private static DomainException missing(AuthorizationFacts facts, GraphSnapshot graph, List<Dependency> additions) {
        DomainException failure = expect(DomainException.class, () -> AssetDependencyValidator.validateAddition(facts, graph, additions));
        equal(404, failure.status, "Unknown, unauthorized and foreign references return 404");
        equal("OBJECT_NOT_FOUND", failure.code, "Object errors use the documented uniform code");
        equal(List.of(), failure.details, "Object errors reveal no existence, identifier or tenant");
        return failure;
    }
    private static void structural(String reason, AuthorizationFacts facts, GraphSnapshot graph, List<Dependency> additions) {
        DomainException failure = expect(DomainException.class, () -> AssetDependencyValidator.validateAddition(facts, graph, additions));
        equal(400, failure.status, "Invalid directed structure is a controlled validation failure");
        equal("VALIDATION_ERROR", failure.code, "Structural failures use the documented validation code");
        equal(List.of(java.util.Map.of("reason", reason)), failure.details, "Structural reasons contain no asset identifiers");
    }
    private static void group(Runnable checks) { checks.run(); groups++; }
    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
    private static <T extends Throwable> T expect(Class<T> type, Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + failure.getClass().getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
