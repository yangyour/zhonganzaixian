package cn.mop.worker;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkerRuntimeSettingsTest {
    private static final String PRIVATE_PASSWORD="isolated-test-password",PRIVATE_ACCESS="isolated-test-access",PRIVATE_SECRET="isolated-test-secret";

    private static Map<String,String> settings() {
        return new HashMap<>(Map.of(
            "MOP_PHOTO_WORKER_ENABLED","true",
            "SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test",
            "SPRING_DATASOURCE_USERNAME","mop_test_runtime",
            "SPRING_DATASOURCE_PASSWORD",PRIVATE_PASSWORD,
            "MOP_S3_ENDPOINT","http://127.0.0.1:19000",
            "MOP_S3_BUCKET","mop-test-photos",
            "MOP_S3_ACCESS_KEY",PRIVATE_ACCESS,
            "MOP_S3_SECRET_KEY",PRIVATE_SECRET));
    }

    private static WorkerRuntimeSettings parse(Map<String,String> settings,String... profiles) {
        return WorkerRuntimeSettings.from(profiles,settings::get);
    }

    @Test void acceptsOnlyExplicitFileWorkerInRemoteTest() {
        assertEquals("mop_test_runtime",parse(settings(),"remote_test").databaseUser());
        for(String[] profiles:new String[][]{{},{"local_fixture"},{"production"},{"integration"},{"remote_test","local_fixture"}})
            assertThrows(IllegalStateException.class,()->parse(settings(),profiles));
        for(String enabled:new String[]{"false","TRUE","1",""}) {
            Map<String,String> values=settings();values.put("MOP_PHOTO_WORKER_ENABLED",enabled);
            assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        }
        Map<String,String> values=settings();values.remove("MOP_PHOTO_WORKER_ENABLED");
        assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
    }

    @Test void rejectsOtherDatabasesRolesAndUnapprovedConnectionOptions() {
        for(String url:new String[]{
            "jdbc:postgresql://localhost:15432/mop_test","jdbc:postgresql://example.invalid:5432/mop_test",
            "jdbc:postgresql://127.0.0.1:15432/other","jdbc:postgresql://127.0.0.1:15432/mop_test?user=mop_test_migrator",
            "jdbc:postgresql://127.0.0.1:0/mop_test","jdbc:postgresql://127.0.0.1:65536/mop_test",
            "jdbc:postgresql://127.0.0.1:99999999999/mop_test"}) {
            Map<String,String> values=settings();values.put("SPRING_DATASOURCE_URL",url);
            assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        }
        for(String user:new String[]{"mop_test_migrator","postgres","other"}) {
            Map<String,String> values=settings();values.put("SPRING_DATASOURCE_USERNAME",user);
            assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        }
    }

    @Test void requiresTheFixedMinioNamespaceAndEveryPrivateCredential() {
        for(Map.Entry<String,String> invalid:Map.of(
            "MOP_S3_ENDPOINT","http://example.invalid:9000","MOP_S3_BUCKET","other-project").entrySet()) {
            Map<String,String> values=settings();values.put(invalid.getKey(),invalid.getValue());
            assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        }
        for(String key:new String[]{"SPRING_DATASOURCE_PASSWORD","MOP_S3_ACCESS_KEY","MOP_S3_SECRET_KEY"}) {
            Map<String,String> values=settings();values.remove(key);
            assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
            values.put(key," ");
            assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        }
    }

    @Test void diagnosticsDoNotEchoSuppliedCredentialsOrConnectionText() {
        String description=parse(settings(),"remote_test").toString();
        for(String credential:new String[]{PRIVATE_PASSWORD,PRIVATE_ACCESS,PRIVATE_SECRET})assertFalse(description.contains(credential));
        Map<String,String> values=settings();
        values.put("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test?password="+PRIVATE_PASSWORD);
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        assertFalse(failure.getMessage().contains(PRIVATE_PASSWORD));
        assertFalse(failure.getMessage().contains("jdbc:"));
    }
    private Map<String,String> ossSettings() {
        var values=settings();values.keySet().removeIf(key->key.startsWith("MOP_S3_")||key.startsWith("MOP_IMPORT_S3_"));
        values.putAll(Map.ofEntries(Map.entry("OBJECT_STORAGE_ENABLED","True"),Map.entry("OBJECT_STORAGE_PROVIDER","oss"),
            Map.entry("OBJECT_STORAGE_ENDPOINT","https://oss-cn-beijing.aliyuncs.com"),Map.entry("OBJECT_STORAGE_PUBLIC_ENDPOINT","https://oss-cn-beijing.aliyuncs.com"),Map.entry("OBJECT_STORAGE_REGION","cn-beijing"),Map.entry("OBJECT_STORAGE_BUCKET","synthetic-worker-bucket"),
            Map.entry("OBJECT_STORAGE_ACCESS_KEY","synthetic-access"),Map.entry("OBJECT_STORAGE_SECRET_KEY","synthetic-secret"),Map.entry("OBJECT_STORAGE_PRESIGN_EXPIRES","3600"),Map.entry("MOP_OBJECT_STORAGE_PREFIX","mop-niko-test/00000000-0000-4000-8000-000000000001/")));
        return values;
    }
    @Test void acceptsExplicitScopedOssWithoutLegacyCredentials(){
        var parsed=parse(ossSettings(),"remote_test");assertNotNull(parsed.ossSettings());assertNull(parsed.storageEndpoint());assertNull(parsed.storageSecretKey());assertFalse(parsed.toString().contains("synthetic-secret"));
    }
    @Test void partialDisabledOrUnknownOssNeverFallsBack(){
        for(var change:Map.of("OBJECT_STORAGE_ENABLED","false","OBJECT_STORAGE_PROVIDER","unknown","OBJECT_STORAGE_SECRET_KEY","","MOP_OBJECT_STORAGE_PREFIX","../").entrySet()){
            var values=ossSettings();values.put(change.getKey(),change.getValue());assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
        }
        var partial=settings();partial.put("OBJECT_STORAGE_PROVIDER","oss");assertThrows(IllegalStateException.class,()->parse(partial,"remote_test"));
    }
    @Test void ossStillRequiresRestrictedDatabaseAndExplicitWorkerOptIn(){
        var elevated=ossSettings();elevated.put("SPRING_DATASOURCE_USERNAME","mop_test_migrator");assertThrows(IllegalStateException.class,()->parse(elevated,"remote_test"));
        var values=ossSettings();values.remove("SPRING_DATASOURCE_PASSWORD");var missing=values;assertThrows(IllegalStateException.class,()->parse(missing,"remote_test"));
        var optOut=ossSettings();optOut.remove("MOP_PHOTO_WORKER_ENABLED");assertThrows(IllegalStateException.class,()->parse(optOut,"remote_test"));
    }

}
