package cn.mop.contactworker;
import java.time.Duration;
import java.util.function.Function;
import java.util.regex.Pattern;
/** Only the explicitly approved loopback project database; credentials excluded from diagnostics. */
record ContactImportWorkerSettings(String jdbcUrl,String databaseUser,String databasePassword,Duration lease) {
    private static final Pattern DATABASE=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test");
    static ContactImportWorkerSettings from(String[] profiles,Function<String,String> settings){
        if(profiles==null||profiles.length!=1||!"remote_test".equals(profiles[0]))throw rejected("exactly remote_test is required");
        if(!"true".equals(settings.apply("MOP_CONTACT_IMPORT_WORKER_ENABLED")))throw rejected("explicit MOP_CONTACT_IMPORT_WORKER_ENABLED=true is required");
        if("true".equals(settings.apply("MOP_PHOTO_WORKER_ENABLED"))||"true".equals(settings.apply("MOP_ASSET_IMPORT_WORKER_ENABLED")))throw rejected("other worker enable flags must not be mixed into this dedicated entrypoint");
        String url=required(settings,"SPRING_DATASOURCE_URL");var match=DATABASE.matcher(url);if(!match.matches())throw rejected("only approved loopback mop_test without URL options is allowed");int port=Integer.parseInt(match.group(1));if(port<1||port>65535)throw rejected("database port is outside valid bounds");
        String user=required(settings,"SPRING_DATASOURCE_USERNAME");if(!"mop_test_runtime".equals(user))throw rejected("restricted mop_test_runtime role is required");
        String milliseconds=settings.apply("MOP_CONTACT_IMPORT_LEASE_MILLIS");long lease=30000;if(milliseconds!=null){if(!milliseconds.matches("[1-9][0-9]{0,6}"))throw rejected("lease must be explicit canonical milliseconds");lease=Long.parseLong(milliseconds);if(lease<1000||lease>3600000)throw rejected("runtime lease must be 1000 through 3600000 milliseconds");}
        return new ContactImportWorkerSettings(url,user,required(settings,"SPRING_DATASOURCE_PASSWORD"),Duration.ofMillis(lease));
    }
    private static String required(Function<String,String> settings,String key){String value=settings.apply(key);if(value==null||value.isBlank())throw rejected("missing private setting "+key);return value;}
    private static IllegalStateException rejected(String reason){return new IllegalStateException("FAIL_CLOSED: contact import worker "+reason);}
    @Override public String toString(){return "ContactImportWorkerSettings[remote_test,contact-import-only,credentials=REDACTED]";}
}
