package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.inspection.InspectionWorksheetRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import cn.mop.persistence.PostgresInspectionWorksheetRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class InspectionWorksheetConfiguration {
    @Bean InspectionWorksheetRepository inspectionWorksheetRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Inspection worksheets require the real PostgreSQL repository");
        return new PostgresInspectionWorksheetRepository(testDataSource,postgres);
    }
}
