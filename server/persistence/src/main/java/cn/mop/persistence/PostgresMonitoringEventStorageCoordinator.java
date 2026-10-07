package cn.mop.persistence;

import cn.mop.domain.monitoring.QuarantinedMonitoringEvent;
import cn.mop.domain.monitoring.RawMonitoringEvent;
import javax.sql.DataSource;
import java.util.Objects;

/** Internal storage routing only; callers must separately authenticate sources and resolve current mappings. */
public final class PostgresMonitoringEventStorageCoordinator {
    public sealed interface Result permits RawResult, HeldResult {
        default boolean sourceAuthorized(){return false;}
        default boolean dispatchAllowed(){return false;}
    }
    public record RawResult(PostgresRawMonitoringEventRepository.Receipt receipt) implements Result {
        public RawResult {
            Objects.requireNonNull(receipt);
            if(receipt.outcome()==PostgresRawMonitoringEventRepository.Outcome.CONFLICT_REQUIRES_REVIEW)
                throw new IllegalArgumentException("A conflict requires a durable held receipt");
        }
        @Override public String toString(){return "MonitoringStorageResult[raw facts only; unverified; content redacted]";}
    }
    public record HeldResult(PostgresMonitoringQuarantineRepository.Receipt receipt) implements Result {
        public HeldResult {Objects.requireNonNull(receipt);}
        @Override public String toString(){return "MonitoringStorageResult[held unverified; no canonical details]";}
    }
    private final PostgresRawMonitoringEventRepository raw;
    private final PostgresMonitoringQuarantineRepository quarantine;
    public PostgresMonitoringEventStorageCoordinator(DataSource source){
        Objects.requireNonNull(source);raw=new PostgresRawMonitoringEventRepository(source);quarantine=new PostgresMonitoringQuarantineRepository(source);
    }
    /** The candidate claims below are incoming binding IDs, not separately parsed original source claims. */
    public Result store(RawMonitoringEvent incoming){
        Objects.requireNonNull(incoming);
        var result=raw.store(incoming);
        return switch(result.outcome()) {
            case STORED, SAME_CONTENT -> new RawResult(result);
            case CONFLICT_REQUIRES_REVIEW -> {
                // The first transaction changed no canonical row. Only a committed hold may produce HeldResult.
                // Failure or a crash in this gap remains unacknowledged; replay must use the same source facts.
                var candidate=new QuarantinedMonitoringEvent(incoming.key(),new QuarantinedMonitoringEvent.Claims(incoming.binding().tenantId(),incoming.binding().assetId()),QuarantinedMonitoringEvent.Reason.SOURCE_KEY_CONFLICT,incoming.occurredAt(),incoming.receivedAt(),incoming.payload());
                yield new HeldResult(quarantine.hold(candidate));
            }
        };
    }
}
