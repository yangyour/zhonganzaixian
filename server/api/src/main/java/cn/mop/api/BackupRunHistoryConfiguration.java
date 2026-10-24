package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.backup.BackupRunHistoryReader;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class BackupRunHistoryConfiguration {
 @Bean BackupRunHistoryReader backupRunHistoryReader(DataSource source,PlatformRepository platform,Environment environment){if(!(platform instanceof PostgresPlatformRepository))throw new IllegalStateException("Backup run history requires PostgreSQL");return configured(source,environment::getProperty);}
 static BackupRunHistoryReader configured(DataSource source,Function<String,String> config){return (access,asset,before,size)->{
  String file=config.apply("MOP_BACKUP_ACCESS_GRANTS_FILE"),binding=config.apply("MOP_BACKUP_RUN_HISTORY_BINDING");
  if(file==null||file.isBlank()||binding==null||binding.isBlank())throw new DomainException(503,"BACKUP_RUN_HISTORY_NOT_CONFIGURED","备份运行历史的独立授权尚未配置");
  try{var grants=new BackupGrantFile(Path.of(file));grants.get();return new PostgresBackupRunHistoryReader(source,new PostgresBackupTaskAuthorization(binding,grants,Clock.systemUTC())).read(access,asset,before,size);}
  catch(DomainException denied){throw denied;}catch(RuntimeException failure){throw new DomainException(503,"BACKUP_RUN_HISTORY_UNAVAILABLE","备份运行历史配置无法核对");}
 };}
}
