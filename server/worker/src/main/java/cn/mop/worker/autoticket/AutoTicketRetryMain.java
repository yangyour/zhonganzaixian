package cn.mop.worker.autoticket;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.MonitoringEventCenterRepository.Access;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in standalone registration retry only. Does not activate ticket workflows, send notifications or migrate SQL. */
public final class AutoTicketRetryMain {
 private AutoTicketRetryMain(){}
 public static void run(){
  var settings=AutoTicketRetrySettings.from(System::getenv);var config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.databaseUser());config.setPassword(settings.databasePassword());
  config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setValidationTimeout(3000);config.setInitializationFailTimeout(5000);config.setPoolName("mop-autoticket-registration-retry");config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");
  var running=new AtomicBoolean(true);Thread stop=new Thread(()->running.set(false),"autoticket-registration-retry-stop");Runtime.getRuntime().addShutdownHook(stop);
  try(var source=cn.mop.persistence.RuntimeDataSources.openRestrictedTest(config)){
   var mapper=new ObjectMapper().findAndRegisterModules();var platform=new PostgresPlatformRepository(source,mapper);var events=new PostgresMonitoringEventCenterRepository(source,platform);
   if(!events.readinessProblems().isEmpty())throw new IllegalStateException("CONFIG_PENDING: monitoring event schema is not ready; no startup migration is allowed");
   do{
    try{int handled=runOnce(settings,platform,events);System.out.println("Autoticket registration retry batch handled="+handled+"; provider sends=0; workflow activation=false");}
    catch(RuntimeException failure){if(settings.once())throw new IllegalStateException("Autoticket registration retry did not complete; private details withheld");System.err.println("Autoticket registration retry dependency or authorization unavailable; next explicit poll will recheck");}
    if(settings.once())break;
    try{Thread.sleep(settings.pollMillis());}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
   }while(running.get()&&!Thread.currentThread().isInterrupted());
  }finally{try{Runtime.getRuntime().removeShutdownHook(stop);}catch(IllegalStateException shuttingDown){/* hook already owned by JVM */}}
 }
 static String requestHash(String id,long version){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("autoticket-registration-retry-v1|"+id+"|"+version).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}}
 static int runOnce(AutoTicketRetrySettings settings,PostgresPlatformRepository platform,PostgresMonitoringEventCenterRepository events){
  var member=platform.membership(settings.actorId(),settings.tenantId()).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","重试执行身份已失效"));
  if(!member.active())throw new DomainException(401,"SESSION_EXPIRED","重试执行身份已失效");
  var access=new Access(settings.tenantId(),settings.actorId(),member.permissionVersion(),UUID.randomUUID().toString());int handled=0;
  for(var attempt:events.dueRegistrationRetries(access,settings.batchSize())){
   try{platform.idempotentAuthorized(access.actorId(),access.tenantId(),"WORKER /autoticket-registration-retry/"+attempt.id(),"retry-"+attempt.id()+"-v"+attempt.version(),requestHash(attempt.id(),attempt.version()),access.permissionVersion(),()->events.authorizeWrite(access),()->events.retryRegistration(access,attempt.id(),attempt.version()));handled++;}
   catch(DomainException conflict){if(conflict.status!=409)throw conflict;}
  }return handled;
 }
}
