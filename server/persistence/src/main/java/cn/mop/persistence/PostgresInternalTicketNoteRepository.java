package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.ticketnote.*;
import cn.mop.domain.ticketnote.InternalTicketNoteRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Real current-tenant internal notes. No customer projection, note mutation, ticket transition or provider call. */
public final class PostgresInternalTicketNoteRepository implements InternalTicketNoteRepository {
    private static final System.Logger LOG=System.getLogger(PostgresInternalTicketNoteRepository.class.getName());
    private static final String SELECT="SELECT id::text,tenant_id::text,ticket_id::text,actor_id::text,observed_ticket_version,note,note_sha256,created_at,visibility,record_source,verification_status,ticket_state_changed,sla_changed FROM public.mop_ticket_internal_note";
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresInternalTicketNoteRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @FunctionalInterface private interface Read<T>{T run(Connection c,long parentVersion)throws SQLException;}
    private <T>T read(Access access,String ticket,Read<T> action){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,access,false);long parent=ticket(c,access,ticket,false);T value=action.run(c,parent);c.commit();return value;}
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
    @Override public Entry create(Access a,String ticket,long expected,InternalTicketNote note){
        Connection c=platform.idempotentCommandConnection();if(expected<1||note==null)throw invalid();String parent=uuid(ticket);
        try{
            authorize(c,a,true);long version=ticket(c,a,parent,true);if(version!=expected)throw new DomainException(409,"VERSION_CONFLICT","父登记版本已变化，请刷新核对后再提交备注");String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_ticket_internal_note(id,tenant_id,ticket_id,actor_id,observed_ticket_version,note,note_sha256) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,parent);s.setString(4,a.actorId());s.setLong(5,version);s.setString(6,note.text());s.setString(7,note.sha256());s.executeUpdate();}
            platform.audit(a.actorId(),a.tenantId(),"ticket.internal_note.create","ticket_internal_note",id,"SUCCEEDED","INTERNAL_TEXT_ONLY_NO_TICKET_OR_SLA_TRANSITION",a.traceId());return entry(c,a,parent,id,version);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"ticket_notes:write_internal":"ticket_notes:read_internal");

        if(a==null||a.actorId()==null||a.tenantId()==null||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!Capabilities.internal(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","内部备注只向当前租户内部人员开放，客户不可读取或追加");}}
    }
    private static long ticket(Connection c,Access a,String id,boolean lock)throws SQLException{
        try(var s=statement(c,"SELECT version FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid"+(lock?" FOR SHARE":""))){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();long version=r.getLong(1);if(version<1)throw corrupt();return version;}}
    }
    private static Entry entry(Connection c,Access a,String parent,String id,long version)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE tenant_id=?::uuid AND ticket_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,parent);s.setString(3,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(r,version);}}
    }
    private static Entry entry(ResultSet r,long parentVersion)throws SQLException{
        if(!"INTERNAL".equals(r.getString("visibility"))||!"MANUAL_INTERNAL_NOTE".equals(r.getString("record_source"))||!"SELF_REPORTED".equals(r.getString("verification_status"))||r.getBoolean("ticket_state_changed")||r.getBoolean("sla_changed"))throw corrupt();long observed=r.getLong("observed_ticket_version");if(observed<1||observed>parentVersion)throw corrupt();
        InternalTicketNote note;try{note=new InternalTicketNote(r.getString("note"));}catch(RuntimeException malformed){throw corrupt();}if(!note.sha256().equals(r.getString("note_sha256")))throw corrupt();return new Entry(r.getString("id"),r.getString("tenant_id"),r.getString("ticket_id"),r.getString("actor_id"),observed,note,r.getObject("created_at",OffsetDateTime.class).toInstant());
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","内部备注、父版本或分页参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","内部备注持久事实或父版本不一致，未返回未核验结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Internal note PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","内部备注数据库或结构不可用，未取得成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,SELECT+" WHERE false")){s.executeQuery().close();}
            try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,'public.mop_ticket_internal_note','SELECT') AND has_table_privilege(current_user,'public.mop_ticket_internal_note','INSERT') AND NOT has_table_privilege(current_user,'public.mop_ticket_internal_note','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_ticket_internal_note','UPDATE') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_ticket_internal_note'::regclass AND a.grantee=0)");var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("INTERNAL_NOTE_SCHEMA_OR_ACCESS_UNAVAILABLE");}return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("INTERNAL_NOTE_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
