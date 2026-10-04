package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.backup.BackupPlanDraftRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class BackupPlanDraftConfiguration {
    @Bean BackupPlanDraftRepository backupPlanDraftRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Backup plan drafts require real PostgreSQL");
        return new PostgresBackupPlanDraftRepository(testDataSource,postgres);
    }
}
