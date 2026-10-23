package cn.mop.domain.ticket;

import java.util.Set;
import java.util.UUID;

/** A recovery reminder identity for one durable problem onset, independent of later raw signal deliveries. */
public record MonitoringRecurrenceClock(int percent, UUID eventId, int sequence) {
    public MonitoringRecurrenceClock {
        if (!Set.of(50, 80, 100).contains(percent) || eventId == null || sequence < 1) {
            throw new IllegalArgumentException("Invalid monitoring recovery clock identity");
        }
    }

    public String key() {
        return canonicalKey() + ":monitor:" + eventId + ":" + sequence;
    }

    public String canonicalKey() {
        return "recovery:" + percent;
    }

    public static MonitoringRecurrenceClock parse(String value) {
        if (value == null || !value.contains(":monitor:")) {
            return null;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 5 || !parts[0].equals("recovery") || !parts[2].equals("monitor")) {
            throw new IllegalArgumentException("Invalid monitoring recovery clock key");
        }
        var result = new MonitoringRecurrenceClock(Integer.parseInt(parts[1]),
            UUID.fromString(parts[3]), Integer.parseInt(parts[4]));
        if (!result.key().equals(value)) {
            throw new IllegalArgumentException("Noncanonical monitoring recovery clock key");
        }
        return result;
    }
}
