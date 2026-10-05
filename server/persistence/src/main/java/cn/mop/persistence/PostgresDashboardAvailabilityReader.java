package cn.mop.persistence;
import cn.mop.domain.Capabilities;
import cn.mop.domain.DomainException;
import cn.mop.domain.dashboard.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
/** Read-only authorization/clock projection. Missing metric adapters are explicit; PG failure is a 503, never unknown-but-successful data. */
public final class PostgresDashboardAvailabilityReader implements DashboardAvailabilityReader {
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresDashboardAvailabilityReader(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public DashboardAvailabilitySnapshot read(Access a,DashboardAvailabilitySnapshot.TrendWindow window){
        platform.requireStandaloneRead();if(a==null||a.permissionVersion()<1||a.permissionVersion()>9_007_199_254_740_991L)throw expired();if(!uuid(a.actorId())||!uuid(a.tenantId())||window==null)throw invalid();
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{
                try(var s=c.prepareStatement("SELECT m.role_code,m.permission_version,clock_timestamp()::timestamptz(3) AS observed_at FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active")){
                    s.setQueryTimeout(10);s.setString(1,a.tenantId());s.setString(2,a.actorId());
                    try(var r=s.executeQuery()){
                        if(!r.next()||r.getLong("permission_version")!=a.permissionVersion())throw expired();
                        if(!Capabilities.forRole(r.getString("role_code")).contains("dashboard:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能读取本租户运营首页");
                        OffsetDateTime observed=r.getObject("observed_at",OffsetDateTime.class);if(observed==null)throw unavailable();
                        DashboardAvailabilitySnapshot result;
                        try{result=new DashboardAvailabilitySnapshot(UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),a.permissionVersion(),observed.toInstant(),window);}catch(RuntimeException corrupt){throw unavailable();}
                        c.commit();return result;
                    }
                }
            }catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}
        }catch(SQLException e){throw unavailable();}
    }
    private static boolean uuid(String v){return v!=null&&v.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","首页范围或授权参数无效");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","首页授权或数据库时钟读取不可用；没有返回伪成功指标");}
}
