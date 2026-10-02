package cn.mop.worker;
import java.util.*;
public final class WorkerRuntimeSettingsCheck {
    static Map<String,String> valid(){return new HashMap<>(Map.of("MOP_PHOTO_WORKER_ENABLED","true","SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test","SPRING_DATASOURCE_USERNAME","mop_test_runtime","SPRING_DATASOURCE_PASSWORD","FAKE_SECRET","MOP_S3_ENDPOINT","http://127.0.0.1:19000","MOP_S3_BUCKET","mop-test-photos","MOP_S3_ACCESS_KEY","FAKE_ACCESS","MOP_S3_SECRET_KEY","FAKE_SECRET"));}
    static void denied(String[] profiles,Map<String,String> values){try{WorkerRuntimeSettings.from(profiles,values::get);throw new AssertionError("unsafe worker configuration accepted");}catch(IllegalStateException expected){if(expected.getMessage().contains("FAKE_SECRET"))throw new AssertionError("secret disclosed");}}
    public static void main(String[] args){String[] remote={"remote_test"};WorkerRuntimeSettings safe=WorkerRuntimeSettings.from(remote,valid()::get);if(safe.toString().contains("FAKE_SECRET"))throw new AssertionError("secret settings exposed");int count=1;
       denied(new String[]{"local_fixture"},valid());count++;denied(new String[]{"remote_test","local_fixture"},valid());count++;
       for(var invalid:Map.of("MOP_PHOTO_WORKER_ENABLED","false","SPRING_DATASOURCE_URL","jdbc:postgresql://example.invalid:5432/mop_test","SPRING_DATASOURCE_USERNAME","mop_test_migrator","MOP_S3_BUCKET","other-bucket","MOP_S3_ENDPOINT","http://127.0.0.1:9000").entrySet()){Map<String,String> settings=valid();settings.put(invalid.getKey(),invalid.getValue());denied(remote,settings);count++;}
       Map<String,String> absent=valid();absent.remove("MOP_S3_SECRET_KEY");denied(remote,absent);count++;
       Map<String,String> oss=valid();oss.keySet().removeIf(key->key.startsWith("MOP_S3_"));
       oss.putAll(Map.ofEntries(Map.entry("OBJECT_STORAGE_ENABLED","True"),Map.entry("OBJECT_STORAGE_PROVIDER","oss"),Map.entry("OBJECT_STORAGE_ENDPOINT","https://oss-cn-beijing.aliyuncs.com"),Map.entry("OBJECT_STORAGE_PUBLIC_ENDPOINT","https://oss-cn-beijing.aliyuncs.com"),Map.entry("OBJECT_STORAGE_REGION","cn-beijing"),Map.entry("OBJECT_STORAGE_BUCKET","mop-fake-oss"),Map.entry("OBJECT_STORAGE_ACCESS_KEY","FAKE_ACCESS"),Map.entry("OBJECT_STORAGE_SECRET_KEY","FAKE_SECRET"),Map.entry("OBJECT_STORAGE_PRESIGN_EXPIRES","3600"),Map.entry("MOP_OBJECT_STORAGE_PREFIX","mop-niko-test/00000000-0000-4000-8000-000000000001/")));
       WorkerRuntimeSettings nativeOss=WorkerRuntimeSettings.from(remote,oss::get);if(nativeOss.ossSettings()==null||nativeOss.storageEndpoint()!=null||nativeOss.toString().contains("FAKE_SECRET"))throw new AssertionError("OSS selection or redaction failed");count++;
       Map<String,String> partial=valid();partial.put("OBJECT_STORAGE_PROVIDER","oss");denied(remote,partial);count++;
       for(var invalid:Map.of("OBJECT_STORAGE_ENABLED","false","OBJECT_STORAGE_PROVIDER","unknown","OBJECT_STORAGE_SECRET_KEY","","MOP_OBJECT_STORAGE_PREFIX","../").entrySet()){Map<String,String> values=new HashMap<>(oss);values.put(invalid.getKey(),invalid.getValue());denied(remote,values);count++;}
       System.out.println("WorkerRuntimeSettings pure JDK assertions passed: "+count+"; no component connection executed");
    }
}
