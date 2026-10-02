package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import cn.mop.domain.audit.AuditReader;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;

@Configuration @Profile("remote_test")
public class AuditReadConfiguration {
    @Bean AuditReader auditReader(DataSource testDataSource,PlatformRepository platform,ObjectMapper mapper){
        if(!(platform instanceof PostgresPlatformRepository))throw new IllegalStateException("Audit queries require the real PostgreSQL platform repository");
        return new PostgresAuditReader(testDataSource,mapper);
    }
}
