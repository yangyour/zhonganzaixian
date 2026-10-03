package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.contact.CustomerContactRepository;
import cn.mop.domain.contact.ContactImportRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class CustomerContactConfiguration {
    @Bean CustomerContactRepository customerContactRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Contacts require the real PostgreSQL platform repository");
        return new PostgresCustomerContactRepository(testDataSource,postgres);
    }
    @Bean ContactImportRepository contactImportRepository(DataSource testDataSource,PlatformRepository platform){if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Contact imports require the real PostgreSQL platform repository");return new PostgresContactImportRepository(testDataSource,postgres);}

}
