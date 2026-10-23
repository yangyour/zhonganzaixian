package cn.mop.adapters.backup;

import cn.mop.domain.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupContentRuntimeTest {
    @Test
    void disabledRuntimeAndAbsentKmsDoNotConstructStorageOrOpenProvider() {
        var failure = assertThrows(DomainException.class,
                () -> BackupContentRuntime.open(key -> null, true, (a, s, o) -> false, List.of()));
        assertEquals("BACKUP_CONTENT_NOT_CONFIGURED", failure.code);
        assertEquals("BACKUP_KMS_NOT_CONFIGURED", assertThrows(DomainException.class,
                () -> BackupContentRuntime.open(key -> key.equals("MOP_BACKUP_CONTENT_ENABLED") ? "true" : null,
                        true, (a, s, o) -> false, List.of())).code);
    }

    @Test
    void kmsSelectionRequiresExactUniqueApprovedAdapter() {
        BackupKmsProvider plugin = new BackupKmsProvider() {
            public String id() { return "synthetic-unit-provider"; }
            public Session open(java.util.function.Function<String, String> settings) {
                fail("Selection cannot open a key service");
                return null;
            }
        };
        assertSame(plugin, BackupContentRuntime.select("synthetic-unit-provider", List.of(plugin)));
        assertThrows(DomainException.class, () -> BackupContentRuntime.select("other", List.of(plugin)));
        assertThrows(DomainException.class, () -> BackupContentRuntime.select("synthetic-unit-provider", List.of(plugin, plugin)));
        assertThrows(DomainException.class, () -> BackupContentRuntime.select("https://not-a-provider.invalid", List.of(plugin)));
    }
}
