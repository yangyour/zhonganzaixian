package cn.mop.worker;
import java.util.*;
public final class WorkerRuntimeSettingsCheck {
    static Map<String,String> valid(){return new HashMap<>(Map.of("MOP_PHOTO_WORKER_ENABLED","true","SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:15432/mop_test","SPRING_DATASOURCE_USERNAME","mop_test_runtime","SPRING_DATASOURCE_PASSWORD","FAKE_SECRET","MOP_S3_ENDPOINT","http://127.0.0.1:19000","MOP_S3_BUCKET","mop-test-photos","MOP_S3_ACCESS_KEY","FAKE_ACCESS","MOP_S3_SECRET_KEY","FAKE_SECRET"));}
    static void denied(String[] profiles,Map<String,String> values){try{WorkerRuntimeSettings.from(profiles,values::get);throw new AssertionError("unsafe worker configuration accepted");}catch(IllegalStateException expected){if(expected.getMessage().contains("FAKE_SECRET"))throw new AssertionError("secret disclosed");}}
    public static void main(String[] args){String[] remote={"remote_test"};WorkerRuntimeSettings safe=WorkerRuntimeSettings.from(remote,valid()::get);if(safe.toString().contains("FAKE_SECRET"))throw new AssertionError("secret settings exposed");int count=1;
       denied(new String[]{"local_fixture"},valid());count++;denied(new String[]{"remote_test","local_fixture"},valid());count++;
       for(var invalid:Map.of("MOP_PHOTO_WORKER_ENABLED","false","SPRING_DATASOURCE_URL","jdbc:postgresql://example.invalid:5432/mop_test","SPRING_DATASOURCE_USERNAME","mop_test_migrator","MOP_S3_BUCKET","other-bucket","MOP_S3_ENDPOINT","http://127.0.0.1:9000").entrySet()){Map<String,String> settings=valid();settings.put(invalid.getKey(),invalid.getValue());denied(remote,settings);count++;}
       Map<String,String> absent=valid();absent.remove("MOP_S3_SECRET_KEY");denied(remote,absent);count++;
       System.out.println("WorkerRuntimeSettings pure JDK assertions passed: "+count+"; no component connection executed");
    }
}
