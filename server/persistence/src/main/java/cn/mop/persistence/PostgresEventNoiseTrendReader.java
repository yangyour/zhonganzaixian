package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringEventPipeline.NoiseLayer;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Fixed server windows and one shared SQL aggregation for both series and all six ledger layers. */
public final class PostgresEventNoiseTrendReader implements EventNoiseTrendReader {
 private final DataSource source;private final PostgresPlatformRepository platform;
 public PostgresEventNoiseTrendReader(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
 public Snapshot read(Access a,Window window){platform.requireStandaloneRead();Objects.requireNonNull(a);Objects.requireNonNull(window);
  try(var c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{
   authorize(c,a);
   Instant observed;try(var q=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();observed=r.getObject(1,OffsetDateTime.class).toInstant();}
   var from=observed.minusSeconds(window.durationSeconds());var rows=new ArrayList<Aggregated>();
   try(var q=query(c,"SELECT floor(extract(epoch FROM (recorded_at-?::timestamptz)) / ?)::integer AS bucket,layer,count(*) AS total FROM public.mop_event_noise_record WHERE tenant_id=? AND recorded_at>=? AND recorded_at<? GROUP BY 1,2 ORDER BY 1,2")){
    q.setObject(1,from.atOffset(ZoneOffset.UTC));q.setInt(2,window.seconds);q.setObject(3,a.tenantId());q.setObject(4,from.atOffset(ZoneOffset.UTC));q.setObject(5,observed.atOffset(ZoneOffset.UTC));try(var r=q.executeQuery()){while(r.next())rows.add(new Aggregated(r.getInt(1),NoiseLayer.valueOf(r.getString(2)),r.getLong(3)));}}
   var result=EventNoiseTrendReader.assemble(a,window,observed,rows);
   if(window==Window.HOURS_24){var markers=new ArrayList<Marker>();try(var q=query(c,"SELECT n.id,n.event_id,n.recorded_at,e.rule_name,e.severity FROM public.mop_event_noise_record n JOIN public.mop_monitoring_event e ON e.tenant_id=n.tenant_id AND e.id=n.event_id WHERE n.tenant_id=? AND n.layer='RAW_INTAKE' AND n.recorded_at>=? AND n.recorded_at<? ORDER BY n.recorded_at DESC,n.id DESC LIMIT 9")){q.setObject(1,a.tenantId());q.setObject(2,from.atOffset(ZoneOffset.UTC));q.setObject(3,observed.atOffset(ZoneOffset.UTC));try(var r=q.executeQuery()){while(r.next())markers.add(new Marker(r.getObject("id",UUID.class),r.getObject("event_id",UUID.class),r.getString("rule_name"),r.getString("severity"),r.getObject("recorded_at",OffsetDateTime.class).toInstant()));}}
    if(markers.size()!=Math.min(9,result.totals().get(NoiseLayer.RAW_INTAKE)))throw unavailable();boolean truncated=markers.size()>8;if(truncated)markers.remove(8);result=new Snapshot(a,window,observed,result.buckets(),result.totals(),markers,truncated);
   }c.commit();return result;
  }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}
  catch(DomainException failure){throw failure;}catch(Exception failure){throw unavailable();}
 }
 public RecordPage records(Access a,Instant from,Instant to,RecordCursor before,int size){platform.requireStandaloneRead();if(a==null||from==null||to==null||from.getNano()%1000000!=0||to.getNano()%1000000!=0||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(1))>0||size<1||size>50||before!=null&&(before.recordedAt().isBefore(from)||!before.recordedAt().isBefore(to)))throw invalid();
  try(var c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a);Instant observed;try(var q=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();observed=r.getObject(1,OffsetDateTime.class).toInstant();}if(to.isAfter(observed)||from.isBefore(observed.minusSeconds(31L*86400)))throw invalid();
   String population="tenant_id=? AND layer='RAW_INTAKE' AND recorded_at>=? AND recorded_at<?";long total;
   try(var q=query(c,"SELECT count(*) FROM public.mop_event_noise_record WHERE "+population)){bounds(q,a,from,to);try(var r=q.executeQuery()){if(!r.next())throw unavailable();total=r.getLong(1);}}
   var items=new ArrayList<RawRecord>();try(var q=query(c,"SELECT id,event_id,recorded_at,detail FROM public.mop_event_noise_record WHERE "+population+(before==null?"":" AND (recorded_at,id)<(?,?::uuid)")+" ORDER BY recorded_at DESC,id DESC LIMIT ?")){bounds(q,a,from,to);int p=4;if(before!=null){q.setObject(p++,before.recordedAt().atOffset(ZoneOffset.UTC));q.setString(p++,before.id().toString());}q.setInt(p,size+1);try(var r=q.executeQuery()){while(r.next())items.add(new RawRecord(r.getObject("id",UUID.class),r.getObject("event_id",UUID.class),r.getObject("recorded_at",OffsetDateTime.class).toInstant(),r.getString("detail")));}}
   if(before!=null&&!items.isEmpty()){var first=items.get(0);if(first.recordedAt().isAfter(before.recordedAt())||first.recordedAt().equals(before.recordedAt())&&first.id().toString().compareTo(before.id().toString())>=0)throw unavailable();}
   if(before==null&&items.size()!=Math.min(total,size+1L))throw unavailable();RecordCursor next=null;if(items.size()>size){items.remove(size);var last=items.get(size-1);next=new RecordCursor(last.recordedAt(),last.id());}var result=new RecordPage(a,from,to,observed,size,total,items,next);c.commit();return result;
  }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}
  catch(DomainException failure){throw failure;}catch(Exception failure){throw unavailable();}
 }
 private static void bounds(PreparedStatement q,Access a,Instant from,Instant to)throws SQLException{q.setObject(1,a.tenantId());q.setObject(2,from.atOffset(ZoneOffset.UTC));q.setObject(3,to.atOffset(ZoneOffset.UTC));}
 private static void authorize(Connection c,Access a)throws SQLException{
   for(var cap:List.of("dashboard:read","tickets:read"))PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),cap);
   try(var q=query(c,"SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active AND m.permission_version=?")){q.setObject(1,a.tenantId());q.setObject(2,a.actorId());q.setLong(3,a.permissionVersion());try(var r=q.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","当前成员已失效");if(!Capabilities.internal(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","仅内部授权成员可读事件趋势");}}
 }
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","原始记录明细只接受近31天内最多1天的明确桶及成对游标");}
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(10);return q;}
 private static DomainException unavailable(){return new DomainException(503,"EVENT_NOISE_TREND_UNAVAILABLE","事件降噪日志时序无法核对，未返回伪造曲线");}
}
