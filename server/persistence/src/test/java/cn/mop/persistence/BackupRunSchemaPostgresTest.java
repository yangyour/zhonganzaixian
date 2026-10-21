package cn.mop.persistence;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;import java.util.*;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOP_BACKUP_RUN_TEST_ENVIRONMENT",matches="dot-backup-run-disposable")
class BackupRunSchemaPostgresTest {
 @org.junit.jupiter.api.Test void isolatedCatalogContract()throws Exception{main(new String[0]);}
 public static void main(String[]args)throws Exception{
  if(!"dot-backup-run-disposable".equals(System.getenv("MOP_BACKUP_RUN_TEST_ENVIRONMENT")))throw new IllegalStateException("Explicit disposable gate required");
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("SPRING_DATASOURCE_URL"));ds.setUser("mop_test_migrator");ds.setPassword(System.getenv("MOP_MIGRATION_PASSWORD"));int passed=0;
  try(var c=ds.getConnection()){
   BackupRunSchemaVerifier.resource();BackupRunSchemaVerifier.verify(c);System.out.println("PASS catalog_original");passed++;
   try(var s=c.createStatement()){s.execute("SET timezone='America/New_York'");s.execute("SET datestyle='ISO, DMY'");}BackupRunSchemaVerifier.verify(c);try(var s=c.createStatement();var r=s.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")){r.next();if(!r.getString(1).equals("America/New_York")||!r.getString(2).equals("ISO, DMY"))throw new AssertionError("session settings not restored");}System.out.println("PASS timezone_independent_restores_session");passed++;
   for(String sql:List.of("ALTER TABLE mop_backup_run_event DROP CONSTRAINT mop_backup_run_event_attempt_number_check","ALTER TABLE mop_backup_run_event DISABLE TRIGGER mop_backup_run_event_guard","GRANT UPDATE ON mop_backup_run_event TO mop_test_runtime","DROP INDEX mop_backup_attempt_lease_once","ALTER FUNCTION mop_backup_run_event_guard() SECURITY DEFINER")){
    c.setAutoCommit(false);boolean denied=false;try{try(var s=c.createStatement()){s.execute(sql);}try{BackupRunSchemaVerifier.verify(c);}catch(IllegalStateException|SQLException e){denied=true;}}finally{c.rollback();c.setAutoCommit(true);}if(!denied)throw new AssertionError("tamper accepted "+sql);BackupRunSchemaVerifier.verify(c);System.out.println("PASS tamper_rollback_"+(++passed));
   }
  }
  System.out.println("TOTAL_SCHEMA_PASS="+passed);
 }
}
