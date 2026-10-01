package cn.mop.domain.assetdependency;

import cn.mop.domain.DomainException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure structural validation for M3-9 asset dependencies, directed from upstream parent to
 * downstream child. This graph is independent of physical links and topology coordinates.
 *
 * <p>The caller must supply server-established authorization and a complete dependency snapshot
 * from one tenant. Supplied facts are not loaded, authenticated or refreshed by this class. A
 * successful result is a checked proposal, not a persisted graph, a publication approval or proof
 * of integration. A future writer must obtain the snapshot and perform validation and persistence
 * in the same transaction, with tenant-scoped locking or equivalent revision protection against
 * concurrent additions. It must also enforce action permission, audit and idempotency there.
 */
public final class AssetDependencyValidator {
    private AssetDependencyValidator() {}

    /** Asset identifiers are tenant-qualified; identical identifiers in two tenants are distinct. */
    public record AssetReference(String tenantId, String assetId) {
        public AssetReference {
            requireIdentifier(tenantId, "tenantId");
            requireIdentifier(assetId, "assetId");
        }
    }

    /**
     * An existing asset fact and its authorization for the proposed dependency operation.
     * Mere existence, visibility in a topology or membership in another tenant is insufficient.
     */
    public record AssetFact(AssetReference reference, boolean authorized) {
        public AssetFact {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * Explicit authorization facts supplied by the trusted service layer. Neither set is derived
     * from request fields. Unknown and unauthorized assets remain indistinguishable in errors.
     */
    public record AuthorizationFacts(Set<String> authorizedTenantIds, List<AssetFact> assetFacts) {
        public AuthorizationFacts {
            authorizedTenantIds = Set.copyOf(Objects.requireNonNull(authorizedTenantIds, "authorizedTenantIds"));
            authorizedTenantIds.forEach(tenant -> requireIdentifier(tenant, "authorizedTenantId"));
            assetFacts = List.copyOf(Objects.requireNonNull(assetFacts, "assetFacts"));
            Set<AssetReference> references = new HashSet<>();
            for (AssetFact fact : assetFacts) {
                if (!references.add(fact.reference())) {
                    throw new IllegalArgumentException("Asset facts must identify each asset once");
                }
            }
        }
    }

    /**
     * Business dependency, never a physical topology edge. Type, suppression scope and enabled
     * state are supplied metadata: null stays null and no customer dictionary, suppression rule
     * or activation default is invented. Structural validation covers every edge, including
     * disabled edges, regardless of type or scope. Publication needs separate confirmed rules.
     */
    public record Dependency(AssetReference parent, AssetReference child, String type,
                             String suppressionScope, Boolean enabled) {
        public Dependency {
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(child, "child");
        }
    }

    /**
     * Entire existing dependency graph for a single tenant across all types and scopes.
     * Completeness must be explicitly attested by its provider; a filtered or incomplete graph
     * cannot prove that an addition is acyclic. Revision is retained for a future guarded write.
     */
    public record GraphSnapshot(String tenantId, long revision, boolean complete,
                                List<Dependency> dependencies) {
        public GraphSnapshot {
            requireIdentifier(tenantId, "tenantId");
            if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        }
    }

    /** Checked combined proposal. Its revision has not been incremented or persisted. */
    public record ValidatedAddition(String tenantId, long baselineRevision,
                                    List<Dependency> dependencies) {
        public ValidatedAddition {
            requireIdentifier(tenantId, "tenantId");
            if (baselineRevision < 0) throw new IllegalArgumentException("baselineRevision must be nonnegative");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        }
    }

    /**
     * Checks the existing graph and an entire batch together; it never mutates caller inputs.
     * Authorization of all references precedes structural diagnostics to avoid object disclosure.
     * O(V + E + F) time and space, where F is the number of supplied asset authorization facts.
     * No recursion is used, so long dependency chains do not exhaust the Java stack.
     *
     * @throws DomainException 404 OBJECT_NOT_FOUND for an inaccessible tenant, foreign reference,
     *     unknown asset or unauthorized asset, using identical messages and empty details
     * @throws DomainException 409 ASSET_DEPENDENCY_GRAPH_INCOMPLETE for incomplete evidence
     * @throws DomainException 400 VALIDATION_ERROR for invalid directed structure, with a
     *     self-reference or cycle reason in details and no object identifiers
     */
    public static ValidatedAddition validateAddition(AuthorizationFacts authorization,
                                                     GraphSnapshot current,
                                                     List<Dependency> additions) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(current, "current");
        if (!authorization.authorizedTenantIds().contains(current.tenantId())) throw notFound();
        List<Dependency> candidates = List.copyOf(Objects.requireNonNull(additions, "additions"));
        Map<AssetReference, Boolean> assets = new HashMap<>();
        for (AssetFact fact : authorization.assetFacts()) assets.put(fact.reference(), fact.authorized());

        List<Dependency> combined = new ArrayList<>(current.dependencies());
        combined.addAll(candidates);
        for (Dependency dependency : combined) {
            requireAuthorized(current.tenantId(), dependency.parent(), assets);
            requireAuthorized(current.tenantId(), dependency.child(), assets);
        }
        if (!current.complete()) {
            throw new DomainException(409, "ASSET_DEPENDENCY_GRAPH_INCOMPLETE",
                "Complete asset dependency graph is required");
        }

        Map<AssetReference, Set<AssetReference>> outgoing = new HashMap<>();
        Map<AssetReference, Integer> indegree = new HashMap<>();
        for (Dependency dependency : combined) {
            AssetReference parent = dependency.parent();
            AssetReference child = dependency.child();
            if (parent.equals(child)) {
                throw invalid("ASSET_DEPENDENCY_SELF_REFERENCE", "Asset dependency cannot reference itself");
            }
            indegree.putIfAbsent(parent, 0);
            indegree.putIfAbsent(child, 0);
            if (outgoing.computeIfAbsent(parent, unused -> new LinkedHashSet<>()).add(child)) {
                indegree.merge(child, 1, Integer::sum);
            }
        }

        ArrayDeque<AssetReference> ready = new ArrayDeque<>();
        indegree.forEach((asset, count) -> { if (count == 0) ready.addLast(asset); });
        int visited = 0;
        while (!ready.isEmpty()) {
            AssetReference parent = ready.removeFirst();
            visited++;
            for (AssetReference child : outgoing.getOrDefault(parent, Set.of())) {
                if (indegree.merge(child, -1, Integer::sum) == 0) ready.addLast(child);
            }
        }
        if (visited != indegree.size()) {
            throw invalid("ASSET_DEPENDENCY_CYCLE", "Asset dependency graph contains a directed cycle");
        }
        return new ValidatedAddition(current.tenantId(), current.revision(), combined);
    }

    private static void requireAuthorized(String tenantId, AssetReference reference,
                                          Map<AssetReference, Boolean> assets) {
        if (!tenantId.equals(reference.tenantId()) || !Boolean.TRUE.equals(assets.get(reference))) {
            throw notFound();
        }
    }

    private static DomainException notFound() {
        return new DomainException(404, "OBJECT_NOT_FOUND", "Asset dependency reference not found");
    }

    private static DomainException invalid(String reason, String message) {
        return new DomainException(400, "VALIDATION_ERROR", message, List.of(Map.of("reason", reason)));
    }

    private static void requireIdentifier(String identifier, String name) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(name + " must be supplied");
        }
    }
}
