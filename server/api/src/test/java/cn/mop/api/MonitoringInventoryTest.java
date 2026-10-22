package cn.mop.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringInventoryTest {
    static JsonNode golden() throws Exception {
        return MonitoringPresetRegistry.JSON.readTree(Files.readString(Path.of("../../fixtures/monitoring-inventory-golden.json")));
    }

    @Test void generatedInventoryIsAcceptedAndAllPythonHashesMatch() throws Exception {
        JsonNode golden = golden();
        var registry = new MonitoringPresetRegistry(golden.get("configuration").toString(), "[]", "[]");
        var rows = golden.get("configuration").get("targets");
        assertEquals(15, rows.size());
        var presets = new HashSet<String>();
        for (JsonNode row : rows) {
            var target = registry.find(row.get("tenant_id").asText(), row.get("id").asText()).orElseThrow();
            assertEquals(golden.get("target_hashes").get(target.id()).asText(), target.hash());
            assertFalse(registry.approved(target));
            assertEquals(row.get("preset_id").asText(), target.preset().id());
            assertTrue(target.assetIp().startsWith("192.0.2."));
            presets.add(target.preset().id());
        }
        assertEquals(13, presets.size());
    }

    @Test void repeatedPresetWithDifferentPortIsAcceptedWithoutRelaxingAuthorization() {
        var config = MonitoringPresetTest.config("tcp-service");
        ObjectNode first = MonitoringPresetTest.row(config);
        first.put("target", "192.0.2.10:22");
        ObjectNode second = first.deepCopy();
        second.put("id", "79000000-0000-4000-8000-000000000099").put("target", "192.0.2.10:5432");
        config.withArray("targets").add(second);
        var registry = new MonitoringPresetRegistry(config.toString(), "[\"" + first.get("endpoint").asText() + "\"]", "[\"192.0.2.10:22\"]");
        var rows = registry.forTenant(first.get("tenant_id").asText());
        assertEquals(2, rows.size());
        assertTrue(registry.approved(rows.get(0)));
        assertFalse(registry.approved(rows.get(1)));
    }

    @Test void differentHttpPathsRemainSeparateTargets() {
        var config = MonitoringPresetTest.config("http-service");
        ObjectNode first = MonitoringPresetTest.row(config);
        first.put("target", "https://192.0.2.10/health");
        ObjectNode second = first.deepCopy();
        second.put("id", "79000000-0000-4000-8000-000000000099").put("target", "https://192.0.2.10/ready");
        config.withArray("targets").add(second);
        assertEquals(2, new MonitoringPresetRegistry(config.toString(), "[]", "[]").forTenant(first.get("tenant_id").asText()).size());
    }

    @Test void exactDestinationCannotBeDuplicatedByChangingIdentifierOrTiming() {
        var config = MonitoringPresetTest.config("linux-node");
        ObjectNode second = MonitoringPresetTest.row(config).deepCopy();
        second.put("id", "79000000-0000-4000-8000-000000000099").put("interval_seconds", 60);
        config.withArray("targets").add(second);
        assertThrows(IllegalArgumentException.class, () -> new MonitoringPresetRegistry(config.toString(), "[]", "[]"));
    }

    @Test void equivalentHttpDefaultPortAndEmptyPathCannotEvadeDeduplication() {
        var config = MonitoringPresetTest.config("http-service");
        ObjectNode first = MonitoringPresetTest.row(config);
        first.put("target", "https://192.0.2.10");
        ObjectNode second = first.deepCopy();
        second.put("id", "79000000-0000-4000-8000-000000000099").put("target", "https://192.0.2.10:443/");
        config.withArray("targets").add(second);
        assertThrows(IllegalArgumentException.class, () -> new MonitoringPresetRegistry(config.toString(), "[]", "[]"));
    }

    @Test void tcpPortFormattingCannotEvadeDeduplication() {
        var config = MonitoringPresetTest.config("tcp-service");
        ObjectNode first = MonitoringPresetTest.row(config);
        first.put("target", "192.0.2.10:22");
        ObjectNode second = first.deepCopy();
        second.put("id", "79000000-0000-4000-8000-000000000099").put("target", "192.0.2.10:00022");
        config.withArray("targets").add(second);
        assertThrows(IllegalArgumentException.class, () -> new MonitoringPresetRegistry(config.toString(), "[]", "[]"));
    }
}
