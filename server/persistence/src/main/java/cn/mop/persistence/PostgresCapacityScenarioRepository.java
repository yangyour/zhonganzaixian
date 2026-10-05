package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.capacity.*;
import cn.mop.domain.capacity.CapacityScenarioRepository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Real immutable proposals, recomputed against recorded formula versions on every read; no measurement fallback. */
public final class PostgresCapacityScenarioRepository implements CapacityScenarioRepository {
    private static final System.Logger LOG=System.getLogger(PostgresCapacityScenarioRepository.class.getName());
    private static final String TABLE="public.mop_capacity_scenario_draft";
    private final DataSource source;private final PostgresPlatformRepository platform;private final ObjectMapper mapper;private final ObjectReader exactJson;
    public PostgresCapacityScenarioRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);this.mapper=Objects.requireNonNull(mapper);this.exactJson=mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);}
    @Override public void authorizeCreate(Access a){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Stored create(Access a,long expectedVersion,CapacityScenarioDraft draft){
        Connection c=platform.idempotentCommandConnection();if(draft==null||expectedVersion!=0)throw invalid();try{
            authorize(c,a,true);String calculation=mapper.writeValueAsString(draft.calculation().wire()),snapshot=mapper.writeValueAsString(draft.calculation().snapshot()),id=UUID.randomUUID().toString();var reference=draft.benchmarkReference();
            try(var s=statement(c,"WITH material AS (SELECT ?::jsonb c,?::jsonb s) INSERT INTO "+TABLE+"(id,tenant_id,actor_id,label,note,calculation,calculation_sha256,snapshot,snapshot_sha256,benchmark_reference,benchmark_title,benchmark_sha256) SELECT ?::uuid,?::uuid,?::uuid,?,?,c,encode(sha256(convert_to(c::text,'UTF8')),'hex'),s,encode(sha256(convert_to(s::text,'UTF8')),'hex'),?,?,? FROM material")){
                s.setString(1,calculation);s.setString(2,snapshot);s.setString(3,id);s.setString(4,a.tenantId());s.setString(5,a.actorId());s.setString(6,draft.label());s.setString(7,draft.note());s.setString(8,reference==null?null:reference.reference());s.setString(9,reference==null?null:reference.title());s.setString(10,reference==null?null:reference.sha256());if(s.executeUpdate()!=1)throw corrupt();
            }
            Stored saved=get(c,a,id);platform.audit(a.actorId(),a.tenantId(),"capacity.scenario.create","capacity_scenario",id,"SUCCEEDED","IMMUTABLE_DRAFT_NOT_MEASURED_OR_APPROVED",a.traceId());return saved;
        }catch(JsonProcessingException failure){throw corrupt();}catch(SQLException failure){throw unavailable(failure);}
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access a,Read<T> action){try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a,false);T result=action.run(c);c.commit();return result;}catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Stored get(Access a,String id){return read(a,c->get(c,a,uuid(id)));}
    private Stored get(Connection c,Access a,String id)throws SQLException{try(var s=statement(c,select()+" WHERE tenant_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return stored(r,a);}}}
    @Override public List<Stored> list(Access a,String query,int limit,Instant before,String beforeId){
        if(query==null||query.codePointCount(0,query.length())>100||query.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)||limit<1||limit>101||(before==null)!=(beforeId==null))throw invalid();
        String cursor=beforeId==null?null:uuid(beforeId);if(before!=null&&(before.getNano()%1000000!=0||before.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||before.isAfter(Instant.parse("9999-12-31T23:59:59.999Z"))))throw invalid();
        return read(a,c->{List<Stored> result=new ArrayList<>();try(var s=statement(c,select()+" WHERE tenant_id=?::uuid AND strpos(lower(label COLLATE \"pg_catalog\".\"und-x-icu\"),lower(? COLLATE \"pg_catalog\".\"und-x-icu\"))>0"+(before==null?"":" AND (recorded_at,id)<(?::timestamptz,?::uuid)")+" ORDER BY recorded_at DESC,id DESC LIMIT ?")){int p=1;s.setString(p++,a.tenantId());s.setString(p++,query);if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,cursor);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())result.add(stored(r,a));}}return List.copyOf(result);});
    }
    static String select(){return "SELECT *,calculation_sha256=encode(sha256(convert_to(calculation::text,'UTF8')),'hex') AS calculation_digest_valid,snapshot_sha256=encode(sha256(convert_to(snapshot::text,'UTF8')),'hex') AS snapshot_digest_valid FROM "+TABLE;}
    Stored stored(ResultSet r,Access a)throws SQLException{
        try{
            if(!a.tenantId().equals(r.getString("tenant_id"))||r.getLong("version")!=1||!"DRAFT".equals(r.getString("lifecycle_status"))||!"UNVERIFIED".equals(r.getString("model_approval_status"))||r.getBoolean("effective")||r.getBoolean("measurement_verified")||r.getBoolean("poc_executed")||!"EXPLICIT_CAPACITY_DRAFT".equals(r.getString("record_source"))||!CapacityCalculation.CAPACITY_FORMULA.equals(r.getString("capacity_formula_version"))||!CapacityCalculation.BUFFER_FORMULA.equals(r.getString("buffer_formula_version"))||!r.getBoolean("calculation_digest_valid")||!r.getBoolean("snapshot_digest_valid"))throw corrupt();
            CapacityCalculation calculation=CapacityCalculation.fromWire(mapper.readValue(r.getString("calculation"),Map.class));var computed=calculation.snapshot();
            JsonNode actual=exactJson.readTree(r.getString("snapshot")),expected=exactJson.readTree(mapper.writeValueAsString(computed));if(!equalNumericJson(actual,expected))throw corrupt();
            String ref=r.getString("benchmark_reference"),title=r.getString("benchmark_title"),digest=r.getString("benchmark_sha256");if(ref==null&&(title!=null||digest!=null))throw corrupt();var benchmark=ref==null?null:new CapacityScenarioDraft.BenchmarkReference(ref,title,digest);
            var draft=new CapacityScenarioDraft(r.getString("label"),r.getString("note"),calculation,benchmark);
            return new Stored(r.getString("id"),a.tenantId(),r.getString("actor_id"),r.getObject("recorded_at",OffsetDateTime.class).toInstant(),1,draft,computed,r.getString("calculation_sha256"),r.getString("snapshot_sha256"));
        }catch(JsonProcessingException|IllegalArgumentException failure){throw corrupt();}
    }
    /** JSONB may normalize 1E+1 to10; numeric equality stays exact and never goes through double. */
    static boolean equalNumericJson(JsonNode left,JsonNode right){if(left==null||right==null)return left==right;if(left.isNumber()&&right.isNumber())return left.decimalValue().compareTo(right.decimalValue())==0;if(left.isObject()&&right.isObject()){if(left.size()!=right.size())return false;var fields=left.fields();while(fields.hasNext()){var field=fields.next();if(!right.has(field.getKey())||!equalNumericJson(field.getValue(),right.get(field.getKey())))return false;}return true;}if(left.isArray()&&right.isArray()){if(left.size()!=right.size())return false;for(int i=0;i<left.size();i++)if(!equalNumericJson(left.get(i),right.get(i)))return false;return true;}return left.equals(right);}
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"capacity_scenarios:write":"capacity_scenarios:read");

        if(a==null||a.actorId()==null||a.tenantId()==null||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id::text=? AND m.user_id::text=? AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","容量场景草稿仅向当前租户获准平台管理员开放");}}
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","容量草稿查询或明确输入不符合边界");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","容量草稿记录、公式结果或摘要不一致，未返回成功结论");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Capacity draft PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","容量场景数据库或结构未就绪，未取得成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){c.setReadOnly(true);if(!PostgresUnicodeSearch.ready(c))return List.of("CAPACITY_SCENARIO_UNICODE_SEARCH_UNAVAILABLE");try(var s=statement(c,"SELECT id,tenant_id,actor_id,version,label,note,calculation,calculation_sha256,snapshot,snapshot_sha256,capacity_formula_version,buffer_formula_version,benchmark_reference,benchmark_title,benchmark_sha256,recorded_at,lifecycle_status,model_approval_status,measurement_verified,poc_executed,effective,record_source FROM "+TABLE+" WHERE false")){s.executeQuery().close();}
            try(var s=statement(c,"SELECT c.relkind='r' AND c.relowner=(SELECT oid FROM pg_roles WHERE rolname='mop_test_migrator') AND (SELECT count(*)=22 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped) AND (SELECT count(*)=21 FROM pg_constraint x WHERE x.conrelid=c.oid AND x.contype='c' AND x.convalidated) AND NOT EXISTS(SELECT 1 FROM pg_constraint x WHERE x.conrelid=c.oid AND NOT x.convalidated) AND EXISTS(SELECT 1 FROM pg_index i JOIN pg_class idx ON idx.oid=i.indexrelid WHERE i.indrelid=c.oid AND idx.relname='mop_capacity_scenario_order' AND i.indisvalid AND i.indisready) FROM pg_class c WHERE c.oid='public.mop_capacity_scenario_draft'::regclass")){try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("CAPACITY_SCENARIO_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
            try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,'"+TABLE+"','SELECT') AND has_table_privilege(current_user,'"+TABLE+"','INSERT') AND NOT has_table_privilege(current_user,'"+TABLE+"','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,'"+TABLE+"','UPDATE') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid='"+TABLE+"'::regclass AND a.grantee=0)")){try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("CAPACITY_SCENARIO_SCHEMA_OR_ACCESS_UNAVAILABLE");}}return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("CAPACITY_SCENARIO_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
