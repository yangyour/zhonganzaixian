package cn.mop.worker.sla;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit standalone mode; no Spring scan, scheduler, photo store or automatic migration. */
public final class SlaReminderMain {
    private SlaReminderMain() {}
    public static void run(){
        var settings=SlaReminderSettings.from(System::getenv);
        var config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.databaseUser());config.setPassword(settings.databasePassword());
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setValidationTimeout(3000);config.setInitializationFailTimeout(5000);
        config.setPoolName("mop-sla-reminder");config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");
        var running=new AtomicBoolean(true);Thread stop=new Thread(()->running.set(false),"sla-reminder-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try(var source=new HikariDataSource(config)){
            var mapper=new ObjectMapper();var repository=new PostgresSlaReminderRepository(source,new PostgresPlatformRepository(source,mapper),mapper);
            if(!repository.ready())throw new IllegalStateException("CONFIG_PENDING: SLA reminder V36 schema is not verified; apply migration separately");
            String owner="sla-reminder-"+UUID.randomUUID();System.out.println("SLA reminder worker started; intents only, no provider delivery or paid sends");
            while(running.get()&&!Thread.currentThread().isInterrupted()){
                try{var claim=repository.claim(owner,settings.leaseSeconds());if(claim.isPresent()){repository.scan(claim.get(),settings.maxAttempts());continue;}}
                catch(RuntimeException unavailable){System.err.println("SLA reminder dependency unavailable; lease expiry controls retry; private details withheld");}
                try{Thread.sleep(settings.pollMillis());}catch(InterruptedException stopRequested){Thread.currentThread().interrupt();}
            }
        }finally{try{Runtime.getRuntime().removeShutdownHook(stop);}catch(IllegalStateException shuttingDown){/* JVM shutdown already owns the hook. */}}
    }
}
