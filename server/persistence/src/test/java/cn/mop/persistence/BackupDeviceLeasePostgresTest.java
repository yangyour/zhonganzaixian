package cn.mop.persistence;
import cn.mop.persistence.PostgresBackupDeviceLeaseRepository;
import cn.mop.domain.DomainException;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.*;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOP_DEVICE_LEASE_TEST_ENVIRONMENT",matches="dot-backup-device-lease-disposable")
class BackupDeviceLeasePostgresTest {
 @org.junit.jupiter.api.Test void isolatedDatabaseContract()throws Exception{main(new String[0]);}
 static PGSimpleDataSource runtime,migrator;static UUID tenant;static int checks;
 static final String REF="sha256:"+"a".repeat(64);
 static PGSimpleDataSource source(String user,String password){var s=new PGSimpleDataSource();s.setURL(System.getenv("SPRING_DATASOURCE_URL"));s.setUser(user);s.setPassword(password);return s;}
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static PostgresBackupDeviceLeaseRepository repo(){return new PostgresBackupDeviceLeaseRepository(runtime,(c,t,a,r)->{if(!tenant.equals(t))throw new DomainException(403,"SYNTHETIC_SCOPE_DENIED","synthetic");},(c,s,ref)->{if(!REF.equals(ref))throw new DomainException(409,"SYNTHETIC_STOP_UNVERIFIED","synthetic");});}
 static UUID asset()throws Exception{UUID id=UUID.randomUUID();try(var c=migrator.getConnection();var s=c.prepareStatement("INSERT INTO mop_asset(id,tenant_id,name,type,management_ip,site_id,source) SELECT ?,tenant_id,?,'router','192.0.2.1',id,'SYNTHETIC_LEASE_TEST' FROM mop_site WHERE tenant_id=? ORDER BY id LIMIT 1")){s.setObject(1,id);s.setString(2,"synthetic-lease-"+id);s.setObject(3,tenant);if(s.executeUpdate()!=1)throw new AssertionError("site fixture missing");}return id;}
 interface Attempt{void run()throws Exception;}
 static void denied(Attempt action,String name)throws Exception{try{action.run();}catch(DomainException e){if(!Set.of(403,404,409).contains(e.status))throw e;check(true,name);return;}catch(SQLException e){if(!Set.of("23514","23505","42501").contains(e.getSQLState()))throw e;check(true,name);return;}throw new AssertionError("accepted "+name);}
 static long count()throws Exception{try(var c=runtime.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM mop_backup_device_lease_event")){r.next();return r.getLong(1);}}
 static void raw(UUID a,UUID r,long v,long f,String owner,int millis,String action,String extraCols,String extraValues)throws Exception{try(var c=runtime.getConnection();var s=c.prepareStatement("INSERT INTO mop_backup_device_lease_event(tenant_id,asset_id,version,fencing_token,request_id,worker_owner,lease_millis,action,trace_id"+extraCols+") VALUES(?,?,?,?,?,?,?,?,?"+extraValues+")")){s.setObject(1,tenant);s.setObject(2,a);s.setLong(3,v);s.setLong(4,f);s.setObject(5,r);s.setString(6,owner);s.setInt(7,millis);s.setString(8,action);s.setString(9,"synthetic");s.executeUpdate();}}
 public static void main(String[]args)throws Exception{
  if(!"dot-backup-device-lease-disposable".equals(System.getenv("MOP_DEVICE_LEASE_TEST_ENVIRONMENT")))throw new IllegalStateException("Explicit disposable gate required");
  runtime=source("mop_test_runtime",System.getenv("SPRING_DATASOURCE_PASSWORD"));migrator=source("mop_test_migrator",System.getenv("MOP_MIGRATION_PASSWORD"));
  try(var c=migrator.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT tenant_id FROM mop_site ORDER BY tenant_id,id LIMIT 1")){if(!r.next())throw new AssertionError("site fixture required");tenant=r.getObject(1,UUID.class);}
  var repo=repo();var a=asset();var req=UUID.randomUUID();var first=repo.acquire(tenant,a,req,"one",Duration.ofSeconds(30),"t1");
  check(first.state().version()==1&&first.state().fencingToken()==1&&!first.deviceExecutionAuthorized(),"acquire_database_clock_no_execution_authority");
  long n=count();var replay=repo.acquire(tenant,a,req,"one",Duration.ofSeconds(30),"t2");check(replay.replay()&&replay.state().snapshot().equals(first.state().snapshot())&&count()==n,"lost_response_acquire_replay_no_write");
  denied(()->repo.acquire(tenant,a,req,"other",Duration.ofSeconds(30),"t3"),"replay_payload_mismatch");
  denied(()->repo.acquire(tenant,a,UUID.randomUUID(),"two",Duration.ofSeconds(30),"t4"),"held_request_blocks_new_owner");
  denied(()->repo.renew(tenant,a,req,"wrong",1,1,"t5"),"wrong_owner_denied");denied(()->repo.renew(tenant,a,req,"one",2,1,"t6"),"wrong_fence_denied");denied(()->repo.renew(tenant,a,req,"one",1,2,"t7"),"stale_version_denied");
  Thread.sleep(5);var renewed=repo.renew(tenant,a,req,"one",1,1,"t8");check(renewed.state().version()==2&&renewed.state().leaseUntil().isAfter(first.state().leaseUntil()),"renew_advances_version_and_clock");
  n=count();denied(()->repo.release(tenant,a,req,"one",1,2,"sha256:"+"b".repeat(64),"t9"),"mandatory_stop_verifier_denies_unproven_reference");check(count()==n,"failed_stop_verification_rollback_no_event");
  var released=repo.release(tenant,a,req,"one",1,2,REF,"t10");check(released.state().releasedAt()!=null&&released.state().version()==3,"verified_synthetic_release");
  UUID req2=UUID.randomUUID();var second=repo.acquire(tenant,a,req2,"two",Duration.ofSeconds(30),"t11");check(second.state().fencingToken()==2&&second.state().version()==4,"new_request_advances_fence");
  n=count();check(repo.release(tenant,a,req,"one",1,2,REF,"t12").replay()&&repo.acquire(tenant,a,req,"one",Duration.ofSeconds(30),"t13").replay()&&count()==n,"released_old_replays_do_not_release_or_reacquire_new_request");
  check(repo.current(tenant,a,req).isEmpty()&&repo.current(tenant,a,req2).orElseThrow().state().releasedAt()==null&&count()==n,"scoped_current_read_is_nonmutating");
  var revoked=new PostgresBackupDeviceLeaseRepository(runtime,(c,t,x,r)->{throw new DomainException(403,"REVOKED","synthetic");},(c,s,ref)->{});
  denied(()->revoked.acquire(tenant,a,req,"one",Duration.ofSeconds(30),"t14"),"authorization_checked_before_replay");
  denied(()->repo.acquire(UUID.randomUUID(),a,UUID.randomUUID(),"one",Duration.ofSeconds(30),"t15"),"cross_tenant_denied");
  UUID expiryAsset=asset(),expiryRequest=UUID.randomUUID();repo.acquire(tenant,expiryAsset,expiryRequest,"exp",Duration.ofMillis(250),"e1");Thread.sleep(350);
  check(repo.current(tenant,expiryAsset,expiryRequest).orElseThrow().status().name().equals("RECONCILIATION_REQUIRED"),"expiry_observed_as_reconciliation_required");
  denied(()->repo.acquire(tenant,expiryAsset,UUID.randomUUID(),"new",Duration.ofSeconds(30),"e2"),"expired_lease_blocks_takeover");denied(()->repo.release(tenant,expiryAsset,expiryRequest,"exp",1,1,REF,"e3"),"expired_lease_cannot_release");denied(()->repo.renew(tenant,expiryAsset,expiryRequest,"exp",1,1,"e4"),"expired_lease_cannot_renew");
  UUID concurrent=asset();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var jobs=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++){final int k=i;jobs.add(pool.submit(()->{start.await();try{repo().acquire(tenant,concurrent,UUID.randomUUID(),"worker"+k,Duration.ofSeconds(30),"concurrent"+k);return true;}catch(DomainException ex){if(ex.status!=409)throw ex;return false;}}));}start.countDown();int successes=0;for(var job:jobs)if(job.get(15,TimeUnit.SECONDS))successes++;check(successes==1,"independent_connections_exactly_one_concurrent_acquisition");}finally{pool.shutdownNow();}
  UUID rawAsset=asset(),rawRequest=UUID.randomUUID();denied(()->raw(rawAsset,rawRequest,1,1,"raw",30000,"ACQUIRE",",observed_at",",clock_timestamp()"),"caller_supplied_timestamp_denied");raw(rawAsset,rawRequest,1,1,"raw",30000,"ACQUIRE","","");
  denied(()->raw(rawAsset,rawRequest,2,1,"raw",30000,"RELEASE","",""),"missing_release_reference_denied");denied(()->raw(rawAsset,rawRequest,3,1,"raw",30000,"RENEW","",""),"direct_sql_version_gap_denied");
  for(String action:List.of("UPDATE mop_backup_device_lease_event SET worker_owner='attacker'","DELETE FROM mop_backup_device_lease_event","TRUNCATE mop_backup_device_lease_event")){denied(()->{try(var c=runtime.getConnection();var s=c.createStatement()){s.execute(action);}},"runtime_immutable_"+action.split(" ")[0]);}
  n=count();try(var c=runtime.getConnection()){c.setAutoCommit(false);try(var s=c.createStatement()){s.execute("SELECT 1");}c.rollback();}check(count()==n,"read_rollback_no_event");
  UUID rollbackAsset=asset(),rollbackRequest=UUID.randomUUID();n=count();try(var c=runtime.getConnection()){c.setAutoCommit(false);try(var q=c.prepareStatement("INSERT INTO mop_backup_device_lease_event(tenant_id,asset_id,version,fencing_token,request_id,worker_owner,lease_millis,action,trace_id) VALUES(?,?,1,1,?,'rollback',30000,'ACQUIRE','rollback')")){q.setObject(1,tenant);q.setObject(2,rollbackAsset);q.setObject(3,rollbackRequest);q.executeUpdate();}c.rollback();}check(count()==n&&repo.current(tenant,rollbackAsset,rollbackRequest).isEmpty(),"actual_acquire_transaction_rollback_no_event");
  n=count();var expiredReplay=repo.acquire(tenant,expiryAsset,expiryRequest,"exp",Duration.ofMillis(250),"e5");check(expiredReplay.replay()&&expiredReplay.status().name().equals("RECONCILIATION_REQUIRED")&&count()==n,"expired_original_replay_never_extends_lease");
  denied(()->repo.acquire(tenant,UUID.randomUUID(),UUID.randomUUID(),"missing",Duration.ofSeconds(30),"missing"),"missing_scoped_asset_denied");
  raw(rawAsset,rawRequest,2,1,"raw",30000,"RELEASE",",release_confirmation_reference",",'"+REF+"'");UUID rawNext=UUID.randomUUID();raw(rawAsset,rawNext,3,2,"raw",30000,"ACQUIRE","","");raw(rawAsset,rawNext,4,2,"raw",30000,"RELEASE",",release_confirmation_reference",",'"+REF+"'");denied(()->raw(rawAsset,rawRequest,5,3,"raw",30000,"ACQUIRE","",""),"historical_acquisition_request_cannot_be_reused");
  check(repo.acquire(tenant,asset(),UUID.randomUUID(),"independent",Duration.ofSeconds(30),"i1").state().version()==1,"independent_asset_unblocked");
  System.out.println("TOTAL_PASS="+checks+" SYNTHETIC_AUTHORIZATION_AND_STOP_ADAPTERS_ONLY=true NO_DEVICE_EXECUTION=true");
 }
}
