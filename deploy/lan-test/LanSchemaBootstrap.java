import org.flywaydb.core.Flyway;
import java.sql.*;
import java.io.*;

/** Explicit baseline for a newly created LAN test database, never an application startup hook. */
public final class LanSchemaBootstrap {
    public static void main(String[] args) {
        var output = System.out;
        var errors = System.err;
        try (var discarded = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discarded);
            System.setErr(discarded);
            if (args.length != 1 || !args[0].equals("--initialize-empty-lan-test-database")) throw new IllegalArgumentException();
            String url = System.getenv("SPRING_DATASOURCE_URL");
            String user = System.getenv("MOP_MIGRATION_USERNAME");
            String password = System.getenv("MOP_MIGRATION_PASSWORD");
            if (!"jdbc:postgresql://127.0.0.1:12060/mop_test".equals(url) || !"mop_test_migrator".equals(user)
                    || !"mop-lan-test".equals(System.getenv("MOP_NEW_TEST_ENVIRONMENT"))) throw new IllegalArgumentException();
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT current_database()='mop_test' AND current_user='mop_test_migrator' AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname=current_user")) {
                    if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException();
                }
                try (var result = statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")) {
                    result.next();
                    if (result.getInt(1) != 0) throw new IllegalStateException("Empty database required");
                }
            }
            var flyway = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                    .schemas("public").defaultSchema("public").createSchemas(false).cleanDisabled(true)
                    .baselineOnMigrate(false).outOfOrder(false).mixed(false).executeInTransaction(true).group(true)
                    .target("4").validateOnMigrate(true).validateMigrationNaming(true).failOnMissingLocations(true)
                    .skipDefaultCallbacks(true).load();
            var result = flyway.migrate();
            if (!result.success || result.migrationsExecuted != 4) throw new IllegalStateException();
            flyway.validate();
            output.println("LAN_SCHEMA baseline_V1_V4=PASS executed=4 existing_migrations_modified=false");
        } catch (Exception failure) {
            errors.println("LAN_SCHEMA initialization=FAILED exception_type=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
