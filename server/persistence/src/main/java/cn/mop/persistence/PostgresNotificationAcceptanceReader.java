package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.NotificationAcceptance;
import cn.mop.domain.dashboard.NotificationAcceptance.*;
import java.sql.*;import java.time.*;import java.util.*;import javax.sql.DataSource;
/** Same-snapshot current tenant submission cohort. Test messages, addresses and content never enter the projection. */
public final class PostgresNotificationAcceptanceReader implements Reader {
 private final DataSource source;private final PostgresPlatformRepository platform;
 public PostgresNotificationAcceptanceReader(DataSource source,PostgresPlatformRepository platform){this.source=source;this.platform=platform;}
 public Snapshot read(Access access){platform.requireStandaloneRead();try(var c=source.getConnection()){
  c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
  try{
   for(var cap:List.of("dashboard:read","tickets:read","ticket_notes:read_internal"))PostgresAuthorization.require(c,access.actorId().toString(),access.tenantId().toString(),access.permissionVersion(),cap);
   try(var s=c.prepareStatement("SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.permission_version=? AND m.active AND u.active FOR SHARE OF m,u")){s.setQueryTimeout(10);s.setObject(1,access.tenantId());s.setObject(2,access.actorId());s.setLong(3,access.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","成员权限已失效");if(!Set.of("msp","padmin").contains(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能读取内部通知统计");}}
   Instant at;try(var s=c.prepareStatement("SELECT date_trunc('milliseconds',clock_timestamp()),current_user,session_user")){s.setQueryTimeout(10);try(var r=s.executeQuery()){if(!r.next()||!r.getString(2).equals("mop_test_runtime")||!r.getString(3).equals("mop_test_runtime"))throw unavailable();at=r.getObject(1,OffsetDateTime.class).toInstant();}}
   String fields="id,status,created_at,started_at,finished_at";String where=" WHERE tenant_id=? AND kind='NOTIFICATION' AND created_at>=? AND created_at<?";
   var rows=new ArrayList<Entry>();try(var s=c.prepareStatement("SELECT * FROM (SELECT 'EMAIL' AS channel,"+fields+" FROM public.mop_email_delivery"+where+" UNION ALL SELECT 'WECHAT_WORK' AS channel,"+fields+" FROM public.mop_wecom_delivery"+where+") d ORDER BY created_at DESC,channel,id LIMIT 10001")){
    s.setQueryTimeout(15);for(int i:List.of(1,4)){s.setObject(i,access.tenantId());s.setObject(i+1,OffsetDateTime.ofInstant(at.minusSeconds(86400),ZoneOffset.UTC));s.setObject(i+2,OffsetDateTime.ofInstant(at,ZoneOffset.UTC));}
    try(var r=s.executeQuery()){while(r.next()){if(rows.size()==10000)throw new DomainException(422,"NOTIFICATION_ACCEPTANCE_LIMIT","通知范围超过完整读取预算，未截断统计");rows.add(new Entry(r.getObject("id",UUID.class),r.getString("channel"),r.getString("status"),instant(r,"created_at"),instant(r,"started_at"),instant(r,"finished_at")));}}
   }
   var result=NotificationAcceptance.evaluate(access,at,rows);c.commit();return result;
  }catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}
 }catch(DomainException e){throw e;}catch(SQLException|RuntimeException e){throw unavailable();}}
 private static Instant instant(ResultSet row,String name)throws SQLException{var v=row.getObject(name,OffsetDateTime.class);return v==null?null:v.toInstant();}
 private static DomainException unavailable(){return new DomainException(503,"NOTIFICATION_ACCEPTANCE_UNAVAILABLE","通知受理来源读取失败，未返回部分统计");}
}
