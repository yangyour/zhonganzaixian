package cn.mop.worker.autoticket;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AutoTicketRetrySettingsTest {
 Map<String,String> valid(){var m=new HashMap<String,String>();m.put("SPRING_PROFILES_ACTIVE","remote_test");m.put("MOP_AUTOTICKET_REGISTRATION_RETRY_ENABLED","true");m.put("MOP_AUTOTICKET_RETRY_SCOPE","REGISTRATION_ONLY");m.put("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:25432/mop_test");m.put("SPRING_DATASOURCE_USERNAME","mop_test_runtime");m.put("SPRING_DATASOURCE_PASSWORD","SYNTHETIC_TEST_VALUE");m.put("MOP_AUTOTICKET_RETRY_TENANT_ID","55000000-0000-4000-8000-000000000001");m.put("MOP_AUTOTICKET_RETRY_ACTOR_ID","55000000-0000-4000-8000-000000000002");m.put("MOP_AUTOTICKET_RETRY_BATCH_SIZE","10");m.put("MOP_AUTOTICKET_RETRY_POLL_MILLIS","5000");m.put("MOP_AUTOTICKET_RETRY_MODE","ONCE");return m;}
 @Test void explicitBoundedTestConfiguration(){var s=AutoTicketRetrySettings.from(valid()::get);assertTrue(s.once());assertEquals(10,s.batchSize());assertFalse(s.toString().contains("SYNTHETIC"));}
 @Test void everyDependencyMustBeExplicit(){for(String key:valid().keySet()){var m=valid();m.remove(key);assertThrows(IllegalStateException.class,()->AutoTicketRetrySettings.from(m::get),key);}}
 @Test void productionRemoteOrPrivilegedDatabaseCannotBeSelected(){for(var entry:Map.of("SPRING_PROFILES_ACTIVE","production","SPRING_DATASOURCE_URL","jdbc:postgresql://example.com/mop_test","SPRING_DATASOURCE_USERNAME","postgres","MOP_AUTOTICKET_RETRY_SCOPE","ACTIVATE_WORKFLOW").entrySet()){var m=valid();m.put(entry.getKey(),entry.getValue());assertThrows(IllegalStateException.class,()->AutoTicketRetrySettings.from(m::get));}}
 @Test void invalidLimitsAndSecretsAreNotEchoed(){for(String v:List.of("0","26","-1","1e2","999999999")){var m=valid();m.put("MOP_AUTOTICKET_RETRY_BATCH_SIZE",v);var e=assertThrows(IllegalStateException.class,()->AutoTicketRetrySettings.from(m::get));assertFalse(e.getMessage().contains("SYNTHETIC_TEST_VALUE"));}}
 @Test void retryIdentityHashIsStableAndVersionBound(){String a=AutoTicketRetryMain.requestHash("synthetic-attempt",2);assertTrue(a.matches("[0-9a-f]{64}"));assertEquals(a,AutoTicketRetryMain.requestHash("synthetic-attempt",2));assertNotEquals(a,AutoTicketRetryMain.requestHash("synthetic-attempt",3));}
}
