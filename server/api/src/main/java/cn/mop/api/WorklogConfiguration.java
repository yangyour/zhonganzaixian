package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.worklog.WorklogRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class WorklogConfiguration {
    @Bean WorklogRepository worklogRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Worklogs require the real PostgreSQL platform repository");
        return new PostgresWorklogRepository(testDataSource,postgres);
    }
}
