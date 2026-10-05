package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.manualincident.*;
import cn.mop.domain.manualincident.ManualIncidentRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Immutable manual-source registrations; never a monitoring sample, recovery, ticket or SLA transition. */
public final class PostgresManualIncidentRepository implements ManualIncidentRepository {
    private static final System.Logger LOG=System.getLogger(PostgresManualIncidentRepository.class.getName());
    private static final String SELECT="SELECT id::text,tenant_id::text,asset_id::text,actor_id::text,observed_asset_version,asset_name_at_registration,title,severity,registered_at,source_state,registration_status,verification_status,monitor_state,disposition_state,ticket_priority,observed_at,recovered_at,manual_recovery_status,ticket_automatically_created,record_source FROM public.mop_manual_incident_registration";
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresManualIncidentRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access a,Read<T> action){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,a,false);T value=action.run(c);c.commit();return value;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"manual_incidents:write":"manual_incidents:read");

        if(a==null||!validUuid(a.actorId())||!validUuid(a.tenantId())||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){
                if(!r.next())throw expired();ManualIncidentAccess.require(new Membership(a.actorId(),a.tenantId(),r.getString(1),r.getLong(2),true),a.permissionVersion(),a.tenantId());
            }
        }
    }
    private record AssetSnapshot(String name,long version){}
    private static AssetSnapshot asset(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,"SELECT name,version FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid FOR SHARE")){
            s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){
                if(!r.next())throw missing();String name=r.getString(1);long version=r.getLong(2);
                if(name==null||name.isEmpty()||name.codePointCount(0,name.length())>100||version<1||version>9007199254740991L)throw corrupt();
                return new AssetSnapshot(name,version);
            }
        }
    }
    @Override public void authorizeCurrentAsset(Access a,String assetId){
        Connection c=platform.idempotentCommandConnection();String id=uuid(assetId);
        try{authorize(c,a,true);asset(c,a,id);}catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public List<Entry> list(Access a,int limit,Instant before,String beforeId){
        if(limit<1||limit>101||(before==null)!=(beforeId==null))throw invalid();String boundary=beforeId==null?null:uuid(beforeId);if(before!=null)validateInstant(before);
        return read(a,c->{List<Entry> result=new ArrayList<>();
            try(var s=statement(c,SELECT+" WHERE tenant_id=?::uuid"+(before==null?"":" AND (registered_at,id)<(?,?::uuid)")+" ORDER BY registered_at DESC,id DESC LIMIT ?")){
                s.setString(1,a.tenantId());int p=2;if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,boundary);}s.setInt(p,limit);
                try(var r=s.executeQuery()){while(r.next())result.add(entry(r));}
            }return List.copyOf(result);
        });
    }
    @Override public Entry get(Access a,String id){String registration=uuid(id);return read(a,c->get(c,a,registration));}
    private static Entry get(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE tenant_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(r);}}
    }
    @Override public Entry create(Access a,ManualIncident incident){
        Connection c=platform.idempotentCommandConnection();if(incident==null)throw invalid();
        try{
            authorize(c,a,true);AssetSnapshot current=asset(c,a,incident.assetId());
            if(current.version()!=incident.observedAssetVersion())throw new DomainException(409,"VERSION_CONFLICT","关联资产版本已变化，请刷新核对后重新提交手工登记");
            String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_manual_incident_registration(id,tenant_id,asset_id,actor_id,observed_asset_version,asset_name_at_registration,title,severity) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?)")){
                s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,incident.assetId());s.setString(4,a.actorId());s.setLong(5,current.version());s.setString(6,current.name());s.setString(7,incident.title());s.setString(8,incident.severity().code());s.executeUpdate();
            }
            platform.audit(a.actorId(),a.tenantId(),"manual_incident.register","manual_incident_registration",id,"SUCCEEDED","MANUAL_SELF_REPORTED_NO_RECOVERY_OR_TICKET_TRANSITION",a.traceId());
            return get(c,a,id);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static Entry entry(ResultSet r)throws SQLException{
        if(!"MANUAL".equals(r.getString("source_state"))||!"REGISTERED_ONLY".equals(r.getString("registration_status"))||!"SELF_REPORTED".equals(r.getString("verification_status"))||!"NOT_CONFIRMED".equals(r.getString("manual_recovery_status"))||!"MANUAL_INCIDENT_REGISTRATION".equals(r.getString("record_source"))||r.getBoolean("ticket_automatically_created"))throw corrupt();
        for(String field:List.of("monitor_state","disposition_state","ticket_priority","observed_at","recovered_at"))if(r.getObject(field)!=null)throw corrupt();
        try{
            ManualIncident incident=new ManualIncident(r.getString("title"),ManualIncident.Severity.fromCode(r.getString("severity")),r.getString("asset_id"),r.getLong("observed_asset_version"));
            String name=r.getString("asset_name_at_registration");if(name==null||name.isEmpty()||name.codePointCount(0,name.length())>100)throw corrupt();
            Instant time=r.getObject("registered_at",OffsetDateTime.class).toInstant();validateInstant(time);
            return new Entry(uuid(r.getString("id")),uuid(r.getString("tenant_id")),incident,name,uuid(r.getString("actor_id")),time);
        }catch(RuntimeException malformed){throw corrupt();}
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static boolean validUuid(String value){return value!=null&&value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");}
    private static String uuid(String value){if(!validUuid(value))throw invalid();return UUID.fromString(value).toString();}
    private static void validateInstant(Instant time){if(time.getNano()%1000000!=0||time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||time.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","手工登记或分页参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","手工登记持久事实不一致，未返回未核验结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Manual incident PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","手工登记数据库或结构不可用，未取得成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,SELECT+" WHERE false")){s.executeQuery().close();}
            try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,'public.mop_manual_incident_registration','SELECT') AND has_table_privilege(current_user,'public.mop_manual_incident_registration','INSERT') AND NOT has_table_privilege(current_user,'public.mop_manual_incident_registration','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'public.mop_manual_incident_registration','UPDATE') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='public.mop_manual_incident_registration'::regclass AND a.grantee=0)");var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("MANUAL_INCIDENT_SCHEMA_OR_ACCESS_UNAVAILABLE");}return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("MANUAL_INCIDENT_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
