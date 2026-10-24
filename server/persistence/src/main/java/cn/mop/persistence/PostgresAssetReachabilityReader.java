package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.AssetReachability.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Full bounded ledger in one authorization snapshot. No writes, no synthetic status substitution. */
public final class PostgresAssetReachabilityReader implements Reader {
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresAssetReachabilityReader(DataSource source,PostgresPlatformRepository platform){this.source=source;this.platform=platform;}
    public Inventory read(Access a){platform.requireStandaloneRead();try(var c=source.getConnection()){
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
        try{
            PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),"assets:read");
            PostgresAuthorization.require(c,a.actorId().toString(),a.tenantId().toString(),a.permissionVersion(),"dashboard:read");
            try(var s=c.prepareStatement("SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=? AND m.user_id=? AND m.active AND u.active AND m.permission_version=? FOR SHARE OF m,u")){s.setQueryTimeout(10);s.setObject(1,a.tenantId());s.setObject(2,a.actorId());s.setLong(3,a.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","成员权限已失效");}}
            Instant at;try(var s=c.prepareStatement("SELECT date_trunc('milliseconds',clock_timestamp()), current_user, session_user")){s.setQueryTimeout(10);try(var r=s.executeQuery()){if(!r.next()||!r.getString(2).equals("mop_test_runtime")||!r.getString(3).equals("mop_test_runtime"))throw unavailable();at=r.getObject(1,OffsetDateTime.class).toInstant();}}
            var assets=new ArrayList<Asset>();try(var s=c.prepareStatement("SELECT id,name,management_ip,version FROM public.mop_asset WHERE tenant_id=? ORDER BY id LIMIT 10001")){s.setQueryTimeout(10);s.setObject(1,a.tenantId());try(var r=s.executeQuery()){while(r.next()){if(assets.size()==10000)throw new DomainException(422,"ASSET_REACHABILITY_LIMIT","资产范围超过完整读取预算，未截断统计");assets.add(new Asset(UUID.fromString(r.getString(1)),r.getString(2),r.getString(3),r.getLong(4)));}}}
            var result=new Inventory(a,at,assets);c.commit();return result;
        }catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}
    }catch(DomainException e){throw e;}catch(SQLException|RuntimeException e){throw unavailable();}}
    private static DomainException unavailable(){return new DomainException(503,"ASSET_REACHABILITY_UNAVAILABLE","资产在线范围读取失败，未返回部分统计");}
}
