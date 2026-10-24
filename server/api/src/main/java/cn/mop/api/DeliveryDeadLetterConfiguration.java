package cn.mop.api;
import cn.mop.domain.PlatformRepository;
import cn.mop.persistence.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration @Profile("remote_test")
public class DeliveryDeadLetterConfiguration {
 @Bean DeliveryDeadLetterService deliveryDeadLetterService(DataSource source,PlatformRepository platform,Environment env){
  if(!(platform instanceof PostgresPlatformRepository postgres))throw new IllegalStateException("Manual disposition requires the actual PostgreSQL repository");
  boolean enabled=MonitoringEnrollmentConfiguration.flag(env,"MOP_DELIVERY_DEAD_LETTER_ENABLED")&&MonitoringEnrollmentConfiguration.flag(env,"MOP_DELIVERY_JOURNAL_READ_ENABLED");
  return new DeliveryDeadLetterService(source,postgres,enabled,DeliveryJournalConfiguration.readPools(env.getProperty("MOP_DELIVERY_JOURNAL_READ_POOLS")),DeliveryJournalConfiguration.readPools(env.getProperty("MOP_DELIVERY_DEAD_LETTER_POOLS")));
 }
}
