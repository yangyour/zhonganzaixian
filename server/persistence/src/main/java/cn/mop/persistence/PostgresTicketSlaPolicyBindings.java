package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.ticket.TicketLifecycleRepository.*;
import cn.mop.domain.sla.SlaClockCalculator;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Package-private consumer of already-authorized ticket transactions. Never modifies opening evidence. */
final class PostgresTicketSlaPolicyBindings {
    static final String KEY="sla.reopen_and_mttr_policy";
    private final ObjectMapper mapper;
    PostgresTicketSlaPolicyBindings(ObjectMapper mapper){this.mapper=mapper;}
    PolicyBinding view(Connection c,Access a,Entry e)throws SQLException{
        JsonNode opening=decode(e.openingSnapshotJson());String frozen=policy(opening.path("sla_opening").path("configuration_inputs").path(KEY).path("value"));
        if(frozen!=null)return result(e,"OPENING_FROZEN",null,null,frozen,null,"opening:"+opening.path("configuration_release_ref").asText(),null);
        if(!opening.path("sla_opening").isObject())return result(e,"CONFIG_PENDING",null,null,null,null,null,"OPENING_SNAPSHOT_MISSING");
        if(!schemaPresent(c))return result(e,"CONFIG_PENDING",null,null,null,null,null,"V35_REQUIRED");
        try(var s=statement(c,"SELECT b.*,r.value_json,r.tenant_id AS release_tenant,r.configuration_key,r.version AS source_version FROM public.mop_ticket_sla_policy_binding b JOIN public.mop_runtime_configuration_release r ON r.id=b.release_id WHERE b.tenant_id=?::uuid AND b.ticket_id=?::uuid")){
            s.setString(1,a.tenantId());s.setString(2,e.ticketId());try(var r=s.executeQuery()){
                if(r.next()){
                    JsonNode saved=decode(r.getString("policy_json"));String code=policy(saved);
                    if(!a.tenantId().equals(r.getString("release_tenant"))||!KEY.equals(r.getString("configuration_key"))||r.getLong("source_version")!=r.getLong("release_version")||r.getLong("lifecycle_version")>e.snapshot().version()||code==null||!code.equals(r.getString("clock_policy"))||!saved.equals(decode(r.getString("value_json"))))throw unavailable();
                    String id=r.getString("release_id");long version=r.getLong("release_version");return result(e,"BOUND",id,version,code,time(r,"created_at"),"binding:"+e.ticketId()+":"+id+":"+version,null);
                }
            }
        }
        Publication publication=current(c,a.tenantId(),now(c));
        if(publication==null)return result(e,"CONFIG_PENDING",null,null,null,null,null,"ACTIVE_Q27_PUBLICATION_REQUIRED");
        String code=policy(publication.value());if(code==null)return result(e,"CONFIG_PENDING",null,null,null,null,null,"CLOCK_POLICY_MISSING_OR_UNSUPPORTED");
        return result(e,"AVAILABLE",publication.id(),publication.version(),code,null,null,null);
    }
    PolicyBinding bind(Connection c,Access a,Entry e,String release,long version)throws SQLException{
        // Caller holds ticket lock. Match publication writer's lock before observing its active value.
        try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"mop.runtime.configuration|"+a.tenantId()+"|"+KEY);s.execute();}
        PolicyBinding candidate=view(c,a,e);
        if(!candidate.status().equals("AVAILABLE"))throw new DomainException(422,"STATE_NOT_ALLOWED","已有冻结口径不能覆盖，或缺少可绑定配置",List.of(Map.of("reason",candidate.missing()==null?candidate.status():candidate.missing())));
        if(!release.equals(candidate.releaseId())||version!=candidate.releaseVersion())throw new DomainException(409,"CONFIG_RELEASE_CONFLICT","当前生效配置版本已变化，请重新核对");
        Instant observed=now(c);Publication publication=current(c,a.tenantId(),observed);
        if(publication==null||!release.equals(publication.id())||version!=publication.version())throw new DomainException(409,"CONFIG_RELEASE_CONFLICT","配置已到期或变化，请重新读取");
        String json=publication.value().toString();if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new DomainException(422,"CONFIG_PENDING","Q27配置超过绑定技术预算");
        try(var s=statement(c,"INSERT INTO public.mop_ticket_sla_policy_binding(tenant_id,ticket_id,lifecycle_version,release_id,release_version,policy_json,clock_policy,actor_id,actor_permission_version,created_at) VALUES (?::uuid,?::uuid,?,?::uuid,?,?::jsonb,?,?::uuid,?,?)")){
            s.setString(1,a.tenantId());s.setString(2,e.ticketId());s.setLong(3,e.snapshot().version());s.setString(4,release);s.setLong(5,version);s.setString(6,json);s.setString(7,candidate.clockPolicy());s.setString(8,a.actorId());s.setLong(9,a.permissionVersion());s.setObject(10,observed.atOffset(ZoneOffset.UTC));s.executeUpdate();
        }
        return view(c,a,e);
    }
    private record Publication(String id,long version,JsonNode value){}
    private Publication current(Connection c,String tenant,Instant observed)throws SQLException{
        try(var s=statement(c,"SELECT id,version,action,value_json,expires_at FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND configuration_key=? AND effective_at<=? ORDER BY version DESC LIMIT 1")){
            s.setString(1,tenant);s.setString(2,KEY);s.setObject(3,observed.atOffset(ZoneOffset.UTC));try(var r=s.executeQuery()){
                if(!r.next()||"DISABLE".equals(r.getString("action"))||time(r,"expires_at")!=null&&!observed.isBefore(time(r,"expires_at")))return null;
                return new Publication(r.getString("id"),r.getLong("version"),decode(r.getString("value_json")));
            }
        }
    }
    static String policy(JsonNode value){if(value==null||!value.isObject()||!value.path("clock_policy").isTextual())return null;String code=value.get("clock_policy").textValue();try{return SlaClockCalculator.ReopenPolicy.valueOf(code).name();}catch(IllegalArgumentException invalid){return null;}}
    private static PolicyBinding result(Entry e,String status,String id,Long version,String policy,Instant at,String ref,String missing){return new PolicyBinding(e.snapshot().tenantId(),e.ticketId(),e.snapshot().version(),status,id,version,policy,at,ref,missing);}
    private static boolean schemaPresent(Connection c)throws SQLException{try(var s=statement(c,"SELECT to_regclass('public.mop_ticket_sla_policy_binding') IS NOT NULL");var r=s.executeQuery()){r.next();return r.getBoolean(1);}}
    private JsonNode decode(String json){try{return mapper.readTree(json);}catch(Exception invalid){throw unavailable();}}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static Instant time(ResultSet r,String key)throws SQLException{var v=r.getObject(key,OffsetDateTime.class);return v==null?null:v.toInstant();}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","SLA口径绑定记录或依赖不可用");}
}
