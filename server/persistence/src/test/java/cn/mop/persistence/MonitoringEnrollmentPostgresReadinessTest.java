package cn.mop.persistence;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
/** Read-only designated isolated-component checks. Never creates a database, seeds data or applies a migration. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_MONITORING_ENROLLMENT_PG_TESTS",matches="true")
class MonitoringEnrollmentPostgresReadinessTest {
 private Connection connection()throws Exception{
  String url=required("MOP_MONITORING_ENROLLMENT_TEST_JDBC_URL");
  assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_monitoring_test_[a-f0-9]{12}"),"Only an explicitly coordinated isolated designated-server database is allowed");
  var props=new java.util.Properties();props.setProperty("user","mop_test_runtime");props.setProperty("password",required("MOP_MONITORING_ENROLLMENT_TEST_PASSWORD"));props.setProperty("connectTimeout","5");props.setProperty("socketTimeout","10");
  var c=DriverManager.getConnection(url,props);c.setReadOnly(true);c.setAutoCommit(false);return c;
 }
 @Test void exactV54TablesAndImmutableGuardMustExist()throws Exception{try(var c=connection()){try(var q=c.createStatement();var r=q.executeQuery("SELECT current_user='mop_test_runtime' AND session_user='mop_test_runtime' AND current_database() ~ '^mop_monitoring_test_[a-f0-9]{12}$'")){assertTrue(r.next());assertTrue(r.getBoolean(1));}MonitoringEnrollmentSchemaVerifier.verify(c);c.rollback();}}
 @Test void runtimeCannotUpdateOrDeletePriorRegistrationEvidence()throws Exception{try(var c=connection()){try(var q=c.createStatement();var r=q.executeQuery("SELECT NOT has_table_privilege(current_user,'public.mop_monitoring_enrollment','UPDATE') AND NOT has_table_privilege(current_user,'public.mop_monitoring_enrollment','DELETE') AND NOT has_table_privilege(current_user,'public.mop_monitoring_enrollment_revision','UPDATE') AND NOT has_table_privilege(current_user,'public.mop_monitoring_enrollment_revision','DELETE')")){assertTrue(r.next());assertTrue(r.getBoolean(1));}c.rollback();}}
 private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing private test setting: "+name);return value;}
}
