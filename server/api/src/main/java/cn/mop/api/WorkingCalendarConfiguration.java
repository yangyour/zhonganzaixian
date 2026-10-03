package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.calendar.WorkingCalendarRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class WorkingCalendarConfiguration {
    @Bean WorkingCalendarRepository workingCalendarRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Calendar drafts require the real PostgreSQL platform repository");
        return new PostgresWorkingCalendarRepository(testDataSource,postgres);
    }
}
