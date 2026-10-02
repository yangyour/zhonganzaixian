import org.flywaydb.core.Flyway;
import java.sql.*;
import java.io.*;
import org.flywaydb.core.api.logging.*;

/** Explicit first-time initialization of the new isolated database; never called by API/Worker. */
public class NikoSchemaBootstrap {
 public static void main(String[] args) {
  var out=System.out;var err=System.err;
  try(var discarded=new PrintStream(OutputStream.nullOutputStream())) {
   System.setOut(discarded);System.setErr(discarded);
   if(args.length!=1||!args[0].equals("--initialize-new-niko-database"))throw new IllegalArgumentException();
   String url=System.getenv("SPRING_DATASOURCE_URL"),user=System.getenv("MOP_MIGRATION_USERNAME"),password=System.getenv("MOP_MIGRATION_PASSWORD");
   if(!"jdbc:postgresql://127.0.0.1:25432/mop_test".equals(url)||!"mop_test_migrator".equals(user))throw new IllegalArgumentException();
   try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()) {
    try(var r=s.executeQuery("SELECT current_database()='mop_test' AND current_user='mop_test_migrator' AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname=current_user")){if(!r.next()||!r.getBoolean(1))throw new IllegalStateException();}
    try(var r=s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("New database only");}
   }
   var f=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").schemas("public").defaultSchema("public").createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).mixed(false).executeInTransaction(true).group(true).target("4").validateOnMigrate(true).validateMigrationNaming(true).failOnMissingLocations(true).skipDefaultCallbacks(true).load();
   var r=f.migrate();if(!r.success||r.migrationsExecuted!=4)throw new IllegalStateException();f.validate();
   out.println("NIKO_SCHEMA baseline_V1_V4=PASS executed=4 existing_migrations_modified=false");
  }catch(Exception e){err.println("NIKO_SCHEMA initialization=FAILED exception_type="+e.getClass().getSimpleName());System.exit(1);}
 }
}
