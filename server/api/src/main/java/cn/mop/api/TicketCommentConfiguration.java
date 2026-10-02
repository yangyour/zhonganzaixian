package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.ticketcomment.TicketCommentRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class TicketCommentConfiguration {
    @Bean TicketCommentRepository ticketCommentRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Customer-visible comments require the real PostgreSQL platform repository");
        return new PostgresTicketCommentRepository(testDataSource,postgres);
    }
}
