package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.domain.contact.CustomerContactRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
@Configuration @Profile("remote_test")
public class CustomerContactConfiguration {
    @Bean CustomerContactRepository customerContactRepository(DataSource testDataSource,PlatformRepository platform){
        if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Contacts require the real PostgreSQL platform repository");
        return new PostgresCustomerContactRepository(testDataSource,postgres);
    }
}
