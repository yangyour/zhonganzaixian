package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.capacity.CapacityScenarioRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class CapacityScenarioConfiguration {
    @Bean CapacityScenarioRepository capacityScenarioRepository(DataSource testDataSource,PlatformRepository platform,ObjectMapper mapper){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Capacity drafts require the real PostgreSQL platform repository");
        return new PostgresCapacityScenarioRepository(testDataSource,postgres,mapper);
    }
}
