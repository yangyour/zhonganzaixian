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
        Map.entry("12","V12__unpublished_working_calendar_drafts.sql"),
        Map.entry("13","V13__immutable_manual_incident_registrations.sql"),
        Map.entry("14","V14__immutable_manual_recovery_confirmations.sql"),
        Map.entry("15","V15__manual_warranty_details.sql"),
        Map.entry("16","V16__immutable_capacity_scenario_drafts.sql"),
        Map.entry("17","V17__customer_contact_revisions.sql"),Map.entry("18","V18__durable_contact_import_jobs.sql"),Map.entry("19","V19__contact_import_generated_hash_guard.sql"),Map.entry("20","V20__durable_asset_export_jobs.sql"),Map.entry("21","V21__inspection_template_revisions.sql"),Map.entry("22","V22__manual_inspection_worksheet_drafts.sql"),Map.entry("23","V23__immutable_inspection_source_collections.sql"),Map.entry("24","V24__unpublished_backup_plan_drafts.sql"),Map.entry("25","V25__unreviewed_change_notice_drafts.sql"),Map.entry("26","V26__own_notification_preference_drafts.sql"),Map.entry("27","V27__project_identity_and_roles.sql"),Map.entry("28","V28__identity_inventory_and_password_formats.sql"),Map.entry("29","V29__immutable_raw_monitoring_events.sql"),Map.entry("30","V30__immutable_monitoring_quarantine.sql"),Map.entry("31","V31__own_export_inbox.sql"),Map.entry("32","V32__runtime_configuration_publications.sql"),Map.entry("33","V33__ticket_lifecycle_and_durable_clocks.sql"),Map.entry("34","V34__ticket_notification_intents.sql"),Map.entry("35","V35__service_tiers_and_change_history.sql"),Map.entry("36","V36__oncall_escalation_and_sla_monthly_results.sql"),Map.entry("37","V37__monitoring_events_noise_funnel_and_maintenance_windows.sql"),Map.entry("38","V38__wecom_bindings_deliveries_and_login_state.sql"),Map.entry("39","V39__email_bindings_and_delivery_ledger.sql"),Map.entry("40","V40__immutable_change_notice_approval_events.sql"),Map.entry("41","V41__immutable_inspection_quarterly_reports.sql"),Map.entry("42","V42__ticket_lifecycle_revisions.sql"),Map.entry("43","V43__ticket_autoclose_jobs.sql"),Map.entry("44","V44__ticket_sla_policy_bindings.sql"),Map.entry("45","V45__sla_reminder_intents.sql"),Map.entry("46","V46__backup_device_coordination_journal.sql"),Map.entry("47","V47__backup_run_event_journal.sql"),Map.entry("48","V48__durable_backup_archive_staging.sql"));
    static final String IMPORT_SHA256="1f532822668274b7aebd12ea78b57e6be0aa9fd53ceb77023ee8067589cd2b4b";
    static final String DEPENDENCY_SHA256="5a8ce8d49fe948fc0e9586c102f7c8bfc46cb975a0b5d9f32d4bd1ccc72670bb";
    static final String WORKLOG_SHA256="942ea4dc88a8169b40662edcfd89871e8280a429ac922bd0cc1eb451b82ad2f8";
    static final String ASSET_METADATA_SHA256="a311d8ea11516bb60f2375cafd02b293beb904d07c03c890653dd5bb3751412c";
    static final String INTERNAL_NOTE_SHA256="ba1ad02e7df8079936288d8712a84062a5bd695885b1e45daacec9f4d35ad198";
    static final String TICKET_COMMENT_SHA256="d8535bbf13ca9e35c115278f36232a786b4562c99cc9afd9f5195a416028a8e3";
    static final String WORKING_CALENDAR_SHA256="e6e0412e8a96936a6f4e09dc9f96431cd6551dd9c15ebaf7d2ed414467aedb1a";
    static final String MANUAL_INCIDENT_SHA256="80734d0a0dcd436a3008ed76af0b7da3e81956f516a25d778c0717df79665700";
    static final String MANUAL_RECOVERY_SHA256="39be55d8759de87c6e2b8f85b3958c60be24ae9e6ece2deb5d849c225050ed72";
    static final String MANUAL_WARRANTY_SHA256="317b865df601888f2d6ea5324415f469acdeaea4c2b49b6c837fef9954a30baf";
    static final String WARRANTY_GUARD_SHA256="89451fe60c0fc9774967bbb3b018694bc6a5e4d2c623a14bc947924662312e9e";
    static final String CAPACITY_SCENARIO_SHA256="74f095f56ca8735ef6d97405908aa523b554e76316acca052eeaa3cbbe010d05";
    static final String CUSTOMER_CONTACT_SHA256="3e7d2f89729682f9a51d0ccf4be9738ac25421d94738943d3d5f06461417a10b";
    static final String CONTACT_GUARD_SHA256="b3c16bf2730f173a86d001e835cec861758650d7fed379ad8e1a7873ee587ed7";
    static final String CONTACT_IMPORT_SHA256="75325f0423f788d5a71b71e0b1c8ba3b5c30e628aa3c588437f671954cbb39e9";
    static final String CONTACT_IMPORT_TRANSITION_SHA256="4690490d675a6d16e978631cd6996ba3e1e6552216a01149106d47d18388ec19";
    static final String CONTACT_IMPORT_COMPLETION_SHA256="d16a4a8dd54ef416e25be027e89bfbf1626aec46b6470132f7808a7815a55211";
    static final String CONTACT_IMPORT_FIX_SHA256="b84a80aa548a7c2d7d3050bb983214353cd3e1951f5d3ac1b04b413cedc2cdc7";
    static final String CONTACT_IMPORT_FIXED_TRANSITION_SHA256="199559616f25813c7d45acf02ecdca89cab3bf06d836669f50e78964d671d283";
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
    enum Plan {
        PHOTOS_PENDING(4),ASSET_IMPORTS_PENDING(5),ASSET_IMPORTS_APPLIED(6),ASSET_DEPENDENCIES_APPLIED(7),WORKLOGS_APPLIED(8),ASSET_METADATA_APPLIED(9),INTERNAL_NOTES_APPLIED(10),TICKET_COMMENTS_APPLIED(11),WORKING_CALENDARS_APPLIED(12),MANUAL_INCIDENTS_APPLIED(13),MANUAL_RECOVERIES_APPLIED(14),MANUAL_WARRANTY_APPLIED(15),CAPACITY_SCENARIOS_APPLIED(16),CUSTOMER_CONTACTS_APPLIED(17),CONTACT_IMPORTS_APPLIED(18),CONTACT_IMPORTS_FIXED(19),ASSET_EXPORTS_APPLIED(20),INSPECTION_TEMPLATES_APPLIED(21),INSPECTION_WORKSHEETS_APPLIED(22),INSPECTION_SOURCE_COLLECTIONS_APPLIED(23),BACKUP_PLAN_DRAFTS_APPLIED(24),CHANGE_NOTICE_DRAFTS_APPLIED(25),NOTIFICATION_PREFERENCE_DRAFTS_APPLIED(26),PROJECT_IDENTITY_APPLIED(27),IDENTITY_COMPATIBILITY_APPLIED(28),RAW_MONITORING_EVENTS_APPLIED(29),MONITORING_QUARANTINE_APPLIED(30),EXPORT_INBOX_APPLIED(31),RUNTIME_CONFIGURATION_APPLIED(32),TICKET_WORKFLOW_APPLIED(33),NOTIFICATION_INTENTS_APPLIED(34),SERVICE_TIERS_APPLIED(35),ONCALL_ESCALATION_APPLIED(36),MONITORING_EVENT_CENTER_APPLIED(37),WECOM_INTEGRATION_APPLIED(38),EMAIL_INTEGRATION_APPLIED(39),CHANGE_NOTICE_APPROVAL_APPLIED(40),INSPECTION_QUARTERLY_REPORTS_APPLIED(41),TICKET_LIFECYCLE_APPLIED(42),TICKET_AUTOCLOSE_APPLIED(43),TICKET_SLA_POLICY_APPLIED(44),SLA_REMINDERS_APPLIED(45),BACKUP_DEVICE_COORDINATION_APPLIED(46),BACKUP_RUN_JOURNAL_APPLIED(47),BACKUP_ARCHIVE_STAGING_APPLIED(48);
        final int schemaVersion;Plan(int version){schemaVersion=version;}
        static Plan atVersion(int version){for(Plan value:values())if(value.schemaVersion==version)return value;throw new IllegalStateException("Unsupported validated schema version");}
    }
    enum Mode {
        READ_ONLY_CHECK(0),APPLY_PHOTOS(5),APPLY_ASSET_IMPORTS(6),APPLY_ASSET_DEPENDENCIES(7),APPLY_WORKLOGS(8),APPLY_ASSET_METADATA(9),APPLY_INTERNAL_NOTES(10),APPLY_TICKET_COMMENTS(11),APPLY_WORKING_CALENDARS(12),APPLY_MANUAL_INCIDENTS(13),APPLY_MANUAL_RECOVERIES(14),APPLY_MANUAL_WARRANTY(15),APPLY_CAPACITY_SCENARIOS(16),APPLY_CUSTOMER_CONTACTS(17),APPLY_CONTACT_IMPORTS(18),APPLY_CONTACT_IMPORT_FIX(19),APPLY_ASSET_EXPORTS(20),APPLY_INSPECTION_TEMPLATES(21),APPLY_INSPECTION_WORKSHEETS(22),APPLY_INSPECTION_SOURCE_COLLECTIONS(23),APPLY_BACKUP_PLAN_DRAFTS(24),APPLY_CHANGE_NOTICE_DRAFTS(25),APPLY_NOTIFICATION_PREFERENCE_DRAFTS(26),APPLY_PROJECT_IDENTITY(27),APPLY_IDENTITY_COMPATIBILITY(28),APPLY_RAW_MONITORING_EVENTS(29),APPLY_MONITORING_QUARANTINE(30),APPLY_EXPORT_INBOX(31),APPLY_RUNTIME_CONFIGURATION(32),APPLY_TICKET_WORKFLOW(33),APPLY_NOTIFICATION_INTENTS(34),APPLY_SERVICE_TIERS(35),APPLY_ONCALL_ESCALATION(36),APPLY_MONITORING_EVENT_CENTER(37),APPLY_WECOM_INTEGRATION(38),APPLY_EMAIL_INTEGRATION(39),APPLY_CHANGE_NOTICE_APPROVAL(40),APPLY_INSPECTION_QUARTERLY_REPORTS(41),APPLY_TICKET_LIFECYCLE(42),APPLY_TICKET_AUTOCLOSE(43),APPLY_TICKET_SLA_POLICY(44),APPLY_SLA_REMINDERS(45),APPLY_BACKUP_DEVICE_COORDINATION(46),APPLY_BACKUP_RUN_JOURNAL(47),APPLY_BACKUP_ARCHIVE_STAGING(48);
        final int targetVersion;Mode(int version){targetVersion=version;}
    }

    static boolean applyRequested(String[] args) {
        return requestedMode(args)!=Mode.READ_ONLY_CHECK;
    }
    static Mode requestedMode(String[] args) {
        if(args.length==1&&"--apply-backup-archive-staging".equals(args[0]))return Mode.APPLY_BACKUP_ARCHIVE_STAGING;
        if(args.length==1&&"--apply-backup-run-journal".equals(args[0]))return Mode.APPLY_BACKUP_RUN_JOURNAL;
        if(args.length==1&&"--apply-backup-device-coordination".equals(args[0]))return Mode.APPLY_BACKUP_DEVICE_COORDINATION;
        if(args.length==1&&"--apply-sla-reminders".equals(args[0]))return Mode.APPLY_SLA_REMINDERS;
        if(args.length==1&&"--apply-ticket-sla-policy".equals(args[0]))return Mode.APPLY_TICKET_SLA_POLICY;
        if(args.length==1&&"--apply-ticket-autoclose".equals(args[0]))return Mode.APPLY_TICKET_AUTOCLOSE;
        if(args.length==1&&"--apply-ticket-lifecycle".equals(args[0]))return Mode.APPLY_TICKET_LIFECYCLE;
        if(args.length==1&&"--apply-email-integration".equals(args[0]))return Mode.APPLY_EMAIL_INTEGRATION;
        if(args.length==1&&"--apply-inspection-quarterly-reports".equals(args[0]))return Mode.APPLY_INSPECTION_QUARTERLY_REPORTS;
        if(args.length==1&&"--apply-wecom-integration".equals(args[0]))return Mode.APPLY_WECOM_INTEGRATION;
        if(args.length==1&&"--apply-change-notice-approval".equals(args[0]))return Mode.APPLY_CHANGE_NOTICE_APPROVAL;
        if(args.length==1&&"--apply-monitoring-event-center".equals(args[0]))return Mode.APPLY_MONITORING_EVENT_CENTER;
        if(args.length==1&&"--apply-oncall-escalation".equals(args[0]))return Mode.APPLY_ONCALL_ESCALATION;
        if(args.length==1&&"--apply-service-tiers".equals(args[0]))return Mode.APPLY_SERVICE_TIERS;
        if(args.length==1&&"--apply-notification-intents".equals(args[0]))return Mode.APPLY_NOTIFICATION_INTENTS;
        if(args.length==1&&"--apply-ticket-workflow".equals(args[0]))return Mode.APPLY_TICKET_WORKFLOW;
        if(args.length==1&&"--apply-runtime-configuration".equals(args[0]))return Mode.APPLY_RUNTIME_CONFIGURATION;
        if(args.length==1&&"--apply-export-inbox".equals(args[0]))return Mode.APPLY_EXPORT_INBOX;
        if(args.length==0||(args.length==1&&"--check".equals(args[0])))return Mode.READ_ONLY_CHECK;
        if(args.length==1&&"--apply-monitoring-quarantine".equals(args[0]))return Mode.APPLY_MONITORING_QUARANTINE;
        if(args.length==1&&"--apply-raw-monitoring-events".equals(args[0]))return Mode.APPLY_RAW_MONITORING_EVENTS;
        if(args.length==1&&"--apply-identity-compatibility".equals(args[0]))return Mode.APPLY_IDENTITY_COMPATIBILITY;
        if(args.length==1&&"--apply-project-identity".equals(args[0]))return Mode.APPLY_PROJECT_IDENTITY;
        if(args.length==1&&"--apply-photos".equals(args[0]))return Mode.APPLY_PHOTOS;
        if(args.length==1&&"--apply-asset-imports".equals(args[0]))return Mode.APPLY_ASSET_IMPORTS;
        if(args.length==1&&"--apply-asset-dependencies".equals(args[0]))return Mode.APPLY_ASSET_DEPENDENCIES;
        if(args.length==1&&"--apply-worklogs".equals(args[0]))return Mode.APPLY_WORKLOGS;
        if(args.length==1&&"--apply-asset-metadata".equals(args[0]))return Mode.APPLY_ASSET_METADATA;
        if(args.length==1&&"--apply-internal-notes".equals(args[0]))return Mode.APPLY_INTERNAL_NOTES;
        if(args.length==1&&"--apply-ticket-comments".equals(args[0]))return Mode.APPLY_TICKET_COMMENTS;
        if(args.length==1&&"--apply-working-calendars".equals(args[0]))return Mode.APPLY_WORKING_CALENDARS;
        if(args.length==1&&"--apply-manual-incidents".equals(args[0]))return Mode.APPLY_MANUAL_INCIDENTS;
        if(args.length==1&&"--apply-manual-recoveries".equals(args[0]))return Mode.APPLY_MANUAL_RECOVERIES;
        if(args.length==1&&"--apply-manual-warranty".equals(args[0]))return Mode.APPLY_MANUAL_WARRANTY;
        if(args.length==1&&"--apply-capacity-scenarios".equals(args[0]))return Mode.APPLY_CAPACITY_SCENARIOS;
        if(args.length==1&&"--apply-customer-contacts".equals(args[0]))return Mode.APPLY_CUSTOMER_CONTACTS;
        if(args.length==1&&"--apply-contact-imports".equals(args[0]))return Mode.APPLY_CONTACT_IMPORTS;
        if(args.length==1&&"--apply-contact-import-fix".equals(args[0]))return Mode.APPLY_CONTACT_IMPORT_FIX;
        if(args.length==1&&"--apply-asset-exports".equals(args[0]))return Mode.APPLY_ASSET_EXPORTS;
        if(args.length==1&&"--apply-inspection-templates".equals(args[0]))return Mode.APPLY_INSPECTION_TEMPLATES;
        if(args.length==1&&"--apply-inspection-worksheets".equals(args[0]))return Mode.APPLY_INSPECTION_WORKSHEETS;
        if(args.length==1&&"--apply-inspection-source-collections".equals(args[0]))return Mode.APPLY_INSPECTION_SOURCE_COLLECTIONS;
        if(args.length==1&&"--apply-notification-preference-drafts".equals(args[0]))return Mode.APPLY_NOTIFICATION_PREFERENCE_DRAFTS;
        if(args.length==1&&"--apply-change-notice-drafts".equals(args[0]))return Mode.APPLY_CHANGE_NOTICE_DRAFTS;
        if(args.length==1&&"--apply-backup-plan-drafts".equals(args[0]))return Mode.APPLY_BACKUP_PLAN_DRAFTS;
        throw new IllegalArgumentException("Use --check or one explicit supported feature apply mode");
    }
    static Plan inspect(List<Entry> entries) {
        if(entries.size()!=SCRIPTS.size())throw new IllegalStateException("Unexpected migration set");
        Map<Integer,Entry> byVersion=new HashMap<>();
        for(Entry entry:entries){
            if(entry.version()==null||!Objects.equals(SCRIPTS.get(entry.version()),entry.script())||!"SQL".equals(entry.type())||entry.checksum()==null)throw new IllegalStateException("Unexpected migration identity");
            int version=Integer.parseInt(entry.version());if(byVersion.put(version,entry)!=null)throw new IllegalStateException("Duplicate migration identity");
        }
        boolean pending=false;int appliedThrough=0;
        for(int version=1;version<=SCRIPTS.size();version++){
            Entry entry=byVersion.get(version);if(entry==null)throw new IllegalStateException("Missing migration identity");
            boolean unapplied=version>=5&&!entry.applied()&&(entry.state()==MigrationState.PENDING||version>=6&&entry.state()==MigrationState.ABOVE_TARGET);
            if(unapplied){pending=true;continue;}
            if(pending||entry.state()!=MigrationState.SUCCESS||!entry.applied()||!entry.checksumMatches())throw new IllegalStateException("Inconsistent or noncontiguous migration history");
            appliedThrough=version;
        }
        return Plan.atVersion(appliedThrough);
    }
    static boolean shouldMigrate(Mode mode,Plan plan){
        if(mode==Mode.READ_ONLY_CHECK)return false;
        if(plan.schemaVersion<mode.targetVersion-1)throw new IllegalStateException("Explicit feature apply requires complete prior history");
        return plan.schemaVersion==mode.targetVersion-1;
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
            stage="migration_resources";verifyImportResource();verifyDependencyResource();verifyWorklogResource();verifyAssetMetadataResource();verifyInternalNoteResource();verifyTicketCommentResource();verifyWorkingCalendarResource();verifyManualIncidentResource();verifyManualRecoveryResource();verifyManualWarrantyResource();verifyCapacityScenarioResource();verifyCustomerContactResource();verifyContactImportResource();verifyContactImportFixResource();AssetExportSchemaVerifier.resource();InspectionTemplateSchemaVerifier.resource();InspectionWorksheetSchemaVerifier.resource();InspectionSourceCollectionSchemaVerifier.resource();BackupPlanSchemaVerifier.resource();ChangeNoticeSchemaVerifier.resource();NotificationPreferenceSchemaVerifier.resource();ProjectIdentitySchemaVerifier.resource();ProjectIdentitySchemaVerifier.compatibilityResource();RawMonitoringEventSchemaVerifier.resource();MonitoringQuarantineSchemaVerifier.resource();ExportInboxSchemaVerifier.resource();RuntimeConfigurationSchemaVerifier.resource();TicketWorkflowSchemaVerifier.resource();NotificationIntentSchemaVerifier.resource();ServiceTierSchemaVerifier.resource();OnCallEscalationSchemaVerifier.resource();MonitoringEventCenterSchemaVerifier.resource();WeComSchemaVerifier.resource();EmailSchemaVerifier.resource();ChangeNoticeApprovalSchemaVerifier.resource();InspectionQuarterlyReportSchemaVerifier.resource();TicketLifecycleSchemaVerifier.resource();TicketAutoCloseSchemaVerifier.resource();TicketSlaPolicyBindingSchemaVerifier.resource();SlaReminderSchemaVerifier.resource();BackupDeviceLeaseSchemaVerifier.resource();BackupRunSchemaVerifier.resource();BackupArchiveSchemaVerifier.resource();stage="private_settings";Settings settings=Settings.from(System::getenv);
            stage="identity";
            try(Connection guard=connection(settings,!apply)) {
                verifyIdentity(guard);
                if(apply)requireTrue(guard,"SELECT pg_try_advisory_lock(hashtextextended('mop_test|explicit_schema_migration',0))");
                stage="validate";
                Flyway flyway=Flyway.configure().dataSource(settings.url(),settings.user(),settings.password())
                    .jdbcProperties(jdbcProperties(!apply)).locations("classpath:db/migration").schemas("public").defaultSchema("public")
                    .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false).mixed(false)
                    .executeInTransaction(true).group(true).target(Integer.toString(mode==Mode.READ_ONLY_CHECK?SCRIPTS.size():mode.targetVersion)).validateOnMigrate(true).validateMigrationNaming(true)
                    .failOnMissingLocations(true).skipDefaultCallbacks(true).ignoreMigrationPatterns("*:pending").load();
                flyway.validate();stage="history";Plan before=inspect(flyway);boolean migrate=shouldMigrate(mode,before);
                stage="structure";
                verifyStructure(guard,before);
                int executed=0;
                Plan after=before;
                if(migrate) {
                    stage="apply_"+mode.name().substring("APPLY_".length()).toLowerCase(Locale.ROOT);var result=flyway.migrate();executed=result.migrationsExecuted;
                    if(!result.success||executed!=1)throw new IllegalStateException("Unexpected migration result");
                    stage="post_validate";flyway.validate();
                    after=inspect(flyway);Plan expected=Plan.atVersion(mode.targetVersion);
                    if(after!=expected)throw new IllegalStateException("Explicit migration incomplete");
                    stage="post_structure_acl";verifyStructure(guard,after);
                }
                output.println("MOP_SCHEMA status="+after+" mode="+mode+" migrations_executed="+executed+
                    " baseline=V1_V"+(after.schemaVersion<=5?after.schemaVersion:after.schemaVersion-1)+"_VALIDATED"+
                    " photos="+(after.schemaVersion>=5?"STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " asset_imports="+(after.schemaVersion>=6?"STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " asset_dependencies="+(after.schemaVersion>=7?"DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " worklogs="+(after.schemaVersion>=8?"MANUAL_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " asset_metadata="+(after.schemaVersion>=9?"MANUAL_DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " internal_notes="+(after.schemaVersion>=10?"IMMUTABLE_INTERNAL_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " ticket_comments="+(after.schemaVersion>=11?"IMMUTABLE_REPORTER_COMMENT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " working_calendars="+(after.schemaVersion>=12?"UNPUBLISHED_DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " manual_incidents="+(after.schemaVersion>=13?"IMMUTABLE_MANUAL_REGISTRATION_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " manual_recoveries="+(after.schemaVersion>=14?"IMMUTABLE_HUMAN_CONFIRMATION_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " manual_warranty="+(after.schemaVersion>=15?"ADDITIVE_FIELDS_LEGACY_GUARD_VERIFIED":"NOT_APPLIED")+
                    " capacity_scenarios="+(after.schemaVersion>=16?"IMMUTABLE_UNVERIFIED_DRAFT_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " customer_contacts="+(after.schemaVersion>=17?"SELF_REPORTED_REVISION_GUARD_STRUCTURE_ACL_VERIFIED":"NOT_APPLIED")+
                    " contact_imports="+(after.schemaVersion>=19?"DURABLE_JOB_GENERATED_GUARD_FIXED_STRUCTURE_ACL_VERIFIED":after.schemaVersion>=18?"STRUCTURE_ONLY_GENERATED_GUARD_FIX_REQUIRED":"NOT_APPLIED")+
                    " asset_exports="+(after.schemaVersion>=20?"STRUCTURE_ONLY_NOT_STORE_VERIFIED":"NOT_APPLIED")+
                    " inspection_templates="+(after.schemaVersion>=21?"UNPUBLISHED_STRUCTURE_ONLY":"NOT_APPLIED")+
                    " inspection_worksheets="+(after.schemaVersion>=22?"MANUAL_DRAFT_STRUCTURE_ONLY":"NOT_APPLIED")+
                    " inspection_source_collections="+(after.schemaVersion>=23?"IMMUTABLE_SOURCE_ONLY_NOT_REPORT":"NOT_APPLIED")+
                    " change_notice_drafts="+(after.schemaVersion>=25?"UNREVIEWED_DRAFT_ONLY_NO_APPROVAL":"NOT_APPLIED")+
                    " backup_plan_drafts="+(after.schemaVersion>=24?"UNPUBLISHED_DRAFT_ONLY_NO_EXECUTION":"NOT_APPLIED")+
                    " notification_preferences="+(after.schemaVersion>=26?"OWNER_REQUEST_DRAFT_ONLY_NO_DISPATCH":"NOT_APPLIED")+
                    " raw_monitoring_events="+(after.schemaVersion>=29?"IMMUTABLE_STORAGE_ONLY_UNVERIFIED_SOURCE":"NOT_APPLIED")+
                    " monitoring_quarantine="+(after.schemaVersion>=30?"HELD_UNVERIFIED_NO_DISPATCH":"NOT_APPLIED"));
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
        if(plan.schemaVersion>=42)TicketLifecycleSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_lifecycle_revision') IS NULL AND to_regclass('public.mop_ticket_resolution_snapshot') IS NULL");
        if(plan.schemaVersion>=43)TicketAutoCloseSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_autoclose_job') IS NULL");
        if(plan.schemaVersion>=44)TicketSlaPolicyBindingSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_sla_policy_binding') IS NULL");
        if(plan.schemaVersion>=46)BackupDeviceLeaseSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_backup_device_lease_event') IS NULL");
        if(plan.schemaVersion>=48)BackupArchiveSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_backup_archive_submission') IS NULL AND to_regclass('public.mop_backup_archive_event') IS NULL");
        if(plan.schemaVersion>=47)BackupRunSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_backup_run_event') IS NULL");
        if(plan.schemaVersion>=45)SlaReminderSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_sla_scan_job') IS NULL AND to_regclass('public.mop_ticket_sla_reminder') IS NULL AND to_regclass('public.mop_sla_notification_intent') IS NULL");
        if(plan.schemaVersion>=39)EmailSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_email_binding') IS NULL AND to_regclass('public.mop_email_delivery') IS NULL");
        if(plan.schemaVersion>=38)WeComSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_wecom_binding') IS NULL");
        if(plan.schemaVersion>=32)RuntimeConfigurationSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_runtime_configuration_release') IS NULL");
        if(plan.schemaVersion>=37)MonitoringEventCenterSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_monitoring_event') IS NULL AND to_regclass('public.mop_monitoring_event_member') IS NULL AND to_regclass('public.mop_event_noise_record') IS NULL AND to_regclass('public.mop_maintenance_window') IS NULL AND to_regclass('public.mop_monitoring_quarantine_decision') IS NULL AND to_regclass('public.mop_autoticket_attempt') IS NULL");
        if(plan.schemaVersion>=40)ChangeNoticeApprovalSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_change_notice_approval_event') IS NULL");
        if(plan.schemaVersion>=41)InspectionQuarterlyReportSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_inspection_quarterly_report') IS NULL AND to_regprocedure('public.mop_inspection_quarterly_report_guard()') IS NULL");
        if(plan.schemaVersion>=36)OnCallEscalationSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_oncall_roster') IS NULL AND to_regclass('public.mop_oncall_handoff') IS NULL AND to_regclass('public.mop_escalation_policy') IS NULL AND to_regclass('public.mop_escalation_step_run') IS NULL AND to_regclass('public.mop_sla_monthly_result') IS NULL");
        if(plan.schemaVersion>=35)ServiceTierSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_service_tier') IS NULL AND to_regclass('public.mop_service_tier_change') IS NULL");
        if(plan.schemaVersion>=34)NotificationIntentSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_notification_intent') IS NULL AND to_regclass('public.mop_notification_message') IS NULL");
        if(plan.schemaVersion>=33)TicketWorkflowSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_workflow_event') IS NULL");
        if(plan.schemaVersion>=31)ExportInboxSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_export_inbox') IS NULL AND to_regprocedure('public.mop_export_inbox_guard()') IS NULL");
        if(plan.schemaVersion>=30)MonitoringQuarantineSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_monitoring_quarantine') IS NULL AND to_regprocedure('public.mop_quarantine_fingerprint(uuid,text,text,uuid,uuid,text,bigint,integer,bytea)') IS NULL");
        if(plan.schemaVersion>=29)RawMonitoringEventSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_raw_monitoring_event') IS NULL AND to_regprocedure('public.mop_raw_event_fingerprint(uuid,text,text,uuid,uuid,bigint,integer,bytea)') IS NULL");
        if(plan.schemaVersion>=27)ProjectIdentitySchemaVerifier.verify(connection,plan.schemaVersion>=28,plan.schemaVersion>=41);else requireTrue(connection,"SELECT to_regclass('public.mop_local_account') IS NULL AND to_regclass('public.mop_project_role') IS NULL");
        if(plan.schemaVersion>=24)BackupPlanSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_backup_plan_draft') IS NULL AND to_regclass('public.mop_backup_plan_revision') IS NULL AND to_regclass('public.mop_backup_plan_target') IS NULL AND to_regprocedure('public.mop_backup_plan_head_guard()') IS NULL AND to_regprocedure('public.mop_backup_plan_revision_insert_guard()') IS NULL AND to_regprocedure('public.mop_backup_plan_target_insert_guard()') IS NULL AND to_regprocedure('public.mop_backup_plan_revision_complete()') IS NULL");
        if(plan.schemaVersion>=26)NotificationPreferenceSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_notification_preference_draft') IS NULL AND to_regclass('public.mop_notification_preference_revision') IS NULL AND to_regprocedure('public.mop_notification_preference_head_guard()') IS NULL AND to_regprocedure('public.mop_notification_preference_insert_guard()') IS NULL AND to_regprocedure('public.mop_notification_preference_complete()') IS NULL");
        if(plan.schemaVersion>=25)ChangeNoticeSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_change_notice_draft') IS NULL AND to_regclass('public.mop_change_notice_revision') IS NULL AND to_regclass('public.mop_change_notice_asset') IS NULL AND to_regprocedure('public.mop_change_notice_head_guard()') IS NULL AND to_regprocedure('public.mop_change_notice_revision_insert_guard()') IS NULL AND to_regprocedure('public.mop_change_notice_asset_insert_guard()') IS NULL AND to_regprocedure('public.mop_change_notice_revision_complete()') IS NULL");
        if(plan.schemaVersion>=22)InspectionWorksheetSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_inspection_worksheet') IS NULL AND to_regclass('public.mop_inspection_worksheet_revision') IS NULL AND to_regprocedure('public.mop_inspection_worksheet_head_guard()') IS NULL AND to_regprocedure('public.mop_inspection_worksheet_content_guard()') IS NULL");
        if(plan.schemaVersion>=23)InspectionSourceCollectionSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_inspection_source_collection') IS NULL AND to_regclass('public.mop_inspection_source_collection_item') IS NULL AND to_regprocedure('public.mop_inspection_source_document_guard()') IS NULL AND to_regprocedure('public.mop_inspection_source_item_insert_guard()') IS NULL AND to_regprocedure('public.mop_inspection_source_collection_complete()') IS NULL");
        if(plan.schemaVersion>=21)InspectionTemplateSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_inspection_template') IS NULL AND to_regclass('public.mop_inspection_template_revision') IS NULL AND to_regclass('public.mop_inspection_template_item') IS NULL AND to_regprocedure('public.mop_inspection_template_head_guard()') IS NULL AND to_regprocedure('public.mop_inspection_template_revision_guard()') IS NULL");
        if(plan.schemaVersion>=20)AssetExportSchemaVerifier.verify(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_asset_export_job') IS NULL AND to_regclass('public.mop_asset_export_attempt') IS NULL AND to_regprocedure('public.mop_asset_export_transition_guard()') IS NULL AND to_regprocedure('public.mop_asset_export_attempt_guard()') IS NULL AND to_regprocedure('public.mop_asset_export_capture_attempt()') IS NULL");
        if(plan.schemaVersion>=18)ContactImportSchemaVerifier.verify(connection,plan.schemaVersion>=19);else requireTrue(connection,"SELECT to_regclass('public.mop_contact_import_job') IS NULL AND to_regclass('public.mop_contact_import_result') IS NULL AND to_regprocedure('public.mop_contact_import_transition_guard()') IS NULL AND to_regprocedure('public.mop_contact_import_completion_guard()') IS NULL");
        if(plan.schemaVersion>=17)verifyCustomerContacts(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_customer_contact') IS NULL AND to_regclass('public.mop_customer_contact_revision') IS NULL AND to_regclass('public.mop_contact_tenant_guard') IS NULL AND to_regprocedure('public.mop_contact_decision_guard()') IS NULL");
        if(plan.schemaVersion>=16)verifyCapacityScenarios(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_capacity_scenario_draft') IS NULL");
        if(plan.schemaVersion>=15)verifyManualWarranty(connection);else requireTrue(connection,"SELECT to_regprocedure('public.mop_warranty_declaration_guard()') IS NULL AND NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=to_regclass('public.mop_asset_manual_revision') AND attname IN ('warranty_start','warranty_supplier','warranty_details_declared') AND NOT attisdropped)");
        if(plan.schemaVersion>=14)verifyManualRecoveries(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_manual_recovery_confirmation') IS NULL");
        if(plan.schemaVersion>=13)verifyManualIncidents(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_manual_incident_registration') IS NULL");
        if(plan.schemaVersion>=12)verifyWorkingCalendars(connection);else {requireTrue(connection,"SELECT to_regclass('public.mop_working_calendar_draft') IS NULL AND to_regclass('public.mop_working_calendar_revision') IS NULL");requireTrue(connection,"SELECT to_regprocedure('public.mop_calendar_windows_valid(jsonb,timestamp with time zone,timestamp with time zone)') IS NULL");}
        if(plan.schemaVersion>=11)verifyTicketComments(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_comment') IS NULL");
        if(plan.schemaVersion>=10)verifyInternalNotes(connection);else requireTrue(connection,"SELECT to_regclass('public.mop_ticket_internal_note') IS NULL");
        if(plan.schemaVersion>=5)verifyPhotos(connection);else verifyBeforePhotos(connection);
        if(plan.schemaVersion>=6)verifyAssetImports(connection);else verifyBeforeAssetImports(connection);
        if(plan.schemaVersion>=7)verifyAssetDependencies(connection);else requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_asset_dependency_graph','mop_asset_dependency_revision','mop_asset_dependency_edge')");
        if(plan.schemaVersion>=8)verifyWorklogs(connection);else requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_worklog','mop_worklog_revision')");
        if(plan.schemaVersion>=9)verifyAssetMetadata(connection,plan.schemaVersion>=15);
        else {requireTrue(connection,"SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ('mop_asset_manual_details','mop_asset_manual_revision')");verifyRuntimeAcl(connection,"mop_asset",Set.of());}
    }
    static void verifyContactImportResource()throws Exception{ContactImportSchemaVerifier.resource();}
    static void verifyContactImportFixResource()throws Exception{ContactImportSchemaVerifier.fixResource();}
    static void verifyContactImports(Connection connection)throws SQLException{ContactImportSchemaVerifier.verify(connection,true);}
    static void verifyCustomerContactResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("17"))){
            if(input==null)throw new IllegalStateException("Missing contact migration");byte[] bytes=input.readAllBytes();
            if(!CUSTOMER_CONTACT_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed contact migration resource");
            String sql=new String(bytes,StandardCharsets.UTF_8);int marker=sql.indexOf(" AS $$"),end=marker<0?-1:sql.indexOf("$$;",marker+6);
            if(marker<0||end<0||!CONTACT_GUARD_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed contact guard body");
        }
    }
    static void verifyCustomerContacts(Connection connection)throws SQLException{
        verifyColumns(connection,"mop_contact_tenant_guard",Map.of("tenant_id","uuid","version","bigint"),Set.of(),true);
        verifyColumns(connection,"mop_customer_contact",Map.of("id","uuid","tenant_id","uuid","created_by","uuid","version","bigint","created_at","timestamp(3) with time zone","updated_at","timestamp(3) with time zone"),Set.of(),true);
        verifyColumns(connection,"mop_customer_contact_revision",Map.ofEntries(Map.entry("tenant_id","uuid"),Map.entry("contact_id","uuid"),Map.entry("version","bigint"),Map.entry("previous_version","bigint"),Map.entry("recorded_by","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("action","character varying(16)"),Map.entry("name","character varying(200)"),Map.entry("roles","text[]"),Map.entry("mobile","character varying(16)"),Map.entry("email","character varying(254)"),Map.entry("wecom_user_id","character varying(256)"),Map.entry("preferred_priorities","text[]"),Map.entry("quiet_declared","boolean"),Map.entry("quiet_enabled","boolean"),Map.entry("quiet_start","character varying(5)"),Map.entry("quiet_end","character varying(5)"),Map.entry("enabled","boolean"),Map.entry("archived","boolean"),Map.entry("record_source","character varying(40)")),Set.of("previous_version","mobile","email","wecom_user_id","preferred_priorities","quiet_enabled","quiet_start","quiet_end"),true);
        verifyRuntimeAcl(connection,"mop_contact_tenant_guard",Set.of("version"));verifyRuntimeAcl(connection,"mop_customer_contact",Set.of("version","updated_at"));verifyRuntimeAcl(connection,"mop_customer_contact_revision",Set.of());
        for(String table:List.of("mop_contact_tenant_guard","mop_customer_contact","mop_customer_contact_revision")){
            requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid=('public.'||?)::regclass",table);
            requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND NOT convalidated)",table);
        }
        requireConstraint(connection,"mop_contact_tenant_guard","p",List.of("tenant_id"),null,List.of(),false);
        requireConstraint(connection,"mop_contact_tenant_guard","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_customer_contact","p",List.of("id"),null,List.of(),false);requireConstraint(connection,"mop_customer_contact","u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,"mop_customer_contact","f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,"mop_customer_contact","f",List.of("tenant_id","created_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,"mop_customer_contact","f",List.of("tenant_id","id","version"),"mop_customer_contact_revision",List.of("tenant_id","contact_id","version"),true);
        requireConstraint(connection,"mop_customer_contact_revision","p",List.of("tenant_id","contact_id","version"),null,List.of(),false);
        requireConstraint(connection,"mop_customer_contact_revision","f",List.of("tenant_id","contact_id"),"mop_customer_contact",List.of("tenant_id","id"),false);
        requireConstraint(connection,"mop_customer_contact_revision","f",List.of("tenant_id","recorded_by"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireConstraint(connection,"mop_customer_contact_revision","f",List.of("tenant_id","contact_id","previous_version"),"mop_customer_contact_revision",List.of("tenant_id","contact_id","version"),false);
        for(var pair:Map.of("mop_contact_tenant_guard",1,"mop_customer_contact",3,"mop_customer_contact_revision",15).entrySet())requireTrue(connection,"SELECT count(*)="+pair.getValue()+" FROM pg_constraint WHERE conrelid=('public.'||?)::regclass AND contype='c'",pair.getKey());
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_customer_contact_order' AND c.relnamespace='public'::regnamespace");
        requireTrue(connection,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.mop_contact_decision_guard()')",CONTACT_GUARD_SHA256);
        requireTrue(connection,"SELECT count(*)=1 FROM pg_trigger WHERE tgrelid='public.mop_customer_contact'::regclass AND NOT tgisinternal");
        requireTrue(connection,"SELECT tgenabled='O' AND tgtype=21 AND tgfoid=to_regprocedure('public.mop_contact_decision_guard()') AND tgdeferrable AND tginitdeferred FROM pg_trigger WHERE tgrelid='public.mop_customer_contact'::regclass AND tgname='mop_contact_decision_guard_trigger'");
    }
    static void verifyCapacityScenarioResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("16"))){if(input==null||!CAPACITY_SCENARIO_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed capacity scenario migration resource");}
    }
    private static void verifyCapacityScenarios(Connection connection)throws SQLException{
        String table="mop_capacity_scenario_draft";
        verifyColumns(connection,table,Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("actor_id","uuid"),Map.entry("version","bigint"),Map.entry("label","character varying(100)"),Map.entry("note","text"),Map.entry("calculation","jsonb"),Map.entry("calculation_sha256","character(64)"),Map.entry("snapshot","jsonb"),Map.entry("snapshot_sha256","character(64)"),Map.entry("capacity_formula_version","character varying(64)"),Map.entry("buffer_formula_version","character varying(64)"),Map.entry("benchmark_reference","character varying(160)"),Map.entry("benchmark_title","character varying(200)"),Map.entry("benchmark_sha256","character(64)"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("lifecycle_status","character varying(16)"),Map.entry("model_approval_status","character varying(16)"),Map.entry("measurement_verified","boolean"),Map.entry("poc_executed","boolean"),Map.entry("effective","boolean"),Map.entry("record_source","character varying(40)")),Set.of("benchmark_reference","benchmark_title","benchmark_sha256"),true);
        verifyRuntimeAcl(connection,table,Set.of());
        requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_capacity_scenario_draft'::regclass");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_capacity_scenario_draft'::regclass AND NOT convalidated)");
        requireConstraint(connection,table,"p",List.of("id"),null,List.of(),false);requireConstraint(connection,table,"u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,table,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);requireConstraint(connection,table,"f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=21 FROM pg_constraint WHERE conrelid='public.mop_capacity_scenario_draft'::regclass AND contype='c'");
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_capacity_scenario_order' AND c.relnamespace='public'::regnamespace");
    }
    static void verifyManualWarrantyResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("15"))){
            if(input==null)throw new IllegalStateException("Missing warranty migration");byte[] bytes=input.readAllBytes();
            if(!MANUAL_WARRANTY_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalStateException("Unreviewed warranty migration resource");
            String sql=new String(bytes,StandardCharsets.UTF_8);int marker=sql.indexOf(" AS $$"),end=marker<0?-1:sql.indexOf("$$;",marker+6);
            if(marker<0||end<0||!WARRANTY_GUARD_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.substring(marker+6,end).getBytes(StandardCharsets.UTF_8)))))throw new IllegalStateException("Unreviewed warranty guard body");
        }
    }
    private static void verifyManualWarranty(Connection connection)throws SQLException{
        requireTrue(connection,"SELECT count(*)=4 FROM pg_constraint WHERE conrelid='public.mop_asset_manual_revision'::regclass AND conname IN ('mop_warranty_start_range','mop_warranty_supplier_text','mop_warranty_declaration_fields','mop_warranty_dates_order') AND contype='c' AND convalidated");
        requireTrue(connection,"SELECT pg_get_expr(d.adbin,d.adrelid)='false' FROM pg_attribute a JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid='public.mop_asset_manual_revision'::regclass AND a.attname='warranty_details_declared' AND a.attnotnull");
        requireTrue(connection,"SELECT NOT p.prosecdef AND p.prorettype='trigger'::regtype AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND p.proconfig=ARRAY['search_path=pg_catalog'] AND has_function_privilege('mop_test_runtime',p.oid,'EXECUTE') AND NOT EXISTS(SELECT 1 FROM aclexplode(COALESCE(p.proacl,acldefault('f',p.proowner))) a WHERE a.grantee=0) FROM pg_proc p WHERE p.oid=to_regprocedure('public.mop_warranty_declaration_guard()')",WARRANTY_GUARD_SHA256);
        requireTrue(connection,"SELECT count(*)=1 FROM pg_trigger WHERE tgrelid='public.mop_asset_manual_revision'::regclass AND NOT tgisinternal");
        requireTrue(connection,"SELECT tgenabled='O' AND tgtype=7 AND tgfoid=to_regprocedure('public.mop_warranty_declaration_guard()') FROM pg_trigger WHERE tgrelid='public.mop_asset_manual_revision'::regclass AND tgname='mop_asset_warranty_declaration_guard' AND NOT tgisinternal");
    }
    static void verifyManualRecoveryResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("14"))){if(input==null||!MANUAL_RECOVERY_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed manual recovery migration resource");}
    }
    private static void verifyManualRecoveries(Connection connection)throws SQLException{
        String table="mop_manual_recovery_confirmation";
        verifyColumns(connection,table,Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("incident_id","uuid"),Map.entry("actor_id","uuid"),Map.entry("version","bigint"),Map.entry("note","text"),Map.entry("note_sha256","character(64)"),Map.entry("reported_recovered_at","timestamp(3) with time zone"),Map.entry("recorded_at","timestamp(3) with time zone"),Map.entry("manual_recovery_status","character varying(24)"),Map.entry("verification_status","character varying(24)"),Map.entry("monitor_state","character varying(32)"),Map.entry("disposition_state","character varying(32)"),Map.entry("monitoring_recovery_applicable","boolean"),Map.entry("ticket_state_changed","boolean"),Map.entry("sla_changed","boolean"),Map.entry("record_source","character varying(40)")),Set.of("reported_recovered_at","monitor_state","disposition_state"),true);
        verifyRuntimeAcl(connection,table,Set.of());
        requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_manual_recovery_confirmation'::regclass");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_manual_recovery_confirmation'::regclass AND NOT convalidated)");
        requireConstraint(connection,table,"p",List.of("id"),null,List.of(),false);requireConstraint(connection,table,"u",List.of("tenant_id","id"),null,List.of(),false);requireConstraint(connection,table,"u",List.of("tenant_id","incident_id"),null,List.of(),false);
        requireConstraint(connection,table,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);requireConstraint(connection,table,"f",List.of("tenant_id","incident_id"),"mop_manual_incident_registration",List.of("tenant_id","id"),false);requireConstraint(connection,table,"f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=16 FROM pg_constraint WHERE conrelid='public.mop_manual_recovery_confirmation'::regclass AND contype='c'");
    }
    static void verifyManualIncidentResource()throws Exception{
        try(var input=TestSchemaMigration.class.getResourceAsStream("/db/migration/"+SCRIPTS.get("13"))){if(input==null||!MANUAL_INCIDENT_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))throw new IllegalStateException("Unreviewed manual incident migration resource");}
    }
    private static void verifyManualIncidents(Connection connection)throws SQLException{
        String table="mop_manual_incident_registration";
        verifyColumns(connection,table,Map.ofEntries(Map.entry("id","uuid"),Map.entry("tenant_id","uuid"),Map.entry("asset_id","uuid"),Map.entry("actor_id","uuid"),Map.entry("observed_asset_version","bigint"),Map.entry("asset_name_at_registration","character varying(100)"),Map.entry("title","character varying(200)"),Map.entry("severity","character varying(16)"),Map.entry("registered_at","timestamp(3) with time zone"),Map.entry("source_state","character varying(16)"),Map.entry("registration_status","character varying(24)"),Map.entry("verification_status","character varying(24)"),Map.entry("monitor_state","character varying(32)"),Map.entry("disposition_state","character varying(32)"),Map.entry("ticket_priority","character varying(8)"),Map.entry("observed_at","timestamp(3) with time zone"),Map.entry("recovered_at","timestamp(3) with time zone"),Map.entry("manual_recovery_status","character varying(24)"),Map.entry("ticket_automatically_created","boolean"),Map.entry("record_source","character varying(40)")),Set.of("monitor_state","disposition_state","ticket_priority","observed_at","recovered_at"),true);
        verifyRuntimeAcl(connection,table,Set.of());
        requireTrue(connection,"SELECT relkind='r' AND relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') FROM pg_class WHERE oid='public.mop_manual_incident_registration'::regclass");
        requireTrue(connection,"SELECT NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='public.mop_manual_incident_registration'::regclass AND NOT convalidated)");
        requireConstraint(connection,table,"p",List.of("id"),null,List.of(),false);requireConstraint(connection,table,"u",List.of("tenant_id","id"),null,List.of(),false);
        requireConstraint(connection,table,"f",List.of("tenant_id"),"mop_tenant",List.of("id"),false);
        requireConstraint(connection,table,"f",List.of("tenant_id","asset_id"),"mop_asset",List.of("tenant_id","id"),false);
        requireConstraint(connection,table,"f",List.of("tenant_id","actor_id"),"mop_membership",List.of("tenant_id","user_id"),false);
        requireTrue(connection,"SELECT count(*)=18 FROM pg_constraint WHERE conrelid='public.mop_manual_incident_registration'::regclass AND contype='c'");
        requireTrue(connection,"SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='mop_manual_incident_registration_order' AND c.relnamespace='public'::regnamespace");
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
    private static void verifyAssetMetadata(Connection connection,boolean warranty)throws SQLException{
        verifyColumns(connection,"mop_asset_manual_details",Map.of("tenant_id","uuid","asset_id","uuid","revision_version","bigint"),Set.of(),true);
        Map<String,String> columns=new HashMap<>(Map.ofEntries(
            Map.entry("tenant_id","uuid"),Map.entry("asset_id","uuid"),Map.entry("asset_version","bigint"),Map.entry("previous_metadata_version","bigint"),Map.entry("actor_id","uuid"),Map.entry("recorded_at","timestamp(3) with time zone"),
            Map.entry("name","character varying(100)"),Map.entry("type","character varying(32)"),Map.entry("management_ip","character varying(45)"),Map.entry("site_id","character varying(64)"),Map.entry("brand","character varying(100)"),Map.entry("model","character varying(100)"),Map.entry("serial_number","character varying(128)"),Map.entry("location","character varying(200)"),Map.entry("owner_id","uuid"),Map.entry("business_description","character varying(200)"),Map.entry("purchase_date","date"),Map.entry("warranty_expiry","date"),Map.entry("overdue_service_date","date"),Map.entry("collection_method_note","character varying(100)"),Map.entry("note","character varying(2000)"),Map.entry("before_snapshot","jsonb"),Map.entry("verification_status","character varying(24)"),Map.entry("monitoring_applied","boolean"),Map.entry("cmdb_applied","boolean")));
        Set<String> nullable=new HashSet<>(Set.of("previous_metadata_version","brand","model","serial_number","location","owner_id","business_description","purchase_date","warranty_expiry","overdue_service_date","collection_method_note"));
        if(warranty){columns.put("warranty_start","date");columns.put("warranty_supplier","character varying(200)");columns.put("warranty_details_declared","boolean");nullable.addAll(Set.of("warranty_start","warranty_supplier"));}
        verifyColumns(connection,"mop_asset_manual_revision",columns,nullable,true);
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
        requireTrue(connection,"SELECT count(*)="+(warranty?26:22)+" FROM pg_constraint WHERE conrelid='public.mop_asset_manual_revision'::regclass AND contype='c'");
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
    static void verifyColumns(Connection connection,String table,Map<String,String> types,Set<String> nullable,boolean complete)throws SQLException {
        Set<String> found=new HashSet<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT attname,format_type(atttypid,atttypmod),attnotnull FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped")) {
            statement.setString(1,"public."+table);try(ResultSet rows=statement.executeQuery()){while(rows.next()) {
                String column=rows.getString(1);if(!types.containsKey(column)){if(complete)throw new IllegalStateException("Unexpected photo column");continue;}
                if(!types.get(column).equals(rows.getString(2))||rows.getBoolean(3)==nullable.contains(column))throw new IllegalStateException("Incompatible photo column");found.add(column);
            }}
        }
        if(!found.equals(types.keySet()))throw new IllegalStateException("Missing photo column");
    }
    static void verifyRuntimeAcl(Connection connection,String table,Set<String> allowedUpdates)throws SQLException {
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
    static void requireConstraint(Connection connection,String table,String type,List<String> columns,String reference,List<String> referencedColumns,boolean deferred)throws SQLException {
        String sql="SELECT EXISTS(SELECT 1 FROM pg_constraint c WHERE c.conrelid=?::regclass AND c.contype=?::\"char\" AND c.convalidated AND c.condeferrable=? AND c.condeferred=? AND ARRAY(SELECT a.attname::text FROM unnest(c.conkey) WITH ORDINALITY k(num,position) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.position)=?::text[]"+
            (reference==null?"":" AND c.confmatchtype='s' AND c.confdeltype='a' AND c.confupdtype='a' AND c.confrelid=?::regclass AND ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY k(num,position) JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.position)=?::text[]")+")";
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,"public."+table);statement.setString(2,type);statement.setBoolean(3,deferred);statement.setBoolean(4,deferred);statement.setArray(5,connection.createArrayOf("text",columns.toArray()));
            if(reference!=null){statement.setString(6,"public."+reference);statement.setArray(7,connection.createArrayOf("text",referencedColumns.toArray()));}
            try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))throw new IllegalStateException("Missing validated photo constraint");}
        }
    }
    static void requireTrue(Connection connection,String sql,String...parameters)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setQueryTimeout(10);for(int index=0;index<parameters.length;index++)statement.setString(index+1,parameters[index]);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1)||rows.next())throw new IllegalStateException("Schema verification rejected");}}
    }
}
