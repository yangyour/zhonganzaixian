package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.configuration.RuntimeConfigurationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class PostgresRuntimeConfigurationRepository implements RuntimeConfigurationRepository {
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final ObjectMapper mapper;
    public PostgresRuntimeConfigurationRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper){this.source=source;this.platform=platform;this.mapper=mapper;}
    private static final String SELECT="SELECT * FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND configuration_key=?";
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access access,Read<T> work){return read(access,true,work);}
    private void readAccess(Connection c,Access a,boolean management)throws SQLException{if(management)authorize(c,a,false);else PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),"assets:read");}
    private <T>T read(Access access,boolean management,Read<T> work){
        Connection command=platform.existingCommandConnection();if(command!=null)try{readAccess(command,access,management);return work.run(command);}catch(SQLException e){throw unavailable();}
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{readAccess(c,access,management);T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException e){c.rollback();throw e;}
        }catch(SQLException e){throw unavailable();}
    }
    @Override public Map<String,Object> read(Access a,String key){return read(a,c->current(c,a,key));}
    @Override public Map<String,Object> monitoringRefresh(Access a){return read(a,false,c->current(c,a,"monitor.page_refresh_sec"));}
    private Map<String,Object> current(Connection c,Access a,String key)throws SQLException{registry(c,key);Instant now=now(c);long latest=latest(c,a.tenantId(),key);Map<String,Object> selected=null;
        try(var s=c.prepareStatement(SELECT+" AND effective_at<=? ORDER BY version DESC LIMIT 1")){s.setString(1,a.tenantId());s.setString(2,key);s.setObject(3,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));try(var r=s.executeQuery()){if(r.next())selected=dto(r);}}
        String status=selected==null?(latest==0?"UNCONFIGURED":"SCHEDULED"):"DISABLE".equals(selected.get("action"))?"DISABLED":selected.get("expires_at")!=null&&!now.isBefore(Instant.parse((String)selected.get("expires_at")))?"EXPIRED":"ACTIVE";
        var result=map("tenant_id",a.tenantId(),"configuration_key",key,"environment","remote_test","scope","CURRENT_TENANT","observed_at",now.toString(),"latest_version",latest,"status",status,"effective_value",status.equals("ACTIVE")?selected.get("value"):null,"selected_release",selected,"source","postgresql","customer_confirmation_status","UNVERIFIED","production_approved",false);
        return result;
    }
    @Override public List<Map<String,Object>> history(Access a,String key,long before){return read(a,c->{registry(c,key);List<Map<String,Object>> values=new ArrayList<>();try(var s=c.prepareStatement(SELECT+" AND version<? ORDER BY version DESC LIMIT 25")){s.setString(1,a.tenantId());s.setString(2,key);s.setLong(3,before);try(var r=s.executeQuery()){while(r.next())values.add(dto(r));}}return values;});}
    @Override public Map<String,Object> release(Access a,String key,String id){return read(a,c->{try(var s=c.prepareStatement(SELECT+" AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,key);s.setString(3,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return dto(r);}}});}
    @Override public void authorizeCommand(Access a,String key){try{Connection c=platform.idempotentCommandConnection();authorize(c,a,true);registry(c,key);}catch(SQLException e){throw unavailable();}}
    @Override public Map<String,Object> publish(Access a,String key,Command command,String definitionHash){
        Connection c=platform.idempotentCommandConnection();try{
            authorize(c,a,true);var registry=registry(c,key);if("fixed_constraint".equals(registry.get("contract_value_kind")))throw new DomainException(422,"STATE_NOT_ALLOWED","合同固定约束不可发布覆盖");
            try(var s=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"mop.runtime.configuration|"+a.tenantId()+"|"+key);s.execute();}
            long previous=latest(c,a.tenantId(),key);if(previous!=command.expectedVersion())throw new DomainException(409,"VERSION_CONFLICT","发布版本已变化，请重新读取");
            if(previous>=9007199254740990L)throw new DomainException(422,"STATE_NOT_ALLOWED","发布版本达到技术上限");
            String revision=command.revisionId();Long revisionVersion=command.revisionVersion();Object value=null;
            if(command.action().equals("ROLLBACK")){
                try(var s=c.prepareStatement(SELECT+" AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,key);s.setString(3,command.targetReleaseId());try(var r=s.executeQuery()){if(!r.next())throw missing();if("DISABLE".equals(r.getString("action")))throw new DomainException(422,"STATE_NOT_ALLOWED","禁用记录不能作为值回滚来源");revision=r.getString("revision_id");revisionVersion=r.getLong("revision_version");}}
            }
            if(!command.action().equals("DISABLE")){
                try(var s=c.prepareStatement("SELECT proposal,workflow_status,version FROM public.mop_configuration_revision WHERE tenant_id=?::uuid AND configuration_key=? AND id=?::uuid FOR SHARE")){s.setString(1,a.tenantId());s.setString(2,key);s.setString(3,revision);try(var r=s.executeQuery()){if(!r.next())throw missing();if(r.getLong("version")!=revisionVersion)throw new DomainException(409,"VERSION_CONFLICT","提案版本已变化");if(!"APPROVED".equals(r.getString("workflow_status")))throw new DomainException(422,"STATE_NOT_ALLOWED","只能发布已由另一管理员审核通过的提案");value=json(r.getString("proposal")).get("proposed_value");}}
            }
            Instant now=now(c),effective=command.activation().equals("IMMEDIATE")?now:command.effectiveAt();
            if(effective==null||effective.isBefore(now)||command.expiresAt()!=null&&!command.expiresAt().isAfter(effective))throw new DomainException(400,"VALIDATION_ERROR","生效时间已过去或到期时间不晚于生效时间");
            String id=UUID.randomUUID().toString();
            try(var s=c.prepareStatement("INSERT INTO public.mop_runtime_configuration_release(id,tenant_id,configuration_key,version,action,revision_id,revision_version,target_release_id,value_json,definition_hash,effective_at,expires_at,published_by,published_permission_version,published_at,reason) VALUES(?::uuid,?::uuid,?,?,?,?::uuid,?,?::uuid,?::jsonb,?,?,?,?,?,?,?) RETURNING *")){
                s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,key);s.setLong(4,previous+1);s.setString(5,command.action());s.setString(6,revision);if(revisionVersion==null)s.setNull(7,Types.BIGINT);else s.setLong(7,revisionVersion);s.setString(8,command.targetReleaseId());s.setString(9,value==null?null:encode(value));s.setString(10,definitionHash);s.setObject(11,OffsetDateTime.ofInstant(effective,ZoneOffset.UTC));s.setObject(12,command.expiresAt()==null?null:OffsetDateTime.ofInstant(command.expiresAt(),ZoneOffset.UTC));s.setObject(13,UUID.fromString(a.actorId()));s.setLong(14,a.permissionVersion());s.setObject(15,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));s.setString(16,command.reason());
                try(var r=s.executeQuery()){if(!r.next())throw unavailable();Map<String,Object> result=dto(r);platform.audit(a.actorId(),a.tenantId(),"configuration.runtime."+command.action().toLowerCase(Locale.ROOT),"runtime_configuration_release",id,"SUCCEEDED","TEST_RUNTIME_PUBLICATION_NOT_CUSTOMER_PRODUCTION_APPROVAL",a.traceId());return result;}
            }
        }catch(SQLException e){throw unavailable();}
    }
    private void authorize(Connection c,Access a,boolean write)throws SQLException{PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),write?"configurations:write":"configurations:read");try(var s=c.prepareStatement("SELECT role_code FROM public.mop_membership WHERE tenant_id=?::uuid AND user_id=?::uuid AND active AND permission_version=?"+(write?" FOR SHARE":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setLong(3,a.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","成员关系已失效");if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前账号无运行配置权限");}}}
    private Map<String,Object> registry(Connection c,String key)throws SQLException{try(var s=c.prepareStatement("SELECT registry_json FROM public.mop_configuration WHERE key=?")){s.setString(1,key);try(var r=s.executeQuery()){if(!r.next())throw missing();return json(r.getString(1));}}}
    private long latest(Connection c,String tenant,String key)throws SQLException{try(var s=c.prepareStatement("SELECT COALESCE(max(version),0) FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND configuration_key=?")){s.setString(1,tenant);s.setString(2,key);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    private Map<String,Object> dto(ResultSet r)throws SQLException{return map("id",r.getString("id"),"tenant_id",r.getString("tenant_id"),"configuration_key",r.getString("configuration_key"),"version",r.getLong("version"),"action",r.getString("action"),"revision_id",r.getString("revision_id"),"revision_version",r.getObject("revision_version"),"target_release_id",r.getString("target_release_id"),"value",r.getString("value_json")==null?null:decode(r.getString("value_json")),"definition_hash",r.getString("definition_hash"),"environment",r.getString("environment"),"scope",r.getString("scope"),"effective_at",time(r,"effective_at"),"expires_at",time(r,"expires_at"),"published_by",r.getString("published_by"),"published_permission_version",r.getLong("published_permission_version"),"published_at",time(r,"published_at"),"reason",r.getString("reason"),"customer_confirmation_status",r.getString("customer_confirmation_status"),"production_approved",r.getBoolean("production_approved"),"source","postgresql");}
    private static Instant now(Connection c)throws SQLException{try(var s=c.prepareStatement("SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static String time(ResultSet r,String key)throws SQLException{var t=r.getObject(key,OffsetDateTime.class);return t==null?null:t.toInstant().toString();}
    private Object decode(String value){try{return mapper.readValue(value,Object.class);}catch(Exception e){throw unavailable();}}
    @SuppressWarnings("unchecked") private Map<String,Object> json(String value){return (Map<String,Object>)decode(value);}
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw unavailable();}}
    public static Map<String,Object> map(Object...pairs){var m=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);RuntimeConfigurationSchemaVerifier.verify(c);return List.of();}catch(Exception e){return List.of("RUNTIME_CONFIGURATION_SCHEMA_UNAVAILABLE");}}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","配置或版本不存在或不可见");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","运行配置结构或数据库未就绪，未返回替代值");}
}
