package cn.mop.worker.notification;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Explicit project scope and polling limits for the in-app outbox consumer. */
public record TicketNotificationRelaySettings(String jdbcUrl, String databaseUser, String databasePassword,
                                              List<String> tenantIds, int batchSize, int pollMillis) {
    public TicketNotificationRelaySettings {
        tenantIds = List.copyOf(tenantIds);
    }

    /** Reads only private environment references; there are no customer-policy or destination defaults. */
    public static TicketNotificationRelaySettings from(Function<String, String> setting) {
        requireValue(setting, "SPRING_PROFILES_ACTIVE", "remote_test");
        requireValue(setting, "MOP_TICKET_LIFECYCLE_ENABLED", "true");
        requireValue(setting, "MOP_NOTIFICATION_RELAY_ENABLED", "true");
        String url = required(setting, "SPRING_DATASOURCE_URL");
        var match = Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test").matcher(url);
        if (!match.matches() || Integer.parseInt(match.group(1)) < 1 || Integer.parseInt(match.group(1)) > 65535) {
            throw invalid("SPRING_DATASOURCE_URL");
        }
        requireValue(setting, "SPRING_DATASOURCE_USERNAME", "mop_test_runtime");
        String tenants = required(setting, "MOP_NOTIFICATION_RELAY_TENANT_IDS");
        if (tenants.length() > 3700) {
            throw invalid("MOP_NOTIFICATION_RELAY_TENANT_IDS");
        }
        List<String> ids = Arrays.asList(tenants.split(",", -1));
        if (ids.size() > 100 || ids.stream().distinct().count() != ids.size()) {
            throw invalid("MOP_NOTIFICATION_RELAY_TENANT_IDS");
        }
        for (String id : ids) {
            try {
                if (!UUID.fromString(id).toString().equals(id)) {
                    throw invalid("MOP_NOTIFICATION_RELAY_TENANT_IDS");
                }
            } catch (IllegalArgumentException rejected) {
                throw invalid("MOP_NOTIFICATION_RELAY_TENANT_IDS");
            }
        }
        return new TicketNotificationRelaySettings(url, "mop_test_runtime",
                required(setting, "SPRING_DATASOURCE_PASSWORD"), ids,
                number(setting, "MOP_NOTIFICATION_RELAY_BATCH_SIZE", 1, 100),
                number(setting, "MOP_NOTIFICATION_RELAY_POLL_MILLIS", 100, 60000));
    }

    private static int number(Function<String, String> setting, String key, int min, int max) {
        String value = required(setting, key);
        if (!value.matches("[1-9][0-9]{0,5}")) {
            throw invalid(key);
        }
        int number = Integer.parseInt(value);
        if (number < min || number > max) {
            throw invalid(key);
        }
        return number;
    }

    private static void requireValue(Function<String, String> setting, String key, String expected) {
        if (!expected.equals(setting.apply(key))) {
            throw invalid(key);
        }
    }

    private static String required(Function<String, String> setting, String key) {
        String value = setting.apply(key);
        if (value == null || value.isBlank()) {
            throw invalid(key);
        }
        return value;
    }

    private static IllegalStateException invalid(String key) {
        return new IllegalStateException("CONFIG_PENDING: notification relay requires explicit valid " + key
                + "; private values withheld");
    }

    @Override
    public String toString() {
        return "TicketNotificationRelaySettings[private connection and scope values withheld]";
    }
}
