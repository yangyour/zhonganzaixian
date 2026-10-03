package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.manualincident.ManualIncidentRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class ManualIncidentConfiguration {
    @Bean ManualIncidentRepository manualIncidentRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Manual registrations require the real PostgreSQL platform repository");
        return new PostgresManualIncidentRepository(testDataSource,postgres);
    }
}
