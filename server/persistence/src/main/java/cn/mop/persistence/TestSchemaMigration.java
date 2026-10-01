package cn.mop.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;
import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Independent, fail-closed test migration CLI. Neither API nor worker startup calls this class. */
public final class TestSchemaMigration {
    private static final Map<String,String> SCRIPTS=Map.of(
        "1","V1__remote_test_platform.sql", "2","V2__tenant_configuration_revisions.sql",
        "3","V3__configuration_review_test_identity.sql", "4","V4__durable_customer_report_registration.sql",
        "5","V5__durable_report_photos.sql");
    private static final String RUNTIME="mop_test_runtime";
    private TestSchemaMigration() {}

    record Settings(String url,String user,String password) {
        static Settings from(Function<String,String> environment) {
            String url=required(environment,"SPRING_DATASOURCE_URL"),user=required(environment,"MOP_MIGRATION_USERNAME");
            if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/mop_test"))throw new IllegalArgumentException("Invalid migration destination");
            int port=Integer.parseInt(url.substring(url.lastIndexOf(':')+1,url.lastIndexOf('/')));
            if(port<1||port>65535||!"mop_test_migrator".equals(user))throw new IllegalArgumentException("Invalid migration identity");
            return new Settings(url,user,required(environment,"MOP_MIGRATION_PASSWORD"));
        }
        @Override public String toString(){return "Settings[mop_test,mop_test_migrator,credentials=REDACTED]";}
    }
    record Entry(String version,String script,String type,MigrationState state,boolean applied,boolean checksumMatches,Integer checksum) {}
    enum Plan { PHOTOS_PENDING, PHOTOS_APPLIED }

    static boolean applyRequested(String[] args) {
        if(args.length==0||(args.length==1&&"--check".equals(args[0])))return false;
        if(args.length==1&&"--apply-photos".equals(args[0]))return true;
        throw new IllegalArgumentException("Use --check or --apply-photos");
    }
    static Plan inspect(List<Entry> entries) {
        if(entries.size()!=5)throw new IllegalStateException("Unexpected migration set");
        Set<String> seen=new HashSet<>();Plan plan=null;
        for(Entry entry:entries) {
            if(entry.version()==null||!seen.add(entry.version())||!Objects.equals(SCRIPTS.get(entry.version()),entry.script())||!"SQL".equals(entry.type())||entry.checksum()==null)
                throw new IllegalStateException("Unexpected migration identity");
            if("5".equals(entry.version())&&entry.state()==MigrationState.PENDING&&!entry.applied())plan=Plan.PHOTOS_PENDING;
            else if(entry.state()!=MigrationState.SUCCESS||!entry.applied()||!entry.checksumMatches())throw new IllegalStateException("Inconsistent migration history");
        }
        if(!seen.equals(SCRIPTS.keySet()))throw new IllegalStateException("Missing migration");
        return plan==null?Plan.PHOTOS_APPLIED:plan;
    }
    private static Plan inspect(Flyway flyway) {
        List<Entry> entries=new ArrayList<>();
        for(MigrationInfo migration:flyway.info().all())entries.add(new Entry(
            migration.getVersion()==null?null:migration.getVersion().toString(),migration.getScript(),migration.getType().name(),
            migration.getState(),migration.isApplied(),migration.isChecksumMatching(),migration.getChecksum()));
        return inspect(entries);
    }
    static String safeFailure(String stage,Throwable failure) {
        return "MOP_SCHEMA status=FAILED stage="+stage+" exception_type="+failure.getClass().getSimpleName();
    }
    public static void main(String[] args) {
        PrintStream output=System.out,errors=System.err;String stage="arguments";int exit=0;
        try(PrintStream discarded=new PrintStream(OutputStream.nullOutputStream())) {
            // Flyway/driver diagnostics can include SQL or connection details. Only our fixed summaries leave the CLI.
            System.setOut(discarded);System.setErr(discarded);
            LogFactory.setLogCreator(type->new Log(){
                public boolean isDebugEnabled(){return false;} public void debug(String message){} public void info(String message){}
                public void warn(String message){} public void error(String message){} public void error(String message,Exception error){} public void notice(String message){}
            });
            boolean apply=applyRequested(args);stage="private_settings";Settings settings=Settings.from(System::getenv);
            stage="identity";
            try(Connection guard=connection(settings,!apply)) {
                verifyIdentity(guard);
                if(apply)requireTrue(guard,"SELECT pg_try_advisory_lock(hashtextextended('mop_test|V5_photo_migration',0))");
                stage="validate";
                Flyway flyway=Flyway.configure().dataSource(settings.url(),settings.user(),settings.password())
                    .jdbcProperties(jdbcProperties(!apply)).locations("classpath:db/migration").schemas("public").defaultSchema("public")
                    .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).mixed(false)
                    .executeInTransaction(true).group(true).target("5").validateOnMigrate(true).validateMigrationNaming(true)
                    .failOnMissingLocations(true).skipDefaultCallbacks(true).ignoreMigrationPatterns("*:pending").load();
                flyway.validate();stage="history";Plan before=inspect(flyway);
                stage="structure";
                if(before==Plan.PHOTOS_PENDING)verifyBeforePhotos(guard);else verifyPhotos(guard);
                int executed=0;
                if(apply&&before==Plan.PHOTOS_PENDING) {
                    stage="apply_photos";var result=flyway.migrate();executed=result.migrationsExecuted;
                    if(!result.success||executed>1)throw new IllegalStateException("Unexpected migration result");
                    stage="post_validate";flyway.validate();
                    if(inspect(flyway)!=Plan.PHOTOS_APPLIED)throw new IllegalStateException("Photo migration incomplete");
                    stage="post_structure_acl";verifyPhotos(guard);
                }
                output.println("MOP_SCHEMA status="+(before==Plan.PHOTOS_PENDING&&!apply?"PHOTOS_PENDING":"PHOTOS_APPLIED")+
                    " mode="+(apply?"APPLY_PHOTOS":"READ_ONLY_CHECK")+" migrations_executed="+executed+
                    " baseline=V1_V4_VALIDATED photos="+(before==Plan.PHOTOS_PENDING&&!apply?"NOT_APPLIED":"STRUCTURE_ACL_VERIFIED"));
            }
        }catch(Throwable failure){errors.println(safeFailure(stage,failure));exit=1;}
        finally{System.setOut(output);System.setErr(errors);}
        if(exit!=0)System.exit(exit);
    }
    private static String required(Function<String,String> environment,String name) {
        String value=environment.apply(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing private setting");return value;
    }
    private static Map<String,String> jdbcProperties(boolean readOnly) {
        Map<String,String> properties=new HashMap<>(Map.of("connectTimeout","5","socketTimeout","30","ApplicationName","mop-schema-cli"));
        if(readOnly)properties.put("options","-c default_transaction_read_only=on");return properties;
    }
    private static Connection connection(Settings settings,boolean readOnly)throws SQLException {
        Properties properties=new Properties();properties.putAll(jdbcProperties(readOnly));properties.setProperty("user",settings.user());properties.setProperty("password",settings.password());
        Connection connection=DriverManager.getConnection(settings.url(),properties);connection.setReadOnly(readOnly);return connection;
    }
    private static void verifyIdentity(Connection connection)throws SQLException {
        requireTrue(connection,"SELECT current_database()='mop_test' AND current_user='mop_test_migrator' AND session_user='mop_test_migrator'");
        requireTrue(connection,"SELECT count(*)=2 AND bool_and(rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls) FROM pg_roles WHERE rolname IN ('mop_test_migrator','mop_test_runtime')");
        requireTrue(connection,"SELECT to_regclass('public.flyway_schema_history') IS NOT NULL");
    }
    private static void verifyBeforePhotos(Connection connection)throws SQLException {
        requireTrue(connection,"SELECT to_regclass('public.mop_file') IS NULL AND to_regclass('public.mop_photo_job') IS NULL AND to_regclass('public.mop_file_grant') IS NULL");
        requireTrue(connection,"SELECT count(*)=2 AND bool_and(attnotnull) FROM pg_attribute WHERE NOT attisdropped AND attrelid='public.mop_operation'::regclass AND attname IN ('ticket_id','error')");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_attribute WHERE NOT attisdropped AND attrelid='public.mop_operation'::regclass AND attname='file_id')");
        requireTrue(connection,"SELECT count(*)=2 AND bool_and(convalidated AND pg_get_constraintdef(oid) ~ 'version[[:space:]]*=[[:space:]]*1') FROM pg_constraint WHERE conname IN ('mop_ticket_version_check','mop_ticket_history_version_check') AND conrelid IN ('public.mop_ticket'::regclass,'public.mop_ticket_history'::regclass)");
    }
    private static void verifyPhotos(Connection connection)throws SQLException {
        verifyColumns(connection,"mop_file",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("created_by","uuid"),Map.entry("purpose","character varying(64)"),Map.entry("original_name","character varying(200)"),
            Map.entry("media_type","character varying(64)"),Map.entry("size_bytes","integer"),Map.entry("sha256","character(64)"),Map.entry("object_key","character varying(160)"),Map.entry("operation_id","uuid"),
            Map.entry("permission_version","bigint"),Map.entry("upload_status","character varying(32)"),Map.entry("ticket_id","uuid"),Map.entry("version","bigint"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("updated_at","timestamp(3) with time zone")),Set.of("ticket_id"),true);
        verifyColumns(connection,"mop_photo_job",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("file_id","uuid"),Map.entry("content","bytea"),Map.entry("state","character varying(32)"),Map.entry("lease_owner","character varying(64)"),
            Map.entry("lease_until","timestamp(3) with time zone"),Map.entry("fencing_token","bigint"),Map.entry("attempts","integer"),Map.entry("next_at","timestamp(3) with time zone"),Map.entry("created_at","timestamp(3) with time zone")),Set.of("lease_owner","lease_until","next_at"),true);
        verifyColumns(connection,"mop_file_grant",Map.of("id","uuid","tenant_id","uuid","file_id","uuid","actor_id","uuid","permission_version","bigint","expires_at","timestamp(3) with time zone","created_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(connection,"mop_operation",Map.of("ticket_id","uuid","file_id","uuid","error","jsonb"),Set.of("ticket_id","file_id","error"),false);
        requireTrue(connection,"SELECT count(*)=4 AND bool_and(NOT attnotnull) FROM pg_attribute WHERE NOT attisdropped AND ((attrelid='public.mop_photo_job'::regclass AND attname='next_at') OR (attrelid='public.mop_operation'::regclass AND attname IN ('ticket_id','error')) OR (attrelid='public.mop_file'::regclass AND attname='ticket_id'))");
        try(PreparedStatement statement=connection.prepareStatement("SELECT f.id,f.tenant_id,f.created_by,f.purpose,f.original_name,f.media_type,f.size_bytes,f.sha256,f.object_key,f.operation_id,f.permission_version,f.upload_status,f.ticket_id,f.version,f.created_at,f.updated_at,j.id,j.tenant_id,j.file_id,j.content,j.state,j.lease_owner,j.lease_until,j.fencing_token,j.attempts,j.next_at,j.created_at,g.id,g.tenant_id,g.file_id,g.actor_id,g.permission_version,g.expires_at,g.created_at,o.file_id,o.kind,o.status,o.step,o.error,o.updated_at FROM public.mop_file f JOIN public.mop_photo_job j ON j.tenant_id=f.tenant_id AND j.file_id=f.id JOIN public.mop_operation o ON o.tenant_id=f.tenant_id AND o.id=f.operation_id LEFT JOIN public.mop_file_grant g ON g.tenant_id=f.tenant_id AND g.file_id=f.id WHERE FALSE")){statement.executeQuery().close();}
        for(String table:List.of("mop_file","mop_photo_job","mop_file_grant")) {
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
            requireConstraint(connection,table,"p",List.of("id"),null,List.of(),false);
        }
        requireConstraint(connection,"mop_file","u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_file","u",List.of("object_key"),null,List.of(),false);
        requireConstraint(connection,"mop_file","u",List.of("operation_id"),null,List.of(),false);
        requireConstraint(connection,"mop_photo_job","u",List.of("file_id"),null,List.of(),false);
        requireConstraint(connection,"mop_file","f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,"mop_file","f",List.of("tenant_id","ticket_id"),"mop_ticket",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_operation","u",List.of("tenant_id","file_id"),null,List.of(),false);
        requireConstraint(connection,"mop_operation","u",List.of("tenant_id","file_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_file","f",List.of("tenant_id","id","operation_id"),"mop_operation",List.of("tenant_id","file_id","id"),true);
        for(String table:List.of("mop_photo_job","mop_file_grant","mop_operation"))requireConstraint(connection,table,"f",List.of("tenant_id","file_id"),"mop_file",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_file_grant","f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=6 AND bool_and(i.indisvalid AND i.indisready) FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace n ON n.oid=idx.relnamespace WHERE n.nspname='public' AND idx.relname IN ('mop_file_ticket','mop_file_uploader','mop_photo_job_pending','mop_photo_job_expired','mop_file_grant_actor','mop_ticket_tenant_order')");
        requireTrue(connection,"SELECT count(*)=2 AND bool_and(convalidated AND pg_get_constraintdef(oid) ~ 'version[[:space:]]*>=[[:space:]]*1') FROM pg_constraint WHERE conname IN ('mop_ticket_version_check','mop_ticket_history_version_check') AND conrelid IN ('public.mop_ticket'::regclass,'public.mop_ticket_history'::regclass)");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid IN ('public.mop_file'::regclass,'public.mop_photo_job'::regclass,'public.mop_file_grant'::regclass,'public.mop_operation'::regclass) AND NOT convalidated)");
        Map<String,Set<String>> updates=Map.of(
            "mop_file",Set.of("upload_status","ticket_id","version","updated_at"),
            "mop_photo_job",Set.of("state","lease_owner","lease_until","fencing_token","attempts","next_at"),
            "mop_file_grant",Set.of(),"mop_operation",Set.of("status","step","error","updated_at"),
            "mop_ticket",Set.of("version","updated_at"),"mop_ticket_history",Set.of(),"mop_outbox",Set.of(),"mop_audit",Set.of());
        for(var table:updates.entrySet())verifyRuntimeAcl(connection,table.getKey(),table.getValue());
        requireTrue(connection,"SELECT NOT has_table_privilege('mop_test_runtime','public.flyway_schema_history','SELECT') AND NOT has_table_privilege('mop_test_runtime','public.flyway_schema_history','INSERT,UPDATE,DELETE,TRUNCATE') AND NOT has_schema_privilege('mop_test_runtime','public','CREATE')");
    }
    private static void verifyColumns(Connection connection,String table,Map<String,String> types,Set<String> nullable,boolean complete)throws SQLException {
        Set<String> found=new HashSet<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT attname,format_type(atttypid,atttypmod),attnotnull FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped")) {
            statement.setString(1,"public."+table);try(ResultSet rows=statement.executeQuery()){while(rows.next()) {
                String column=rows.getString(1);if(!types.containsKey(column)){if(complete)throw new IllegalStateException("Unexpected photo column");continue;}
                if(!types.get(column).equals(rows.getString(2))||rows.getBoolean(3)==nullable.contains(column))throw new IllegalStateException("Incompatible photo column");found.add(column);
            }}
        }
        if(!found.equals(types.keySet()))throw new IllegalStateException("Missing photo column");
    }
    private static void verifyRuntimeAcl(Connection connection,String table,Set<String> allowedUpdates)throws SQLException {
        requireTrue(connection,"SELECT has_table_privilege(?,?,'SELECT') AND has_table_privilege(?,?,'INSERT') AND NOT has_table_privilege(?,?,'UPDATE,DELETE,TRUNCATE,TRIGGER')",RUNTIME,"public."+table,RUNTIME,"public."+table,RUNTIME,"public."+table);
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=?::regclass AND a.grantee=0)","public."+table);
        Set<String> found=new HashSet<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT attname,has_column_privilege('mop_test_runtime',attrelid,attnum,'UPDATE'),NOT EXISTS(SELECT 1 FROM aclexplode(attacl) a WHERE a.grantee=0) FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped")) {
            statement.setString(1,"public."+table);try(ResultSet rows=statement.executeQuery()){while(rows.next()) {
                String column=rows.getString(1);if(rows.getBoolean(2)!=allowedUpdates.contains(column)||!rows.getBoolean(3))throw new IllegalStateException("Unexpected runtime column privilege");found.add(column);
            }}
        }
        if(!found.containsAll(allowedUpdates))throw new IllegalStateException("Missing runtime update column");
    }
    private static void requireConstraint(Connection connection,String table,String type,List<String> columns,String reference,List<String> referencedColumns,boolean deferred)throws SQLException {
        String sql="SELECT EXISTS(SELECT 1 FROM pg_constraint c WHERE c.conrelid=?::regclass AND c.contype=?::\"char\" AND c.convalidated AND c.condeferrable=? AND c.condeferred=? AND ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num,position) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.position)=?::text[]"+
            (reference==null?"":" AND c.confrelid=?::regclass AND ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY k(num,position) JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.position)=?::text[]")+")";
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,"public."+table);statement.setString(2,type);statement.setBoolean(3,deferred);statement.setBoolean(4,deferred);statement.setArray(5,connection.createArrayOf("text",columns.toArray()));
            if(reference!=null){statement.setString(6,"public."+reference);statement.setArray(7,connection.createArrayOf("text",referencedColumns.toArray()));}
            try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))throw new IllegalStateException("Missing validated photo constraint");}
        }
    }
    private static void requireTrue(Connection connection,String sql,String...parameters)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setQueryTimeout(10);for(int index=0;index<parameters.length;index++)statement.setString(index+1,parameters[index]);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1)||rows.next())throw new IllegalStateException("Schema verification rejected");}}
    }
}
