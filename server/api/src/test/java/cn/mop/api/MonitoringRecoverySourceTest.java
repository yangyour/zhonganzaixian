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
        when(monitoring.summary(BINDING, "")).thenReturn(Map.of("metrics", Map.of("icmp", Map.of("state", "FRESH"))));
        when(monitoring.history(BINDING, "icmp", "HOURS_24")).thenReturn(Map.of("asset_id", ASSET,
            "metric", "icmp", "source", "ZABBIX_SIMPLE_CHECK", "unit", "", "points", List.of(
                Map.of("recorded_at", "2026-10-23T00:00:00.123456789Z", "value", "1"))));
        return monitoring;
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
            Map.of("metrics", Map.of("icmp", Map.of("state", "FRESH"))),
            Map.of("metrics", Map.of("icmp", Map.of("state", "COLLECTOR_ERROR"))));
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
}
