package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.search.*;
import cn.mop.domain.search.GlobalSearchRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Same read-only PG snapshot for active authorization and both supported sources. No descriptions, contacts, comments or credentials. */
public final class PostgresGlobalSearchRepository implements GlobalSearchRepository {
    private final DataSource source;
    private static final System.Logger LOG=System.getLogger(PostgresGlobalSearchRepository.class.getName());
    public PostgresGlobalSearchRepository(DataSource source){this.source=Objects.requireNonNull(source);}
    @Override public Snapshot search(Access access,GlobalSearchQuery query){
        if(query==null)throw new DomainException(400,"VALIDATION_ERROR","搜索词缺失");
        if(access==null||access.actorId()==null||access.tenantId()==null||access.permissionVersion()<1)throw expired();
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{
                String role=authorize(c,access);boolean own=Capabilities.customer(role);Instant observed;try(var s=c.createStatement();var r=s.executeQuery("SELECT date_trunc('milliseconds',transaction_timestamp())")){r.next();observed=r.getObject(1,OffsetDateTime.class).toInstant();}
                List<AssetHit> assets=new ArrayList<>();try(var s=c.prepareStatement("SELECT id::text,tenant_id::text,name,type,source,version FROM public.mop_asset WHERE tenant_id::text=? AND strpos(lower(name COLLATE \"pg_catalog\".\"und-x-icu\"),lower(? COLLATE \"pg_catalog\".\"und-x-icu\"))>0 ORDER BY created_at DESC,id DESC LIMIT ?")){s.setQueryTimeout(5);s.setString(1,access.tenantId());s.setString(2,query.text());s.setInt(3,GlobalSearchQuery.ASSET_LIMIT+1);try(var r=s.executeQuery()){while(r.next())assets.add(new AssetHit(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6)));}}
                List<TicketHit> tickets=new ArrayList<>();try(var s=c.prepareStatement("SELECT id::text,tenant_id::text,title,receipt_no,source,registration_status,version FROM public.mop_ticket WHERE tenant_id::text=? AND source='customer_report'"+(own?" AND reporter_id::text=?":"")+" AND (strpos(lower(title COLLATE \"pg_catalog\".\"und-x-icu\"),lower(? COLLATE \"pg_catalog\".\"und-x-icu\"))>0 OR strpos(lower(receipt_no COLLATE \"pg_catalog\".\"und-x-icu\"),lower(? COLLATE \"pg_catalog\".\"und-x-icu\"))>0) ORDER BY created_at DESC,id DESC LIMIT ?")){s.setQueryTimeout(5);int p=1;s.setString(p++,access.tenantId());if(own)s.setString(p++,access.actorId());s.setString(p++,query.text());s.setString(p++,query.text());s.setInt(p,GlobalSearchQuery.TICKET_LIMIT+1);try(var r=s.executeQuery()){while(r.next())tickets.add(new TicketHit(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7)));}}
                Snapshot result=new Snapshot(query.text(),access.tenantId(),observed,own?"OWN_REPORT_REGISTRATIONS":"CURRENT_TENANT_INTERNAL",assets.subList(0,Math.min(assets.size(),GlobalSearchQuery.ASSET_LIMIT)),assets.size()>GlobalSearchQuery.ASSET_LIMIT,tickets.subList(0,Math.min(tickets.size(),GlobalSearchQuery.TICKET_LIMIT)),tickets.size()>GlobalSearchQuery.TICKET_LIMIT);c.commit();return result;
            }catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){LOG.log(System.Logger.Level.WARNING,"Global search unavailable SQLSTATE={0}",failure.getSQLState());throw new DomainException(503,"UPSTREAM_UNAVAILABLE","当前授权搜索暂不可用，不返回伪造空结果");}
    }
    private static String authorize(Connection c,Access a)throws SQLException{try(var s=c.prepareStatement("SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id::text=? AND m.user_id::text=? AND m.active AND u.active")){s.setQueryTimeout(5);s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();String role=r.getString(1);if(!Capabilities.forRole(role).contains("search:read")||!Capabilities.forRole(role).contains("assets:read")||!Capabilities.forRole(role).contains("tickets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无分组搜索权限");return role;}}}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
