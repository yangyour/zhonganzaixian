package cn.mop.worker.sla;
import java.util.function.Function;
import java.util.regex.Pattern;
/** No credentials or business timeout defaults. SLA timing comes from frozen opening evidence and verified registry keys. */
public record SlaReminderSettings(String jdbcUrl,String databaseUser,String databasePassword,int leaseSeconds,int maxAttempts,int pollMillis){
    public static SlaReminderSettings from(Function<String,String> setting){
        if(!"remote_test".equals(setting.apply("SPRING_PROFILES_ACTIVE")))throw invalid("SPRING_PROFILES_ACTIVE");
        for(String key:new String[]{"MOP_TICKET_LIFECYCLE_ENABLED","MOP_SLA_REMINDER_ENABLED"})if(!"true".equals(setting.apply(key)))throw invalid(key);
        String url=required(setting,"SPRING_DATASOURCE_URL");var m=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test").matcher(url);
        if(!m.matches()||Integer.parseInt(m.group(1))<1||Integer.parseInt(m.group(1))>65535)throw invalid("SPRING_DATASOURCE_URL");
        String user=required(setting,"SPRING_DATASOURCE_USERNAME");if(!"mop_test_runtime".equals(user))throw invalid("SPRING_DATASOURCE_USERNAME");
        return new SlaReminderSettings(url,user,required(setting,"SPRING_DATASOURCE_PASSWORD"),number(setting,"MOP_SLA_REMINDER_LEASE_SECONDS",5,300),number(setting,"MOP_SLA_REMINDER_MAX_ATTEMPTS",1,100),number(setting,"MOP_SLA_REMINDER_POLL_MILLIS",100,60000));
    }
    private static int number(Function<String,String> settings,String key,int min,int max){String s=required(settings,key);if(!s.matches("[1-9][0-9]{0,5}"))throw invalid(key);int n=Integer.parseInt(s);if(n<min||n>max)throw invalid(key);return n;}
    private static String required(Function<String,String> settings,String key){String s=settings.apply(key);if(s==null||s.isBlank())throw invalid(key);return s;}
    private static IllegalStateException invalid(String key){return new IllegalStateException("CONFIG_PENDING: SLA reminder requires explicit valid "+key+"; values withheld");}
    @Override public String toString(){return "SlaReminderSettings[private connection values withheld]";}
}
