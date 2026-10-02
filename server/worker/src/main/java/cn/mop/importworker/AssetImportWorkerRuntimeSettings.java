package cn.mop.importworker;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Import worker destinations are fixed; secret values are never included in diagnostics. */
record AssetImportWorkerRuntimeSettings(String jdbcUrl,String databaseUser,String databasePassword,
    String storageEndpoint,String storageBucket,String storageAccessKey,String storageSecretKey,Path parserJar) {
    private static final Pattern DATABASE=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test");
    static AssetImportWorkerRuntimeSettings from(String[] profiles,Function<String,String> setting){
        return from(profiles,setting,Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }
    static AssetImportWorkerRuntimeSettings from(String[] profiles,Function<String,String> setting,Path project){
        if(profiles.length!=1||!"remote_test".equals(profiles[0])||!"true".equals(setting.apply("MOP_IMPORT_WORKER_ENABLED")))
            throw new IllegalStateException("FAIL_CLOSED: import worker requires exactly remote_test and explicit MOP_IMPORT_WORKER_ENABLED=true");
        String url=required(setting,"SPRING_DATASOURCE_URL");var destination=DATABASE.matcher(url);
        if(!destination.matches()||Integer.parseInt(destination.group(1))<1||Integer.parseInt(destination.group(1))>65535)
            throw new IllegalStateException("FAIL_CLOSED: import worker requires the loopback SSH-forwarded mop_test database without connection options");
        String user=required(setting,"SPRING_DATASOURCE_USERNAME");
        if(!"mop_test_runtime".equals(user))throw new IllegalStateException("FAIL_CLOSED: import worker requires the restricted mop_test_runtime role");
        String endpoint=required(setting,"MOP_IMPORT_S3_ENDPOINT"),bucket=required(setting,"MOP_IMPORT_S3_BUCKET");
        if(!"http://127.0.0.1:19000".equals(endpoint)||!"mop-test-asset-imports".equals(bucket))
            throw new IllegalStateException("FAIL_CLOSED: import worker requires the designated loopback import storage namespace");
        Path jar;
        try{jar=Path.of(required(setting,"MOP_IMPORT_PARSER_JAR"));if(!project.isAbsolute()||!jar.isAbsolute()||!jar.normalize().getParent().equals(project.normalize().resolve("server/import-parser/target")))throw new IllegalArgumentException();new AssetImportParserProcess(jar);}
        catch(RuntimeException invalid){throw new IllegalStateException("FAIL_CLOSED: import worker requires an absolute project standalone parser jar destination");}
        return new AssetImportWorkerRuntimeSettings(url,user,required(setting,"SPRING_DATASOURCE_PASSWORD"),endpoint,bucket,
            required(setting,"MOP_IMPORT_S3_ACCESS_KEY"),required(setting,"MOP_IMPORT_S3_SECRET_KEY"),jar.normalize());
    }
    private static String required(Function<String,String> setting,String key){String value=setting.apply(key);if(value==null||value.isBlank())throw new IllegalStateException("FAIL_CLOSED: missing private import worker setting "+key);return value;}
    @Override public String toString(){return "AssetImportWorkerRuntimeSettings[remote_test,project-import-jobs,credentials=REDACTED]";}
}
