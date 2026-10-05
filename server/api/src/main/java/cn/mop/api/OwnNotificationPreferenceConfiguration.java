package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.notification.OwnNotificationPreferenceRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class OwnNotificationPreferenceConfiguration {
    @Bean OwnNotificationPreferenceRepository ownNotificationPreferenceRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Own preferences require real PostgreSQL");
        return new PostgresOwnNotificationPreferenceRepository(testDataSource,postgres);
    }
}
