package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.ticketnote.InternalTicketNoteRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class InternalTicketNoteConfiguration {
    @Bean InternalTicketNoteRepository internalTicketNoteRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Internal notes require the real PostgreSQL platform repository");
        return new PostgresInternalTicketNoteRepository(testDataSource,postgres);
    }
}
