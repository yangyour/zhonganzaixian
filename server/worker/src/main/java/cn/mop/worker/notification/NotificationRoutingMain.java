package cn.mop.worker.notification;

import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit standalone V51 planning mode; existing in-app relay and demo configuration are unchanged. */
public final class NotificationRoutingMain {
    private NotificationRoutingMain() {}

    public static void run() {
        // Reuse validated polling/tenant/connection settings, with an additional explicit V51 feature gate.
        var settings = TicketNotificationRelaySettings.from(System::getenv);
        if (!"true".equals(System.getenv("MOP_NOTIFICATION_ROUTING_ENABLED"))) {
            throw new IllegalStateException("CONFIG_PENDING: explicit notification routing enablement required");
        }
        UUID actor;
        try {
            String value = System.getenv("MOP_NOTIFICATION_ROUTING_ACTOR_ID");
            actor = UUID.fromString(value);
            if (!actor.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("CONFIG_PENDING: explicit notification service principal required");
        }
        var config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.databaseUser());
        config.setPassword(settings.databasePassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "20");
        var running = new AtomicBoolean(true);
        var stop = new Thread(() -> running.set(false), "notification-routing-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try (var source = new HikariDataSource(config)) {
            var mapper = new ObjectMapper();
            var store = new NotificationControlStore(source, mapper);
            if (!store.ready()) {
                throw new IllegalStateException("V51 notification control unavailable");
            }
            var scheduler = new NotificationRoutingScheduler(store, new NotificationRoutingService(store, mapper),
                    actor, settings.tenantIds().stream().map(UUID::fromString).toList(), settings.batchSize(), Clock.systemUTC());
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                var result = scheduler.tick(running::get);
                if (!result.ready() || result.failures() > 0) {
                    System.err.println("Notification routing dependencies or authorization unavailable; durable work retained");
                }
                try {
                    Thread.sleep(settings.pollMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("FAIL_CLOSED: notification routing unavailable; private details withheld");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException shuttingDown) {
                // JVM shutdown owns the hook.
            }
        }
    }
}
