package cn.mop.contactworker;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
/** Dedicated contact-import entrypoint: no scan, storage client, notification consumer or automatic migration. */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName={"org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration","org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"})
@Import(ContactImportWorkerConfiguration.class)
public class ContactImportWorkerApplication {
    public static void main(String[] args){SpringApplication application=new SpringApplication(ContactImportWorkerApplication.class);application.setWebApplicationType(WebApplicationType.NONE);application.run(args);}
}
