package cn.mop.worker.autoticket;
import java.util.function.Function;
import java.util.regex.Pattern;
/** Explicit isolated-test scope; contains no provider credentials or authorization to activate a workflow. */
public record AutoTicketRetrySettings(String jdbcUrl,String databaseUser,String databasePassword,String tenantId,String actorId,int batchSize,int pollMillis,boolean once){
 public static AutoTicketRetrySettings from(Function<String,String> get){
  if(!"remote_test".equals(get.apply("SPRING_PROFILES_ACTIVE")))throw invalid("SPRING_PROFILES_ACTIVE");
  if(!"true".equals(get.apply("MOP_AUTOTICKET_REGISTRATION_RETRY_ENABLED")))throw invalid("MOP_AUTOTICKET_REGISTRATION_RETRY_ENABLED");
  if(!"REGISTRATION_ONLY".equals(get.apply("MOP_AUTOTICKET_RETRY_SCOPE")))throw invalid("MOP_AUTOTICKET_RETRY_SCOPE");
  String url=required(get,"SPRING_DATASOURCE_URL");var match=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{1,5})/mop_test").matcher(url);
  if(!match.matches()||Integer.parseInt(match.group(1))<1||Integer.parseInt(match.group(1))>65535)throw invalid("SPRING_DATASOURCE_URL");
  String user=required(get,"SPRING_DATASOURCE_USERNAME");if(!"mop_test_runtime".equals(user))throw invalid("SPRING_DATASOURCE_USERNAME");
  String mode=required(get,"MOP_AUTOTICKET_RETRY_MODE");if(!mode.equals("ONCE")&&!mode.equals("CONTINUOUS"))throw invalid("MOP_AUTOTICKET_RETRY_MODE");
  return new AutoTicketRetrySettings(url,user,required(get,"SPRING_DATASOURCE_PASSWORD"),uuid(get,"MOP_AUTOTICKET_RETRY_TENANT_ID"),uuid(get,"MOP_AUTOTICKET_RETRY_ACTOR_ID"),number(get,"MOP_AUTOTICKET_RETRY_BATCH_SIZE",1,25),number(get,"MOP_AUTOTICKET_RETRY_POLL_MILLIS",1000,60000),mode.equals("ONCE"));
 }
 private static String required(Function<String,String> get,String key){String v=get.apply(key);if(v==null||v.isBlank())throw invalid(key);return v;}
 private static String uuid(Function<String,String> get,String key){String v=required(get,key);if(!v.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid(key);return v;}
 private static int number(Function<String,String> get,String key,int min,int max){String v=required(get,key);if(!v.matches("[1-9][0-9]{0,5}"))throw invalid(key);int n=Integer.parseInt(v);if(n<min||n>max)throw invalid(key);return n;}
 private static IllegalStateException invalid(String key){return new IllegalStateException("CONFIG_PENDING: automatic registration retry requires explicit valid "+key+"; values withheld");}
 @Override public String toString(){return "AutoTicketRetrySettings[private connection and scope values withheld]";}
}
