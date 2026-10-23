package cn.mop.domain.monitoring;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Supplied by a server-owned reader after checking the current asset/host/item binding, never from HTTP input. */
public record RecoverySourceBatch(UUID sourceNamespace, UUID tenantId, UUID assetId, String assetAddress, String metric,
                                  String unit, String bindingSha256, List<RecoveryObservation.Sample> samples) {
    public RecoverySourceBatch {
        Objects.requireNonNull(sourceNamespace);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(assetId);
        if (assetAddress == null || assetAddress.isBlank() || assetAddress.length() > 45) {
            throw new IllegalArgumentException("Source asset address required");
        }
        Objects.requireNonNull(metric);
        Objects.requireNonNull(unit);
        if (bindingSha256 == null || !bindingSha256.matches("[0-9a-f]{64}")
                || samples == null || samples.size() > RecoveryObservation.MAX_SAMPLES) {
            throw new IllegalArgumentException("Invalid bound recovery source batch");
        }
        samples = List.copyOf(samples);
    }
}
