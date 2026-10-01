package cn.mop.worker;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates destinations before opening a connection; credentials never appear in diagnostics. */
record WorkerRuntimeSettings(String jdbcUrl,String databaseUser,String databasePassword,
                             String storageEndpoint,String storageBucket,String storageAccessKey,String storageSecretKey) {
    private static final Pattern DATABASE_URL=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test");

    static WorkerRuntimeSettings from(String[] profiles,Function<String,String> setting) {
        if(profiles.length!=1||!"remote_test".equals(profiles[0]))
            throw new IllegalStateException("FAIL_CLOSED: file-only worker requires exactly remote_test; fixture and other runtimes cannot claim jobs");
        if(!"true".equals(setting.apply("MOP_PHOTO_WORKER_ENABLED")))
            throw new IllegalStateException("FAIL_CLOSED: file-only worker requires explicit MOP_PHOTO_WORKER_ENABLED=true");
        String url=required(setting,"SPRING_DATASOURCE_URL");
        Matcher destination=DATABASE_URL.matcher(url);
        if(!destination.matches()||Integer.parseInt(destination.group(1))<1||Integer.parseInt(destination.group(1))>65535)
            throw new IllegalStateException("FAIL_CLOSED: file-only worker requires the loopback SSH-forwarded mop_test PostgreSQL URL without credentials or options");
        String user=required(setting,"SPRING_DATASOURCE_USERNAME");
        if(!"mop_test_runtime".equals(user))
            throw new IllegalStateException("FAIL_CLOSED: file-only worker requires the restricted mop_test_runtime role");
        String endpoint=required(setting,"MOP_S3_ENDPOINT"),bucket=required(setting,"MOP_S3_BUCKET");
        if(!"http://127.0.0.1:19000".equals(endpoint)||!"mop-test-photos".equals(bucket))
            throw new IllegalStateException("FAIL_CLOSED: file-only worker requires the designated loopback MinIO photo namespace");
        return new WorkerRuntimeSettings(url,user,required(setting,"SPRING_DATASOURCE_PASSWORD"),
            endpoint,bucket,required(setting,"MOP_S3_ACCESS_KEY"),required(setting,"MOP_S3_SECRET_KEY"));
    }

    private static String required(Function<String,String> setting,String key) {
        String value=setting.apply(key);
        if(value==null||value.isBlank())
            throw new IllegalStateException("FAIL_CLOSED: missing private file-only worker setting "+key);
        return value;
    }

    @Override public String toString(){return "WorkerRuntimeSettings[remote_test,file-only,credentials=REDACTED]";}
}
