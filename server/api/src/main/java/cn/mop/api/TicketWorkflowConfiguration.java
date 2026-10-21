package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import cn.mop.domain.ticket.TicketWorkflowRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.*;
import java.util.*;

@Configuration @Profile("remote_test") @EnableScheduling
public class TicketWorkflowConfiguration {
    @Bean TicketWorkflowRepository ticketWorkflowRepository(DataSource source,PlatformRepository platform,ObjectMapper mapper){if(!(platform instanceof PostgresPlatformRepository p))throw new IllegalStateException("Ticket workflow requires designated PostgreSQL");return new PostgresTicketWorkflowRepository(source,p,mapper);}
    @Bean TicketRuntimePolicy ticketRuntimePolicy(RuntimeConfigurationRepository runtime,RuntimeConfigurationCatalog catalog,ObjectMapper mapper){return new TicketRuntimePolicy(runtime,catalog,mapper);}
    @Bean TicketClock ticketClock(TicketWorkflowRepository repository,@Value("${MOP_TICKET_CLOCK_ENABLED:false}") boolean enabled,@Value("${MOP_TICKET_CLOCK_TENANT_IDS:}") String tenants){return new TicketClock(repository,enabled,tenants);}
    public static final class TicketClock {
        private final TicketWorkflowRepository repository;private final boolean enabled;private final List<String> tenants;
        private volatile String lastResult="NOT_RUN";
        TicketClock(TicketWorkflowRepository repository,boolean enabled,String raw){this.repository=repository;this.enabled=enabled;
            this.tenants=raw.isBlank()?List.of():Arrays.stream(raw.split(",",-1)).map(String::trim).map(v->{if(!UUID.fromString(v).toString().equals(v))throw new IllegalArgumentException("Explicit clock tenant IDs required");return v;}).distinct().toList();
            if(enabled&&(tenants.isEmpty()||tenants.size()>100))throw new IllegalArgumentException("Clock requires 1-100 explicit project tenants");
        }
        @Scheduled(fixedDelay=30000) public void run(){if(!enabled)return;try{for(String tenant:tenants)repository.tick(tenant,100);lastResult="SUCCEEDED";}catch(RuntimeException failure){lastResult="DEPENDENCY_UNAVAILABLE";System.getLogger(TicketClock.class.getName()).log(System.Logger.Level.WARNING,"Ticket clock dependencies unavailable; durable tasks retained");}}
        public Map<String,Object> status(){return ApiResponse.map("enabled",enabled,"last_result",lastResult,"tick_seconds",30,"external_delivery_confirmed",false);}
    }
}
