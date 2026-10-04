package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class InspectionSourceCollectionConfiguration {
    @Bean InspectionSourceCollectionRepository inspectionSourceCollectionRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Inspection source collections require the real PostgreSQL repository");
        return new PostgresInspectionSourceCollectionRepository(testDataSource,postgres);
    }
}
