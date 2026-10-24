package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringEnrollment.Policy;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;
import java.util.function.Function;
@Configuration @Profile("remote_test")
public class MonitoringEnrollmentConfiguration {
 @Bean MonitoringEnrollmentRepository monitoringEnrollmentRepository(PlatformRepository platform,ObjectMapper mapper,MonitoringOnboardingPlanner planner,Environment env){
  if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Monitoring enrollment requires designated PostgreSQL");
  return new PostgresMonitoringEnrollmentRepository(postgres,mapper,policy(env.getProperty("MOP_MONITORING_ENROLLMENT_APPROVAL_POLICY")),planner::catalogSha256);
 }
 @Bean MonitoringEnrollmentProbe monitoringEnrollmentProbe(Environment env){boolean enabled=flag(env,"MOP_MONITORING_ENROLLMENT_TEST_ENABLED");return new MonitoringEnrollmentProbe(enabled,env.getProperty("MOP_MONITORING_ENROLLMENT_ALLOWED_ENDPOINTS","[]"),env.getProperty("MOP_MONITORING_ENROLLMENT_ALLOWED_TARGETS","[]"),enabled?MonitoringPresetProbeService.network():t->{throw new IllegalStateException("Disabled enrollment probe");});}
 static boolean flag(Environment env,String key){String raw=env.getProperty(key,"false");if(!Set.of("true","false").contains(raw))throw new IllegalStateException("Explicit monitoring enrollment flag required");return raw.equals("true");}
 static Function<String,Policy> policy(String raw){
  if(raw==null||raw.isBlank())return tenant->new Policy(null,null,null);
  if(raw.length()>65536)throw new IllegalStateException("Monitoring enrollment policy too large");
  try{var json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);var root=json.readTree(raw);if(!root.isObject()||root.size()>100)throw new IllegalArgumentException();var out=new HashMap<String,Policy>();var fields=root.fields();while(fields.hasNext()){
   var entry=fields.next();if(!MonitoringPresetRegistry.uuid(entry.getKey()))throw new IllegalArgumentException();var p=json.treeToValue(entry.getValue(),Policy.class);String ref=p.reference()==null?null:p.reference()+":"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(entry.getValue())));out.put(entry.getKey(),new Policy(ref,p.reviewers(),p.selfApprovalAllowed()));
  }var frozen=Map.copyOf(out);return tenant->frozen.getOrDefault(tenant,new Policy(null,null,null));}catch(Exception e){throw new IllegalStateException("Invalid explicit monitoring enrollment approval policy");}
 }
}
