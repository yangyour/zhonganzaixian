package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.ticket.TicketLifecycleRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.persistence.PostgresTicketLifecycleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Optional feature wiring; never bootstraps a schema, credentials, role grants or test substitutes. */
@Configuration @Profile("remote_test")
public class TicketLifecycleConfiguration {
    @Bean TicketLifecycleRepository ticketLifecycleRepository(DataSource testDataSource,PlatformRepository platform,
                                                               ObjectMapper mapper,Environment environment){
        if(!(platform instanceof PostgresPlatformRepository postgres))
            throw new IllegalStateException("Ticket lifecycle requires the PostgreSQL platform repository");
        return new PostgresTicketLifecycleRepository(testDataSource,postgres,mapper,enabled(environment));
    }
    static boolean enabled(Environment environment){
        String value=environment.getProperty("MOP_TICKET_LIFECYCLE_ENABLED");
        if(value==null)return false;
        if("true".equals(value))return true;
        if("false".equals(value))return false;
        throw new IllegalArgumentException("MOP_TICKET_LIFECYCLE_ENABLED must be exactly true or false");
    }
}
