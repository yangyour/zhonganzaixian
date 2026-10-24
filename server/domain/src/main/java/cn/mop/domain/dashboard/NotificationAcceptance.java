package cn.mop.domain.dashboard;
import cn.mop.domain.DomainException;
import java.time.*;import java.util.*;
/** Existing provider submission records prove acceptance, not delivered-to-recipient status. */
public final class NotificationAcceptance {
 private NotificationAcceptance(){}
 public static final String VERSION="MOP_NOTIFICATION_ACCEPTANCE_V1";
 public record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{Objects.requireNonNull(tenantId);Objects.requireNonNull(actorId);if(permissionVersion<1||permissionVersion>9007199254740991L)throw invalid();}}
 public record Entry(UUID id,String channel,String status,Instant createdAt,Instant startedAt,Instant finishedAt){public Entry{
  Objects.requireNonNull(id);Objects.requireNonNull(createdAt);
  if(!Set.of("EMAIL","WECHAT_WORK").contains(channel)||!Set.of("QUEUED","PROCESSING","ACCEPTED","FAILED","UNKNOWN","CANCELLED").contains(status))throw invalid();
  if(startedAt!=null&&startedAt.isBefore(createdAt)||finishedAt!=null&&(startedAt==null||finishedAt.isBefore(startedAt)))throw invalid();
  if(status.equals("QUEUED")?(startedAt!=null||finishedAt!=null):status.equals("PROCESSING")?(startedAt==null||finishedAt!=null):(startedAt==null||finishedAt==null))throw invalid();
 }}
 public record Counts(int total,int queued,int processing,int accepted,int failed,int unknown,int cancelled){}
 public record Snapshot(UUID tenantId,UUID actorId,long permissionVersion,Instant observedAt,Instant windowStart,Counts counts,Map<String,Counts> byChannel,List<Entry> items){public Snapshot{byChannel=Map.copyOf(byChannel);items=List.copyOf(items);}}
 @FunctionalInterface public interface Reader{Snapshot read(Access access);}
 public static Snapshot evaluate(Access access,Instant observed,List<Entry> entries){
  Objects.requireNonNull(access);Objects.requireNonNull(observed);if(entries==null||entries.size()>10000)throw invalid();
  var from=observed.minusSeconds(86400);var keys=new HashSet<String>();
  for(var item:entries)if(!keys.add(item.channel()+":"+item.id())||item.createdAt().isBefore(from)||!item.createdAt().isBefore(observed)||item.startedAt()!=null&&item.startedAt().isAfter(observed)||item.finishedAt()!=null&&item.finishedAt().isAfter(observed))throw invalid();
  var ordered=entries.stream().sorted(Comparator.comparing(Entry::createdAt).reversed().thenComparing(Entry::channel).thenComparing(Entry::id)).toList();
  var byChannel=new LinkedHashMap<String,Counts>();for(var channel:List.of("EMAIL","WECHAT_WORK"))byChannel.put(channel,counts(ordered.stream().filter(e->e.channel().equals(channel)).toList()));
  return new Snapshot(access.tenantId(),access.actorId(),access.permissionVersion(),observed,from,counts(ordered),byChannel,ordered);
 }
 private static Counts counts(List<Entry> rows){var counts=new HashMap<String,Integer>();for(var row:rows)counts.merge(row.status(),1,Integer::sum);return new Counts(rows.size(),counts.getOrDefault("QUEUED",0),counts.getOrDefault("PROCESSING",0),counts.getOrDefault("ACCEPTED",0),counts.getOrDefault("FAILED",0),counts.getOrDefault("UNKNOWN",0),counts.getOrDefault("CANCELLED",0));}
 static DomainException invalid(){return new DomainException(503,"NOTIFICATION_ACCEPTANCE_INVALID","通知受理范围或来源记录无效，未返回部分统计");}
}
