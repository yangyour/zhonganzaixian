package cn.mop.exportworker;
import java.time.Duration;
import java.util.function.Function;
import java.util.regex.Pattern;
/** Explicit single-job, preparation-only project-test settings; no implicit queue draining or publication. */
record ExportPreparationWorkerSettings(String jobId,String jdbcUrl,String user,String password,String workerId,Duration lease){
    private static final Pattern DATABASE=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test");
    static ExportPreparationWorkerSettings from(String[] args,Function<String,String> env){
        if(args==null||args.length!=2||!"--prepare-one-without-publication".equals(args[0])||args[1]==null||!args[1].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw rejected("one explicit canonical job and preparation-only mode required");
        if(!"remote_test".equals(env.apply("SPRING_PROFILES_ACTIVE")))throw rejected("exactly remote_test required");
        if(!"true".equals(env.apply("MOP_EXPORT_PREPARATION_WORKER_ENABLED")))throw rejected("explicit preparation worker opt-in required");
        for(String key:new String[]{"MOP_PHOTO_WORKER_ENABLED","MOP_IMPORT_WORKER_ENABLED","MOP_ASSET_IMPORT_WORKER_ENABLED","MOP_CONTACT_IMPORT_WORKER_ENABLED"})if("true".equals(env.apply(key)))throw rejected("other enabled worker roles must not be mixed");
        String url=required(env,"SPRING_DATASOURCE_URL");var match=DATABASE.matcher(url);if(!match.matches())throw rejected("restricted loopback mop_test without URL options required");int port=Integer.parseInt(match.group(1));if(port<1||port>65535)throw rejected("invalid database port");
        String user=required(env,"SPRING_DATASOURCE_USERNAME");if(!"mop_test_runtime".equals(user))throw rejected("restricted runtime database identity required");
        String worker=required(env,"MOP_EXPORT_PREPARATION_WORKER_ID");if(!worker.matches("[A-Za-z0-9._:-]{1,64}"))throw rejected("invalid technical worker label");
        String milliseconds=required(env,"MOP_EXPORT_PREPARATION_LEASE_MILLIS");if(!milliseconds.matches("[1-9][0-9]{0,5}"))throw rejected("explicit canonical lease milliseconds required");long lease=Long.parseLong(milliseconds);if(lease<1000||lease>600000)throw rejected("technical runtime lease must be 1000 through 600000 milliseconds");
        return new ExportPreparationWorkerSettings(args[1],url,user,required(env,"SPRING_DATASOURCE_PASSWORD"),worker,Duration.ofMillis(lease));
    }
    private static String required(Function<String,String> env,String key){String value=env.apply(key);if(value==null||value.isBlank())throw rejected("required private setting missing");return value;}
    private static IllegalStateException rejected(String reason){return new IllegalStateException("FAIL_CLOSED: export preparation worker "+reason);}
    @Override public String toString(){return "ExportPreparationWorkerSettings[remote_test,single-job,preparation-only,credentials=REDACTED]";}
}
