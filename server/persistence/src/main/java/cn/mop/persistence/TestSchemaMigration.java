package cn.mop.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Independent, fail-closed test migration CLI. Neither API nor worker startup calls this class. */
public final class TestSchemaMigration {
    private static final Map<String,String> SCRIPTS=Map.ofEntries(
        Map.entry("1","V1__remote_test_platform.sql"),
        Map.entry("2","V2__tenant_configuration_revisions.sql"),
        Map.entry("3","V3__configuration_review_test_identity.sql"),
        Map.entry("4","V4__durable_customer_report_registration.sql"),
        Map.entry("5","V5__durable_report_photos.sql"),
        Map.entry("6","V6__asset_import_sources_and_mapping.sql"),
        Map.entry("7","V7__asset_dependency_draft_revisions.sql"),
        Map.entry("8","V8__manual_worklog_revisions.sql"),
        Map.entry("9","V9__manual_asset_metadata_revisions.sql"),
        Map.entry("10","V10__immutable_internal_ticket_notes.sql"),
        Map.entry("11","V11__immutable_ticket_comments.sql"),
        Map.entry("12","V12__unpublished_working_calendar_drafts.sql"));
    static final String IMPORT_SHA256="1f532822668274b7aebd12ea78b57e6be0aa9fd53ceb77023ee8067589cd2b4b";
    static final String DEPENDENCY_SHA256="5a8ce8d49fe948fc0e9586c102f7c8bfc46cb975a0b5d9f32d4bd1ccc72670bb";
    static final String WORKLOG_SHA256="942ea4dc88a8169b40662edcfd89871e8280a429ac922bd0cc1eb451b82ad2f8";
    static final String ASSET_METADATA_SHA256="a311d8ea11516bb60f2375cafd02b293beb904d07c03c890653dd5bb3751412c";
    static final String INTERNAL_NOTE_SHA256="ba1ad02e7df8079936288d8712a84062a5bd695885b1e45daacec9f4d35ad198";
    static final String TICKET_COMMENT_SHA256="d8535bbf13ca9e35c115278f36232a786b4562c99cc9afd9f5195a416028a8e3";
    static final String WORKING_CALENDAR_SHA256="e6e0412e8a96936a6f4e09dc9f96431cd6551dd9c15ebaf7d2ed414467aedb1a";
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
    static final String WORKING_CALENDAR_CHECKER_SHA256="07749cb8084dc2f0e9f2a6ed899b77be3dc4e1aeb55f15eed1bf558e48b18fb6";
    enum Plan { PHOTOS_PENDING, ASSET_IMPORTS_PENDING, ASSET_IMPORTS_APPLIED, ASSET_DEPENDENCIES_APPLIED, WORKLOGS_APPLIED, ASSET_METADATA_APPLIED, INTERNAL_NOTES_APPLIED, TICKET_COMMENTS_APPLIED, WORKING_CALENDARS_APPLIED }
    enum Mode { READ_ONLY_CHECK, APPLY_PHOTOS, APPLY_ASSET_IMPORTS, APPLY_ASSET_DEPENDENCIES, APPLY_WORKLOGS, APPLY_ASSET_METADATA, APPLY_INTERNAL_NOTES, APPLY_TICKET_COMMENTS, APPLY_WORKING_CALENDARS }

    static boolean applyRequested(String[] args) {
        return requestedMode(args)!=Mode.READ_ONLY_CHECK;
    }
    static Mode requestedMode(String[] args) {
        if(args.length==0||(args.length==1&&"--check".equals(args[0])))return Mode.READ_ONLY_CHECK;
        if(args.length==1&&"--apply-photos".equals(args[0]))return Mode.APPLY_PHOTOS;
        if(args.length==1&&"--apply-asset-imports".equals(args[0]))return Mode.APPLY_ASSET_IMPORTS;
        if(args.length==1&&"--apply-asset-dependencies".equals(args[0]))return Mode.APPLY_ASSET_DEPENDENCIES;
        if(args.length==1&&"--apply-worklogs".equals(args[0]))return Mode.APPLY_WORKLOGS;
        if(args.length==1&&"--apply-asset-metadata".equals(args[0]))return Mode.APPLY_ASSET_METADATA;
        if(args.length==1&&"--apply-internal-notes".equals(args[0]))return Mode.APPLY_INTERNAL_NOTES;
        if(args.length==1&&"--apply-ticket-comments".equals(args[0]))return Mode.APPLY_TICKET_COMMENTS;
        if(args.length==1&&"--apply-working-calendars".equals(args[0]))return Mode.APPLY_WORKING_CALENDARS;
        throw new IllegalArgumentException("Use --check or one explicit supported feature apply mode");
    }
    static Plan inspect(List<Entry> entries) {
        if(entries.size()!=12)throw new IllegalStateException("Unexpected migration set");
        Set<String> seen=new HashSet<>();boolean photosPending=false,importsPending=false,dependenciesPending=false,worklogsPending=false,metadataPending=false,notesPending=false,commentsPending=false,calendarsPending=false;
        for(Entry entry:entries) {
            if(entry.version()==null||!seen.add(entry.version())||!Objects.equals(SCRIPTS.get(entry.version()),entry.script())||!"SQL".equals(entry.type())||entry.checksum()==null)
                throw new IllegalStateException("Unexpected migration identity");
            if("5".equals(entry.version())&&entry.state()==MigrationState.PENDING&&!entry.applied())photosPending=true;
            else if("6".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())importsPending=true;
            else if("7".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())dependenciesPending=true;
            else if("8".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())worklogsPending=true;
            else if("9".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())metadataPending=true;
            else if("10".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())notesPending=true;
            else if("11".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())commentsPending=true;
            else if("12".equals(entry.version())&&(entry.state()==MigrationState.PENDING||entry.state()==MigrationState.ABOVE_TARGET)&&!entry.applied())calendarsPending=true;
            else if(entry.state()!=MigrationState.SUCCESS||!entry.applied()||!entry.checksumMatches())throw new IllegalStateException("Inconsistent migration history");
        }
        if(!seen.equals(SCRIPTS.keySet()))throw new IllegalStateException("Missing migration");
        if(photosPending&&!importsPending)throw new IllegalStateException("Asset imports precede the photo baseline");
        if(!dependenciesPending&&(photosPending||importsPending))throw new IllegalStateException("Dependencies precede the import baseline");
        if(!worklogsPending&&(photosPending||importsPending||dependenciesPending))throw new IllegalStateException("Worklogs precede the V1-V7 baseline");
        if(!metadataPending&&(photosPending||importsPending||dependenciesPending||worklogsPending))throw new IllegalStateException("Asset metadata precedes the V1-V8 baseline");
        if(!notesPending&&(photosPending||importsPending||dependenciesPending||worklogsPending||metadataPending))throw new IllegalStateException("Internal notes precede the V1-V9 baseline");
        if(!commentsPending&&(photosPending||importsPending||dependenciesPending||worklogsPending||metadataPending||notesPending))throw new IllegalStateException("Ticket comments precede the V1-V10 baseline");
        if(!calendarsPending&&(photosPending||importsPending||dependenciesPending||worklogsPending||metadataPending||notesPending||commentsPending))throw new IllegalStateException("Working calendars precede the V1-V11 baseline");
        return photosPending?Plan.PHOTOS_PENDING:importsPending?Plan.ASSET_IMPORTS_PENDING:dependenciesPending?Plan.ASSET_IMPORTS_APPLIED:worklogsPending?Plan.ASSET_DEPENDENCIES_APPLIED:metadataPending?Plan.WORKLOGS_APPLIED:notesPending?Plan.ASSET_METADATA_APPLIED:commentsPending?Plan.INTERNAL_NOTES_APPLIED:calendarsPending?Plan.TICKET_COMMENTS_APPLIED:Plan.WORKING_CALENDARS_APPLIED;
    }
    static boolean shouldMigrate(Mode mode,Plan plan){
        if(mode==Mode.APPLY_ASSET_IMPORTS&&plan==Plan.PHOTOS_PENDING)throw new IllegalStateException("Apply asset imports requires validated V1-V5 history");
        if(mode==Mode.APPLY_ASSET_DEPENDENCIES&&(plan==Plan.PHOTOS_PENDING||plan==Plan.ASSET_IMPORTS_PENDING))throw new IllegalStateException("Apply dependencies requires validated V1-V6 history");
        if(mode==Mode.APPLY_WORKLOGS&&plan!=Plan.ASSET_DEPENDENCIES_APPLIED&&plan!=Plan.WORKLOGS_APPLIED&&plan!=Plan.ASSET_METADATA_APPLIED&&plan!=Plan.INTERNAL_NOTES_APPLIED&&plan!=Plan.TICKET_COMMENTS_APPLIED&&plan!=Plan.WORKING_CALENDARS_APPLIED)throw new IllegalStateException("Apply worklogs requires validated V1-V7 history");
        if(mode==Mode.APPLY_ASSET_METADATA&&plan!=Plan.WORKLOGS_APPLIED&&plan!=Plan.ASSET_METADATA_APPLIED&&plan!=Plan.INTERNAL_NOTES_APPLIED&&plan!=Plan.TICKET_COMMENTS_APPLIED&&plan!=Plan.WORKING_CALENDARS_APPLIED)throw new IllegalStateException("Apply asset metadata requires validated V1-V8 history");
        if(mode==Mode.APPLY_INTERNAL_NOTES&&plan!=Plan.ASSET_METADATA_APPLIED&&plan!=Plan.INTERNAL_NOTES_APPLIED&&plan!=Plan.TICKET_COMMENTS_APPLIED&&plan!=Plan.WORKING_CALENDARS_APPLIED)throw new IllegalStateException("Apply internal notes requires validated V1-V9 history");
        if(mode==Mode.APPLY_TICKET_COMMENTS&&plan!=Plan.INTERNAL_NOTES_APPLIED&&plan!=Plan.TICKET_COMMENTS_APPLIED&&plan!=Plan.WORKING_CALENDARS_APPLIED)throw new IllegalStateException("Apply ticket comments requires validated V1-V10 history");
        if(mode==Mode.APPLY_WORKING_CALENDARS&&plan!=Plan.TICKET_COMMENTS_APPLIED&&plan!=Plan.WORKING_CALENDARS_APPLIED)throw new IllegalStateException("Apply working calendars requires validated V1-V11 history");
        return mode==Mode.APPLY_PHOTOS&&plan==Plan.PHOTOS_PENDING||mode==Mode.APPLY_ASSET_IMPORTS&&plan==Plan.ASSET_IMPORTS_PENDING||mode==Mode.APPLY_ASSET_DEPENDENCIES&&plan==Plan.ASSET_IMPORTS_APPLIED||mode==Mode.APPLY_WORKLOGS&&plan==Plan.ASSET_DEPENDENCIES_APPLIED||mode==Mode.APPLY_ASSET_METADATA&&plan==Plan.WORKLOGS_APPLIED||mode==Mode.APPLY_INTERNAL_NOTES&&plan==Plan.ASSET_METADATA_APPLIED||mode==Mode.APPLY_TICKET_COMMENTS&&plan==Plan.INTERNAL_NOTES_APPLIED||mode==Mode.APPLY_WORKING_CALENDARS&&plan==Plan.TICKET_COMMENTS_APPLIED;
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
            Mode mode=requestedMode(args);boolean apply=mode!=Mode.READ_ONLY_CHECK;
            stage="migration_resources";verifyImportResource();verifyDependencyResource();verifyWorklogResource();verifyAssetMetadataResource();verifyInternalNoteResource();verifyTicketCommentResource();verifyWorkingCalendarResource();stage="private_settings";Settings settings=Settings.from(System::getenv);
            stage="identity";
            try(Connection guard=connection(settings,!apply)) {
                verifyIdentity(guard);
                if(apply)requireTrue(guard,"SELECT pg_try_advisory_lock(hashtextextended('mop_test|explicit_schema_migration',0))");
                stage="validate";
                Flyway flyway=Flyway.configure().dataSource(settings.url(),settings.user(),settings.password())
                    .jdbcProperties(jdbcProperties(!apply)).locations("classpath:db/migration").schemas("public").defaultSchema("public")
                    .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).mixed(false)
                    .executeInTransaction(true).group(true).target(mode==Mode.APPLY_PHOTOS?"5":mode==Mode.APPLY_ASSET_IMPORTS?"6":mode==Mode.APPLY_ASSET_DEPENDENCIES?"7":mode==Mode.APPLY_WORKLOGS?"8":mode==Mode.APPLY_ASSET_METADATA?"9":mode==Mode.APPLY_INTERNAL_NOTES?"10":mode==Mode.APPLY_TICKET_COMMENTS?"11":"12").validateOnMigrate(true).validateMigrationNaming(true)
                    .failOnMissingLocations(true).skipDefaultCallbacks(true).ignoreMigrationPatterns("*:pending").load();
                flyway.validate();stage="history";Plan before=inspect(flyway);boolean migrate=shouldMigrate(mode,before);
                stage="structure";
                verifyStructure(guard,before);
                int executed=0;
                Plan after=before;
                if(migrate) {
                    stage=mode==Mode.APPLY_PHOTOS?"apply_photos":mode==Mode.APPLY_ASSET_IMPORTS?"apply_asset_imports":mode==Mode.APPLY_ASSET_DEPENDENCIES?"apply_asset_dependencies":mode==Mode.APPLY_WORKLOGS?"apply_worklogs":mode==Mode.APPLY_ASSET_METADATA?"apply_asset_metadata":mode==Mode.APPLY_INTERNAL_NOTES?"apply_internal_notes":mode==Mode.APPLY_TICKET_COMMENTS?"apply_ticket_comments":"apply_working_calendars";var result=flyway.migrate();executed=result.migrationsExecuted;
                    if(!result.success||executed!=1)throw new IllegalStateException("Unexpected migration result");
                    stage="post_validate";flyway.validate();
                    after=inspect(flyway);Plan expected=mode==Mode.APPLY_PHOTOS?Plan.ASSET_IMPORTS_PENDING:mode==Mode.APPLY_ASSET_IMPORTS?Plan.ASSET_IMPORTS_APPLIED:mode==Mode.APPLY_ASSET_DEPENDENCIES?Plan.ASSET_DEPENDENCIES_APPLIED:mode==Mode.APPLY_WORKLOGS?Plan.WORKLOGS_APPLIED:mode==Mode.APPLY_ASSET_METADATA?Plan.ASSET_METADATA_APPLIED:mode==Mode.APPLY_INTERNAL_NOTES?Plan.INTERNAL_NOTES_APPLIED:mode==Mode.APPLY_TICKET_COMMENTS?Plan.TICKET_COMMENTS_APPLIED:Plan.WORKING_CALENDARS_APPLIED;
                    if(after!=expected)throw new IllegalStateException("Explicit migration incomplete");
                    stage="post_structure_acl";verifyStructure(guard,after);
                }
                output.println("MOP_SCHEMA status="+after+" mode="+mode+" migrations_executed="+executed+
                    " baseline="+(after==Plan.WORKING_CALENDARS_APPLIED?"V1_V11_VALIDATED":(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED)?"V1_V10_VALIDATED":(after==Plan.INTERNAL_NOTES_APPLIED||(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED))?"V1_V9_VALIDATED":after==Plan.ASSET_METADATA_APPLIED?"V1_V8_VALIDATED":after==Plan.WORKLOGS_APPLIED?"V1_V7_VALIDATED":after==Plan.ASSET_DEPENDENCIES_APPLIED?"V1_V6_VALIDATED":after==Plan.PHOTOS_PENDING?"V1_V4_VALIDATED":"V1_V5_VALIDATED")+
                    " photos="+(after==Plan.PHOTOS_PENDING?"NOT_APPLIED":"STRUCTURE_ACL_VERIFIED")+
                    " asset_imports="+((after==Plan.ASSET_IMPORTS_APPLIED||after==Plan.ASSET_DEPENDENCIES_APPLIED||after==Plan.WORKLOGS_APPLIED||after==Plan.ASSET_METADATA_APPLIED||(after==Plan.INTERNAL_NOTES_APPLIED||(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED)))?"STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " asset_dependencies="+((after==Plan.ASSET_DEPENDENCIES_APPLIED||after==Plan.WORKLOGS_APPLIED||after==Plan.ASSET_METADATA_APPLIED||(after==Plan.INTERNAL_NOTES_APPLIED||(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED)))?"DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " worklogs="+((after==Plan.WORKLOGS_APPLIED||after==Plan.ASSET_METADATA_APPLIED||(after==Plan.INTERNAL_NOTES_APPLIED||(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED)))?"MANUAL_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " asset_metadata="+((after==Plan.ASSET_METADATA_APPLIED||(after==Plan.INTERNAL_NOTES_APPLIED||(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED)))?"MANUAL_DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " internal_notes="+((after==Plan.INTERNAL_NOTES_APPLIED||(after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED))?"IMMUTABLE_INTERNAL_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " ticket_comments="+((after==Plan.TICKET_COMMENTS_APPLIED||after==Plan.WORKING_CALENDARS_APPLIED)?"IMMUTABLE_REPORTER_COMMENT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " working_calendars="+(after==Plan.WORKING_CALENDARS_APPLIED?"UNPUBLISHED_DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED"));
            }
        }catch(Throwable failure){errors.println(safeFailure(stage,failure));exit=1;}
        finally{System.setOut(output);System.setErr(errors);}
        if(exit!=0)System.exit(exit);
    }
    static void verifyImportResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("6"))){
            if(input==null||!IMPORT_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed asset import migration resource");
        }
    }
    private static void verifyStructure(Connection connection,Plan plan)throws SQLException{
        if(plan==Plan.WORKING_CALENDARS_APPLIED)verifyWorkingCalendars(connection);else {requireTrue(connection,"SELECT to_regclass('public.mop_working_calendar_draft') IS NULL AND to_regclass('public.mop_working_calendar_revision') IS NULL");requireTrue(connection,"SELECT to_regprocedure('public.mop_calendar_windows_valid(jsonb,timestamp with time zone,timestamp with time zone)') IS NULL");}
        if((plan==Plan.TICKET_COMMENTS_APPLIED||plan==Plan.WORKING_CALENDARS_APPLIED))verifyTicketComments(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_comment') IS NULL");
        if((plan==Plan.INTERNAL_NOTES_APPLIED||(plan==Plan.TICKET_COMMENTS_APPLIED||plan==Plan.WORKING_CALENDARS_APPLIED)))verifyInternalNotes(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_internal_note') IS NULL");
        if(plan==Plan.PHOTOS_PENDING)verifyBeforePhotos(connection);else verifyPhotos(connection);
        if(plan==Plan.ASSET_IMPORTS_APPLIED||plan==Plan.ASSET_DEPENDENCIES_APPLIED||plan==Plan.WORKLOGS_APPLIED||plan==Plan.ASSET_METADATA_APPLIED||(plan==Plan.INTERNAL_NOTES_APPLIED||(plan==Plan.TICKET_COMMENTS_APPLIED||plan==Plan.WORKING_CALENDARS_APPLIED)))verifyAssetImports(connection);else verifyBeforeAssetImports(connection);
        if(plan==Plan.ASSET_DEPENDENCIES_APPLIED||plan==Plan.WORKLOGS_APPLIED||plan==Plan.ASSET_METADATA_APPLIED||(plan==Plan.INTERNAL_NOTES_APPLIED||(plan==Plan.TICKET_COMMENTS_APPLIED||plan==Plan.WORKING_CALENDARS_APPLIED)))verifyAssetDependencies(connection);else requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_asset_dependency_graph','mop_asset_dependency_revision','mop_asset_dependency_edge')");
        if(plan==Plan.WORKLOGS_APPLIED||plan==Plan.ASSET_METADATA_APPLIED||(plan==Plan.INTERNAL_NOTES_APPLIED||(plan==Plan.TICKET_COMMENTS_APPLIED||plan==Plan.WORKING_CALENDARS_APPLIED)))verifyWorklogs(connection);else requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_worklog','mop_worklog_revision')");
        if(plan==Plan.ASSET_METADATA_APPLIED||(plan==Plan.INTERNAL_NOTES_APPLIED||(plan==Plan.TICKET_COMMENTS_APPLIED||plan==Plan.WORKING_CALENDARS_APPLIED)))verifyAssetMetadata(connection);
        else {requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_asset_manual_details','mop_asset_manual_revision')");verifyRuntimeAcl(connection,"mop_asset",Set.of());}
    }
    static void verifyDependencyResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("7"))){
            if(input==null||!DEPENDENCY_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed asset dependency migration resource");
        }
    }
    private static void verifyAssetDependencies(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_asset_dependency_graph",Map.of("tenant_id","uuid","version","bigint","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(connection,"mop_asset_dependency_revision",Map.of("tenant_id","uuid","version","bigint","created_by","uuid","created_at","timestamp(3) with time zone","lifecycle_status","character varying(16)","decision_status","character varying(16)","monitoring_applied","boolean"),Set.of(),true);
        verifyColumns(connection,"mop_asset_dependency_edge",Map.of("tenant_id","uuid","revision_version","bigint","parent_id","uuid","child_id","uuid","proposed_dependency_type","character varying(64)","proposed_suppression_scope","character varying(128)","proposed_enabled","boolean"),Set.of("proposed_dependency_type","proposed_suppression_scope","proposed_enabled"),true);
        for(String table:List.of("mop_asset_dependency_graph","mop_asset_dependency_revision","mop_asset_dependency_edge")){
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
            verifyRuntimeAcl(connection,table,table.equals("mop_asset_dependency_graph")?Set.of("version","updated_at"):Set.of());
            requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=?::regclass AND NOT convalidated)","public."+table);
        }
        requireConstraint(connection,"mop_asset_dependency_graph","p",List.of("tenant_id"),null,List.of(),false);
        requireConstraint(connection,"mop_asset_dependency_graph","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_asset_dependency_graph","f",List.of("tenant_id","version"),"mop_asset_dependency_revision",List.of("tenant_id","version"),true);
        requireConstraint(connection,"mop_asset_dependency_revision","p",List.of("tenant_id","version"),null,List.of(),false);
        requireConstraint(connection,"mop_asset_dependency_revision","f",List.of("tenant_id"),"mop_asset_dependency_graph",List.of("tenant_id"),false);
        requireConstraint(connection,"mop_asset_dependency_revision","f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,"mop_asset_dependency_edge","p",List.of("tenant_id","revision_version","parent_id","child_id"),null,List.of(),false);
        requireConstraint(connection,"mop_asset_dependency_edge","f",List.of("tenant_id","revision_version"),"mop_asset_dependency_revision",List.of("tenant_id","version"),false);
        for(String column:List.of("parent_id","child_id"))requireConstraint(connection,"mop_asset_dependency_edge","f",List.of("tenant_id",column),"mop_asset",List.of("tenant_id","id"),false);
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_asset_dependency_edge_child' AND c.relnamespace='public'::regnamespace");
    }
    static void verifyWorklogResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("8"))){if(input==null||!WORKLOG_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed worklog migration resource");}
    }
    private static void verifyWorklogs(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_worklog",Map.of("id","uuid","tenant_id","uuid","ticket_id","uuid","owner_id","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(connection,"mop_worklog_revision",Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("worklog_id","uuid"),Map.entry("version","bigint"),Map.entry("actor_id","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("action","character varying(16)"),Map.entry("kind","character varying(16)"),Map.entry("started_at","timestamp(3) with time zone"),Map.entry("ended_at","timestamp(3) with time zone"),Map.entry("note","character varying(2000)"),Map.entry("record_source","character varying(32)")),Set.of("note"),true);
        for(String table:List.of("mop_worklog","mop_worklog_revision")){
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
            verifyRuntimeAcl(connection,table,table.equals("mop_worklog")?Set.of("version","updated_at"):Set.of());
            requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=?::regclass AND NOT convalidated)","public."+table);
        }
        requireConstraint(connection,"mop_worklog","p",List.of("id"),null,List.of(),false);
        requireConstraint(connection,"mop_worklog","u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_worklog","u",List.of("tenant_id","id","owner_id"),null,List.of(),false);
        requireConstraint(connection,"mop_worklog","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_worklog","f",List.of("tenant_id","ticket_id"),"mop_ticket",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_worklog","f",List.of("tenant_id","owner_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,"mop_worklog","f",List.of("tenant_id","id","version"),"mop_worklog_revision",List.of("tenant_id","worklog_id","version"),true);
        requireConstraint(connection,"mop_worklog_revision","p",List.of("tenant_id","worklog_id","version"),null,List.of(),false);
        requireConstraint(connection,"mop_worklog_revision","f",List.of("tenant_id","worklog_id","actor_id"),"mop_worklog",List.of("tenant_id","id","owner_id"),false);
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_worklog_ticket_owner_order' AND c.relnamespace='public'::regnamespace");
    }
    static void verifyAssetMetadataResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("9"))){if(input==null||!ASSET_METADATA_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed manual asset metadata migration resource");}
    }
    private static void verifyAssetMetadata(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_asset_manual_details",Map.of("tenant_id","uuid","asset_id","uuid","revision_version","bigint"),Set.of(),true);
        verifyColumns(connection,"mop_asset_manual_revision",Map.ofEntries(
            Map.entry("tenant_id","uuid"),Map.entry("asset_id","uuid"),Map.entry("asset_version","bigint"),Map.entry("previous_metadata_version","bigint"),Map.entry("actor_id","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),
            Map.entry("name","character varying(100)"),Map.entry("type","character varying(32)"),Map.entry("management_ip","character varying(45)"),Map.entry("site_id","character varying(64)"),Map.entry("brand","character varying(100)"),Map.entry("model","character varying(100)"),Map.entry("serial_number","character varying(128)"),Map.entry("location","character varying(200)"),Map.entry("owner_id","uuid"),Map.entry("business_description","character varying(200)"),Map.entry("purchase_date","date"),Map.entry("warranty_expiry","date"),Map.entry("overdue_service_date","date"),Map.entry("collection_method_note","character varying(100)"),Map.entry("note","character varying(2000)"),Map.entry("before_snapshot","jsonb"),Map.entry("verification_status","character varying(24)"),Map.entry("monitoring_applied","boolean"),Map.entry("cmdb_applied","boolean")),
            Set.of("previous_metadata_version","brand","model","serial_number","location","owner_id","business_description","purchase_date","warranty_expiry","overdue_service_date","collection_method_note"),true);
        for(String table:List.of("mop_asset_manual_details","mop_asset_manual_revision")){
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
            verifyRuntimeAcl(connection,table,table.equals("mop_asset_manual_details")?Set.of("revision_version"):Set.of());
            requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=?::regclass AND NOT convalidated)","public."+table);
            requireConstraint(connection,table,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
        }
        verifyRuntimeAcl(connection,"mop_asset",Set.of("name","type","site_id","version","updated_at","updated_by"));
        requireConstraint(connection,"mop_asset_manual_details","p",List.of("tenant_id","asset_id"),null,List.of(),false);
        requireConstraint(connection,"mop_asset_manual_details","f",List.of("tenant_id","asset_id","revision_version"),"mop_asset_manual_revision",List.of("tenant_id","asset_id","asset_version"),true);
        requireConstraint(connection,"mop_asset_manual_revision","p",List.of("tenant_id","asset_id","asset_version"),null,List.of(),false);
        requireConstraint(connection,"mop_asset_manual_revision","f",List.of("tenant_id","asset_id","previous_metadata_version"),"mop_asset_manual_revision",List.of("tenant_id","asset_id","asset_version"),false);
        for(String field:List.of("actor_id","owner_id"))requireConstraint(connection,"mop_asset_manual_revision","f",List.of("tenant_id",field),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,"mop_asset_manual_revision","f",List.of("tenant_id","site_id"),"mop_site",List.of("tenant_id","id"),false);
        requireTrue(connection,"SELECT count(*)=22 FROM pg_constraint WHERE conrelid='public.mop_asset_manual_revision'::regclass AND contype='c'");
        requireTrue(connection,"SELECT count(*)=1 FROM pg_constraint WHERE conrelid='public.mop_asset_manual_details'::regclass AND contype='c'");
    }
    static void verifyInternalNoteResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("10"))){if(input==null||!INTERNAL_NOTE_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed internal ticket note migration resource");}
    }
    private static void verifyInternalNotes(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_ticket_internal_note",Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("ticket_id","uuid"),Map.entry("actor_id","uuid"),Map.entry("observed_ticket_version","bigint"),Map.entry("note","text"),Map.entry("note_sha256","character(64)"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("visibility","character varying(16)"),Map.entry("record_source","character varying(32)"),Map.entry("verification_status","character varying(24)"),Map.entry("ticket_state_changed","boolean"),Map.entry("sla_changed","boolean")),Set.of(),true);
        verifyRuntimeAcl(connection,"mop_ticket_internal_note",Set.of());
        requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_ticket_internal_note'::regclass");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_ticket_internal_note'::regclass AND NOT convalidated)");
        requireConstraint(connection,"mop_ticket_internal_note","p",List.of("id"),null,List.of(),false);
        requireConstraint(connection,"mop_ticket_internal_note","u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_ticket_internal_note","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_ticket_internal_note","f",List.of("tenant_id","ticket_id"),"mop_ticket",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_ticket_internal_note","f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=11 FROM pg_constraint WHERE conrelid='public.mop_ticket_internal_note'::regclass AND contype='c'");
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_ticket_internal_note_order' AND c.relnamespace='public'::regnamespace");
    }
    static void verifyTicketCommentResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("11"))){if(input==null||!TICKET_COMMENT_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed ticket comment migration resource");}
    }
    private static void verifyTicketComments(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_ticket_comment",Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("ticket_id","uuid"),Map.entry("actor_id","uuid"),Map.entry("observed_ticket_version","bigint"),Map.entry("author_kind","character varying(16)"),Map.entry("comment_text","text"),Map.entry("comment_sha256","character(64)"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("visibility","character varying(24)"),Map.entry("record_source","character varying(32)"),Map.entry("verification_status","character varying(24)"),Map.entry("ticket_state_changed","boolean"),Map.entry("sla_changed","boolean"),Map.entry("itsm_applied","boolean"),Map.entry("itsm_sync_status","character varying(24)")),Set.of(),true);
        verifyRuntimeAcl(connection,"mop_ticket_comment",Set.of());
        requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_ticket_comment'::regclass");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_ticket_comment'::regclass AND NOT convalidated)");
        requireConstraint(connection,"mop_ticket_comment","p",List.of("id"),null,List.of(),false);
        requireConstraint(connection,"mop_ticket_comment","u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_ticket_comment","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_ticket_comment","f",List.of("tenant_id","ticket_id"),"mop_ticket",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_ticket_comment","f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=14 FROM pg_constraint WHERE conrelid='public.mop_ticket_comment'::regclass AND contype='c'");
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_ticket_comment_order' AND c.relnamespace='public'::regnamespace");
    }
    static void verifyWorkingCalendarResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("12"))){
            if(input==null)throw new IllegalStateException("Missing working calendar migration resource");
            byte[] bytes=input.readAllBytes();
            if(!WORKING_CALENDAR_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed working calendar migration resource");
            String sql=new String(bytes,StandardCharsets.UTF_8);int marker=sql.indexOf(" AS $$");int end=marker<0?-1:sql.indexOf("$$;",marker+6);
            if(marker<0||end<0||!WORKING_CALENDAR_CHECKER_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed working calendar checker body");
        }
    }
    private static void verifyWorkingCalendars(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_working_calendar_draft",Map.of("id","uuid","tenant_id","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(connection,"mop_working_calendar_revision",Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("calendar_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("actor_id","uuid"),Map.entry("name","character varying(100)"),Map.entry("coverage_start","timestamp(3) with time zone"),Map.entry("coverage_end","timestamp(3) with time zone"),Map.entry("working_windows","jsonb"),Map.entry("coverage_complete","boolean"),Map.entry("note","text"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("timezone","character varying(64)"),Map.entry("lifecycle_status","character varying(16)"),Map.entry("customer_confirmation","character varying(24)"),Map.entry("effective","boolean"),Map.entry("record_source","character varying(32)")),Set.of("previous_version","note"),true);
        verifyRuntimeAcl(connection,"mop_working_calendar_draft",Set.of("version","updated_at"));verifyRuntimeAcl(connection,"mop_working_calendar_revision",Set.of());
        for(String table:List.of("mop_working_calendar_draft","mop_working_calendar_revision")){
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
            requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=?::regclass AND NOT convalidated)","public."+table);
        }
        requireConstraint(connection,"mop_working_calendar_draft","p",List.of("id"),null,List.of(),false);
        requireConstraint(connection,"mop_working_calendar_draft","u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_working_calendar_draft","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_working_calendar_draft","f",List.of("tenant_id","id","version"),"mop_working_calendar_revision",List.of("tenant_id","calendar_id","version"),true);
        requireConstraint(connection,"mop_working_calendar_revision","p",List.of("tenant_id","calendar_id","version"),null,List.of(),false);
        requireConstraint(connection,"mop_working_calendar_revision","f",List.of("tenant_id","calendar_id"),"mop_working_calendar_draft",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_working_calendar_revision","f",List.of("tenant_id","calendar_id","previous_version"),"mop_working_calendar_revision",List.of("tenant_id","calendar_id","version"),false);
        requireConstraint(connection,"mop_working_calendar_revision","f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=4 FROM pg_constraint WHERE conrelid='public.mop_working_calendar_draft'::regclass AND contype='c'");
        requireTrue(connection,"SELECT count(*)=14 FROM pg_constraint WHERE conrelid='public.mop_working_calendar_revision'::regclass AND contype='c'");
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_working_calendar_draft_order' AND c.relnamespace='public'::regnamespace");
        requireTrue(connection,"SELECT NOT p.prosecdef AND p.provolatile='i' AND p.proparallel='s' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND p.proconfig @> ARRAY['search_path=pg_catalog']::text[] AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid='public.mop_calendar_windows_valid(jsonb,timestamp with time zone,timestamp with time zone)'::regprocedure",WORKING_CALENDAR_CHECKER_SHA256);
    }
    private static void verifyBeforeAssetImports(Connection connection)throws SQLException{
        requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_asset_import_source','mop_asset_import_operation','mop_asset_import_job','mop_asset_import_artifact','mop_asset_import_mapping')");
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
    private static void verifyAssetImports(Connection connection)throws SQLException {
        verifyColumns(connection,"mop_asset_import_source",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("created_by","uuid"),Map.entry("purpose","character varying(64)"),Map.entry("original_name","character varying(200)"),Map.entry("declared_format","character varying(8)"),
            Map.entry("size_bytes","integer"),Map.entry("sha256","character(64)"),Map.entry("object_key","character varying(160)"),Map.entry("permission_version","bigint"),Map.entry("resource_policy_version","character varying(64)"),
            Map.entry("store_kind","character varying(16)"),Map.entry("parse_kind","character varying(16)"),Map.entry("store_operation_id","uuid"),Map.entry("parse_operation_id","uuid"),Map.entry("storage_status","character varying(16)"),Map.entry("parse_status","character varying(24)"),
            Map.entry("latest_artifact_id","uuid"),Map.entry("latest_mapping_id","uuid"),Map.entry("version","bigint"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("updated_at","timestamp(3) with time zone")),Set.of("parse_operation_id","latest_artifact_id","latest_mapping_id"),true);
        verifyColumns(connection,"mop_asset_import_operation",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("source_id","uuid"),Map.entry("created_by","uuid"),Map.entry("permission_version","bigint"),Map.entry("kind","character varying(16)"),Map.entry("status","character varying(16)"),
            Map.entry("step","character varying(32)"),Map.entry("result_artifact_id","uuid"),Map.entry("error","jsonb"),Map.entry("created_at","timestamp(3) with time zone"),Map.entry("updated_at","timestamp(3) with time zone")),Set.of("result_artifact_id","error"),true);
        verifyColumns(connection,"mop_asset_import_job",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("source_id","uuid"),Map.entry("operation_id","uuid"),Map.entry("kind","character varying(16)"),Map.entry("content","bytea"),Map.entry("selection_json","jsonb"),
            Map.entry("content_size","integer"),Map.entry("content_sha256","character(64)"),Map.entry("store_operation_id","uuid"),Map.entry("state","character varying(16)"),Map.entry("lease_owner","character varying(64)"),Map.entry("lease_until","timestamp(3) with time zone"),
            Map.entry("fencing_token","bigint"),Map.entry("attempts","integer"),Map.entry("next_at","timestamp(3) with time zone"),Map.entry("created_at","timestamp(3) with time zone")),Set.of("content","selection_json","content_size","content_sha256","store_operation_id","lease_owner","lease_until","next_at"),true);
        verifyColumns(connection,"mop_asset_import_artifact",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("source_id","uuid"),Map.entry("operation_id","uuid"),Map.entry("kind","character varying(16)"),Map.entry("parse_hash","character(64)"),Map.entry("source_sha256","character(64)"),
            Map.entry("selection_json","jsonb"),Map.entry("artifact_json","jsonb"),Map.entry("schema_version","character varying(64)"),Map.entry("created_at","timestamp(3) with time zone")),Set.of(),true);
        verifyColumns(connection,"mop_asset_import_mapping",Map.ofEntries(
            Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("source_id","uuid"),Map.entry("artifact_id","uuid"),Map.entry("created_by","uuid"),Map.entry("permission_version","bigint"),Map.entry("version","bigint"),Map.entry("source_parse_hash","character(64)"),
            Map.entry("preview_hash","character(64)"),Map.entry("schema_version","character varying(64)"),Map.entry("target_source_columns","jsonb"),Map.entry("ignored_source_columns","jsonb"),Map.entry("mapped_raw_rows","jsonb"),Map.entry("created_at","timestamp(3) with time zone")),Set.of(),true);
        List<String> tables=List.of("mop_asset_import_source","mop_asset_import_operation","mop_asset_import_job","mop_asset_import_artifact","mop_asset_import_mapping");
        for(String table:tables){
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=?::regclass","public."+table);
            requireConstraint(connection,table,"p",List.of("id"),null,List.of(),false);
        }
        String source="mop_asset_import_source",operation="mop_asset_import_operation",job="mop_asset_import_job",artifact="mop_asset_import_artifact",mapping="mop_asset_import_mapping";
        for(List<String> columns:List.of(List.of("object_key"),List.of("store_operation_id"),List.of("tenant_id","id"),List.of("tenant_id","id","created_by"),List.of("tenant_id","id","sha256"),List.of("tenant_id","id","size_bytes","sha256"),List.of("tenant_id","id","store_operation_id")))requireConstraint(connection,source,"u",columns,null,List.of(),false);
        for(List<String> columns:List.of(List.of("tenant_id","source_id","kind","id"),List.of("tenant_id","source_id","created_by","kind","id"),List.of("tenant_id","source_id","created_by","permission_version","kind","id")))requireConstraint(connection,operation,"u",columns,null,List.of(),false);
        requireConstraint(connection,job,"u",List.of("operation_id"),null,List.of(),false);
        for(List<String> columns:List.of(List.of("operation_id"),List.of("tenant_id","source_id","id"),List.of("tenant_id","source_id","operation_id","id"),List.of("tenant_id","source_id","id","parse_hash")))requireConstraint(connection,artifact,"u",columns,null,List.of(),false);
        for(List<String> columns:List.of(List.of("tenant_id","source_id","version"),List.of("tenant_id","source_id","id"),List.of("tenant_id","source_id","artifact_id","id")))requireConstraint(connection,mapping,"u",columns,null,List.of(),false);
        requireConstraint(connection,source,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,source,"f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,operation,"f",List.of("tenant_id","source_id","created_by"),source,List.of("tenant_id","id","created_by"),false);
        requireConstraint(connection,source,"f",List.of("tenant_id","id","created_by","permission_version","store_kind","store_operation_id"),operation,List.of("tenant_id","source_id","created_by","permission_version","kind","id"),true);
        requireConstraint(connection,source,"f",List.of("tenant_id","id","created_by","parse_kind","parse_operation_id"),operation,List.of("tenant_id","source_id","created_by","kind","id"),false);
        for(String table:List.of(job,artifact))requireConstraint(connection,table,"f",List.of("tenant_id","source_id","kind","operation_id"),operation,List.of("tenant_id","source_id","kind","id"),false);
        requireConstraint(connection,job,"f",List.of("tenant_id","source_id","content_size","content_sha256"),source,List.of("tenant_id","id","size_bytes","sha256"),false);
        requireConstraint(connection,job,"f",List.of("tenant_id","source_id","store_operation_id"),source,List.of("tenant_id","id","store_operation_id"),false);
        requireConstraint(connection,artifact,"f",List.of("tenant_id","source_id","source_sha256"),source,List.of("tenant_id","id","sha256"),false);
        requireConstraint(connection,operation,"f",List.of("tenant_id","source_id","id","result_artifact_id"),artifact,List.of("tenant_id","source_id","operation_id","id"),false);
        requireConstraint(connection,source,"f",List.of("tenant_id","id","parse_operation_id","latest_artifact_id"),artifact,List.of("tenant_id","source_id","operation_id","id"),false);
        requireConstraint(connection,mapping,"f",List.of("tenant_id","source_id","created_by"),source,List.of("tenant_id","id","created_by"),false);
        requireConstraint(connection,mapping,"f",List.of("tenant_id","source_id","artifact_id","source_parse_hash"),artifact,List.of("tenant_id","source_id","id","parse_hash"),false);
        requireConstraint(connection,source,"f",List.of("tenant_id","id","latest_artifact_id","latest_mapping_id"),mapping,List.of("tenant_id","source_id","artifact_id","id"),false);
        requireTrue(connection,"SELECT count(*)=3 AND bool_and(attgenerated='s') FROM pg_attribute WHERE attrelid='public.mop_asset_import_job'::regclass AND attname IN ('content_size','content_sha256','store_operation_id') AND NOT attisdropped");
        requireTrue(connection,"SELECT replace(regexp_replace(pg_get_expr(d.adbin,d.adrelid),'[[:space:]]','','g'),'pg_catalog.','')='octet_length(content)' FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum WHERE d.adrelid='public.mop_asset_import_job'::regclass AND a.attname='content_size'");
        requireTrue(connection,"SELECT position('encode(sha256(content),''hex''::text)' IN replace(regexp_replace(pg_get_expr(d.adbin,d.adrelid),'[[:space:]]','','g'),'pg_catalog.',''))>0 FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum WHERE d.adrelid='public.mop_asset_import_job'::regclass AND a.attname='content_sha256'");
        requireTrue(connection,"SELECT pg_get_expr(d.adbin,d.adrelid) ~ 'CASE[[:space:]]+WHEN.*kind.*STORE_RAW.*THEN[[:space:]]+operation_id.*ELSE[[:space:]]+NULL::uuid.*END' FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum WHERE d.adrelid='public.mop_asset_import_job'::regclass AND a.attname='store_operation_id'");
        for(var expected:Map.of(source,15,operation,5,job,6,artifact,6,mapping,8).entrySet())requireTrue(connection,"SELECT count(*)="+expected.getValue()+" AND bool_and(convalidated) FROM pg_constraint WHERE conrelid=?::regclass AND contype='c'","public."+expected.getKey());
        requireTrue(connection,"SELECT count(*)=4 AND bool_and(i.indisvalid AND i.indisready) FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace n ON n.oid=idx.relnamespace WHERE n.nspname='public' AND idx.relname IN ('mop_asset_import_source_owner','mop_asset_import_operation_owner','mop_asset_import_job_pending','mop_asset_import_job_expired')");
        Map<String,Set<String>> updates=Map.of(source,Set.of("storage_status","parse_status","parse_operation_id","latest_artifact_id","latest_mapping_id","version","updated_at"),operation,Set.of("status","step","result_artifact_id","error","updated_at"),job,Set.of("state","lease_owner","lease_until","fencing_token","attempts","next_at"),artifact,Set.of(),mapping,Set.of());
        for(var entry:updates.entrySet())verifyRuntimeAcl(connection,entry.getKey(),entry.getValue());
        requireTrue(connection,"SELECT NOT has_schema_privilege('mop_test_runtime','public','CREATE') AND NOT has_database_privilege('mop_test_runtime',current_database(),'CREATE') AND NOT pg_has_role('mop_test_runtime','mop_test_migrator','MEMBER')");
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
        requireTrue(connection,"SELECT has_table_privilege(?,?,'SELECT') AND has_table_privilege(?,?,'INSERT') AND NOT has_table_privilege(?,?,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')",RUNTIME,"public."+table,RUNTIME,"public."+table,RUNTIME,"public."+table);
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
            (reference==null?"":" AND c.confmatchtype='s' AND c.confdeltype='a' AND c.confupdtype='a' AND c.confrelid=?::regclass AND ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY k(num,position) JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.position)=?::text[]")+")";
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
