package cn.mop.worker.sla;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SlaReminderSettingsTest {
    private Map<String,String> valid(){var m=new HashMap<String,String>();m.put("SPRING_PROFILES_ACTIVE","remote_test");m.put("MOP_TICKET_LIFECYCLE_ENABLED","true");m.put("MOP_SLA_REMINDER_ENABLED","true");m.put("SPRING_DATASOURCE_URL","jdbc:postgresql://127.0.0.1:5432/mop_test");m.put("SPRING_DATASOURCE_USERNAME","mop_test_runtime");m.put("SPRING_DATASOURCE_PASSWORD","synthetic-only-not-credential");m.put("MOP_SLA_REMINDER_LEASE_SECONDS","30");m.put("MOP_SLA_REMINDER_MAX_ATTEMPTS","3");m.put("MOP_SLA_REMINDER_POLL_MILLIS","1000");return m;}
    @Test void allSettingsAreExplicit(){var m=valid();assertEquals(30,SlaReminderSettings.from(m::get).leaseSeconds());for(String k:m.keySet()){var copy=valid();copy.remove(k);assertThrows(IllegalStateException.class,()->SlaReminderSettings.from(copy::get),k);}}
    @Test void onlyRestrictedApprovedDestinationIsAccepted(){for(String url:List.of("jdbc:postgresql://other:5432/mop_test","jdbc:postgresql://127.0.0.1:5432/production","jdbc:postgresql://127.0.0.1:5432/mop_test?password=x","jdbc:postgresql://127.0.0.1:0/mop_test")){var m=valid();m.put("SPRING_DATASOURCE_URL",url);assertThrows(IllegalStateException.class,()->SlaReminderSettings.from(m::get));}}
    @Test void flagsProfilesAndRolesCannotBroadenScope(){for(String k:List.of("SPRING_PROFILES_ACTIVE","MOP_TICKET_LIFECYCLE_ENABLED","MOP_SLA_REMINDER_ENABLED","SPRING_DATASOURCE_USERNAME")){var m=valid();m.put(k,"unexpected");assertThrows(IllegalStateException.class,()->SlaReminderSettings.from(m::get));}}
    @Test void operationalBoundsAndStrictSyntaxAreEnforced(){for(String value:List.of("0","-1","301","5.5"," 30","999999999")){var m=valid();m.put("MOP_SLA_REMINDER_LEASE_SECONDS",value);assertThrows(IllegalStateException.class,()->SlaReminderSettings.from(m::get));}}
    @Test void privateValuesNeverAppearInSettingsString(){var m=valid();assertFalse(SlaReminderSettings.from(m::get).toString().contains(m.get("SPRING_DATASOURCE_PASSWORD")));}
    @Test void extraArgumentsCannotFallBackToPhotoWorker(){assertEquals("SLA reminder mode accepts no other arguments",assertThrows(IllegalArgumentException.class,()->cn.mop.worker.WorkerApplication.main(new String[]{"--sla-reminders","--unexpected"})).getMessage());}
}
