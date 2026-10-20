package cn.mop.worker.autoclose;
import java.util.function.Function;
import java.util.regex.Pattern;
/** No credentials or business timeout defaults. The latter stays in each opening snapshot. */
public record TicketAutoCloseSettings(String jdbcUrl,String databaseUser,String databasePassword,int leaseSeconds,int maxAttempts,int pollMillis){
    public static TicketAutoCloseSettings from(Function<String,String> setting){
        if(!"remote_test".equals(setting.apply("SPRING_PROFILES_ACTIVE")))throw invalid("SPRING_PROFILES_ACTIVE");
        for(String key:new String[]{"MOP_TICKET_LIFECYCLE_ENABLED","MOP_TICKET_AUTOCLOSE_ENABLED"})if(!"true".equals(setting.apply(key)))throw invalid(key);
        String url=required(setting,"SPRING_DATASOURCE_URL");var m=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test").matcher(url);
        if(!m.matches()||Integer.parseInt(m.group(1))<1||Integer.parseInt(m.group(1))>65535)throw invalid("SPRING_DATASOURCE_URL");
        String user=required(setting,"SPRING_DATASOURCE_USERNAME");if(!"mop_test_runtime".equals(user))throw invalid("SPRING_DATASOURCE_USERNAME");
        return new TicketAutoCloseSettings(url,user,required(setting,"SPRING_DATASOURCE_PASSWORD"),number(setting,"MOP_TICKET_AUTOCLOSE_LEASE_SECONDS",5,300),number(setting,"MOP_TICKET_AUTOCLOSE_MAX_ATTEMPTS",1,100),number(setting,"MOP_TICKET_AUTOCLOSE_POLL_MILLIS",100,60000));
    }
    private static int number(Function<String,String> settings,String key,int min,int max){String s=required(settings,key);if(!s.matches("[1-9][0-9]{0,5}"))throw invalid(key);int n=Integer.parseInt(s);if(n<min||n>max)throw invalid(key);return n;}
    private static String required(Function<String,String> settings,String key){String s=settings.apply(key);if(s==null||s.isBlank())throw invalid(key);return s;}
    private static IllegalStateException invalid(String key){return new IllegalStateException("CONFIG_PENDING: ticket auto-close requires explicit valid "+key+"; values withheld");}
    @Override public String toString(){return "TicketAutoCloseSettings[private connection values withheld]";}
}
