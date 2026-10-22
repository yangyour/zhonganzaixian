package cn.mop.persistence;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.*;import java.util.*;
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="MOP_DELIVERY_JOURNAL_TEST_ENVIRONMENT",matches="dot-delivery-journal-disposable")
public class DeliveryReceiptSchemaPostgresTest {
 @org.junit.jupiter.api.Test void isolatedCatalogContract()throws Exception{main(new String[0]);}
 public static void main(String[]args)throws Exception{
  if(!"dot-delivery-journal-disposable".equals(System.getenv("MOP_DELIVERY_JOURNAL_TEST_ENVIRONMENT")))throw new IllegalStateException("Explicit disposable gate required");
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("SPRING_DATASOURCE_URL"));ds.setUser("mop_test_migrator");ds.setPassword(System.getenv("MOP_MIGRATION_PASSWORD"));int passed=0;
  try(var c=ds.getConnection()){
   DeliveryReceiptSchemaVerifier.resource();DeliveryReceiptSchemaVerifier.verify(c);System.out.println("SCHEMA_PASS original");passed++;
   try(var q=c.createStatement()){q.execute("SET timezone='America/New_York'");q.execute("SET datestyle='ISO, DMY'");}DeliveryReceiptSchemaVerifier.verify(c);try(var q=c.createStatement();var r=q.executeQuery("SELECT current_setting('TimeZone'),current_setting('DateStyle')")){r.next();if(!r.getString(1).equals("America/New_York")||!r.getString(2).equals("ISO, DMY"))throw new AssertionError("session settings not restored");}System.out.println("SCHEMA_PASS timezone_independence");passed++;
   for(String sql:List.of("ALTER TABLE mop_delivery_receipt_journal DROP CONSTRAINT mop_delivery_receipt_journal_version_check","ALTER TABLE mop_delivery_receipt_journal DISABLE TRIGGER mop_delivery_receipt_journal_guard","GRANT UPDATE ON mop_delivery_receipt_journal TO mop_test_runtime","GRANT UPDATE(trace_id) ON mop_delivery_receipt_journal TO mop_test_runtime","DROP INDEX mop_delivery_receipt_dedup","ALTER FUNCTION mop_delivery_receipt_journal_guard() SECURITY DEFINER","ALTER TABLE mop_delivery_receipt_journal ADD COLUMN unexpected text","ALTER TABLE mop_delivery_receipt_journal DROP CONSTRAINT mop_delivery_receipt_journal_check","ALTER TABLE mop_delivery_receipt_journal ADD CONSTRAINT extra CHECK(version>=0)","ALTER TABLE mop_delivery_receipt_journal DROP CONSTRAINT mop_delivery_receipt_journal_version_check; ALTER TABLE mop_delivery_receipt_journal ADD CONSTRAINT mop_delivery_receipt_journal_version_check CHECK(version>=0)")){
    c.setAutoCommit(false);boolean rejected=false;try{try(var q=c.createStatement()){q.execute(sql);}try{DeliveryReceiptSchemaVerifier.verify(c);}catch(IllegalStateException|SQLException e){rejected=true;}}finally{c.rollback();c.setAutoCommit(true);}if(!rejected)throw new AssertionError("tamper accepted "+sql);DeliveryReceiptSchemaVerifier.verify(c);System.out.println("SCHEMA_PASS tamper_rollback_"+(++passed));
   }
  }
  System.out.println("TOTAL_SCHEMA_PASS="+passed);
 }
}
