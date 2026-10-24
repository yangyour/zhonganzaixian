package cn.mop.api;

import cn.mop.domain.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringRecoverySourceTest {
    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String ASSET = "10000000-0000-4000-8000-000000000002";
    static final NetworkMonitoringService.Binding BINDING = new NetworkMonitoringService.Binding(
        TENANT, ASSET, "192.0.2.1", "TEST", "TEST", "101", Map.of("icmp", "201"));

    static NetworkMonitoringService ready() {
        var monitoring = mock(NetworkMonitoringService.class);
        when(monitoring.enabled()).thenReturn(true);
        when(monitoring.binding(TENANT, ASSET)).thenReturn(Optional.of(BINDING));
        when(monitoring.summary(BINDING, "")).thenReturn(summary("FRESH", "2026-10-23T00:00:00Z", "1"));
        when(monitoring.history(BINDING, "icmp", "HOURS_24")).thenReturn(Map.of("asset_id", ASSET,
            "metric", "icmp", "source", "ZABBIX_SIMPLE_CHECK", "unit", "", "from", "2026-10-22T00:00:00Z",
            "to", "2026-10-23T00:00:00Z", "timestamp_meaning", "ZABBIX_RECORD_TIME", "points", List.of(
                Map.of("recorded_at", "2026-10-23T00:00:00.123456789Z", "value", "1"))));
        return monitoring;
    }

    static Map<String, Object> summary(String state, String at, String value) {
        return Map.of("asset_id", ASSET, "management_ip", BINDING.ip(), "metrics",
                Map.of("icmp", Map.of("state", state, "recorded_at", at, "value", value, "unit", "")));
    }

    @Test
    void serverBindingProducesScopedImmutableSampleBatchAndPreservesNanos() {
        var monitoring = ready();
        var source = new MonitoringRecoverySource(monitoring, "http://source.invalid/api_jsonrpc.php");
        var batch = source.read(TENANT, ASSET, "icmp");
        assertEquals(TENANT, batch.tenantId().toString());
        assertEquals(ASSET, batch.assetId().toString());
        assertEquals("192.0.2.1", batch.assetAddress());
        assertEquals(Instant.parse("2026-10-23T00:00:00.123456789Z"), batch.samples().get(0).recordedAt());
        assertEquals(64, batch.bindingSha256().length());
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), batch.historyFrom());
        assertEquals(Instant.parse("2026-10-23T00:00:01Z"), batch.historyToExclusive());
        assertThrows(UnsupportedOperationException.class, () -> batch.samples().clear());
        verify(monitoring, times(2)).summary(BINDING, "");
    }

    @Test
    void missingSourceOrBindingNeverUsesClientValuesOrEmptySuccess() {
        var monitoring = ready();
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class,
            () -> new MonitoringRecoverySource(monitoring, null).read(TENANT, ASSET, "icmp")).code);
        assertEquals("CONFIG_PENDING", assertThrows(DomainException.class,
            () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, "other", "icmp")).code);
        verify(monitoring, never()).history(any(), any(), any());
    }

    @Test
    void sourceBecomingUnknownAfterHistoryRejectsTheWholeBatch() {
        var monitoring = ready();
        when(monitoring.summary(BINDING, "")).thenReturn(
            summary("FRESH", "2026-10-23T00:00:00Z", "1"),
            summary("COLLECTOR_ERROR", "2026-10-23T00:00:00Z", "1"));
        assertEquals("RECOVERY_SOURCE_NOT_FRESH", assertThrows(DomainException.class,
            () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, ASSET, "icmp")).code);
    }

    @Test
    void historyForAnotherAssetIsRejected() {
        var monitoring = ready();
        when(monitoring.history(BINDING, "icmp", "HOURS_24")).thenReturn(Map.of("asset_id", "other",
            "metric", "icmp", "source", "ZABBIX_SIMPLE_CHECK", "unit", "", "points", List.of()));
        assertThrows(DomainException.class,
            () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, ASSET, "icmp"));
    }

    @Test
    void differentSourceInstancesCannotShareEvidenceNamespace() {
        var monitoring = ready();
        var one = new MonitoringRecoverySource(monitoring, "http://one.invalid/api_jsonrpc.php").read(TENANT, ASSET, "icmp");
        var two = new MonitoringRecoverySource(monitoring, "http://two.invalid/api_jsonrpc.php").read(TENANT, ASSET, "icmp");
        assertNotEquals(one.sourceNamespace(), two.sourceNamespace());
        assertNotEquals(one.bindingSha256(), two.bindingSha256());
    }

    @Test
    void freshFailureAfterHistoryCannotConfirmTheOlderHealthyTail() {
        var monitoring = ready();
        when(monitoring.summary(BINDING, "")).thenReturn(
                summary("FRESH", "2026-10-23T00:00:00Z", "1"),
                summary("FRESH", "2026-10-23T00:00:01Z", "0"));
        assertEquals("RECOVERY_SOURCE_CHANGED", assertThrows(DomainException.class,
                () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, ASSET, "icmp")).code);
    }

    @Test
    void newerHealthyPointMustAlsoBeIncludedInHistoryBeforeConfirmation() {
        var monitoring = ready();
        when(monitoring.summary(BINDING, "")).thenReturn(
                summary("FRESH", "2026-10-23T00:00:00Z", "1"),
                summary("FRESH", "2026-10-23T00:00:01Z", "1"));
        assertEquals("RECOVERY_SOURCE_CHANGED", assertThrows(DomainException.class,
                () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, ASSET, "icmp")).code);
    }

    @Test
    void sameSecondValueChangeAndWrongCurrentAssetAreRejected() {
        var monitoring = ready();
        when(monitoring.summary(BINDING, "")).thenReturn(
                summary("FRESH", "2026-10-23T00:00:00Z", "0"),
                summary("FRESH", "2026-10-23T00:00:00Z", "1"));
        assertEquals("RECOVERY_SOURCE_CHANGED", assertThrows(DomainException.class,
                () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, ASSET, "icmp")).code);
        when(monitoring.summary(BINDING, "")).thenReturn(Map.of("asset_id", TENANT,
                "management_ip", BINDING.ip(), "metrics", summary("FRESH", "2026-10-23T00:00:00Z", "1").get("metrics")));
        assertEquals("RECOVERY_SOURCE_UNAVAILABLE", assertThrows(DomainException.class,
                () -> new MonitoringRecoverySource(monitoring, "endpoint").read(TENANT, ASSET, "icmp")).code);
    }
}
