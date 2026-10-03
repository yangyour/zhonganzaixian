package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.manualincident.ManualRecoveryRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class ManualRecoveryConfiguration {
    @Bean ManualRecoveryRepository manualRecoveryRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Human recovery confirmations require the real PostgreSQL platform repository");
        return new PostgresManualRecoveryRepository(testDataSource,postgres);
    }
}
