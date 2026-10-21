package cn.mop.api;

import cn.mop.adapters.monitoring.VendorMappingYaml;
import cn.mop.domain.monitoring.VendorMappingCandidate;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the shipped MIB-derived candidate, not a live device or active template. */
class HuaweiS5731MappingCandidateTest {
    private VendorMappingCandidate candidate() throws Exception {
        Path folder = Path.of("").toAbsolutePath();
        while (folder != null && !Files.exists(folder.resolve("deploy/device-snmp/huawei-s5731-v200r022-candidate.yaml")))
            folder = folder.getParent();
        assertNotNull(folder, "Project candidate must be available");
        return VendorMappingYaml.parse(Files.readAllBytes(folder.resolve("deploy/device-snmp/huawei-s5731-v200r022-candidate.yaml")));
    }

    private VendorMappingCandidate.Metric metric(String id) throws Exception {
        return candidate().metrics().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    @Test void remainsUnpublishedAndUnverifiedWithNoInventedRollback() throws Exception {
        var value = candidate();
        assertFalse(value.effective()); assertFalse(value.sourceVerified());
        assertNull(value.rollbackRevision()); assertEquals(7, value.metrics().size());
        assertTrue(value.firmware().contains("unverified"));
    }

    @Test void opticalPowerDividesByOneHundredAndKeepsNegativeValues() throws Exception {
        for (var id : new String[]{"optical_rx_power", "optical_tx_power"}) {
            var metric = metric(id);
            assertEquals("dBm", metric.targetUnit());
            assertEquals(0, new BigDecimal("-12.34").compareTo(metric.convert("-1234")));
            assertEquals(0, new BigDecimal("0.01").compareTo(metric.convert("1")));
        }
    }

    @Test void entityUptimeIsSecondsRatherThanSysUpTimeTicks() throws Exception {
        var value = metric("entity_uptime");
        assertEquals("s", value.sourceUnit()); assertEquals("s", value.targetUnit());
        assertEquals(0, new BigDecimal("3600").compareTo(value.convert("3600")));
    }

    @Test void percentRangeIsMibDefinedAndNotAnAlertThreshold() throws Exception {
        for (var id : new String[]{"cpu", "memory"}) {
            var value = metric(id);
            assertEquals(0, BigDecimal.ZERO.compareTo(value.convert("0")));
            assertEquals(0, new BigDecimal("100").compareTo(value.convert("100")));
            assertThrows(IllegalArgumentException.class, () -> value.convert("101"));
            assertThrows(IllegalArgumentException.class, () -> value.convert("-1"));
        }
    }

    @Test void counter64IsExactRawBytesAndDoesNotInventBitRate() throws Exception {
        for (var id : new String[]{"if_hc_in_octets", "if_hc_out_octets"}) {
            var value = metric(id);
            assertEquals(VendorMappingCandidate.ValueType.COUNTER, value.type());
            assertEquals("bytes", value.targetUnit());
            assertEquals(0, new BigDecimal("18446744073709551615").compareTo(value.convert("18446744073709551615")));
            assertThrows(IllegalArgumentException.class, () -> value.convert("18446744073709551616"));
            assertThrows(IllegalArgumentException.class, () -> value.convert("-1"));
            assertThrows(IllegalArgumentException.class, () -> value.convert("1.5"));
        }
    }
}
