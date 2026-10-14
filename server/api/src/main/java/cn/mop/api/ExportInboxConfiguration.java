package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration @Profile("remote_test")
public class ExportInboxConfiguration {
    @Bean ExportInboxSupport exportInboxSupport(Environment env,DataSource testDataSource,PlatformRepository platform,AssetExportPublicationSupport publication){
        String flag=env.getProperty("MOP_EXPORT_INBOX_ENABLED","false");if("false".equals(flag))return ExportInboxSupport.disabled();
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Export inbox requires designated PostgreSQL");
        return new ExportInboxSupport(true,"true".equals(flag),new PostgresExportInboxRepository(testDataSource,postgres),publication);
    }
}
