package cn.mop.worker.notification;

import cn.mop.domain.notification.TicketNotificationRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated polling tests only; database atomicity is a separate PostgreSQL integration concern. */
class TicketNotificationRelayTest {
    private static TicketNotificationRepository repository(List<String> problems,
                                                          BiFunction<String, Integer, Integer> relay) {
        return (TicketNotificationRepository) Proxy.newProxyInstance(
                TicketNotificationRepository.class.getClassLoader(),
                new Class<?>[]{TicketNotificationRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readinessProblems")) {
                        return problems;
                    }
                    if (method.getName().equals("relay")) {
                        return relay.apply((String) args[0], (Integer) args[1]);
                    }
                    throw new AssertionError("Unexpected repository operation");
                });
    }

    @Test
    void boundedFairBatchesDoNotDrainFirstTenantForever() {
        var seen = new ArrayList<String>();
        var repo = repository(List.of(), (tenant, limit) -> {
            seen.add(tenant);
            assertEquals(10, limit);
            return limit;
        });
        var relay = new TicketNotificationRelay(repo, List.of("a", "b"), 10);
        assertEquals(new TicketNotificationRelay.Cycle(20, 0, true), relay.poll(() -> true));
        assertEquals(List.of("a", "b"), seen);
    }

    @Test
    void tenantFailureDoesNotAcknowledgeWorkOrStarveOtherTenant() {
        var seen = new ArrayList<String>();
        var repo = repository(List.of(), (tenant, limit) -> {
            seen.add(tenant);
            if (tenant.equals("a")) {
                throw new IllegalStateException("SYNTHETIC_PRIVATE");
            }
            return 2;
        });
        var relay = new TicketNotificationRelay(repo, List.of("a", "b"), 10);
        assertEquals(new TicketNotificationRelay.Cycle(2, 1, true), relay.poll(() -> true));
        assertEquals(new TicketNotificationRelay.Cycle(2, 1, true), relay.poll(() -> true));
        assertEquals(List.of("a", "b", "a", "b"), seen);
    }

    @Test
    void commitResponseLossIsRetriedThroughSameDurableRepository() {
        var calls = new AtomicInteger();
        var repo = repository(List.of(), (tenant, limit) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("commit response lost");
            }
            return 0; // Models the repository finding the already committed intent, not a second delivery.
        });
        assertEquals(1, new TicketNotificationRelay(repo, List.of("a"), 10).poll(() -> true).failedTenants());
        assertEquals(new TicketNotificationRelay.Cycle(0, 0, true),
                new TicketNotificationRelay(repo, List.of("a"), 10).poll(() -> true));
    }

    @Test
    void unavailableDependencyNeverCallsRelay() {
        var repo = repository(List.of("SCHEMA_UNAVAILABLE"), (tenant, limit) -> {
            fail("Dependency failed closed");
            return 0;
        });
        assertEquals(new TicketNotificationRelay.Cycle(0, 0, false),
                new TicketNotificationRelay(repo, List.of("a"), 10).poll(() -> true));
    }

    @Test
    void shutdownStopsBeforeNextTenant() {
        var running = new AtomicBoolean(true);
        var repo = repository(List.of(), (tenant, limit) -> {
            assertEquals("a", tenant);
            running.set(false);
            return 1;
        });
        var relay = new TicketNotificationRelay(repo, List.of("a", "b"), 10);
        assertEquals(1, relay.poll(running::get).confirmedIntents());
        assertFalse(relay.poll(running::get).dependencyReady());
    }

    @Test
    void malformedCountsCannotClaimSuccess() {
        for (int count : new int[]{-1, 11}) {
            var relay = new TicketNotificationRelay(repository(List.of(), (tenant, limit) -> count), List.of("a"), 10);
            assertEquals(new TicketNotificationRelay.Cycle(0, 1, true), relay.poll(() -> true));
        }
    }
}
