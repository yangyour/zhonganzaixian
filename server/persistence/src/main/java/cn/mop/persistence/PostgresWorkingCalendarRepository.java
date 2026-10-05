package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.calendar.*;
import cn.mop.domain.calendar.WorkingCalendarRepository.*;
import cn.mop.domain.time.IntervalSets.Interval;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Unpublished configuration drafts only. Every read authorizes in one repeatable read snapshot. */
public final class PostgresWorkingCalendarRepository implements WorkingCalendarRepository {
    private static final String SELECT="SELECT h.id::text,h.tenant_id::text,h.created_at,h.updated_at,r.* FROM public.mop_working_calendar_draft h JOIN public.mop_working_calendar_revision r ON (r.tenant_id,r.calendar_id,r.version)=(h.tenant_id,h.id,h.version)";
    private static final String CHECKER="07749cb8084dc2f0e9f2a6ed899b77be3dc4e1aeb55f15eed1bf558e48b18fb6";
    private final DataSource source;private final PostgresPlatformRepository platform;private final ObjectMapper mapper=new ObjectMapper();
    public PostgresWorkingCalendarRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access a,Read<T> action){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,a,false);T result=action.run(c);c.commit();return result;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable();}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"configurations:write":"configurations:read");

        if(a==null||!validUuid(a.actorId())||!validUuid(a.tenantId())||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无配置日历草稿权限");}
        }
    }
    @Override public void authorizeCurrentAccess(Access a,String id){
        Connection c=platform.idempotentCommandConnection();String calendar=id==null?null:uuid(id);
        try{authorize(c,a,true);if(calendar!=null)head(c,a,calendar,false);}catch(SQLException failure){throw unavailable();}
    }
    @Override public List<Snapshot> list(Access a,int limit,Instant beforeCreated,String beforeId){
        if(limit<1||limit>101||(beforeCreated==null)!=(beforeId==null))throw invalid();
        String boundary=beforeId==null?null:uuid(beforeId);if(beforeCreated!=null)instant(beforeCreated);
        return read(a,c->{List<Snapshot> result=new ArrayList<>();
            try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid"+(beforeCreated==null?"":" AND (h.created_at,h.id)<(?,?::uuid)")+" ORDER BY h.created_at DESC,h.id DESC LIMIT ?")){
                s.setString(1,a.tenantId());int p=2;if(beforeCreated!=null){s.setObject(p++,utc(beforeCreated));s.setString(p++,boundary);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())result.add(snapshot(r));}
            }return List.copyOf(result);
        });
    }
    @Override public Snapshot get(Access a,String id){String calendar=uuid(id);return read(a,c->get(c,a,calendar));}
    private Snapshot get(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return snapshot(r);}}
    }
    @Override public List<Revision> history(Access a,String id,int limit,Long beforeVersion){
        String calendar=uuid(id);if(limit<1||limit>101||beforeVersion!=null&&beforeVersion<1)throw invalid();
        return read(a,c->{head(c,a,calendar,false);List<Revision> result=new ArrayList<>();
            try(var s=statement(c,"SELECT * FROM public.mop_working_calendar_revision WHERE tenant_id=?::uuid AND calendar_id=?::uuid"+(beforeVersion==null?"":" AND version<?")+" ORDER BY version DESC LIMIT ?")){
                s.setString(1,a.tenantId());s.setString(2,calendar);int p=3;if(beforeVersion!=null)s.setLong(p++,beforeVersion);s.setInt(p,limit);
                // Materialize after values before a second query on the same connection.
                List<Revision> partial=new ArrayList<>();try(var r=s.executeQuery()){while(r.next()){long version=r.getLong("version");partial.add(new Revision(calendar,a.tenantId(),version,null,draft(r),r.getString("actor_id"),time(r,"recorded_at")));}}
                for(Revision value:partial){WorkingCalendarDraft before=null;if(value.version()>1)try(var previous=statement(c,"SELECT * FROM public.mop_working_calendar_revision WHERE tenant_id=?::uuid AND calendar_id=?::uuid AND version=?")){previous.setString(1,a.tenantId());previous.setString(2,calendar);previous.setLong(3,value.version()-1);try(var r=previous.executeQuery()){if(!r.next())throw unavailable();before=draft(r);}}
                    result.add(new Revision(calendar,a.tenantId(),value.version(),before,value.after(),value.actorId(),value.recordedAt()));
                }
            }return List.copyOf(result);
        });
    }
    @Override public Snapshot create(Access a,WorkingCalendarDraft draft){
        Connection c=platform.idempotentCommandConnection();if(draft==null)throw invalid();
        try{authorize(c,a,true);String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_working_calendar_draft(id,tenant_id,version) VALUES (?::uuid,?::uuid,1)")){s.setString(1,id);s.setString(2,a.tenantId());s.executeUpdate();}
            revision(c,a,id,1,draft);audit(a,id,"create");return get(c,a,id);
        }catch(SQLException failure){throw unavailable();}
    }
    @Override public Snapshot update(Access a,String id,long expected,WorkingCalendarDraft draft){
        Connection c=platform.idempotentCommandConnection();String calendar=uuid(id);if(expected<1||expected==Long.MAX_VALUE||draft==null)throw invalid();
        try{authorize(c,a,true);long version=head(c,a,calendar,true);if(version!=expected)throw new DomainException(409,"VERSION_CONFLICT","日历草稿版本已变化，请刷新核对后提交");
            revision(c,a,calendar,version+1,draft);
            try(var s=statement(c,"UPDATE public.mop_working_calendar_draft SET version=version+1,updated_at=GREATEST(updated_at,statement_timestamp()) WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")){s.setString(1,a.tenantId());s.setString(2,calendar);s.setLong(3,expected);if(s.executeUpdate()!=1)throw unavailable();}
            audit(a,calendar,"update");return get(c,a,calendar);
        }catch(SQLException failure){throw unavailable();}
    }
    private static long head(Connection c,Access a,String id,boolean lock)throws SQLException{
        try(var s=statement(c,"SELECT version FROM public.mop_working_calendar_draft WHERE tenant_id=?::uuid AND id=?::uuid"+(lock?" FOR UPDATE":""))){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();long version=r.getLong(1);if(version<1)throw unavailable();return version;}}
    }
    private void revision(Connection c,Access a,String id,long version,WorkingCalendarDraft value)throws SQLException{
        var windows=mapper.createArrayNode();for(Interval window:value.workingWindows())windows.addObject().put("start",window.start().toString()).put("end",window.end().toString());
        try(var s=statement(c,"INSERT INTO public.mop_working_calendar_revision(tenant_id,calendar_id,version,previous_version,actor_id,name,coverage_start,coverage_end,working_windows,coverage_complete,note) VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?,?,?::jsonb,?,?)")){
            s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setLong(4,version-1);s.setString(5,a.actorId());s.setString(6,value.name());s.setObject(7,utc(value.coverage().start()));s.setObject(8,utc(value.coverage().end()));s.setString(9,windows.toString());s.setBoolean(10,value.coverageComplete());s.setString(11,value.note());s.executeUpdate();
        }
    }
    private void audit(Access a,String id,String action){platform.audit(a.actorId(),a.tenantId(),"working_calendar.draft."+action,"working_calendar_draft",id,"SUCCEEDED","UNPUBLISHED_DRAFT_NO_EFFECTIVE_SLA",a.traceId());}
    private Snapshot snapshot(ResultSet r)throws SQLException{return new Snapshot(r.getString("id"),r.getString("tenant_id"),r.getLong("version"),draft(r),r.getString("actor_id"),time(r,"created_at"),time(r,"updated_at"));}
    private WorkingCalendarDraft draft(ResultSet r)throws SQLException{
        if(!"DRAFT".equals(r.getString("lifecycle_status"))||!"UNVERIFIED".equals(r.getString("customer_confirmation"))||r.getBoolean("effective")||!"Asia/Shanghai".equals(r.getString("timezone"))||!"MANUAL_CALENDAR_DRAFT".equals(r.getString("record_source")))throw unavailable();
        try{JsonNode json=mapper.readTree(r.getString("working_windows"));if(!json.isArray()||json.size()>WorkingCalendarDraft.MAX_WINDOWS)throw unavailable();List<Interval> windows=new ArrayList<>();for(JsonNode item:json){if(!item.isObject()||item.size()!=2||!item.path("start").isTextual()||!item.path("end").isTextual())throw unavailable();windows.add(new Interval(Instant.parse(item.get("start").textValue()),Instant.parse(item.get("end").textValue())));}
            return new WorkingCalendarDraft(r.getString("name"),new Interval(time(r,"coverage_start"),time(r,"coverage_end")),windows,r.getBoolean("coverage_complete"),r.getString("note"));
        }catch(java.io.IOException|RuntimeException malformed){throw unavailable();}
    }
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){c.setReadOnly(true);try(var s=statement(c,SELECT+" WHERE false")){s.executeQuery().close();}
            String sql="SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND NOT p.prosecdef AND encode(sha256(convert_to(p.prosrc,'UTF8')),'hex')=? AND has_function_privilege(current_user,p.oid,'EXECUTE') FROM pg_proc p WHERE p.oid='public.mop_calendar_windows_valid(jsonb,timestamp with time zone,timestamp with time zone)'::regprocedure";
            try(var s=statement(c,sql)){s.setString(1,CHECKER);try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("WORKING_CALENDAR_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
            for(String table:List.of("mop_working_calendar_draft","mop_working_calendar_revision")){
                try(var s=statement(c,"SELECT has_table_privilege(current_user,?,'SELECT') AND has_table_privilege(current_user,?,'INSERT') AND NOT has_table_privilege(current_user,?,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=?::regclass AND a.grantee=0)")){for(int i=1;i<=4;i++)s.setString(i,"public."+table);try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("WORKING_CALENDAR_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
                try(var s=statement(c,"SELECT attname,has_column_privilege(current_user,?::regclass,attnum,'UPDATE') FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped")){s.setString(1,"public."+table);s.setString(2,"public."+table);try(var r=s.executeQuery()){while(r.next())if(r.getBoolean(2)!=("mop_working_calendar_draft".equals(table)&&Set.of("version","updated_at").contains(r.getString(1))))return List.of("WORKING_CALENDAR_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
            }return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("WORKING_CALENDAR_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static OffsetDateTime utc(Instant value){return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static Instant time(ResultSet r,String name)throws SQLException{return r.getObject(name,OffsetDateTime.class).toInstant();}
    private static boolean validUuid(String value){return value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
    private static String uuid(String value){if(!validUuid(value))throw invalid();return UUID.fromString(value).toString();}
    private static void instant(Instant value){if(value.getNano()%1000000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","日历草稿、版本或分页参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","日历草稿数据库、持久事实或结构不可用");}
}
