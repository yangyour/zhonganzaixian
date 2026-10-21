package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class InspectionQuarterlyReportConfiguration {
    @Bean InspectionQuarterlyReportRepository inspectionQuarterlyReportRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Inspection quarterly report drafts require the real PostgreSQL repository");
        return new PostgresInspectionQuarterlyReportRepository(testDataSource,postgres);
    }
}
