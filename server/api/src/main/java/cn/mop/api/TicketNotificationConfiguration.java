package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.notification.TicketNotificationRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

/** In-app ticket notification records. External channels stay disabled until Q14/Q18 are confirmed. */
@Configuration @Profile("remote_test")
public class TicketNotificationConfiguration {
    @Bean TicketNotificationRepository ticketNotificationRepository(DataSource source, PlatformRepository platform, ObjectMapper mapper) {
        if (!(platform instanceof PostgresPlatformRepository p)) throw new IllegalStateException("Ticket notifications require designated PostgreSQL");
        return new PostgresTicketNotificationRepository(source, p, mapper);
    }
    @Bean NotificationRelay notificationRelay(TicketNotificationRepository repository, @Value("${MOP_NOTIFICATION_RELAY_ENABLED:false}") boolean enabled, @Value("${MOP_NOTIFICATION_RELAY_TENANT_IDS:}") String tenants) {
        return new NotificationRelay(repository, enabled, tenants);
    }
    public static final class NotificationRelay {
        private final TicketNotificationRepository repository; private final boolean enabled; private final List<String> tenants;
        private volatile String lastResult = "NOT_RUN";
        NotificationRelay(TicketNotificationRepository repository, boolean enabled, String raw) {
            this.repository = repository; this.enabled = enabled;
            this.tenants = raw == null || raw.isBlank() ? List.of() : Arrays.stream(raw.split(",", -1)).map(String::trim).map(v -> { if (!UUID.fromString(v).toString().equals(v)) throw new IllegalArgumentException("Explicit relay tenant IDs required"); return v; }).distinct().toList();
            if (enabled && (tenants.isEmpty() || tenants.size() > 100)) throw new IllegalArgumentException("Notification relay requires 1-100 explicit project tenants");
        }
        @Scheduled(fixedDelay = 30000, initialDelay = 15000) public void run() {
            if (!enabled) return;
            try { for (String tenant : tenants) repository.relay(tenant, 100); lastResult = "SUCCEEDED"; }
            catch (RuntimeException failure) { lastResult = "DEPENDENCY_UNAVAILABLE"; System.getLogger(NotificationRelay.class.getName()).log(System.Logger.Level.WARNING, "Notification relay dependencies unavailable; outbox facts retained"); }
        }
        public Map<String, Object> status() { return ApiResponse.map("enabled", enabled, "last_result", lastResult, "tick_seconds", 30, "external_dispatch", "NOT_CONFIGURED"); }
    }
}
