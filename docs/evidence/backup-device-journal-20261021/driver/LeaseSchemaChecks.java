package cn.mop.persistence;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;import java.util.*;
public class LeaseSchemaChecks {
 public static void main(String[]args)throws Exception{
  if(!"dot-backup-device-lease-disposable".equals(System.getenv("MOP_DEVICE_LEASE_TEST_ENVIRONMENT")))throw new IllegalStateException("Explicit disposable gate required");
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("SPRING_DATASOURCE_URL"));ds.setUser("mop_test_migrator");ds.setPassword(System.getenv("MOP_MIGRATION_PASSWORD"));int passed=0;
  try(var c=ds.getConnection()){
   BackupDeviceLeaseSchemaVerifier.resource();BackupDeviceLeaseSchemaVerifier.verify(c);System.out.println("PASS catalog_original");passed++;
   try(var s=c.createStatement()){s.execute("SET timezone='America/New_York'");s.execute("SET datestyle='ISO, DMY'");}BackupDeviceLeaseSchemaVerifier.verify(c);try(var s=c.createStatement();var r=s.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")){r.next();if(!r.getString(1).equals("America/New_York")||!r.getString(2).equals("ISO, DMY"))throw new AssertionError("session settings not restored");}System.out.println("PASS timezone_independent_restores_session");passed++;
   for(String sql:List.of("ALTER TABLE mop_backup_device_lease_event DROP CONSTRAINT mop_backup_device_lease_event_lease_millis_check","ALTER TABLE mop_backup_device_lease_event DISABLE TRIGGER mop_backup_device_lease_insert_guard","GRANT UPDATE ON mop_backup_device_lease_event TO mop_test_runtime","DROP INDEX mop_backup_device_request_once","ALTER FUNCTION mop_backup_device_lease_insert_guard() SECURITY DEFINER")){
    c.setAutoCommit(false);boolean denied=false;try{try(var s=c.createStatement()){s.execute(sql);}try{BackupDeviceLeaseSchemaVerifier.verify(c);}catch(IllegalStateException|SQLException e){denied=true;}}finally{c.rollback();c.setAutoCommit(true);}if(!denied)throw new AssertionError("tamper accepted "+sql);BackupDeviceLeaseSchemaVerifier.verify(c);System.out.println("PASS tamper_rollback_"+(++passed));
   }
  }
  System.out.println("TOTAL_SCHEMA_PASS="+passed);
 }
}
