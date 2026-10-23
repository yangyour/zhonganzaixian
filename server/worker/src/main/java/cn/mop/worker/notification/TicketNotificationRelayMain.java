package cn.mop.worker.notification;

import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.persistence.PostgresTicketNotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone in-app delivery mode. It creates no provider client and performs no automatic migration. */
public final class TicketNotificationRelayMain {
    private TicketNotificationRelayMain() {}

    public static void run() {
        var settings = TicketNotificationRelaySettings.from(System::getenv);
        var config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.databaseUser());
        config.setPassword(settings.databasePassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setInitializationFailTimeout(5000);
        config.setPoolName("mop-notification-relay");
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "20");
        var running = new AtomicBoolean(true);
        Thread stop = new Thread(() -> running.set(false), "notification-relay-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try (var source = new HikariDataSource(config)) {
            var mapper = new ObjectMapper();
            var repository = new PostgresTicketNotificationRepository(source,
                    new PostgresPlatformRepository(source, mapper), mapper);
            if (!repository.readinessProblems().isEmpty()) {
                throw new IllegalStateException("Notification relay dependencies unavailable");
            }
            var relay = new TicketNotificationRelay(repository, settings.tenantIds(), settings.batchSize());
            System.out.println("Notification relay started; in-app workflow facts only; external dispatch disabled");
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                var cycle = relay.poll(running::get);
                if (!cycle.dependencyReady() || cycle.failedTenants() > 0) {
                    System.err.println("Notification relay dependency or tenant batch unavailable; unconfirmed work retained");
                }
                try {
                    Thread.sleep(settings.pollMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("FAIL_CLOSED: notification relay unavailable; private details withheld");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(stop);
            } catch (IllegalStateException shuttingDown) {
                // JVM shutdown already owns the hook.
            }
        }
    }
}
