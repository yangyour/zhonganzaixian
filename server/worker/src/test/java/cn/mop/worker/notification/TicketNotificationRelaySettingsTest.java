package cn.mop.worker.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TicketNotificationRelaySettingsTest {
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    private Map<String, String> valid() {
        var values = new HashMap<String, String>();
        values.put("SPRING_PROFILES_ACTIVE", "remote_test");
        values.put("MOP_TICKET_LIFECYCLE_ENABLED", "true");
        values.put("MOP_NOTIFICATION_RELAY_ENABLED", "true");
        values.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/mop_test");
        values.put("SPRING_DATASOURCE_USERNAME", "mop_test_runtime");
        values.put("SPRING_DATASOURCE_PASSWORD", "SYNTHETIC_PRIVATE");
        values.put("MOP_NOTIFICATION_RELAY_TENANT_IDS", TENANT);
        values.put("MOP_NOTIFICATION_RELAY_BATCH_SIZE", "25");
        values.put("MOP_NOTIFICATION_RELAY_POLL_MILLIS", "1000");
        return values;
    }

    @Test
    void allSettingsAreExplicitAndNoSecretIsRendered() {
        var values = valid();
        var settings = TicketNotificationRelaySettings.from(values::get);
        assertEquals(List.of(TENANT), settings.tenantIds());
        assertEquals(25, settings.batchSize());
        assertFalse(settings.toString().contains("SYNTHETIC_PRIVATE"));
        assertFalse(settings.toString().contains(TENANT));
        for (String key : values.keySet()) {
            var missing = valid();
            missing.remove(key);
            assertThrows(IllegalStateException.class, () -> TicketNotificationRelaySettings.from(missing::get), key);
        }
    }

    @Test
    void unknownDuplicateOrWildcardTenantScopeRejected() {
        for (String scope : List.of("*", "all", TENANT + "," + TENANT, TENANT + ",", " " + TENANT,
                "1-1-1-1-1", "x".repeat(3701))) {
            var values = valid();
            values.put("MOP_NOTIFICATION_RELAY_TENANT_IDS", scope);
            assertThrows(IllegalStateException.class, () -> TicketNotificationRelaySettings.from(values::get));
        }
    }

    @Test
    void onlyRestrictedTunnelDestinationAccepted() {
        for (String url : List.of("jdbc:postgresql://remote:5432/mop_test",
                "jdbc:postgresql://127.0.0.1:5432/production", "jdbc:postgresql://127.0.0.1:0/mop_test",
                "jdbc:postgresql://127.0.0.1:5432/mop_test?password=x")) {
            var values = valid();
            values.put("SPRING_DATASOURCE_URL", url);
            assertThrows(IllegalStateException.class, () -> TicketNotificationRelaySettings.from(values::get));
        }
    }

    @Test
    void flagsRolesAndPollingBoundsCannotBroadenScope() {
        for (String key : List.of("SPRING_PROFILES_ACTIVE", "SPRING_DATASOURCE_USERNAME",
                "MOP_TICKET_LIFECYCLE_ENABLED", "MOP_NOTIFICATION_RELAY_ENABLED",
                "MOP_NOTIFICATION_RELAY_BATCH_SIZE", "MOP_NOTIFICATION_RELAY_POLL_MILLIS")) {
            var values = valid();
            values.put(key, "0");
            assertThrows(IllegalStateException.class, () -> TicketNotificationRelaySettings.from(values::get));
        }
    }

    @Test
    void unsupportedArgumentsNeverFallThroughToOtherWorker() {
        assertEquals("Notification relay mode accepts no other arguments", assertThrows(IllegalArgumentException.class,
                () -> cn.mop.worker.WorkerApplication.main(new String[]{"--notification-relay", "--ticket-autoclose"}))
                .getMessage());
    }
}
