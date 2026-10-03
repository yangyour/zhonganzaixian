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
    @Bean(destroyMethod="close") HikariDataSource contactImportDataSource(ContactImportWorkerSettings settings){
        HikariConfig c=new HikariConfig();c.setJdbcUrl(settings.jdbcUrl());c.setUsername(settings.databaseUser());c.setPassword(settings.databasePassword());c.setMaximumPoolSize(2);c.setMinimumIdle(0);c.setConnectionTimeout(5000);c.setValidationTimeout(3000);c.setInitializationFailTimeout(5000);c.setPoolName("mop-contact-import-worker");c.addDataSourceProperty("connectTimeout","5");c.addDataSourceProperty("socketTimeout","20");c.addDataSourceProperty("tcpKeepAlive","true");c.addDataSourceProperty("ApplicationName","mop-contact-import-worker");try{return new HikariDataSource(c);}catch(RuntimeException failure){throw new IllegalStateException("FAIL_CLOSED: designated restricted contact import PostgreSQL connection unavailable");}
    }
    @Bean ObjectMapper contactImportObjectMapper(){return new ObjectMapper();}
    @Bean ContactImportRepository contactImportRepository(HikariDataSource contactImportDataSource,ObjectMapper contactImportObjectMapper){return new PostgresContactImportRepository(contactImportDataSource,new PostgresPlatformRepository(contactImportDataSource,contactImportObjectMapper));}
    @Bean ContactImportWorkerRuntime contactImportRuntime(ContactImportRepository contactImportRepository,ContactImportWorkerSettings settings){return new ContactImportWorkerRuntime(contactImportRepository,settings.lease());}
}
