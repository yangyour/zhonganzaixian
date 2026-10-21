package cn.mop.persistence;
import cn.mop.persistence.*;
import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupRunState.*;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.*;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOP_BACKUP_RUN_TEST_ENVIRONMENT",matches="dot-backup-run-disposable")
class BackupRunPostgresTest {
 @org.junit.jupiter.api.Test void isolatedDatabaseContract()throws Exception{main(new String[0]);}
 static PGSimpleDataSource runtime,migrator;static UUID tenant;static int checks;
 static final String REF="sha256:"+"a".repeat(64);
 static PGSimpleDataSource source(String user,String password){var s=new PGSimpleDataSource();s.setURL(System.getenv("SPRING_DATASOURCE_URL"));s.setUser(user);s.setPassword(password);return s;}
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static PostgresBackupRunRepository repo(){return new PostgresBackupRunRepository(runtime,(c,t,a,r)->{if(!tenant.equals(t))throw new DomainException(403,"SYNTHETIC_SCOPE_DENIED","synthetic");},(c,r,s,l)->{if(!"worker".equals(l.owner()))throw new DomainException(409,"SYNTHETIC_START_DENIED","synthetic");},(c,r,s,o,f,v,ref)->{if(!REF.equals(ref))throw new DomainException(409,"SYNTHETIC_RESULT_UNVERIFIED","synthetic");});}
 static PostgresBackupDeviceLeaseRepository leases(){return new PostgresBackupDeviceLeaseRepository(runtime,(c,t,a,r)->{if(!tenant.equals(t))throw new DomainException(403,"SYNTHETIC_SCOPE_DENIED","synthetic");},(c,s,ref)->{if(!REF.equals(ref))throw new DomainException(409,"SYNTHETIC_STOP_UNVERIFIED","synthetic");});}
 static UUID asset()throws Exception{UUID id=UUID.randomUUID();try(var c=migrator.getConnection();var s=c.prepareStatement("INSERT INTO mop_asset(id,tenant_id,name,type,management_ip,site_id,source) SELECT ?,tenant_id,?,'router','192.0.2.1',id,'SYNTHETIC_RUN_TEST' FROM mop_site WHERE tenant_id=? ORDER BY id LIMIT 1")){s.setObject(1,id);s.setString(2,"synthetic-run-"+id);s.setObject(3,tenant);if(s.executeUpdate()!=1)throw new AssertionError("site fixture missing");}return id;}
 static PostgresBackupRunRepository.LeaseClaim acquire(UUID asset,long millis){var r=leases().acquire(tenant,asset,UUID.randomUUID(),"worker",Duration.ofMillis(millis),"lease");return new PostgresBackupRunRepository.LeaseClaim(r.state().requestId(),"worker",r.state().fencingToken());}
 static void release(UUID asset,PostgresBackupRunRepository.LeaseClaim claim){var s=leases().current(tenant,asset,claim.requestId()).orElseThrow().state();leases().release(tenant,asset,claim.requestId(),claim.owner(),claim.fencingToken(),s.version(),REF,"release");}
 interface Attempt{void run()throws Exception;}
 static void denied(Attempt action,String name)throws Exception{try{action.run();}catch(DomainException e){if(!Set.of(400,403,404,409).contains(e.status))throw e;check(true,name);return;}catch(SQLException e){if(!Set.of("23514","23505","42501").contains(e.getSQLState()))throw e;check(true,name);return;}throw new AssertionError("accepted "+name);}
 static long count()throws Exception{try(var c=runtime.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM mop_backup_run_event")){r.next();return r.getLong(1);}}
 static UUID id(){return UUID.randomUUID();}
 public static void main(String[]args)throws Exception{
  if(!"dot-backup-run-disposable".equals(System.getenv("MOP_BACKUP_RUN_TEST_ENVIRONMENT")))throw new IllegalStateException("Explicit disposable gate required");
  runtime=source("mop_test_runtime",System.getenv("SPRING_DATASOURCE_PASSWORD"));migrator=source("mop_test_migrator",System.getenv("MOP_MIGRATION_PASSWORD"));
  try(var c=migrator.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT tenant_id FROM mop_site ORDER BY tenant_id,id LIMIT 1")){if(!r.next())throw new AssertionError("site fixture required");tenant=r.getObject(1,UUID.class);}
  var repo=repo();UUID a=asset(),run=id(),cmd=id();var queued=repo.queue(tenant,a,run,cmd,"queue");check(queued.state().version()==0&&queued.state().state()==State.QUEUED&&!queued.deviceExecutionAuthorized()&&!queued.objectVerifiedByJournal(),"queue_durable_no_execution_or_object_authority");
  long n=count();check(repo.queue(tenant,a,run,cmd,"changed_trace").replay()&&count()==n,"queue_exact_replay_no_write");denied(()->repo.queue(tenant,a,run,id(),"queue2"),"run_cannot_requeue");
  var revoked=new PostgresBackupRunRepository(runtime,(c,t,x,r)->{throw new DomainException(403,"REVOKED","synthetic");},(c,r,s,l)->{},(c,r,s,o,f,v,e)->{});denied(()->revoked.queue(tenant,a,run,cmd,"revoked"),"authorization_before_replay");denied(()->repo.current(id(),a,run),"cross_tenant_read_denied");check(repo.current(tenant,asset(),run).isEmpty(),"cross_asset_run_not_visible");
  var missing=new PostgresBackupRunRepository.LeaseClaim(id(),"worker",1);denied(()->repo.start(tenant,a,run,id(),0,missing,"missing"),"start_without_current_lease_denied");var claim=acquire(a,300000);
  var wrong=new PostgresBackupRunRepository.LeaseClaim(claim.requestId(),"wrong",1);denied(()->repo.start(tenant,a,run,id(),0,wrong,"wrong"),"start_verifier_denial");
  UUID startCommand=id();var started=repo.start(tenant,a,run,startCommand,0,claim,"start");check(started.state().version()==1&&started.state().attempts().size()==1&&started.lease().eventVersion()==1,"start_binds_current_lease_and_attempt");
  n=count();check(repo.start(tenant,a,run,startCommand,0,claim,"replay").replay()&&count()==n,"start_replay_not_reexecution");denied(()->repo.start(tenant,a,run,startCommand,1,claim,"mismatch"),"same_command_changed_payload_denied");
  UUID other=id();repo.queue(tenant,a,other,id(),"other");denied(()->repo.start(tenant,a,other,id(),0,claim,"reuse"),"same_lease_cannot_start_another_run");
  var unknown=repo.markUnknown(tenant,a,run,id(),1,claim,"unknown");check(unknown.state().reconciliationRequired()&&unknown.state().nextAttemptAt()==null,"unknown_requires_reconciliation_no_retry_due");denied(()->repo.start(tenant,a,run,id(),2,claim,"blind_retry"),"unknown_cannot_blind_retry");
  n=count();denied(()->repo.fail(tenant,a,run,id(),2,claim,Failure.READ_TIMEOUT,"sha256:"+"b".repeat(64),"unverified"),"completion_verifier_denies_hash_alone");check(count()==n,"failed_completion_rollback_no_event");
  UUID failureCommand=id();var failed=repo.fail(tenant,a,run,failureCommand,2,claim,Failure.READ_TIMEOUT,REF,"fail");check(failed.state().state()==State.RETRY_WAIT&&failed.state().nextAttemptAt().equals(failed.recordedAt().plusSeconds(600)),"known_failure_sets_contract_ten_minute_retry");
  denied(()->repo.start(tenant,a,run,id(),3,claim,"early"),"early_retry_denied");release(a,claim);System.out.println("RETRY_BOUNDARY_WAIT_UNTIL="+failed.state().nextAttemptAt());
  // Independent success, expiry, concurrent-CAS and direct SQL checks while retry interval elapses.
  UUID successAsset=asset(),successRun=id();repo.queue(tenant,successAsset,successRun,id(),"q");var successLease=acquire(successAsset,300000);var active=repo.start(tenant,successAsset,successRun,id(),0,successLease,"s");
  var foreign=new StoredVersion(id(),successAsset,id(),"b".repeat(64),20,active.recordedAt());denied(()->repo.succeed(tenant,successAsset,successRun,id(),1,successLease,foreign,REF,"foreign"),"foreign_object_reference_denied");
  var future=new StoredVersion(tenant,successAsset,id(),"b".repeat(64),20,active.recordedAt().plusSeconds(100));denied(()->repo.succeed(tenant,successAsset,successRun,id(),1,successLease,future,REF,"future"),"future_object_reference_denied");
  var stored=new StoredVersion(tenant,successAsset,id(),"b".repeat(64),20,active.recordedAt());var success=repo.succeed(tenant,successAsset,successRun,id(),1,successLease,stored,REF,"ok");check(success.state().state()==State.SUCCEEDED&&!success.objectVerifiedByJournal(),"synthetic_verified_success_keeps_no_object_authority");denied(()->repo.markUnknown(tenant,successAsset,successRun,id(),2,successLease,"late"),"terminal_success_immutable");check(leases().current(tenant,successAsset,successLease.requestId()).orElseThrow().state().releasedAt()==null,"completion_never_implicitly_releases_device");release(successAsset,successLease);
  UUID expAsset=asset(),expRun=id();repo.queue(tenant,expAsset,expRun,id(),"q");var expired=acquire(expAsset,200);Thread.sleep(300);denied(()->repo.start(tenant,expAsset,expRun,id(),0,expired,"expired"),"expired_lease_cannot_start");
  UUID casAsset=asset(),casRun=id();repo.queue(tenant,casAsset,casRun,id(),"q");var casLease=acquire(casAsset,300000);repo.start(tenant,casAsset,casRun,id(),0,casLease,"s");
  var latch=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var jobs=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{latch.await();try{repo().markUnknown(tenant,casAsset,casRun,id(),1,casLease,"cas");return true;}catch(DomainException e){if(e.status!=409)throw e;return false;}}));latch.countDown();int wins=0;for(var job:jobs)if(job.get(15,TimeUnit.SECONDS))wins++;check(wins==1,"independent_connections_exactly_one_cas_winner");}finally{pool.shutdownNow();}
  for(String action:List.of("UPDATE mop_backup_run_event SET trace_id='x'","DELETE FROM mop_backup_run_event","TRUNCATE mop_backup_run_event"))denied(()->{try(var c=runtime.getConnection();var s=c.createStatement()){s.execute(action);}},"runtime_immutable_"+action.split(" ")[0]);
  n=count();check(repo.current(tenant,a,run).orElseThrow().state().version()==3&&repo.fail(tenant,a,run,failureCommand,2,claim,Failure.READ_TIMEOUT,REF,"replay_old").replay()&&count()==n,"read_and_old_completion_replay_no_write");
  if("true".equals(System.getenv("MOP_RUN_REAL_RETRY_WAIT"))){
   while(true){Instant now;try(var c=runtime.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT clock_timestamp()")){r.next();now=r.getObject(1,OffsetDateTime.class).toInstant();}long remaining=Duration.between(now,failed.state().nextAttemptAt()).toMillis();if(remaining<=0)break;System.out.println("WAIT_REAL_RETRY remaining_ms="+remaining);Thread.sleep(Math.min(remaining+5,30000));}
   var fresh=acquire(a,300000);var second=repo.start(tenant,a,run,id(),3,fresh,"retry_after_real_delay");check(second.state().attempts().size()==2&&second.state().version()==4&&!fresh.requestId().equals(claim.requestId())&&second.recordedAt().compareTo(failed.state().nextAttemptAt())>=0,"actual_database_ten_minute_retry_distinct_lease");
   n=count();check(repo.fail(tenant,a,run,failureCommand,2,claim,Failure.READ_TIMEOUT,REF,"old_replay").replay()&&repo.current(tenant,a,run).orElseThrow().state().version()==4&&count()==n,"old_attempt_replay_cannot_replace_new_attempt");
  }else System.out.println("REAL_TEN_MINUTE_RETRY_NOT_RUN");
  System.out.println("TOTAL_PASS="+checks+" SYNTHETIC_AUTHORIZATION_START_COMPLETION_STOP_ONLY=true NO_DEVICE_EXECUTION=true");
 }
}
