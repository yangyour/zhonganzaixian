package cn.mop.api;
import cn.mop.domain.notification.DeliveryJournalReader;
import cn.mop.persistence.PostgresDeliveryJournalReader;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.function.Function;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class DeliveryJournalConfiguration {
 @Bean DeliveryJournalReader deliveryJournalReader(DataSource source,Environment environment){return new PostgresDeliveryJournalReader(source,readPools(environment.getProperty("MOP_DELIVERY_JOURNAL_READ_POOLS")));}
 static Function<UUID,Set<UUID>> readPools(String raw){if(raw==null||raw.isBlank())return tenant->Set.of();try{
  if(raw.length()>65536)throw new IllegalArgumentException();var json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);var root=json.readTree(raw);if(!root.isObject()||root.size()>100)throw new IllegalArgumentException();var values=new HashMap<UUID,Set<UUID>>();var entries=root.fields();while(entries.hasNext()){
   var e=entries.next();UUID tenant=uuid(e.getKey());if(!e.getValue().isArray()||e.getValue().size()>100)throw new IllegalArgumentException();var pools=new HashSet<UUID>();for(var p:e.getValue())if(!p.isTextual()||!pools.add(uuid(p.textValue())))throw new IllegalArgumentException();values.put(tenant,Set.copyOf(pools));
  }var fixed=Map.copyOf(values);return tenant->fixed.getOrDefault(tenant,Set.of());
 }catch(Exception error){throw new IllegalStateException("Invalid explicit delivery-journal read scope; no provider authority configured");}}
 private static UUID uuid(String value){if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IllegalArgumentException();return UUID.fromString(value);}
}
