package cn.mop.worker;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Input guards only; no PostgreSQL, storage client or scanner is created. */
class AttachmentScanOnceTest {
    @Test
    void disabledScannerCannotReachWorkerRuntimeSettings() {
        assertThrows(IllegalStateException.class, () -> AttachmentScanOnce.requireTenant(key -> {
            assertEquals("MOP_ATTACHMENT_SCAN_ENABLED", key); return null;
        }));
    }

    @Test
    void explicitPolicyStillRequiresAnExactTenantScope() {
        var settings = new HashMap<>(Map.of("MOP_ATTACHMENT_SCAN_ENABLED", "true", "MOP_ATTACHMENT_SCANNER_PORT", "19310",
            "MOP_ATTACHMENT_SCANNER_ENGINE_VERSION", "1.4.3", "MOP_ATTACHMENT_SCANNER_SIGNATURE_VERSION", "12345",
            "MOP_ATTACHMENT_SCANNER_SIGNATURE_VALID_UNTIL", "2026-10-24T00:00:00Z", "MOP_ATTACHMENT_SCAN_EVIDENCE_SECONDS", "120"));
        assertThrows(IllegalStateException.class, () -> AttachmentScanOnce.requireTenant(settings::get));
        settings.put("MOP_ATTACHMENT_SCAN_TENANT_ID", "11111111-1111-4111-8111-111111111111");
        assertEquals(settings.get("MOP_ATTACHMENT_SCAN_TENANT_ID"), AttachmentScanOnce.requireTenant(settings::get));
    }
}
