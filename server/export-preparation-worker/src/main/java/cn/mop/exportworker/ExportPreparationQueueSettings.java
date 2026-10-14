package cn.mop.exportworker;

import java.time.*;
import java.util.function.Function;
import java.util.regex.Pattern;

record ExportPreparationQueueSettings(String jdbcUrl,String user,String password,String workerId,Duration lease,Instant acceptedAfter){
    static ExportPreparationQueueSettings from(String[] args,Function<String,String> env){
        if(args==null||args.length!=1||!"--run-queue-without-publication".equals(args[0])||!"remote_test".equals(env.apply("SPRING_PROFILES_ACTIVE"))||!"true".equals(env.apply("MOP_EXPORT_PREPARATION_QUEUE_ENABLED")))throw invalid();
        for(String key:new String[]{"MOP_PHOTO_WORKER_ENABLED","MOP_IMPORT_WORKER_ENABLED","MOP_ASSET_IMPORT_WORKER_ENABLED","MOP_CONTACT_IMPORT_WORKER_ENABLED","MOP_EXPORT_PREPARATION_WORKER_ENABLED"})if("true".equals(env.apply(key)))throw invalid();
        String url=required(env,"SPRING_DATASOURCE_URL");var m=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test").matcher(url);if(!m.matches())throw invalid();int port=Integer.parseInt(m.group(1));if(port<1||port>65535)throw invalid();
        String user=required(env,"SPRING_DATASOURCE_USERNAME");if(!"mop_test_runtime".equals(user))throw invalid();
        String worker=required(env,"MOP_EXPORT_PREPARATION_WORKER_ID");if(!worker.matches("[A-Za-z0-9._:-]{1,64}"))throw invalid();
        String duration=required(env,"MOP_EXPORT_PREPARATION_LEASE_MILLIS");if(!duration.matches("[1-9][0-9]{0,5}"))throw invalid();long ms=Long.parseLong(duration);if(ms<1000||ms>600000)throw invalid();
        Instant cutoff;
        try{String raw=required(env,"MOP_EXPORT_PREPARATION_ACCEPTED_AFTER");if(!raw.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z"))throw invalid();cutoff=Instant.parse(raw);if(!cutoff.toString().equals(raw)&&!cutoff.toString().replace("Z",".000Z").equals(raw))throw invalid();}catch(RuntimeException invalid){throw invalid();}
        return new ExportPreparationQueueSettings(url,user,required(env,"SPRING_DATASOURCE_PASSWORD"),worker,Duration.ofMillis(ms),cutoff);
    }
    private static String required(Function<String,String> env,String key){String value=env.apply(key);if(value==null||value.isBlank())throw invalid();return value;}
    private static IllegalStateException invalid(){return new IllegalStateException("FAIL_CLOSED: explicit preparation queue, private restricted database, stable UTC cutoff and technical lease required");}
    @Override public String toString(){return "ExportPreparationQueueSettings[remote_test,prospective-preparation-only,credentials=REDACTED]";}
}
