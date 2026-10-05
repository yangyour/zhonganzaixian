package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.ticketcomment.*;
import cn.mop.domain.ticketcomment.TicketCommentRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Real reporter-visible immutable comments; no internal identity projection, workflow transition or provider call. */
public final class PostgresTicketCommentRepository implements TicketCommentRepository {
    private static final System.Logger LOG=System.getLogger(PostgresTicketCommentRepository.class.getName());
    private static final String SELECT="SELECT id::text,tenant_id::text,ticket_id::text,observed_ticket_version,author_kind,comment_text,comment_sha256,created_at,visibility,record_source,verification_status,ticket_state_changed,sla_changed,itsm_applied,itsm_sync_status FROM public.mop_ticket_comment";
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresTicketCommentRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @FunctionalInterface private interface Read<T>{T run(Connection c,long parentVersion)throws SQLException;}
    private <T>T read(Access access,String ticket,Read<T> action){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{Membership member=authorize(c,access,false);long parent=ticket(c,access,member,ticket,false);T value=action.run(c,parent);c.commit();return value;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public List<Entry> list(Access a,String ticket,int limit,Instant beforeCreated,String beforeId){
        if(limit<1||limit>101||(beforeCreated==null)!=(beforeId==null))throw invalid();String parent=uuid(ticket),boundary=beforeId==null?null:uuid(beforeId);
        if(beforeCreated!=null&&(beforeCreated.getNano()%1000000!=0||beforeCreated.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||beforeCreated.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))))throw invalid();
        return read(a,parent,(c,parentVersion)->{List<Entry> result=new ArrayList<>();
            try(var s=statement(c,SELECT+" WHERE tenant_id=?::uuid AND ticket_id=?::uuid"+(beforeCreated==null?"":" AND (created_at,id)<(?,?::uuid)")+" ORDER BY created_at DESC,id DESC LIMIT ?")){s.setString(1,a.tenantId());s.setString(2,parent);int p=3;if(beforeCreated!=null){s.setObject(p++,OffsetDateTime.ofInstant(beforeCreated,ZoneOffset.UTC));s.setString(p++,boundary);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())result.add(entry(r,parentVersion));}}return List.copyOf(result);
        });
    }
    @Override public Entry get(Access a,String ticket,String id){String parent=uuid(ticket),note=uuid(id);return read(a,parent,(c,version)->entry(c,a,parent,note,version));}
    @Override public void authorizeCurrentAccess(Access a,String ticket){
        Connection c=platform.idempotentCommandConnection();String parent=uuid(ticket);
        try{Membership member=authorize(c,a,true);ticket(c,a,member,parent,true);}catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Entry create(Access a,String ticket,long expected,TicketComment note){
        Connection c=platform.idempotentCommandConnection();if(expected<1||note==null)throw invalid();String parent=uuid(ticket);
        try{
            Membership member=authorize(c,a,true);long version=ticket(c,a,member,parent,true);if(version!=expected)throw new DomainException(409,"VERSION_CONFLICT","父登记版本已变化，请刷新核对后再提交评论");String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_ticket_comment(id,tenant_id,ticket_id,actor_id,observed_ticket_version,comment_text,comment_sha256,author_kind) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,parent);s.setString(4,a.actorId());s.setLong(5,version);s.setString(6,note.text());s.setString(7,note.sha256());s.setString(8,TicketCommentAccess.authorKind(member.role()).name());s.executeUpdate();}
            platform.audit(a.actorId(),a.tenantId(),"ticket.comment.create","ticket_comment",id,"SUCCEEDED","REPORTER_VISIBLE_TEXT_ONLY_NO_TICKET_OR_SLA_TRANSITION",a.traceId());return entry(c,a,parent,id,version);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static Membership authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"ticket_comments:write":"ticket_comments:read");

        if(a==null||!validUuid(a.actorId())||!validUuid(a.tenantId())||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.user_id::text,m.tenant_id::text,m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){
            if(!r.next()||r.getLong(4)!=a.permissionVersion())throw expired();String role=r.getString(3);
            if(!Capabilities.customer(role)&&!Capabilities.internal(role))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无客户可见评论权限");
            return new Membership(r.getString(1),r.getString(2),role,r.getLong(4),true);
        }}
    }
    private static long ticket(Connection c,Access a,Membership member,String id,boolean lock)throws SQLException{
        String own=Capabilities.customer(member.role())?" AND reporter_id=?::uuid":"";
        try(var s=statement(c,"SELECT tenant_id::text,reporter_id::text,version FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid"+own+(lock?" FOR SHARE":""))){s.setString(1,a.tenantId());s.setString(2,id);if(!own.isEmpty())s.setString(3,member.userId());try(var r=s.executeQuery()){
            if(!r.next())throw missing();TicketCommentAccess.require(member,a.permissionVersion(),r.getString(1),r.getString(2));long version=r.getLong(3);if(version<1)throw corrupt();return version;
        }}
    }
    private static Entry entry(Connection c,Access a,String parent,String id,long version)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,parent);s.setString(3,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(r,version);}}
    }
    private static Entry entry(ResultSet r,long parentVersion)throws SQLException{
        if(!"REPORTER_AND_INTERNAL".equals(r.getString("visibility"))||!"MANUAL_TICKET_COMMENT".equals(r.getString("record_source"))||!"SELF_REPORTED".equals(r.getString("verification_status"))||r.getBoolean("ticket_state_changed")||r.getBoolean("sla_changed")||r.getBoolean("itsm_applied")||!"NOT_REQUESTED".equals(r.getString("itsm_sync_status")))throw corrupt();long observed=r.getLong("observed_ticket_version");if(observed<1||observed>parentVersion)throw corrupt();
        TicketComment note;try{note=new TicketComment(r.getString("comment_text"));}catch(RuntimeException malformed){throw corrupt();}if(!note.sha256().equals(r.getString("comment_sha256")))throw corrupt();TicketCommentAccess.AuthorKind kind;try{kind=TicketCommentAccess.AuthorKind.valueOf(r.getString("author_kind"));}catch(RuntimeException malformed){throw corrupt();}return new Entry(r.getString("id"),r.getString("tenant_id"),r.getString("ticket_id"),observed,kind,note,r.getObject("created_at",OffsetDateTime.class).toInstant());
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static boolean validUuid(String value){return value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
    private static String uuid(String value){if(!validUuid(value))throw invalid();return UUID.fromString(value).toString();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","客户可见评论、父版本或分页参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","客户可见评论持久事实或父版本不一致，未返回未核验结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Ticket comment PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","客户可见评论数据库或结构不可用，未取得成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,SELECT+" WHERE false")){s.executeQuery().close();}
            try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,'public.mop_ticket_comment','SELECT') AND has_table_privilege(current_user,'public.mop_ticket_comment','INSERT') AND NOT has_table_privilege(current_user,'public.mop_ticket_comment','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_ticket_comment','UPDATE') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_ticket_comment'::regclass AND a.grantee=0)");var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("TICKET_COMMENT_SCHEMA_OR_ACCESS_UNAVAILABLE");}return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("TICKET_COMMENT_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
