package cn.mop.worker.autoclose;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit standalone mode; no Spring scan, scheduler, photo store or automatic migration. */
public final class TicketAutoCloseMain {
    private TicketAutoCloseMain() {}
    public static void run(){
        var settings=TicketAutoCloseSettings.from(System::getenv);
        var config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.databaseUser());config.setPassword(settings.databasePassword());
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setValidationTimeout(3000);config.setInitializationFailTimeout(5000);
        config.setPoolName("mop-ticket-autoclose");config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");
        var running=new AtomicBoolean(true);Thread stop=new Thread(()->running.set(false),"ticket-autoclose-stop");
        Runtime.getRuntime().addShutdownHook(stop);
        try(var source=new HikariDataSource(config)){
            var mapper=new ObjectMapper();var repository=new PostgresTicketAutoCloseRepository(source,new PostgresPlatformRepository(source,mapper),mapper);
            if(!repository.ready())throw new IllegalStateException("CONFIG_PENDING: ticket auto-close V34 schema is not verified; apply migration separately");
            String owner="ticket-autoclose-"+UUID.randomUUID();System.out.println("Ticket auto-close worker started; notifications and other jobs are not consumed");
            while(running.get()&&!Thread.currentThread().isInterrupted()){
                try{var claim=repository.claim(owner,settings.leaseSeconds());if(claim.isPresent()){repository.complete(claim.get(),settings.maxAttempts());continue;}}
                catch(RuntimeException unavailable){System.err.println("Ticket auto-close dependency unavailable; lease expiry controls retry; private details withheld");}
                try{Thread.sleep(settings.pollMillis());}catch(InterruptedException stopRequested){Thread.currentThread().interrupt();}
            }
        }finally{try{Runtime.getRuntime().removeShutdownHook(stop);}catch(IllegalStateException shuttingDown){/* JVM shutdown already owns the hook. */}}
    }
}
