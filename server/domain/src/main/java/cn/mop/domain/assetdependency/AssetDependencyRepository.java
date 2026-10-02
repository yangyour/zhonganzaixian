package cn.mop.domain.assetdependency;

import java.time.Instant;
import java.util.List;

/** Durable proposed graph only. It cannot publish monitoring rules or infer confirmed impact. */
public interface AssetDependencyRepository {
    int MAX_ADDITIONS = 100;
    int MAX_GRAPH_EDGES = 10_000;
    record ProposedEdge(String parentId,String childId,String proposedType,String proposedSuppressionScope,Boolean proposedEnabled) {}
    record Draft(String tenantId,long version,Instant updatedAt,List<ProposedEdge> edges) {
        public Draft { edges=List.copyOf(edges); }
    }
    Draft current(String tenantId,String actorId);
    /** Must run inside the platform's idempotent transaction; audit is part of the same commit. */
    Draft append(String tenantId,String actorId,long expectedVersion,List<ProposedEdge> additions,String traceId);
}
