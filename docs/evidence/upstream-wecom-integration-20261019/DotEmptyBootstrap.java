import org.flywaydb.core.Flyway;
import java.sql.*;
public class DotEmptyBootstrap {
 public static void main(String[] args) throws Exception {
  String url=System.getenv("SPRING_DATASOURCE_URL"), user=System.getenv("MOP_MIGRATION_USERNAME"), password=System.getenv("MOP_MIGRATION_PASSWORD");
  if(!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(url)||!"mop_test_migrator".equals(user)||!"dot-upstream-e739027-fresh-pg".equals(System.getenv("MOP_TEST_ENVIRONMENT_NAME")))throw new IllegalStateException("Wrong isolated environment");
  try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Expected empty dedicated database");}
  var f=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").schemas("public").defaultSchema("public").createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).mixed(false).executeInTransaction(true).group(true).target("4").validateOnMigrate(true).validateMigrationNaming(true).failOnMissingLocations(true).skipDefaultCallbacks(true).load();
  var r=f.migrate();if(!r.success||r.migrationsExecuted!=4)throw new IllegalStateException("Wrong migration count");f.validate();System.out.println("DOT_SCHEMA baseline_V1_V4=PASS executed=4");
 }
}
