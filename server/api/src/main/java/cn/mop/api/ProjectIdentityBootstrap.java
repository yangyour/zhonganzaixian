package cn.mop.api;

import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Explicit stdin-only bootstrap; never starts an application or migrates. */
public final class ProjectIdentityBootstrap {
    public static void main(String[] args) {
        try {
            if(args.length!=1||!args[0].equals("--enable-existing-test-accounts"))throw new IllegalArgumentException();
            String url=System.getenv("SPRING_DATASOURCE_URL"),user=System.getenv("SPRING_DATASOURCE_USERNAME");
            if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/mop_test")||!"mop_test_runtime".equals(user))throw new IllegalArgumentException();
            var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));config.setMaximumPoolSize(2);config.setConnectionTimeout(5000);
            var mapper=new ObjectMapper();Object result;
            try(var source=new HikariDataSource(config)){var platform=new PostgresPlatformRepository(source,mapper);result=new ProjectIdentityRepository(source,platform,new ProjectPasswordCodec(),mapper).bootstrapExistingTestAccounts(mapper.readTree(System.in));}
            System.out.println(mapper.writeValueAsString(result));
        }catch(Exception e){System.err.println("PROJECT_IDENTITY_BOOTSTRAP_FAILED details=redacted");System.exit(1);}
    }
}
