package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.operations.*;
import cn.mop.domain.operations.EventOperationalMetricsReader.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Counts, complete bounded MTTA cohort and cursor page share one authorized read-only REPEATABLE READ snapshot. */
public final class PostgresEventOperationalMetricsReader implements EventOperationalMetricsReader {
 static final String CURRENT="tenant_id=?::uuid AND monitor_state='PROBLEM' AND suppression_state='NONE'";
 static final String COHORT="tenant_id=?::uuid AND problem_at>=? AND problem_at<?";
 static final String FIELDS="id,asset_id,version,rule_name,severity,monitor_state,handling_state,suppression_state,problem_at,first_ack_at,updated_at";
 private final DataSource source;private final PostgresPlatformRepository platform;
 public PostgresEventOperationalMetricsReader(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
 @Override public Snapshot read(Access access,Window window,View view,Cursor before){
  platform.requireStandaloneRead();if(access==null||!uuid(access.actorId())||!uuid(access.tenantId())||access.permissionVersion()<1||access.permissionVersion()>MAX_SAFE||window==null||view==null)throw invalid();
  try(var c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
   try{
    for(var cap:List.of("dashboard:read","tickets:read"))PostgresAuthorization.require(c,access.actorId(),access.tenantId(),access.permissionVersion(),cap);
    try(var q=query(c,"SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?")){
     q.setString(1,access.tenantId());q.setString(2,access.actorId());q.setLong(3,access.permissionVersion());try(var r=q.executeQuery()){if(!r.next())throw expired();if(!Capabilities.internal(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能读取内部事件指标");}}
    Instant observed;try(var q=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=q.executeQuery()){if(!r.next())throw unavailable();observed=r.getObject(1,OffsetDateTime.class).toInstant();}
    Instant from=observed.minus(Duration.ofHours(window.hours));var severity=new EnumMap<Severity,Long>(Severity.class);for(var level:Severity.values())severity.put(level,0L);long total=0;
    try(var q=query(c,"SELECT severity,count(*) AS total FROM public.mop_monitoring_event WHERE "+CURRENT+" GROUP BY severity")){q.setString(1,access.tenantId());try(var r=q.executeQuery()){while(r.next()){var level=Severity.valueOf(r.getString(1));long n=r.getLong(2);if(n<0||n>MAX_SAFE||severity.get(level)!=0)throw unavailable();severity.put(level,n);total=Math.addExact(total,n);}}}
    long population;try(var q=query(c,"SELECT count(*) FROM public.mop_monitoring_event WHERE "+COHORT)){cohort(q,access,from,observed);try(var r=q.executeQuery()){if(!r.next())throw unavailable();population=r.getLong(1);}}
    List<Sample> all=null;
    if(population<=OperationalLatencyStatistics.MAX_SAMPLES){all=new ArrayList<>();try(var q=query(c,"SELECT "+FIELDS+" FROM public.mop_monitoring_event WHERE "+COHORT+" ORDER BY problem_at DESC,id DESC LIMIT 10001")){
     cohort(q,access,from,observed);try(var r=q.executeQuery()){while(r.next())all.add(sample(r));}}
    }
    var mtta=EventOperationalMetrics.calculate(UUID.fromString(access.tenantId()),from,observed,population,all);
    var page=new ArrayList<Sample>();boolean current=view==View.CURRENT_PROBLEMS;
    try(var q=query(c,"SELECT "+FIELDS+" FROM public.mop_monitoring_event WHERE "+(current?CURRENT:COHORT)+(before==null?"":" AND (problem_at,id)<(?,?::uuid)")+" ORDER BY problem_at DESC,id DESC LIMIT 51")){
     int p;if(current){q.setString(1,access.tenantId());p=2;}else{cohort(q,access,from,observed);p=4;}
     if(before!=null){time(q,p++,before.problemAt());q.setString(p,before.id().toString());}
     try(var r=q.executeQuery()){while(r.next())page.add(sample(r));}
    }
    Cursor next=null;if(page.size()>50){page.remove(50);var last=page.get(49);next=new Cursor(last.problemAt(),last.id());}
    var result=new Snapshot(UUID.fromString(access.tenantId()),UUID.fromString(access.actorId()),access.permissionVersion(),observed,window,view,from,severity,total,mtta,page,next);c.commit();return result;
   }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
  }catch(DomainException failure){throw failure;}catch(Exception failure){throw unavailable();}
 }
 private static void cohort(PreparedStatement q,Access access,Instant from,Instant to)throws SQLException{q.setString(1,access.tenantId());time(q,2,from);time(q,3,to);}
 private static Sample sample(ResultSet r)throws SQLException{return new Sample(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("asset_id")),r.getLong("version"),r.getString("rule_name"),Severity.valueOf(r.getString("severity")),r.getString("monitor_state"),r.getString("handling_state"),r.getString("suppression_state"),instant(r,"problem_at"),instant(r,"first_ack_at"),instant(r,"updated_at"));}
 private static Instant instant(ResultSet r,String key)throws SQLException{var value=r.getObject(key,OffsetDateTime.class);return value==null?null:value.toInstant();}
 private static void time(PreparedStatement q,int i,Instant time)throws SQLException{q.setObject(i,OffsetDateTime.ofInstant(time,ZoneOffset.UTC));}
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var q=c.prepareStatement(sql);q.setQueryTimeout(10);return q;}
 private static boolean uuid(String s){return s!=null&&s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","事件指标范围无效");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
 private static DomainException unavailable(){return new DomainException(503,"EVENT_METRICS_UNAVAILABLE","事件投影或完整指标样本读取失败，未返回伪成功数值");}
}
