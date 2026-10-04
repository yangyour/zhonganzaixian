package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.change.ChangeNoticeDraftRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class ChangeNoticeDraftConfiguration {
    @Bean ChangeNoticeDraftRepository changeNoticeDraftRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Change notice drafts require real PostgreSQL");
        return new PostgresChangeNoticeDraftRepository(testDataSource,postgres);
    }
}
