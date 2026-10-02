package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.worklog.*;
import cn.mop.domain.worklog.WorklogRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Restricted engineer-own records; no administrator override, guessed cost, default duration or ticket transition. */
public final class PostgresWorklogRepository implements WorklogRepository {
    private static final System.Logger LOG=System.getLogger(PostgresWorklogRepository.class.getName());
    private static final String SELECT="SELECT w.id::text,w.tenant_id::text,w.ticket_id::text,w.owner_id::text,w.version,w.created_at,w.updated_at,r.kind,r.started_at,r.ended_at,r.note,r.record_source,r.action,r.actor_id,r.recorded_at FROM public.mop_worklog w JOIN public.mop_worklog_revision r ON r.tenant_id=w.tenant_id AND r.worklog_id=w.id AND r.version=w.version";
    private final DataSource dataSource;
    private final PostgresPlatformRepository platform;
    public PostgresWorklogRepository(DataSource dataSource,PostgresPlatformRepository platform){this.dataSource=Objects.requireNonNull(dataSource);this.platform=Objects.requireNonNull(platform);}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access access,String ticket,Read<T> action){
        try(Connection c=dataSource.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,access,false);ticket(c,access,ticket,false);T result=action.run(c);c.commit();return result;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public List<Entry> listOwn(Access a,String ticket,int limit,Instant beforeCreated,String beforeId){
        limit(limit);if((beforeCreated==null)!=(beforeId==null))throw invalid();if(beforeId!=null)uuid(beforeId);String parent=uuid(ticket);
        return read(a,parent,c->{List<Entry> values=new ArrayList<>();String after=beforeCreated==null?"":" AND (w.created_at,w.id)<(?,?::uuid)";
            try(var s=statement(c,SELECT+" WHERE w.tenant_id::text=? AND w.ticket_id::text=? AND w.owner_id::text=?"+after+" ORDER BY w.created_at DESC,w.id DESC LIMIT ?")){
                bindScope(s,a,parent);int next=4;if(beforeCreated!=null){s.setObject(next++,utc(beforeCreated));s.setString(next++,uuid(beforeId));}s.setInt(next,limit);try(var r=s.executeQuery()){while(r.next())values.add(entry(r));}
            }return List.copyOf(values);
        });
    }
    @Override public Entry getOwn(Access a,String ticket,String id){String parent=uuid(ticket),record=uuid(id);return read(a,parent,c->entry(c,a,parent,record,false));}
    @Override public List<Revision> historyOwn(Access a,String ticket,String id,int limit,Long beforeVersion){
        limit(limit);if(beforeVersion!=null&&beforeVersion<1)throw invalid();String parent=uuid(ticket),record=uuid(id);
        return read(a,parent,c->{entry(c,a,parent,record,false);List<Revision> values=new ArrayList<>();
            try(var s=statement(c,"SELECT version,action,actor_id::text,recorded_at,kind,started_at,ended_at,note,record_source FROM public.mop_worklog_revision WHERE tenant_id::text=? AND worklog_id::text=?"+(beforeVersion==null?"":" AND version<?")+" ORDER BY version DESC LIMIT ?")){
                s.setString(1,a.tenantId());s.setString(2,record);int next=3;if(beforeVersion!=null)s.setLong(next++,beforeVersion);s.setInt(next,limit);try(var r=s.executeQuery()){while(r.next()){
                    String action=r.getString("action");if(!Set.of("CREATE","UPDATE").contains(action)||!a.actorId().equals(r.getString("actor_id")))throw corrupt();
                    values.add(new Revision(r.getLong("version"),action,r.getString("actor_id"),instant(r,"recorded_at"),facts(r)));
                }}
            }return List.copyOf(values);
        });
    }
    @Override public Entry create(Access a,String ticket,long expectedTicketVersion,ManualWorklog facts){
        Connection c=platform.idempotentCommandConnection();if(expectedTicketVersion<1||facts==null)throw invalid();String parent=uuid(ticket);
        try{
            authorize(c,a,true);if(ticket(c,a,parent,true)!=expectedTicketVersion)throw conflict();String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_worklog(id,tenant_id,ticket_id,owner_id,version) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,1)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,parent);s.setString(4,a.actorId());s.executeUpdate();}
            revision(c,a,id,1,"CREATE",facts);platform.audit(a.actorId(),a.tenantId(),"worklog.create","worklog",id,"SUCCEEDED","MANUAL_INTERVAL_COST_PENDING",a.traceId());return entry(c,a,parent,id,false);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Entry update(Access a,String ticket,String id,long expected,ManualWorklog facts){
        Connection c=platform.idempotentCommandConnection();if(expected<1||expected==Long.MAX_VALUE||facts==null)throw invalid();String parent=uuid(ticket),record=uuid(id);
        try{
            authorize(c,a,true);ticket(c,a,parent,true);Entry before=entry(c,a,parent,record,true);if(before.version()!=expected)throw conflict();
            try(var s=statement(c,"UPDATE public.mop_worklog SET version=version+1,updated_at=statement_timestamp() WHERE tenant_id::text=? AND ticket_id::text=? AND owner_id::text=? AND id::text=? AND version=?")){
                bindScope(s,a,parent);s.setString(4,record);s.setLong(5,expected);if(s.executeUpdate()!=1)throw conflict();
            }
            revision(c,a,record,expected+1,"UPDATE",facts);platform.audit(a.actorId(),a.tenantId(),"worklog.update","worklog",record,"SUCCEEDED","MANUAL_INTERVAL_HISTORY_RETAINED_COST_PENDING",a.traceId());return entry(c,a,parent,record,false);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null||a.actorId()==null||a.tenantId()==null||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id::text=? AND m.user_id::text=? AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){
                if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();
                if(!"msp".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前只开放工程师本人实填工时；团队查看和代改未获授权");
            }
        }
    }
    private static long ticket(Connection c,Access a,String ticket,boolean lock)throws SQLException{
        try(var s=statement(c,"SELECT version FROM public.mop_ticket WHERE tenant_id::text=? AND id::text=?"+(lock?" FOR SHARE":""))){s.setString(1,a.tenantId());s.setString(2,ticket);try(var r=s.executeQuery()){if(!r.next())throw missing();return r.getLong(1);}}
    }
    private static Entry entry(Connection c,Access a,String ticket,String id,boolean lock)throws SQLException{
        // Lock the stable header first. A joined FOR UPDATE can recheck an updated header against
        // an old revision snapshot after waiting, spuriously hiding the existing record.
        if(lock)try(var s=statement(c,"SELECT id FROM public.mop_worklog WHERE tenant_id::text=? AND ticket_id::text=? AND owner_id::text=? AND id::text=? FOR UPDATE")){
            bindScope(s,a,ticket);s.setString(4,id);try(var r=s.executeQuery()){if(!r.next())throw missing();}
        }
        try(var s=statement(c,SELECT+" WHERE w.tenant_id::text=? AND w.ticket_id::text=? AND w.owner_id::text=? AND w.id::text=?")){bindScope(s,a,ticket);s.setString(4,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(r);}}
    }
    private static Entry entry(ResultSet r)throws SQLException{return new Entry(r.getString("id"),r.getString("tenant_id"),r.getString("ticket_id"),r.getString("owner_id"),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at"),facts(r));}
    private static ManualWorklog facts(ResultSet r)throws SQLException{
        if(!"MANUAL_INTERVAL".equals(r.getString("record_source")))throw corrupt();
        try{return new ManualWorklog(ManualWorklog.Kind.valueOf(r.getString("kind")),instant(r,"started_at"),instant(r,"ended_at"),r.getString("note"));}catch(RuntimeException failure){throw corrupt();}
    }
    private static void revision(Connection c,Access a,String id,long version,String action,ManualWorklog facts)throws SQLException{
        try(var s=statement(c,"INSERT INTO public.mop_worklog_revision(tenant_id,worklog_id,version,actor_id,action,kind,started_at,ended_at,note) VALUES (?::uuid,?::uuid,?,?::uuid,?,?,?,?,?)")){
            s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);s.setString(4,a.actorId());s.setString(5,action);s.setString(6,facts.kind().name());s.setObject(7,utc(facts.startedAt()));s.setObject(8,utc(facts.endedAt()));s.setString(9,facts.note());s.executeUpdate();
        }
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var statement=c.prepareStatement(sql);statement.setQueryTimeout(10);return statement;}
    private static void bindScope(PreparedStatement s,Access a,String ticket)throws SQLException{s.setString(1,a.tenantId());s.setString(2,ticket);s.setString(3,a.actorId());}
    private static Instant instant(ResultSet r,String name)throws SQLException{return r.getObject(name,OffsetDateTime.class).toInstant();}
    private static OffsetDateTime utc(Instant value){return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static void limit(int limit){if(limit<1||limit>101)throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","实填工时记录参数或技术分页边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","工单或工时版本已变化，请刷新后重试");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","已存工时事实不符合当前校验协议");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Worklog PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","实填工时数据库或结构未就绪，未取得写入成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=dataSource.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,SELECT+" WHERE false")){s.executeQuery().close();}
            for(String table:List.of("mop_worklog","mop_worklog_revision")){
                try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,?,'SELECT') AND has_table_privilege(current_user,?,'INSERT') AND NOT has_table_privilege(current_user,?,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=?::regclass AND a.grantee=0)")){
                    for(int i=1;i<=4;i++)s.setString(i,"public."+table);try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("WORKLOG_SCHEMA_OR_ACCESS_UNAVAILABLE");}
                }
                Set<String> allowed=table.equals("mop_worklog")?Set.of("version","updated_at"):Set.of();
                try(var s=statement(c,"SELECT attname,has_column_privilege(current_user,attrelid,attnum,'UPDATE') FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped")){s.setString(1,"public."+table);try(var r=s.executeQuery()){while(r.next())if(r.getBoolean(2)!=allowed.contains(r.getString(1)))return List.of("WORKLOG_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
            }return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("WORKLOG_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
