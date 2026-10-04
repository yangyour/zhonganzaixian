package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.inspection.InspectionTemplateRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.persistence.PostgresInspectionTemplateRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class InspectionTemplateConfiguration {
    @Bean InspectionTemplateRepository inspectionTemplateRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Inspection templates require the real PostgreSQL repository");
        return new PostgresInspectionTemplateRepository(testDataSource,postgres);
    }
}
