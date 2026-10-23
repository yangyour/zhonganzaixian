package cn.mop.worker.notification;

import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationControlStore.Kind;
import cn.mop.persistence.NotificationRoutingService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Continuous bounded discovery and re-planning of durable requests; no provider is constructed by this scheduler. */
public final class NotificationRoutingScheduler {
    private record Cursor(Instant time, UUID id) {}
    public record Cycle(int discovered, int planned, int failures, boolean ready) {}
    private final NotificationControlStore store;
    private final NotificationRoutingService routing;
    private final UUID actor;
    private final List<UUID> tenants;
    private final int batch;
    private final Clock clock;
    private final Map<UUID, Cursor> cursors = new HashMap<>();

    public NotificationRoutingScheduler(NotificationControlStore store, NotificationRoutingService routing,
                                        UUID actor, List<UUID> tenants, int batch, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.routing = Objects.requireNonNull(routing);
        this.actor = Objects.requireNonNull(actor);
        this.tenants = List.copyOf(tenants);
        this.clock = Objects.requireNonNull(clock);
        if (batch < 1 || batch > 100 || tenants.isEmpty() || tenants.size() > 100
                || tenants.stream().distinct().count() != tenants.size()) {
            throw new IllegalArgumentException("Explicit bounded notification scope required");
        }
        this.batch = batch;
    }

    /** Cursor loss only causes a bounded rescan; all business work and command deduplication remain in PostgreSQL. */
    public Cycle tick(BooleanSupplier running) {
        if (!running.getAsBoolean() || !store.ready()) {
            return new Cycle(0, 0, 0, false);
        }
        int discovered = 0;
        int planned = 0;
        int failures = 0;
        for (UUID tenant : tenants) {
            if (!running.getAsBoolean()) {
                break;
            }
            try {
                var access = store.workerAccess(tenant, actor);
                discovered += routing.discover(access, batch);
                var cursor = cursors.get(tenant);
                var page = store.page(access, Kind.REQUEST, batch, cursor == null ? null : cursor.time(),
                        cursor == null ? null : cursor.id());
                for (var request : page.items()) {
                    if (!running.getAsBoolean()) {
                        // Restart this page after an interruption; do not skip unvisited durable work.
                        return new Cycle(discovered, planned, failures, true);
                    }
                    try {
                        String identity = "notification-plan|" + tenant + "|" + request.id() + "|" + request.version();
                        UUID command = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
                        var result = routing.plan(access, request.id(), request.version(), command, clock.instant());
                        if (result.version() > request.version()) {
                            planned++;
                        }
                    } catch (RuntimeException rejected) {
                        failures++;
                    }
                }
                if (page.nextId() == null) {
                    cursors.remove(tenant);
                } else {
                    cursors.put(tenant, new Cursor(page.nextTime(), page.nextId()));
                }
            } catch (RuntimeException unavailable) {
                failures++;
            }
        }
        return new Cycle(discovered, planned, failures, true);
    }
}
