package cn.mop.contactworker;
import cn.mop.domain.contact.ContactImportRepository;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration(proxyBeanMethods=false)
class ContactImportWorkerConfiguration {
    @Bean ContactImportWorkerSettings contactImportSettings(Environment environment){String[] profiles=environment.getActiveProfiles().length==0?environment.getDefaultProfiles():environment.getActiveProfiles();return ContactImportWorkerSettings.from(profiles,environment::getProperty);}
    /** Open only the dedicated runtime identity after verifying its effective PostgreSQL privileges. */
    @Bean(destroyMethod = "close")
    HikariDataSource contactImportDataSource(ContactImportWorkerSettings settings) {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(settings.jdbcUrl());
        configuration.setUsername(settings.databaseUser());
        configuration.setPassword(settings.databasePassword());
        configuration.setMaximumPoolSize(2);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(5000);
        configuration.setValidationTimeout(3000);
        configuration.setInitializationFailTimeout(5000);
        configuration.setPoolName("mop-contact-import-worker");
        configuration.addDataSourceProperty("connectTimeout", "5");
        configuration.addDataSourceProperty("socketTimeout", "20");
        configuration.addDataSourceProperty("tcpKeepAlive", "true");
        configuration.addDataSourceProperty("ApplicationName", "mop-contact-import-worker");
        return RuntimeDataSources.openRestrictedTest(configuration);
    }
    @Bean ObjectMapper contactImportObjectMapper(){return new ObjectMapper();}
    @Bean ContactImportRepository contactImportRepository(HikariDataSource contactImportDataSource,ObjectMapper contactImportObjectMapper){return new PostgresContactImportRepository(contactImportDataSource,new PostgresPlatformRepository(contactImportDataSource,contactImportObjectMapper));}
    @Bean ContactImportWorkerRuntime contactImportRuntime(ContactImportRepository contactImportRepository,ContactImportWorkerSettings settings){return new ContactImportWorkerRuntime(contactImportRepository,settings.lease());}
}
