package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.RecoveryObservation;
import cn.mop.domain.monitoring.RecoverySourceBatch;
import cn.mop.persistence.PostgresMonitoringRecoveryRepository.SourceReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Reuses the configured authenticated read-only Zabbix source. It never provisions collectors or trusts submitted points. */
final class MonitoringRecoverySource implements SourceReader {
    private final NetworkMonitoringService monitoring;
    private final String sourceIdentity;

    MonitoringRecoverySource(NetworkMonitoringService monitoring, String configuredEndpoint) {
        this.monitoring = monitoring;
        this.sourceIdentity = configuredEndpoint == null ? null : hash(configuredEndpoint);
    }

    boolean available(String tenant, String asset) {
        return monitoring.enabled() && sourceIdentity != null && monitoring.binding(tenant, asset).isPresent();
    }

    @Override
    public RecoverySourceBatch read(String tenant, String asset, String metric) {
        if (!available(tenant, asset)) {
            throw new DomainException(503, "CONFIG_PENDING", "当前资产的恢复观察采样源尚未配置");
        }
        var binding = monitoring.binding(tenant, asset).orElseThrow(MonitoringRecoverySource::unavailable);
        if (!NetworkMonitoringService.METRICS.contains(metric)) {
            throw unavailable();
        }
        var before = freshMetric(monitoring.summary(binding, ""), binding, metric);
        Map<String, Object> history = monitoring.history(binding, metric, "HOURS_24");
        if (!asset.equals(history.get("asset_id")) || !metric.equals(history.get("metric"))
                || !"ZABBIX_SIMPLE_CHECK".equals(history.get("source")) || !(history.get("unit") instanceof String unit)
                || !(history.get("points") instanceof java.util.List<?> points)
                || !(history.get("from") instanceof String from)
                || !(history.get("to") instanceof String to)
                || !"ZABBIX_RECORD_TIME".equals(history.get("timestamp_meaning"))) {
            throw unavailable();
        }
        var samples = new ArrayList<RecoveryObservation.Sample>();
        for (Object raw : points) {
            if (!(raw instanceof Map<?, ?> point) || !(point.get("recorded_at") instanceof String at)
                    || !(point.get("value") instanceof String value)) {
                throw unavailable();
            }
            try {
                samples.add(new RecoveryObservation.Sample(Instant.parse(at), RecoveryObservation.Quality.KNOWN,
                    new BigDecimal(value)));
            } catch (IllegalArgumentException failure) {
                throw unavailable();
            }
        }
        var after = freshMetric(monitoring.summary(binding, ""), binding, metric);
        if (samples.isEmpty()) {
            throw changed();
        }
        var tail = samples.get(samples.size() - 1);
        // item.get exposes seconds only; history retains the exact ns. Never fabricate ns for the summary.
        if (!unit.equals(before.unit()) || !unit.equals(after.unit())
                || after.at().isBefore(before.at())
                || after.at().equals(before.at()) && after.value().compareTo(before.value()) != 0
                || tail.recordedAt().getEpochSecond() != after.at().getEpochSecond()
                || tail.value().compareTo(after.value()) != 0) {
            throw changed();
        }
        String identity = sourceIdentity + "|" + tenant + "|" + asset + "|" + binding.ip()
            + "|" + binding.hostId() + "|" + metric + "|" + binding.items().get(metric);
        try {
            // Zabbix time_till includes its whole final second, including ns-valued history points.
            return new RecoverySourceBatch(UUID.nameUUIDFromBytes(("MOP_RECOVERY_SOURCE_V1|" + sourceIdentity)
                    .getBytes(StandardCharsets.UTF_8)), UUID.fromString(tenant), UUID.fromString(asset), binding.ip(), metric,
                    unit, hash(identity), Instant.parse(from), Instant.parse(to).plusSeconds(1), samples);
        } catch (IllegalArgumentException | java.time.DateTimeException invalidWindow) {
            throw unavailable();
        }
    }

    /** Current item identity, value, unit and source seconds are the final history-read fence. */
    private record FreshMetric(Instant at, BigDecimal value, String unit) { }

    private static FreshMetric freshMetric(Map<String, Object> snapshot, NetworkMonitoringService.Binding binding,
                                          String metric) {
        if (!(snapshot.get("metrics") instanceof Map<?, ?> metrics)
                || !(metrics.get(metric) instanceof Map<?, ?> value) || !"FRESH".equals(value.get("state"))) {
            throw new DomainException(503, "RECOVERY_SOURCE_NOT_FRESH", "恢复观察来源未知、陈旧或采集错误，未确认恢复");
        }
        if (!binding.assetId().equals(snapshot.get("asset_id")) || !binding.ip().equals(snapshot.get("management_ip"))
                || !(value.get("recorded_at") instanceof String at) || !(value.get("value") instanceof String numeric)
                || !(value.get("unit") instanceof String unit)) {
            throw unavailable();
        }
        try {
            Instant time = Instant.parse(at);
            if (time.getNano() != 0) {
                throw unavailable();
            }
            return new FreshMetric(time, NetworkMonitoringService.number(metric, numeric), unit);
        } catch (IllegalArgumentException | java.time.DateTimeException invalidMetric) {
            throw unavailable();
        }
    }

    private static DomainException changed() {
        return new DomainException(503, "RECOVERY_SOURCE_CHANGED",
                "最新采样尚未被本次历史完整覆盖或读取期间来源变化，请重新核验，未确认恢复");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DomainException unavailable() {
        return new DomainException(503, "RECOVERY_SOURCE_UNAVAILABLE", "恢复观察来源或当前资产绑定不完整");
    }
}
