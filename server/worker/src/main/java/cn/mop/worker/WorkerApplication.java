package cn.mop.worker;

import cn.mop.adapters.MinioPhotoStore;
import cn.mop.adapters.oss.OssProjectStore;
import cn.mop.domain.PhotoObjectStore;
import cn.mop.persistence.PostgresPlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** File-only worker: no customer-report outbox, messaging, or other job consumer is registered. */
@SpringBootApplication(excludeName={
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
public class WorkerApplication {
    public static void main(String[] args) {
        if (java.util.Arrays.stream(args).anyMatch(argument -> argument.startsWith("--backup-"))) {
            if (args.length != 1 || !java.util.Set.of("--backup-postprocess", "--backup-postprocess-check", "--backup-reconcile-only", "--backup-reconcile-only-check").contains(args[0])) {
                throw new IllegalArgumentException("Backup postprocessing accepts exactly one explicit mode");
            }
            cn.mop.worker.backup.BackupWorkerMain.run(args[0].endsWith("-check"),args[0].startsWith("--backup-reconcile-only")?cn.mop.worker.backup.BackupTaskScheduler.Mode.RECONCILE_ONLY:cn.mop.worker.backup.BackupTaskScheduler.Mode.POSTPROCESS);
            return;
        }
        if (java.util.Arrays.asList(args).contains("--notification-relay")) {
            if (args.length != 1) {
                throw new IllegalArgumentException("Notification relay mode accepts no other arguments");
            }
            cn.mop.worker.notification.TicketNotificationRelayMain.run();
            return;
        }
        if(java.util.Arrays.asList(args).contains("--ticket-autoclose")){if(args.length!=1)throw new IllegalArgumentException("Ticket auto-close mode accepts no other arguments");cn.mop.worker.autoclose.TicketAutoCloseMain.run();return;}
        if(java.util.Arrays.asList(args).contains("--sla-reminders")){if(args.length!=1)throw new IllegalArgumentException("SLA reminder mode accepts no other arguments");cn.mop.worker.sla.SlaReminderMain.run();return;}
        if(java.util.Arrays.asList(args).contains("--autoticket-registration-retries")){if(args.length!=1)throw new IllegalArgumentException("Autoticket registration retry mode accepts no other arguments");cn.mop.worker.autoticket.AutoTicketRetryMain.run();return;}
        SpringApplication app = new SpringApplication(WorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
    @Bean WorkerRuntimeSettings workerRuntimeSettings(Environment environment) {
        String[] profiles=environment.getActiveProfiles().length==0
            ?environment.getDefaultProfiles():environment.getActiveProfiles();
        return WorkerRuntimeSettings.from(profiles,environment::getProperty);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource workerDataSource(WorkerRuntimeSettings settings) {
        HikariConfig configuration=new HikariConfig();
        configuration.setJdbcUrl(settings.jdbcUrl());
        configuration.setUsername(settings.databaseUser());
        configuration.setPassword(settings.databasePassword());
        configuration.setMaximumPoolSize(2);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(5000);
        configuration.setValidationTimeout(3000);
        configuration.setInitializationFailTimeout(5000);
        configuration.setPoolName("mop-file-only-worker");
        configuration.addDataSourceProperty("connectTimeout","5");
        configuration.addDataSourceProperty("socketTimeout","20");
        configuration.addDataSourceProperty("tcpKeepAlive","true");
        return cn.mop.persistence.RuntimeDataSources.openRestrictedTest(configuration);
    }

    @Bean ObjectMapper workerObjectMapper(){return new ObjectMapper();}

    @Bean PostgresPlatformRepository workerRepository(HikariDataSource workerDataSource,ObjectMapper workerObjectMapper) {
        return new PostgresPlatformRepository(workerDataSource,workerObjectMapper);
    }

    @Bean PhotoObjectStore photoObjectStore(WorkerRuntimeSettings settings) {
        if(settings.ossSettings()!=null){try{return new OssProjectStore(settings.ossSettings()).photos();}catch(RuntimeException invalid){throw new IllegalStateException("FAIL_CLOSED: OSS photo client could not be initialized; private details withheld");}}
        if(settings.minioSettings()!=null)return new MinioPhotoStore(settings.minioSettings());
        return new MinioPhotoStore(settings.storageEndpoint(),settings.storageBucket(),settings.storageAccessKey(),settings.storageSecretKey());
    }

    @Bean PhotoWorkerRuntime fileOnlyWorker(PostgresPlatformRepository workerRepository,PhotoObjectStore photoObjectStore) {
        return new PhotoWorkerRuntime(workerRepository,photoObjectStore);
    }
}
