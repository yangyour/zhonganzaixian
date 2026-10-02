package cn.mop.importworker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssetImportWorkerRuntimeSettingsTest {
    @TempDir Path project;
    private Map<String,String> settings(){return new HashMap<>(Map.ofEntries(
        Map.entry("MOP_IMPORT_WORKER_ENABLED","true"),Map.entry("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test"),Map.entry("SPRING_DATASOURCE_USERNAME","mop_test_runtime"),Map.entry("SPRING_DATASOURCE_PASSWORD","PRIVATE-ISOLATED-DB"),
        Map.entry("MOP_IMPORT_S3_ENDPOINT","http://127.0.0.1:19000"),Map.entry("MOP_IMPORT_S3_BUCKET","mop-test-asset-imports"),Map.entry("MOP_IMPORT_S3_ACCESS_KEY","PRIVATE-ISOLATED-ACCESS"),Map.entry("MOP_IMPORT_S3_SECRET_KEY","PRIVATE-ISOLATED-SECRET"),Map.entry("MOP_IMPORT_PARSER_JAR",project.resolve("server/import-parser/target/MOP-FAKE.jar").toString())));}
    private AssetImportWorkerRuntimeSettings parse(Map<String,String> settings,String... profiles){return AssetImportWorkerRuntimeSettings.from(profiles,settings::get,project);}
    @Test void requiresAnExplicitImportWorkerAndExactlyTheDesignatedProfile(){
        assertEquals("mop_test_runtime",parse(settings(),"remote_test").databaseUser());
        for(String[] profiles:new String[][]{{},{"local_fixture"},{"production"},{"remote_test","local_fixture"}})assertThrows(IllegalStateException.class,()->parse(settings(),profiles));
        for(String enabled:List.of("false","TRUE","1","")){var values=settings();values.put("MOP_IMPORT_WORKER_ENABLED",enabled);assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));}
        var values=settings();values.remove("MOP_IMPORT_WORKER_ENABLED");values.put("MOP_PHOTO_WORKER_ENABLED","true");assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));
    }
    @Test void noOtherDatabaseRoleStorageOrParserPathIsAccepted(){
        for(var entry:Map.ofEntries(Map.entry("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/other"),Map.entry("SPRING_DATASOURCE_USERNAME","mop_test_migrator"),Map.entry("MOP_IMPORT_S3_ENDPOINT","http://example.invalid:9000"),Map.entry("MOP_IMPORT_S3_BUCKET","mop-test-photos"),Map.entry("MOP_IMPORT_PARSER_JAR","relative.jar")).entrySet()){var values=settings();values.put(entry.getKey(),entry.getValue());assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));}
        for(String url:List.of("jdbc:postgresql://127.0.0.1:0/mop_test","jdbc:postgresql://127.0.0.1:65536/mop_test","jdbc:postgresql://127.0.0.1:15432/mop_test?password=PRIVATE-ISOLATED-DB")){var values=settings();values.put("SPRING_DATASOURCE_URL",url);var failure=assertThrows(IllegalStateException.class,()->parse(values,"remote_test"));assertFalse(failure.getMessage().contains("PRIVATE"));assertNull(failure.getCause());}
    }
    @Test void diagnosticsRedactAllSecretsAndPhotoOidcCredentialsDoNotSubstitute(){
        var values=settings();String description=parse(values,"remote_test").toString();assertFalse(description.contains("PRIVATE"));assertFalse(description.contains(project.toString()));
        for(String key:List.of("SPRING_DATASOURCE_PASSWORD","MOP_IMPORT_S3_ACCESS_KEY","MOP_IMPORT_S3_SECRET_KEY","MOP_IMPORT_PARSER_JAR")){values=settings();values.remove(key);values.put("MOP_S3_ACCESS_KEY","PRIVATE-ISOLATED-PHOTO");values.put("MOP_S3_SECRET_KEY","PRIVATE-ISOLATED-PHOTO");values.put("MOP_OIDC_CLIENT_SECRET","PRIVATE-ISOLATED-OIDC");var current=values;var failure=assertThrows(IllegalStateException.class,()->parse(current,"remote_test"));assertFalse(failure.getMessage().contains("PRIVATE"));}
    }
}
