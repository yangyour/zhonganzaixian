package cn.mop.persistence;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;import java.util.*;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOP_BACKUP_ARCHIVE_TEST_ENVIRONMENT",matches="dot-backup-archive-disposable")
class BackupArchiveSchemaPostgresTest {
 @org.junit.jupiter.api.Test void isolatedCatalogContract()throws Exception{main(new String[0]);}
 public static void main(String[]args)throws Exception{
  if(!"dot-backup-archive-disposable".equals(System.getenv("MOP_BACKUP_ARCHIVE_TEST_ENVIRONMENT")))throw new IllegalStateException("Explicit disposable gate required");
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("SPRING_DATASOURCE_URL"));ds.setUser("mop_test_migrator");ds.setPassword(System.getenv("MOP_MIGRATION_PASSWORD"));int passed=0;
  try(var c=ds.getConnection()){
   BackupArchiveSchemaVerifier.resource();BackupArchiveSchemaVerifier.verify(c);System.out.println("PASS catalog_original");passed++;
   try(var s=c.createStatement()){s.execute("SET timezone='America/New_York'");s.execute("SET datestyle='ISO, DMY'");}BackupArchiveSchemaVerifier.verify(c);try(var s=c.createStatement();var r=s.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")){r.next();if(!r.getString(1).equals("America/New_York")||!r.getString(2).equals("ISO, DMY"))throw new AssertionError("session settings not restored");}System.out.println("PASS timezone_independent_restores_session");passed++;
   for(String sql:List.of("ALTER TABLE mop_backup_archive_submission DROP CONSTRAINT mop_backup_archive_submission_attempt_number_check","ALTER TABLE mop_backup_archive_submission DISABLE TRIGGER mop_backup_archive_submission_complete_guard","ALTER TABLE mop_backup_archive_event DISABLE TRIGGER mop_backup_archive_event_guard","GRANT UPDATE ON mop_backup_archive_event TO mop_test_runtime","DROP INDEX mop_backup_archive_dispatch_once","ALTER FUNCTION mop_backup_archive_submission_complete_guard() SECURITY DEFINER","ALTER TABLE mop_backup_archive_submission ADD COLUMN unexpected text")){
    c.setAutoCommit(false);boolean denied=false;try{try(var s=c.createStatement()){s.execute(sql);}try{BackupArchiveSchemaVerifier.verify(c);}catch(IllegalStateException|SQLException e){denied=true;}}finally{c.rollback();c.setAutoCommit(true);}if(!denied)throw new AssertionError("tamper accepted "+sql);BackupArchiveSchemaVerifier.verify(c);System.out.println("PASS tamper_rollback_"+(++passed));
   }
  }
  System.out.println("TOTAL_SCHEMA_PASS="+passed);
 }
}
