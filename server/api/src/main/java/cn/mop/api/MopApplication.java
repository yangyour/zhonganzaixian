package cn.mop.api;

import cn.mop.domain.ProfilePolicy;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.FixtureRepository;
import cn.mop.persistence.PostgresPlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.sql.SQLException;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
    excludeName={"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration","org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
public class MopApplication {
    public static void main(String[] args){SpringApplication.run(MopApplication.class,args);}
    @Bean Object failClosedProfileGuard(Environment environment){
        String[] profiles=environment.getActiveProfiles().length==0?environment.getDefaultProfiles():environment.getActiveProfiles();
        ProfilePolicy.requireSupportedRuntime(profiles);
        if(!"127.0.0.1".equals(environment.getProperty("server.address","127.0.0.1")))throw new IllegalStateException("FAIL_CLOSED: test runtime must bind to 127.0.0.1");
        if(java.util.Arrays.asList(profiles).contains("local_fixture")&&!environment.getProperty("mop.fixture.unit-test",Boolean.class,false))
            throw new IllegalStateException("FAIL_CLOSED: local_fixture is reserved for explicitly enabled isolated unit fixtures; normal development uses remote_test");
        return new Object();
    }
    @Bean @Profile("local_fixture") FixtureRepository fixtureRepository(ObjectMapper mapper){return new FixtureRepository(mapper);}
    @Bean(destroyMethod="close") @Profile("remote_test") HikariDataSource testDataSource(Environment environment){
        String url=required(environment,"SPRING_DATASOURCE_URL");
        String runtimeUser=required(environment,"SPRING_DATASOURCE_USERNAME"),runtimePassword=required(environment,"SPRING_DATASOURCE_PASSWORD");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test"))
            throw new IllegalStateException("FAIL_CLOSED: remote_test requires approved loopback PostgreSQL dedicated mop_test URL without credentials");
        if(!"mop_test_runtime".equals(runtimeUser))throw new IllegalStateException("FAIL_CLOSED: use the restricted project runtime role");
        // Application startup never performs DDL. Reviewed migrations run as a separate step;
        // a pending photo schema must not be silently applied or prevent existing no-photo work.
        HikariConfig configuration=new HikariConfig();configuration.setJdbcUrl(url);configuration.setUsername(runtimeUser);configuration.setPassword(runtimePassword);configuration.setMaximumPoolSize(6);configuration.setMinimumIdle(1);configuration.setConnectionTimeout(5000);configuration.setValidationTimeout(3000);configuration.setInitializationFailTimeout(5000);configuration.setPoolName("mop-remote-test");
        HikariDataSource dataSource=new HikariDataSource(configuration);
        try(var connection=dataSource.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT current_database(),current_user")){
            if(!rows.next()||!"mop_test".equals(rows.getString(1))||!runtimeUser.equals(rows.getString(2)))throw new IllegalStateException("FAIL_CLOSED: unexpected runtime database or role");
        }catch(SQLException|RuntimeException exception){dataSource.close();throw new IllegalStateException("FAIL_CLOSED: designated restricted test PostgreSQL runtime unavailable");}
        return dataSource;
    }
    @Bean @Profile("remote_test") PlatformRepository platformRepository(DataSource testDataSource,ObjectMapper mapper){return new PostgresPlatformRepository(testDataSource,mapper);}
    private static String required(Environment environment,String key){String value=environment.getProperty(key);if(value==null||value.isBlank())throw new IllegalStateException("FAIL_CLOSED: missing private test environment setting "+key);return value;}
}
