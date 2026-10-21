package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.change.ChangeNoticeApprovalRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class ChangeNoticeApprovalConfiguration {
    @Bean ChangeNoticeApprovalRepository changeNoticeApprovalRepository(DataSource testDataSource, PlatformRepository platform, ObjectMapper mapper) {
        if (!(platform instanceof PostgresPlatformRepository postgres))
            throw new IllegalStateException("Change notice approval requires real PostgreSQL");
        return new PostgresChangeNoticeApprovalRepository(testDataSource, postgres, mapper);
    }
}
