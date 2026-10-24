package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.NotificationControlStore;
import cn.mop.persistence.NotificationRequestCancellationService;
import cn.mop.persistence.NotificationRoutingService;
import cn.mop.persistence.PostgresPlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** V51 feature assembly. Construction neither migrates the database nor enables an external channel. */
@Configuration
@Profile("remote_test")
public class NotificationRoutingConfiguration {

    @Bean
    NotificationControlStore notificationControlStore(DataSource source, ObjectMapper mapper) {
        return new NotificationControlStore(source, mapper);
    }

    @Bean
    NotificationRoutingService notificationRoutingService(
        NotificationControlStore store,
        ObjectMapper mapper
    ) {
        return new NotificationRoutingService(store, mapper);
    }

    @Bean
    NotificationRequestCancellationService notificationRequestCancellationService(
        NotificationControlStore store,
        PlatformRepository platform
    ) {
        if (!(platform instanceof PostgresPlatformRepository postgres)) {
            throw new IllegalStateException(
                "Notification cancellation requires the actual PostgreSQL project repository"
            );
        }
        return new NotificationRequestCancellationService(store, postgres);
    }

    @Bean
    NotificationRoutingProbe notificationRoutingProbe(
        NotificationControlStore store,
        @Value("${MOP_NOTIFICATION_ROUTING_ENABLED:false}") boolean enabled
    ) {
        return new NotificationRoutingProbe(enabled, store);
    }

    /** A disabled candidate is optional; an enabled feature must have real schema and runtime privileges. */
    public record NotificationRoutingProbe(boolean enabled, NotificationControlStore store) {
        public boolean ready() {
            return !enabled || store.ready();
        }
    }
}
