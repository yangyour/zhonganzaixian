package cn.mop.domain.assetimport;

import cn.mop.domain.assetimport.source.AssetImportSourceRepository.Access;
import cn.mop.domain.assetimport.source.ParsedArtifact;
import cn.mop.domain.assetmetadata.ManualAssetMetadata;
import cn.mop.domain.assetmetadata.ManualWarrantyDetails;
import java.util.List;
import java.util.Map;

/** Atomic registration of source-backed drafts. Collection and production approval remain separate. */
public interface CmdbImportExecutionRepository {
    record Command(long expectedVersion, String parseHash) {
        public Command {
            if (expectedVersion < 1 || expectedVersion >= 9007199254740991L
                    || parseHash == null || !parseHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Source version and parse hash required");
            }
        }
    }

    /** Only the server planner can construct these rows from the locked durable artifact. */
    record Row(long record, int sourceRow, String address, ManualAssetMetadata metadata,
               ManualWarrantyDetails warranty) {}

    record Plan(List<Row> rows, HostFormatValidator.Policy addressPolicy) {
        public Plan {
            rows = List.copyOf(rows);
        }
    }

    @FunctionalInterface
    interface Planner {
        Plan prepare(ParsedArtifact artifact, Map<String, Object> configuration);
    }

    /** Reauthorize owner and capability inside the idempotent transaction, including receipt replay. */
    void authorize(Access access, String sourceId);

    /** Caller supplies the existing idempotent transaction; any failure rolls back the entire batch. */
    Map<String, Object> execute(Access access, String sourceId, Command command);
}
