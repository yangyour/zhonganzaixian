package cn.mop.importworker;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Dedicated import entrypoint: no component scan, photo settings, or other consumers. */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName={"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration","org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@Import(AssetImportWorkerConfiguration.class)
public class AssetImportWorkerApplication {
    public static void main(String[] args){SpringApplication application=new SpringApplication(AssetImportWorkerApplication.class);application.setWebApplicationType(WebApplicationType.NONE);application.run(args);}
}
