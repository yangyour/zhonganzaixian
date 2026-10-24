package cn.mop.adapters.monitoring;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.FetchedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;

/** One exact event on one exact host; not a poller, watermark, business event or recovery confirmation. */
public final class ZabbixMappedEventReader {
 private final UUID pool;private final Function<ZabbixEventPuller.Query,ZabbixEventPuller.Page> reader;
 public ZabbixMappedEventReader(UUID pool,ZabbixReadClient client){this(pool,new ZabbixEventPuller(pool,client)::read);}
 ZabbixMappedEventReader(UUID pool,Function<ZabbixEventPuller.Query,ZabbixEventPuller.Page> reader){this.pool=Objects.requireNonNull(pool);this.reader=Objects.requireNonNull(reader);}
 public FetchedEvent read(MonitoringObjectMapping.Key key,String eventId){
  if(key==null||!pool.equals(key.poolId())||key.objectType()!=MonitoringObjectMapping.ObjectType.HOST)throw new DomainException(422,"MAPPING_CAPTURE_HOST_REQUIRED","单次来源事件保存当前只支持明确主机映射，不推断监控项与触发器关系");
  if(!ZabbixObjectIdentityReader.numeric(key.externalId())||!ZabbixObjectIdentityReader.numeric(eventId))throw new DomainException(400,"INVALID_SOURCE_EVENT_ID","需要规范来源事件及主机ID");
  try{var query=new ZabbixEventPuller.Query(pool,eventId,eventId,0,253402300799L,1);var page=reader.apply(query);
   if(page==null||!query.equals(page.query())||page.hasMore()||page.events().size()!=1)throw missing();var event=page.events().get(0);
   if(!eventId.equals(event.eventId())||!event.hostIds().equals(List.of(key.externalId())))throw missing();
   var data=new TreeMap<String,Object>();data.put("provider","ZABBIX_7");data.put("source_event_id",event.eventId());data.put("trigger_id",event.triggerId());data.put("clock",event.clock());data.put("nanoseconds",event.nanoseconds());data.put("value",event.value());data.put("severity",event.severity());data.put("recovery_event_id",event.recoveryEventId());data.put("host_ids",event.hostIds());
   byte[] payload=new ObjectMapper().writeValueAsBytes(data);
   return new FetchedEvent(event.eventId(),event.value()==1?RawMonitoringEvent.Phase.PROBLEM:RawMonitoringEvent.Phase.RECOVERED,event.occurredAt(),key.externalId(),payload);
  }catch(DomainException denied){throw denied;}catch(Exception error){throw new DomainException(503,"MAPPING_CAPTURE_SOURCE_UNAVAILABLE","来源事件未取得完整可信协议结果，未保存或推进业务状态");}
 }
 private static DomainException missing(){return new DomainException(404,"SOURCE_EVENT_NOT_FOUND","当前主机映射范围没有唯一匹配的来源事件");}
}
