package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/** Shared test PostgreSQL only. No in-memory recovery path exists. */
public final class PostgresPlatformRepository implements PlatformRepository {
    private static final System.Logger log=System.getLogger(PostgresPlatformRepository.class.getName());
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();
    private final ThreadLocal<Boolean> idempotentCommandScope = new ThreadLocal<>();
    private final ThreadLocal<Boolean> importJobScope = new ThreadLocal<>();
    public PostgresPlatformRepository(DataSource dataSource,ObjectMapper mapper){this.dataSource=dataSource;this.mapper=mapper;}
    @Override public RepositoryMetadata metadata(){return new RepositoryMetadata("remote_test","postgresql",true,"test_oidc");}
    @Override public boolean dependencyReady(){
        try(Connection connection=dataSource.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND EXISTS(SELECT 1 FROM public.mop_tenant)")){statement.setQueryTimeout(3);try(ResultSet rows=statement.executeQuery()){return rows.next()&&rows.getBoolean(1);}}
        catch(SQLException exception){return false;}
    }
    @Override public List<String> assetReadProblems(){try(Connection c=dataSource.getConnection()){c.setReadOnly(true);return PostgresUnicodeSearch.ready(c)?List.of():List.of("ASSET_UNICODE_SEARCH_UNAVAILABLE");}catch(SQLException|RuntimeException unavailable){return List.of("ASSET_UNICODE_SEARCH_UNAVAILABLE");}}
    @Override public List<String> workingCalendarProblems(){return new PostgresWorkingCalendarRepository(dataSource,this).readinessProblems();}
    @Override public List<String> ticketCommentProblems(){return new PostgresTicketCommentRepository(dataSource,this).readinessProblems();}
    @Override public List<String> internalNoteProblems(){return new PostgresInternalTicketNoteRepository(dataSource,this).readinessProblems();}
    @Override public List<String> assetMetadataProblems(){return new PostgresManualAssetMetadataRepository(dataSource,this,mapper).readinessProblems();}
    @Override public List<String> worklogProblems(){return new PostgresWorklogRepository(dataSource,this).readinessProblems();}
    @Override public List<String> assetDependencyProblems(){return new PostgresAssetDependencyRepository(dataSource,this).readinessProblems();}
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
    private <T>T connection(SqlWork<T> work){
        Connection active=transaction.get();
        try{if(active!=null)return work.run(active);try(Connection connection=dataSource.getConnection()){return work.run(connection);}}
        catch(SQLException exception){logSqlFailure(exception);throw unavailable();}
    }
    /**
     * Standalone ticket reads authorize at the main SELECT snapshot and project that same snapshot.
     * A later revocation does not retroactively cancel this read. Existing writes keep their locks
     * and isolation. The read connection is not published to the transaction ThreadLocal,
     * so it cannot introduce membership FOR SHARE locks into a read-only transaction.
     */
    private <T>T readSnapshot(SqlWork<T> work){
        if(transaction.get()!=null)return connection(work);
        try(Connection connection=dataSource.getConnection()){
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try{T value=work.run(connection);connection.commit();return value;}
            catch(SQLException|RuntimeException|Error exception){try{connection.rollback();}catch(SQLException ignored){}throw exception;}
        }catch(SQLException exception){logSqlFailure(exception);throw unavailable();}
    }
    private <T>T inTransaction(Supplier<T> work){
        if(transaction.get()!=null)return work.get();
        try(Connection connection=dataSource.getConnection()){
            connection.setAutoCommit(false);transaction.set(connection);
            try{T value=work.get();connection.commit();return value;}
            catch(RuntimeException|Error exception){try{connection.rollback();}catch(SQLException ignored){}throw exception;}
            finally{transaction.remove();}
        }catch(SQLException exception){logSqlFailure(exception);throw unavailable();}
    }
    /** Package-private bridge for the independent asset-import repository; preserves this class's transaction ThreadLocal. */
    <T>T importJobTransaction(Supplier<T> work){return inTransaction(()->scoped(importJobScope,work));}
    /** Requires an actual idempotent command or import-job callback, not merely an unrelated audit transaction. */
    Connection importCommandConnection(){Connection active=transaction.get();if(active==null||!Boolean.TRUE.equals(idempotentCommandScope.get())&&!Boolean.TRUE.equals(importJobScope.get()))throw new IllegalStateException("Import writes require a protected command or job transaction");return active;}
    /** Scoped owned-record writes require the caller's idempotent command, never a worker or unrelated transaction. */
    Connection idempotentCommandConnection(){Connection active=transaction.get();if(active==null||!Boolean.TRUE.equals(idempotentCommandScope.get()))throw new IllegalStateException("Owned-record writes require an idempotent command transaction");return active;}
    private static <T>T scoped(ThreadLocal<Boolean> scope,Supplier<T> work){Boolean previous=scope.get();scope.set(true);try{return work.get();}finally{if(previous==null)scope.remove();else scope.set(previous);}}
    private void lock(String scope){connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){statement.setString(1,scope);statement.execute();return null;}});}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境 PostgreSQL 不可用，未受理请求");}
    private static void logSqlFailure(SQLException failure){String state=failure.getSQLState();log.log(System.Logger.Level.WARNING,"Project PostgreSQL operation failed sql_state={0} exception_type={1}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN",failure.getClass().getSimpleName());}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static FixtureUser user(ResultSet rows)throws SQLException{return new FixtureUser(rows.getString("id"),rows.getString("display_name"),rows.getString("role_code"));}
    @Override public List<FixtureUser> users(){return connection(connection->{List<FixtureUser> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT id,display_name,role_code FROM public.mop_user WHERE active ORDER BY id");ResultSet rows=statement.executeQuery()){while(rows.next())result.add(user(rows));}return result;});}
    @Override public Optional<FixtureUser> user(String id){return lookupUser("id::text=?",id,null);}
    @Override public Optional<FixtureUser> resolveIdentity(String source,String subject){return lookupUser("identity_source=? AND subject=?",source,subject);}
    private Optional<FixtureUser> lookupUser(String predicate,String first,String second){return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,display_name,role_code FROM public.mop_user WHERE active AND "+predicate)){statement.setString(1,first);if(second!=null)statement.setString(2,second);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(user(rows)):Optional.empty();}}});}
    @Override public List<Tenant> allowedTenants(String userId){return connection(connection->{List<Tenant> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT t.id,t.name FROM public.mop_tenant t JOIN public.mop_membership m ON m.tenant_id=t.id JOIN public.mop_user u ON u.id=m.user_id WHERE m.user_id::text=? AND m.active AND u.active ORDER BY t.id")){statement.setString(1,userId);try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(new Tenant(rows.getString(1),rows.getString(2)));}}return result;});}
    @Override public Optional<Membership> membership(String userId,String tenantId){return connection(connection->{
        // Row lock participates in the same write transaction: revocation cannot race a commit.
        String lock=transaction.get()!=null?" FOR SHARE OF m,u":"";
        try(PreparedStatement statement=connection.prepareStatement("SELECT m.user_id,m.tenant_id,m.role_code,m.permission_version,m.active FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.user_id::text=? AND m.tenant_id::text=? AND m.active AND u.active"+lock)){statement.setString(1,userId);statement.setString(2,tenantId);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(new Membership(rows.getString(1),rows.getString(2),rows.getString(3),rows.getLong(4),rows.getBoolean(5))):Optional.empty();}}
    });}
    @Override public Tenant tenant(String id){return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,name FROM public.mop_tenant WHERE id::text=?")){statement.setString(1,id);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw missing();return new Tenant(rows.getString(1),rows.getString(2));}}});}
    private Asset asset(ResultSet rows)throws SQLException{return new Asset(rows.getString("id"),rows.getString("tenant_id"),rows.getString("name"),rows.getString("type"),rows.getString("management_ip"),rows.getString("site_id"),rows.getString("status"),rows.getString("lifecycle_status"),rows.getString("source"),instant(rows,"sampled_at"),rows.getString("unknown_reason"),rows.getLong("version"),instant(rows,"created_at"),instant(rows,"updated_at"),rows.getString("availability"),rows.getString("sync_state"));}
    private static Instant instant(ResultSet rows,String key)throws SQLException{Timestamp timestamp=rows.getTimestamp(key);return timestamp==null?null:timestamp.toInstant();}
    @Override public List<Asset> authorizedAssets(String tenant,String actor,long permissionVersion,AssetReadQuery query){
        if(query==null)throw new DomainException(400,"VALIDATION_ERROR","资产查询条件缺失");
        return readSnapshot(c->{authorizeAssetRead(c,tenant,actor,permissionVersion);StringBuilder sql=new StringBuilder("SELECT a.* FROM public.mop_asset a WHERE a.tenant_id=?::uuid");
            if(query.type()!=null)sql.append(" AND a.type=?");if(query.status()!=null)sql.append(" AND a.status=?");if(query.siteId()!=null)sql.append(" AND a.site_id=?");if(query.query()!=null)sql.append(" AND (strpos(lower(a.name COLLATE \"pg_catalog\".\"und-x-icu\"),lower(? COLLATE \"pg_catalog\".\"und-x-icu\"))>0 OR strpos(a.management_ip,?)>0)");if(query.afterCreatedAt()!=null)sql.append(" AND (a.created_at,a.id)>(?,?::uuid)");sql.append(" ORDER BY a.created_at,a.id LIMIT ?");
            List<Asset> values=new ArrayList<>();try(var statement=c.prepareStatement(sql.toString())){statement.setQueryTimeout(10);int p=1;statement.setString(p++,tenant);if(query.type()!=null)statement.setString(p++,query.type());if(query.status()!=null)statement.setString(p++,query.status());if(query.siteId()!=null)statement.setString(p++,query.siteId());if(query.query()!=null){statement.setString(p++,query.query());statement.setString(p++,query.query());}if(query.afterCreatedAt()!=null){statement.setObject(p++,java.time.OffsetDateTime.ofInstant(query.afterCreatedAt(),java.time.ZoneOffset.UTC));statement.setString(p++,query.afterId());}statement.setInt(p,query.limit());try(var rows=statement.executeQuery()){while(rows.next())values.add(asset(rows));}}return List.copyOf(values);
        });
    }
    @Override public Optional<Asset> authorizedAsset(String tenant,String actor,long permissionVersion,String id){
        return readSnapshot(c->{authorizeAssetRead(c,tenant,actor,permissionVersion);if(id==null||!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))return Optional.empty();try(var statement=c.prepareStatement("SELECT * FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid")){statement.setQueryTimeout(10);statement.setString(1,tenant);statement.setString(2,id);try(var rows=statement.executeQuery()){return rows.next()?Optional.of(asset(rows)):Optional.empty();}}});
    }
    private static void authorizeAssetRead(Connection c,String tenant,String actor,long permissionVersion)throws SQLException{
        if(tenant==null||actor==null||permissionVersion<1)throw new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");
        try(var statement=c.prepareStatement("SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active")){statement.setQueryTimeout(10);statement.setString(1,tenant);statement.setString(2,actor);try(var rows=statement.executeQuery()){if(!rows.next()||rows.getLong(2)!=permissionVersion)throw new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");if(!Capabilities.forRole(rows.getString(1)).contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无资产读取权限");}}
    }
    @Override public List<Asset> list(String tenantId){return connection(connection->{List<Asset> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_asset WHERE tenant_id::text=? ORDER BY created_at,id")){statement.setString(1,tenantId);try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(asset(rows));}}return result;});}
    @Override public Optional<Asset> find(String tenantId,String id){return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_asset WHERE tenant_id::text=? AND id::text=?")){statement.setString(1,tenantId);statement.setString(2,id);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(asset(rows)):Optional.empty();}}});}
    @Override public boolean siteBelongsTo(String tenantId,String siteId){return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM public.mop_site WHERE tenant_id::text=? AND id=?")){statement.setString(1,tenantId);statement.setString(2,siteId);try(ResultSet rows=statement.executeQuery()){return rows.next();}}});}
    @Override public Asset insert(String tenantId,String actorId,String name,String type,String ip,String siteId){
        if(transaction.get()==null)throw new IllegalStateException("Asset writes require idempotent transaction");
        if(!siteBelongsTo(tenantId,siteId))throw missing();
        return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,status,lifecycle_status,source,unknown_reason,version,created_by,updated_by) VALUES (?::uuid,?::uuid,?,?,?,?,'unknown','DRAFT','manual','尚未接入，未取得监控采样',1,?::uuid,?::uuid) RETURNING *")){
            statement.setString(1,UUID.randomUUID().toString());statement.setString(2,tenantId);statement.setString(3,name);statement.setString(4,type);statement.setString(5,ip);statement.setString(6,siteId);statement.setString(7,actorId);statement.setString(8,actorId);
            try(ResultSet rows=statement.executeQuery()){rows.next();return asset(rows);}catch(SQLException exception){if("23505".equals(exception.getSQLState()))throw new DomainException(409,"ASSET_NAME_CONFLICT","同租户资产名称已存在");throw exception;}
        }});
    }
    @Override public Object idempotent(String actor,String tenant,String route,String key,String hash,long expectedPermissionVersion,Supplier<Object> command){return idempotentChecked(actor,tenant,route,key,hash,expectedPermissionVersion,()->{},command);}
    @Override public Object idempotentAuthorized(String actor,String tenant,String route,String key,String hash,long expectedPermissionVersion,Runnable authorize,Supplier<Object> command){
        if(expectedPermissionVersion<1)throw new DomainException(401,"SESSION_EXPIRED","资源级重放必须绑定当前权限版本");
        return idempotentChecked(actor,tenant,route,key,hash,expectedPermissionVersion,Objects.requireNonNull(authorize),command);
    }
    private Object idempotentChecked(String actor,String tenant,String route,String key,String hash,long expectedPermissionVersion,Runnable authorize,Supplier<Object> command){return inTransaction(()->{
        lock("idempotency|"+actor+"|"+tenant+"|"+route+"|"+key);
        Membership current=membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效，禁止重放或写入"));
        if(expectedPermissionVersion>0&&current.permissionVersion()!=expectedPermissionVersion)throw new DomainException(401,"SESSION_EXPIRED","权限已变化，请刷新会话");
        scoped(idempotentCommandScope,()->{authorize.run();return null;});
        Object saved=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT payload_hash,response_json FROM public.mop_idempotency WHERE actor_id::text=? AND tenant_id::text=? AND route=? AND request_key=?")){statement.setString(1,actor);statement.setString(2,tenant);statement.setString(3,route);statement.setString(4,key);try(ResultSet rows=statement.executeQuery()){if(!rows.next())return null;if(!rows.getString(1).equals(hash))throw new DomainException(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同请求内容");return json(rows.getString(2));}}});
        if(saved!=null)return saved;
        Object result=scoped(idempotentCommandScope,command);JsonNode response=mapper.valueToTree(result);rejectSessionSecrets(response);
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_idempotency(actor_id,tenant_id,route,request_key,payload_hash,response_json) VALUES (?::uuid,?::uuid,?,?,?,?::jsonb)")){statement.setString(1,actor);statement.setString(2,tenant);statement.setString(3,route);statement.setString(4,key);statement.setString(5,hash);statement.setString(6,response.toString());statement.executeUpdate();return null;}});
        return result;
    });}
    static void rejectSessionSecrets(JsonNode node){if(node.isObject())node.fields().forEachRemaining(field->{if(Set.of("csrf_token","access_token","refresh_token","id_token","password","secret").contains(field.getKey().toLowerCase(Locale.ROOT)))throw new IllegalStateException("Session secrets must not enter durable replay data");rejectSessionSecrets(field.getValue());});else if(node.isArray())node.forEach(PostgresPlatformRepository::rejectSessionSecrets);}
    private JsonNode json(String value){try{return mapper.readTree(value);}catch(Exception exception){throw new IllegalStateException("Invalid persisted JSON");}}
    private Map<String,Object> config(ResultSet rows)throws SQLException{Map<String,Object> entry=mapper.convertValue(json(rows.getString("registry_json")),LinkedHashMap.class);entry.put("registered_value",entry.get("value"));entry.put("effective_value",null);entry.put("draft_value",rows.getString("draft_json")==null?null:mapper.convertValue(json(rows.getString("draft_json")),Object.class));entry.put("version",rows.getLong("version"));entry.put("lifecycle_status",rows.getString("lifecycle_status"));entry.put("mode","remote_test");entry.put("source_registry_status","design_registry_not_runtime_config");return entry;}
    @Override public List<Map<String,Object>> configurations(){return connection(connection->{List<Map<String,Object>> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_configuration ORDER BY key");ResultSet rows=statement.executeQuery()){while(rows.next())result.add(config(rows));}return result;});}
    @Override public Map<String,Object> configuration(String key){return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_configuration WHERE key=?")){statement.setString(1,key);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw missing();return config(rows);}}});}
    @Override public Map<String,Object> draftConfiguration(String key,Object proposed,long expected){
        if(transaction.get()==null)throw new IllegalStateException("Configuration writes require idempotent transaction");
        return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_configuration WHERE key=? FOR UPDATE")){statement.setString(1,key);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw missing();Map<String,Object> item=config(rows);if("fixed_constraint".equals(item.get("contract_value_kind")))throw new DomainException(422,"STATE_NOT_ALLOWED","合同固定约束不能修改");if(rows.getLong("version")!=expected)throw new DomainException(409,"VERSION_CONFLICT","配置草稿版本已变化");}}
            try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_configuration SET draft_json=?::jsonb,version=version+1,lifecycle_status='DRAFT',updated_at=clock_timestamp() WHERE key=? AND version=? RETURNING *")){statement.setString(1,mapper.valueToTree(proposed).toString());statement.setString(2,key);statement.setLong(3,expected);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw new DomainException(409,"VERSION_CONFLICT","配置草稿版本已变化");return config(rows);}}
        });
    }
    private static final String REVISION_SELECT="SELECT r.*,c.registry_json FROM public.mop_configuration_revision r JOIN public.mop_configuration c ON c.key=r.configuration_key";
    private Map<String,Object> revision(Connection connection,ResultSet rows)throws SQLException{
        JsonNode proposal=json(rows.getString("proposal")),registry=json(rows.getString("registry_json"));
        Map<String,Object> result=new LinkedHashMap<>();String tenant=rows.getString("tenant_id"),id=rows.getString("id");
        result.put("id",id);result.put("tenant_id",tenant);result.put("configuration_key",rows.getString("configuration_key"));
        result.put("proposed_value",mapper.convertValue(proposal.get("proposed_value"),Object.class));result.put("reason",proposal.path("reason").asText());
        result.put("evidence_refs",mapper.convertValue(proposal.get("evidence_refs"),List.class));result.put("workflow_status",rows.getString("workflow_status"));result.put("version",rows.getLong("version"));
        result.put("created_by",rows.getString("created_by"));result.put("created_at",instant(rows,"created_at").toString());result.put("updated_at",instant(rows,"updated_at").toString());
        result.put("reviewed_by",rows.getString("reviewed_by"));Instant reviewed=instant(rows,"reviewed_at");result.put("reviewed_at",reviewed==null?null:reviewed.toString());result.put("review_comment",rows.getString("review_comment"));
        result.put("decision_status",registry.path("decision_status").asText());result.put("effective_value",null);result.put("confirmation_status","UNVERIFIED");result.put("source","postgresql");
        result.put("history",revisionHistory(connection,tenant,id,rows.getLong("version")));return result;
    }
    private List<Map<String,Object>> revisionHistory(Connection connection,String tenant,String id,long version)throws SQLException{
        List<Map<String,Object>> result=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT payload FROM public.mop_configuration_revision_history WHERE tenant_id::text=? AND revision_id::text=? AND revision_version<=? ORDER BY revision_version")){
            statement.setString(1,tenant);statement.setString(2,id);statement.setLong(3,version);try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(mapper.convertValue(json(rows.getString(1)),LinkedHashMap.class));}
        }return result;
    }
    @Override public List<Map<String,Object>> configurationRevisions(String tenant,String key){
        configuration(key);
        return connection(connection->{List<Map<String,Object>> result=new ArrayList<>();
            try(PreparedStatement statement=connection.prepareStatement(REVISION_SELECT+" WHERE r.tenant_id::text=? AND r.configuration_key=? ORDER BY r.created_at DESC,r.id DESC")){
                statement.setString(1,tenant);statement.setString(2,key);try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(revision(connection,rows));}
            }return result;
        });
    }
    @Override public Optional<Map<String,Object>> configurationRevision(String tenant,String id){return connection(connection->{
        try(PreparedStatement statement=connection.prepareStatement(REVISION_SELECT+" WHERE r.tenant_id::text=? AND r.id::text=?")){
            statement.setString(1,tenant);statement.setString(2,id);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(revision(connection,rows)):Optional.empty();}
        }
    });}
    private void revisionAdministrator(String tenant,String actor){
        if(transaction.get()==null)throw new IllegalStateException("Configuration revision writes require idempotent transaction");
        Membership current=membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));
        if(!"padmin".equals(current.role()))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无配置修订或审批权限");
    }
    @Override public Map<String,Object> createConfigurationRevision(String tenant,String actor,String key,Object proposed,String reason,List<Map<String,String>> evidenceRefs){
        revisionAdministrator(tenant,actor);Map<String,Object> registry=configuration(key);
        if("fixed_constraint".equals(registry.get("contract_value_kind")))throw new DomainException(422,"STATE_NOT_ALLOWED","合同固定约束不能修改");
        String id=UUID.randomUUID().toString();Map<String,Object> proposal=new LinkedHashMap<>();proposal.put("proposed_value",proposed);proposal.put("reason",reason);proposal.put("evidence_refs",evidenceRefs);
        Instant created=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_configuration_revision(id,tenant_id,configuration_key,proposal,created_by) VALUES (?::uuid,?::uuid,?,?::jsonb,?::uuid) RETURNING created_at")){
            statement.setString(1,id);statement.setString(2,tenant);statement.setString(3,key);statement.setString(4,mapper.valueToTree(proposal).toString());statement.setString(5,actor);
            try(ResultSet rows=statement.executeQuery()){rows.next();return instant(rows,"created_at");}
        }});
        appendRevisionHistory(tenant,id,1,"CREATE",actor,created,null,"DRAFT",reason);
        return configurationRevision(tenant,id).orElseThrow(PostgresPlatformRepository::missing);
    }
    @Override public Map<String,Object> transitionConfigurationRevision(String tenant,String actor,String id,long expected,String action,String comment){
        revisionAdministrator(tenant,actor);
        Map<String,Object> current=connection(connection->{try(PreparedStatement statement=connection.prepareStatement(REVISION_SELECT+" WHERE r.tenant_id::text=? AND r.id::text=? FOR UPDATE OF r")){
            statement.setString(1,tenant);statement.setString(2,id);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw missing();return revision(connection,rows);}
        }});
        if(((Number)current.get("version")).longValue()!=expected)throw new DomainException(409,"VERSION_CONFLICT","配置修订版本已变化，请刷新");
        String previous=current.get("workflow_status").toString();String next=ConfigurationRevisionWorkflow.nextStatus(previous,current.get("created_by").toString(),actor,action);
        boolean reviewed=Set.of("APPROVE","REJECT").contains(action);
        Instant updated=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_configuration_revision SET workflow_status=?,version=version+1,updated_at=clock_timestamp(),reviewed_by=?::uuid,reviewed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,review_comment=? WHERE tenant_id::text=? AND id::text=? AND version=? RETURNING updated_at")){
            statement.setString(1,next);statement.setString(2,reviewed?actor:null);statement.setBoolean(3,reviewed);statement.setString(4,reviewed?comment:null);statement.setString(5,tenant);statement.setString(6,id);statement.setLong(7,expected);
            try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw new DomainException(409,"VERSION_CONFLICT","配置修订版本已变化，请刷新");return instant(rows,"updated_at");}
        }});
        appendRevisionHistory(tenant,id,expected+1,action,actor,updated,previous,next,comment);
        return configurationRevision(tenant,id).orElseThrow(PostgresPlatformRepository::missing);
    }
    private void appendRevisionHistory(String tenant,String id,long version,String action,String actor,Instant occurred,String from,String to,String comment){
        Map<String,Object> event=new LinkedHashMap<>();event.put("action",action);event.put("actor_id",actor);event.put("occurred_at",occurred.toString());event.put("from_status",from);event.put("to_status",to);event.put("comment",comment);
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_configuration_revision_history(tenant_id,revision_id,revision_version,payload) VALUES (?::uuid,?::uuid,?,?::jsonb)")){
            statement.setString(1,tenant);statement.setString(2,id);statement.setLong(3,version);statement.setString(4,mapper.valueToTree(event).toString());statement.executeUpdate();return null;
        }});
    }
    private Membership ticketAccess(String tenant,String actor){
        Membership current=membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));
        if(!Capabilities.forRole(current.role()).contains("tickets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无工单查询权限");return current;
    }
    private static String ownTicketPredicate(Membership membership){return Capabilities.customer(membership.role())?" AND t.reporter_id::text=?":"";}
    private static final String CURRENT_TICKET_ACCESS=" AND EXISTS(SELECT 1 FROM public.mop_membership access JOIN public.mop_user u ON u.id=access.user_id WHERE access.tenant_id=t.tenant_id AND access.user_id::text=? AND access.active AND u.active AND access.permission_version=? AND access.role_code=?)";
    private static int bindTicketScope(PreparedStatement statement,String tenant,String actor,Membership membership)throws SQLException{
        statement.setString(1,tenant);int index=2;if(Capabilities.customer(membership.role()))statement.setString(index++,actor);statement.setString(index++,actor);statement.setLong(index++,membership.permissionVersion());statement.setString(index++,membership.role());return index;
    }
    private Map<String,Object> ticketProjection(Connection connection,ResultSet rows)throws SQLException{
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","tenant_id","receipt_no","title","description","source","category","impact_scope","urgency","asset_id","asset_name","reporter_id","contact_name","contact_phone","contact_binding_status","registration_status","state","external_id","external_state","sync_state","authority_source","authority_status","priority","operation_id"))result.put(key,rows.getString(key));
        result.put("response_due_at",instant(rows,"response_due_at"));result.put("recovery_due_at",instant(rows,"recovery_due_at"));result.put("version",rows.getLong("version"));result.put("created_at",instant(rows,"created_at").toString());result.put("updated_at",instant(rows,"updated_at").toString());
        List<Map<String,Object>> history=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement("SELECT payload FROM public.mop_ticket_history WHERE tenant_id::text=? AND ticket_id::text=? AND version<=? ORDER BY version")){
            statement.setString(1,rows.getString("tenant_id"));statement.setString(2,rows.getString("id"));statement.setLong(3,rows.getLong("version"));try(ResultSet events=statement.executeQuery()){while(events.next())history.add(mapper.convertValue(json(events.getString(1)),LinkedHashMap.class));}
        }result.put("history",history);List<Map<String,Object>> attachments=new ArrayList<>();
        if(photoSchemaPresent(connection))try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_file WHERE tenant_id::text=? AND ticket_id::text=? ORDER BY created_at,id")){statement.setString(1,rows.getString("tenant_id"));statement.setString(2,rows.getString("id"));try(ResultSet files=statement.executeQuery()){while(files.next())attachments.add(fileProjection(files));}}
        result.put("attachments",attachments);return result;
    }
    @Override public List<Map<String,Object>> tickets(String tenant,String actor,String category,String urgency,String query,Instant afterCreated,String afterId,int limit){
        Membership membership=ticketAccess(tenant,actor);if(limit<1||limit>101||(afterCreated==null)!=(afterId==null))throw new DomainException(400,"VALIDATION_ERROR","工单分页参数无效");
        String sql="SELECT t.* FROM public.mop_ticket t WHERE t.tenant_id::text=?"+ownTicketPredicate(membership)+CURRENT_TICKET_ACCESS+(category==null?"":" AND t.category=?")+(urgency==null?"":" AND t.urgency=?")+(query==null?"":" AND strpos(lower((t.receipt_no||' '||t.title||' '||t.description) COLLATE pg_catalog.\"und-x-icu\"),lower(? COLLATE pg_catalog.\"und-x-icu\"))>0")+(afterCreated==null?"":" AND (t.created_at,t.id)>(?::timestamptz,?::uuid)")+" ORDER BY t.created_at,t.id LIMIT ?";
        return readSnapshot(connection->{List<Map<String,Object>> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement(sql)){
            int index=bindTicketScope(statement,tenant,actor,membership);if(category!=null)statement.setString(index++,category);if(urgency!=null)statement.setString(index++,urgency);if(query!=null)statement.setString(index++,query);if(afterCreated!=null){statement.setString(index++,afterCreated.toString());statement.setString(index++,afterId);}statement.setInt(index,limit);
            try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(ticketProjection(connection,rows));}
        }return result;});
    }
    @Override public Optional<Map<String,Object>> ticket(String tenant,String actor,String id){Membership membership=ticketAccess(tenant,actor);return readSnapshot(connection->{
        try(PreparedStatement statement=connection.prepareStatement("SELECT t.* FROM public.mop_ticket t WHERE t.tenant_id::text=?"+ownTicketPredicate(membership)+CURRENT_TICKET_ACCESS+" AND t.id::text=?")){
            int index=bindTicketScope(statement,tenant,actor,membership);statement.setString(index,id);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(ticketProjection(connection,rows)):Optional.empty();}
        }
    });}
    private Map<String,Object> operationProjection(ResultSet rows)throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","tenant_id","kind","status","step","result_ref"))result.put(key,rows.getString(key));
        result.put("progress",null);String error=rows.getString("error");result.put("error",error==null?null:mapper.convertValue(json(error),LinkedHashMap.class));
        result.put("created_at",instant(rows,"created_at").toString());result.put("updated_at",instant(rows,"updated_at").toString());return result;
    }
    @Override public Optional<Map<String,Object>> operation(String tenant,String actor,String id){return inTransaction(()->{
        Membership membership=ticketAccess(tenant,actor);
        return connection(connection->{
            try(PreparedStatement statement=connection.prepareStatement("SELECT o.* FROM public.mop_operation o JOIN public.mop_ticket t ON t.tenant_id=o.tenant_id AND t.id=o.ticket_id AND t.operation_id=o.id WHERE t.tenant_id::text=?"+ownTicketPredicate(membership)+CURRENT_TICKET_ACCESS+" AND o.id::text=?")){
                int index=bindTicketScope(statement,tenant,actor,membership);statement.setString(index,id);try(ResultSet rows=statement.executeQuery()){if(rows.next())return Optional.of(operationProjection(rows));}
            }
            if(!photoSchemaPresent(connection))return Optional.empty();
            try(PreparedStatement statement=connection.prepareStatement("SELECT o.* FROM public.mop_operation o JOIN public.mop_file f ON f.tenant_id=o.tenant_id AND f.id=o.file_id AND f.operation_id=o.id WHERE f.tenant_id::text=? AND o.id::text=?"+ownFilePredicate(membership))){
                statement.setString(1,tenant);statement.setString(2,id);statement.setString(3,actor);
                try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(operationProjection(rows)):Optional.empty();}
            }
        });
    });}
    @Override public CustomerReportRegistration registerCustomerReport(String tenant,String actor,CustomerReport report){
        if(transaction.get()==null)throw new IllegalStateException("Customer report writes require idempotent transaction");Membership current=ticketAccess(tenant,actor);
        if(!Capabilities.forRole(current.role()).contains("tickets:report"))throw new DomainException(403,"ACTION_FORBIDDEN","内部角色不能代客户报障");
        if(!report.attachmentIds().isEmpty())requirePhotoSchema();
        Asset asset=report.assetId()==null?null:find(tenant,report.assetId()).orElseThrow(PostgresPlatformRepository::missing);
        if(asset!=null){
            lock("ticket-duplicate|"+tenant+"|"+actor+"|"+asset.id()+"|"+report.category());
            // The advisory lock serializes semantic registration. A plain read keeps V4
            // no-photo deduplication usable without granting its append-only table UPDATE.
            // Adding photos separately takes the ticket lock and CAS under the V5 gate.
            CustomerReportRegistration duplicate=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,operation_id,version FROM public.mop_ticket WHERE tenant_id::text=? AND reporter_id::text=? AND asset_id::text=? AND category=? AND created_at>=clock_timestamp()-interval '30 seconds' AND created_at<=clock_timestamp() ORDER BY created_at DESC,id DESC LIMIT 1")){
                statement.setString(1,tenant);statement.setString(2,actor);statement.setString(3,asset.id());statement.setString(4,report.category());try(ResultSet rows=statement.executeQuery()){return rows.next()?new CustomerReportRegistration(rows.getString("operation_id"),rows.getString("id"),false):null;}
            }});if(duplicate!=null){
                boolean changed=false;
                if(!report.attachmentIds().isEmpty()){
                    long before=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT version FROM public.mop_ticket WHERE tenant_id::text=? AND id::text=? FOR UPDATE")){statement.setString(1,tenant);statement.setString(2,duplicate.ticketId());try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getLong(1);}}});
                    Map<String,Object> updated=attachPhotos(tenant,actor,duplicate.ticketId(),before,report.attachmentIds());changed=((Number)updated.get("version")).longValue()!=before;
                }
                return new CustomerReportRegistration(duplicate.operationId(),duplicate.ticketId(),false,changed);
            }
        }
        String id=UUID.randomUUID().toString(),operationId=UUID.randomUUID().toString(),title=report.title()==null?report.category()+" / "+(asset==null?"设备不确定":asset.name()):report.title();
        Instant created=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_ticket(id,tenant_id,receipt_no,title,description,source,category,impact_scope,urgency,asset_id,asset_name,reporter_id,contact_name,contact_phone,operation_id) VALUES (?::uuid,?::uuid,?,?,?,'customer_report',?,?,?,?::uuid,?,?::uuid,?,?,?::uuid) RETURNING created_at")){
            statement.setString(1,id);statement.setString(2,tenant);statement.setString(3,"REG-"+id);statement.setString(4,title);statement.setString(5,report.description());statement.setString(6,report.category());statement.setString(7,report.impactScope());statement.setString(8,report.urgency());statement.setString(9,asset==null?null:asset.id());statement.setString(10,asset==null?null:asset.name());statement.setString(11,actor);statement.setString(12,report.contactName());statement.setString(13,report.contactPhone());statement.setString(14,operationId);try(ResultSet rows=statement.executeQuery()){rows.next();return instant(rows,"created_at");}
        }});
        Map<String,Object> event=new LinkedHashMap<>();event.put("action","REGISTER");event.put("actor_id",actor);event.put("occurred_at",created.toString());event.put("version",1);
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_ticket_history(tenant_id,ticket_id,version,payload) VALUES (?::uuid,?::uuid,1,?::jsonb)")){
            statement.setString(1,tenant);statement.setString(2,id);statement.setString(3,mapper.valueToTree(event).toString());statement.executeUpdate();return null;
        }});
        Map<String,Object> error=new LinkedHashMap<>();error.put("code","CONFIG_PENDING");error.put("message","本平台为一期工单权威；正式状态、SLA、授权、通知与处理规则尚待配置，受理已持久保存");error.put("retryable",false);error.put("question_ids",List.of("Q07","Q08","Q09","Q14","Q16"));
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_operation(id,tenant_id,ticket_id,kind,result_ref,error) VALUES (?::uuid,?::uuid,?::uuid,'CUSTOMER_REPORT_REGISTRATION',?,?::jsonb)")){
            statement.setString(1,operationId);statement.setString(2,tenant);statement.setString(3,id);statement.setString(4,"/api/v1/tickets/"+id);statement.setString(5,mapper.valueToTree(error).toString());statement.executeUpdate();return null;
        }});
        Map<String,Object> payload=Map.of("ticket_id",id,"operation_id",operationId,"kind","CUSTOMER_REPORT_REGISTRATION");
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_outbox(id,tenant_id,ticket_id,operation_id,topic,payload,blocked_question_ids) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'ticket.customer_report.registered',?::jsonb,?::jsonb)")){
            statement.setString(1,UUID.randomUUID().toString());statement.setString(2,tenant);statement.setString(3,id);statement.setString(4,operationId);statement.setString(5,mapper.valueToTree(payload).toString());statement.setString(6,mapper.valueToTree(error.get("question_ids")).toString());statement.executeUpdate();return null;
        }});
        if(!report.attachmentIds().isEmpty())bindPhotoRows(tenant,actor,id,report.attachmentIds(),0);
        return new CustomerReportRegistration(operationId,id,true);
    }
    private static boolean photoSchemaPresent(Connection connection)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT to_regclass('public.mop_file') IS NOT NULL AND to_regclass('public.mop_photo_job') IS NOT NULL AND to_regclass('public.mop_file_grant') IS NOT NULL" );ResultSet rows=statement.executeQuery()){return rows.next()&&rows.getBoolean(1);}
    }
    /** Read-only startup gate. The worker never applies schema migrations. */
    public boolean fileWorkerReady(){
        if(!dependencyReady())return false;
        try{return connection(connection->{
            if(!photoSchemaPresent(connection))return false;
            // Earlier draft shapes rejected NULL next_at/error/ticket_id and cannot run file jobs.
            try(PreparedStatement statement=connection.prepareStatement("SELECT count(*)=3 AND bool_and(NOT attnotnull) FROM pg_attribute WHERE NOT attisdropped AND ((attrelid='public.mop_photo_job'::regclass AND attname='next_at') OR (attrelid='public.mop_operation'::regclass AND attname IN ('ticket_id','error')))" );ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))return false;}
            // Parse/read the exact consumed schema without fetching original bytes.
            try(PreparedStatement statement=connection.prepareStatement("SELECT j.id,j.tenant_id,j.file_id,j.content,j.state,j.lease_owner,j.lease_until,j.fencing_token,j.attempts,j.next_at,j.created_at,f.id,f.tenant_id,f.created_by,f.purpose,f.original_name,f.media_type,f.size_bytes,f.sha256,f.object_key,f.operation_id,f.permission_version,f.upload_status,f.ticket_id,f.version,f.created_at,f.updated_at,o.file_id,o.kind,o.status,o.step,o.error,o.updated_at FROM public.mop_photo_job j JOIN public.mop_file f ON f.tenant_id=j.tenant_id AND f.id=j.file_id JOIN public.mop_operation o ON o.tenant_id=f.tenant_id AND o.id=f.operation_id WHERE FALSE")){statement.executeQuery().close();}
            for(String table:List.of("mop_photo_job","mop_file","mop_operation","mop_membership","mop_user","mop_audit")){
                try(PreparedStatement statement=connection.prepareStatement("SELECT has_table_privilege(current_user,?,'SELECT')")){statement.setString(1,"public."+table);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))return false;}}
            }
            try(PreparedStatement statement=connection.prepareStatement("SELECT has_table_privilege(current_user,'public.mop_audit','INSERT')");ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))return false;}
            // FOR SHARE authorization locks require UPDATE on at least one column.
            for(String table:List.of("mop_membership","mop_user"))try(PreparedStatement statement=connection.prepareStatement("SELECT has_any_column_privilege(current_user,?,'UPDATE')")){statement.setString(1,"public."+table);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))return false;}}
            Map<String,List<String>> updates=Map.of("mop_photo_job",List.of("state","lease_owner","lease_until","fencing_token","attempts","next_at"),"mop_file",List.of("upload_status","version","updated_at"),"mop_operation",List.of("status","step","error","updated_at"));
            for(var table:updates.entrySet())for(String column:table.getValue()){
                try(PreparedStatement statement=connection.prepareStatement("SELECT has_column_privilege(current_user,?,?,'UPDATE')")){statement.setString(1,"public."+table.getKey());statement.setString(2,column);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))return false;}}
            }return true;
        });}catch(DomainException unavailable){return false;}
    }
    private void requirePhotoSchema(){if(!connection(PostgresPlatformRepository::photoSchemaPresent))throw new DomainException(503,"UPSTREAM_UNAVAILABLE","照片耐久数据库结构尚未就绪，未受理照片请求");}
    /** Read-only capability probe: no migration history access, row locks or schema mutation. */
    @Override public List<String> photoDependencyProblems(){
        try(Connection connection=dataSource.getConnection()){
            connection.setReadOnly(true);
            List<String> problems=new ArrayList<>();
            try(PreparedStatement statement=photoProbe(connection,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime'");ResultSet rows=statement.executeQuery()){
                if(!rows.next()||!rows.getBoolean(1))return List.of("PHOTO_NAMESPACE_INVALID");
            }
            try(PreparedStatement statement=photoProbe(connection,"SELECT name FROM (VALUES ('mop_file'),('mop_photo_job'),('mop_file_grant')) required(name) WHERE to_regclass('public.'||name) IS NULL");ResultSet rows=statement.executeQuery()){
                while(rows.next())problems.add("PHOTO_SCHEMA_MISSING:"+rows.getString(1));
            }
            if(!problems.isEmpty())return List.copyOf(problems);
            // The application needs the V5 nullable fields; an earlier incompatible draft cannot run jobs.
            try(PreparedStatement statement=photoProbe(connection,"SELECT count(*)=3 AND bool_and(NOT attnotnull) FROM pg_attribute WHERE NOT attisdropped AND ((attrelid='public.mop_photo_job'::regclass AND attname='next_at') OR (attrelid='public.mop_operation'::regclass AND attname IN ('ticket_id','error')))");ResultSet rows=statement.executeQuery()){
                if(!rows.next()||!rows.getBoolean(1))problems.add("PHOTO_SCHEMA_INCOMPATIBLE");
            }
            String privilegeSql="SELECT object_name,privilege FROM (VALUES "
                +"('mop_file','SELECT'),('mop_photo_job','SELECT'),('mop_file_grant','SELECT'),('mop_operation','SELECT'),('mop_membership','SELECT'),('mop_user','SELECT'),('mop_audit','SELECT'),('mop_ticket','SELECT'),('mop_ticket_history','SELECT'),('mop_idempotency','SELECT'),('mop_outbox','SELECT'),"
                +"('mop_file','INSERT'),('mop_photo_job','INSERT'),('mop_file_grant','INSERT'),('mop_operation','INSERT'),('mop_audit','INSERT'),('mop_ticket','INSERT'),('mop_ticket_history','INSERT'),('mop_idempotency','INSERT'),('mop_outbox','INSERT')) required(object_name,privilege) "
                +"WHERE NOT has_table_privilege(current_user,'public.'||object_name,privilege)";
            try(PreparedStatement statement=photoProbe(connection,privilegeSql);ResultSet rows=statement.executeQuery()){
                while(rows.next())problems.add("PHOTO_PRIVILEGE_MISSING:"+rows.getString(1)+":"+rows.getString(2));
            }
            try(PreparedStatement statement=photoProbe(connection,"SELECT object_name FROM (VALUES ('mop_membership'),('mop_user')) required(object_name) WHERE NOT has_any_column_privilege(current_user,'public.'||object_name,'UPDATE')");ResultSet rows=statement.executeQuery()){
                while(rows.next())problems.add("PHOTO_AUTHORIZATION_LOCK_PRIVILEGE_MISSING:"+rows.getString(1));
            }
            String updateSql="SELECT object_name,column_name FROM (VALUES "
                +"('mop_file','upload_status'),('mop_file','ticket_id'),('mop_file','version'),('mop_file','updated_at'),"
                +"('mop_photo_job','state'),('mop_photo_job','lease_owner'),('mop_photo_job','lease_until'),('mop_photo_job','fencing_token'),('mop_photo_job','attempts'),('mop_photo_job','next_at'),"
                +"('mop_operation','status'),('mop_operation','step'),('mop_operation','error'),('mop_operation','updated_at'),('mop_ticket','version'),('mop_ticket','updated_at')) required(object_name,column_name) "
                +"WHERE NOT has_column_privilege(current_user,'public.'||object_name,column_name,'UPDATE')";
            try(PreparedStatement statement=photoProbe(connection,updateSql);ResultSet rows=statement.executeQuery()){
                while(rows.next())problems.add("PHOTO_PRIVILEGE_MISSING:"+rows.getString(1)+"."+rows.getString(2)+":UPDATE");
            }
            if(!problems.isEmpty())return List.copyOf(problems);
            // Parse the consumed columns and verify SELECT access without reading any original photo bytes.
            try(PreparedStatement statement=photoProbe(connection,"SELECT j.id,j.tenant_id,j.file_id,j.content,j.state,j.lease_owner,j.lease_until,j.fencing_token,j.attempts,j.next_at,j.created_at,f.id,f.tenant_id,f.created_by,f.purpose,f.original_name,f.media_type,f.size_bytes,f.sha256,f.object_key,f.operation_id,f.permission_version,f.upload_status,f.ticket_id,f.version,f.created_at,f.updated_at,o.file_id,o.kind,o.status,o.step,o.error,o.updated_at,g.id,g.tenant_id,g.file_id,g.actor_id,g.permission_version,g.expires_at FROM public.mop_photo_job j JOIN public.mop_file f ON f.tenant_id=j.tenant_id AND f.id=j.file_id JOIN public.mop_operation o ON o.tenant_id=f.tenant_id AND o.id=f.operation_id JOIN public.mop_file_grant g ON g.tenant_id=f.tenant_id AND g.file_id=f.id WHERE FALSE")){
                statement.executeQuery().close();
            }
            return List.of();
        }catch(SQLException unavailable){
            return List.of(switch(String.valueOf(unavailable.getSQLState())){case "42501"->"PHOTO_SCHEMA_ACCESS_DENIED";case "42703","42P01"->"PHOTO_SCHEMA_INCOMPATIBLE";default->"PHOTO_DATABASE_UNAVAILABLE";});
        }catch(RuntimeException unavailable){return List.of("PHOTO_DATABASE_UNAVAILABLE");}
    }
    private static PreparedStatement photoProbe(Connection connection,String sql)throws SQLException{
        PreparedStatement statement=connection.prepareStatement(sql);statement.setQueryTimeout(3);return statement;
    }
    private Membership photoReporter(String tenant,String actor){
        Membership current=ticketAccess(tenant,actor);
        if(!Capabilities.forRole(current.role()).contains("tickets:report"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能上传或追加客户报障照片");return current;
    }
    private static String ownFilePredicate(Membership membership){
        // An upload is private staging until its owner attaches it to a visible tenant ticket.
        return Capabilities.customer(membership.role())?" AND f.created_by::text=?"
            :" AND (f.created_by::text=? OR EXISTS(SELECT 1 FROM public.mop_ticket visible_ticket WHERE visible_ticket.tenant_id=f.tenant_id AND visible_ticket.id=f.ticket_id))";
    }
    private static Map<String,Object> fileProjection(ResultSet rows)throws SQLException {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("id","tenant_id","purpose","original_name","media_type","sha256","upload_status","created_by","ticket_id","operation_id"))result.put(key,rows.getString(key));
        result.put("size_bytes",rows.getLong("size_bytes"));result.put("version",rows.getLong("version"));result.put("created_at",instant(rows,"created_at").toString());result.put("updated_at",instant(rows,"updated_at").toString());
        result.put("scan_status","UNSCANNED");result.put("retention_until",null);result.put("production_approval","UNVERIFIED");result.put("policy_source","USER_DEVELOPMENT_DIRECTION");result.put("source","postgresql");return result;
    }
    @Override public Map<String,Object> acceptPhoto(String tenant,String actor,PhotoUpload upload){
        if(transaction.get()==null)throw new IllegalStateException("Photo acceptance requires idempotent transaction");
        requirePhotoSchema();Membership current=photoReporter(tenant,actor);PhotoRules.validateUpload(upload);
        byte[] content=upload.content();String file=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        connection(connection->{
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_file(id,tenant_id,created_by,original_name,media_type,size_bytes,sha256,object_key,operation_id,permission_version,purpose) VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?,?::uuid,?,'customer_report_photo')")){
                statement.setString(1,file);statement.setString(2,tenant);statement.setString(3,actor);statement.setString(4,upload.originalName());statement.setString(5,upload.mediaType());statement.setInt(6,content.length);statement.setString(7,upload.sha256());statement.setString(8,"photos/"+tenant+"/"+file);statement.setString(9,operation);statement.setLong(10,current.permissionVersion());statement.executeUpdate();
            }
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_operation(id,tenant_id,file_id,kind,status,step,result_ref,error) VALUES (?::uuid,?::uuid,?::uuid,'FILE_UPLOAD','PENDING','QUEUED',?,NULL)")){
                statement.setString(1,operation);statement.setString(2,tenant);statement.setString(3,file);statement.setString(4,"/api/v1/files/"+file);statement.executeUpdate();
            }
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_photo_job(id,tenant_id,file_id,content) VALUES (?::uuid,?::uuid,?::uuid,?)")){
                statement.setString(1,UUID.randomUUID().toString());statement.setString(2,tenant);statement.setString(3,file);statement.setBytes(4,content);statement.executeUpdate();
            }return null;
        });return Map.of("operation_id",operation,"status","PENDING");
    }
    @Override public Optional<Map<String,Object>> file(String tenant,String actor,String id){return inTransaction(()->{
        requirePhotoSchema();Membership current=ticketAccess(tenant,actor);
        return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT f.* FROM public.mop_file f WHERE f.tenant_id::text=? AND f.id::text=?"+ownFilePredicate(current))){
            statement.setString(1,tenant);statement.setString(2,id);statement.setString(3,actor);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(fileProjection(rows)):Optional.empty();}
        }});
    });}
    @Override public Map<String,Object> contentGrant(String tenant,String actor,String id){return inTransaction(()->{
        requirePhotoSchema();Membership current=ticketAccess(tenant,actor);Map<String,Object> photo=file(tenant,actor,id).orElseThrow(PostgresPlatformRepository::missing);
        if(!"READY".equals(photo.get("upload_status")))throw new DomainException(422,"STATE_NOT_ALLOWED","照片尚未存储完成");
        String grant=UUID.randomUUID().toString();Instant expires=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_file_grant(id,tenant_id,file_id,actor_id,permission_version,expires_at) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,clock_timestamp()+interval '15 minutes') RETURNING expires_at")){
            statement.setString(1,grant);statement.setString(2,tenant);statement.setString(3,id);statement.setString(4,actor);statement.setLong(5,current.permissionVersion());try(ResultSet rows=statement.executeQuery()){rows.next();return instant(rows,"expires_at");}
        }});return Map.of("content_url","/api/v1/files/"+id+"/content?grant="+grant,"expires_at",expires.toString());
    });}
    /** Internal descriptor for authorized API proxy reads; never an HTTP metadata response. */
    @Override public Map<String,Object> photoContent(String tenant,String actor,String id,String grant){return inTransaction(()->{
        requirePhotoSchema();Membership current=ticketAccess(tenant,actor);
        return connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT f.* FROM public.mop_file f JOIN public.mop_file_grant g ON g.tenant_id=f.tenant_id AND g.file_id=f.id WHERE f.tenant_id::text=? AND f.id::text=? AND g.id::text=? AND g.actor_id::text=? AND g.permission_version=? AND g.expires_at>clock_timestamp() AND f.upload_status='READY'"+ownFilePredicate(current))){
            statement.setString(1,tenant);statement.setString(2,id);statement.setString(3,grant);statement.setString(4,actor);statement.setLong(5,current.permissionVersion());statement.setString(6,actor);
            try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw missing();return Map.of("object_key",rows.getString("object_key"),"byte_size",rows.getLong("size_bytes"),"sha256",rows.getString("sha256"),"media_type",rows.getString("media_type"),"original_name",rows.getString("original_name"));}
        }});
    });}
    /** Locks requested files in stable ID order after the ticket lock; bindings cannot race across tickets. */
    private boolean bindPhotoRows(String tenant,String actor,String ticket,List<String> ids,int existing){
        requirePhotoSchema();List<String> requested=PhotoRules.attachmentIds(ids);List<String> additions=new ArrayList<>();
        connection(connection->{
            for(String id:requested){try(PreparedStatement statement=connection.prepareStatement("SELECT created_by,upload_status,ticket_id FROM public.mop_file WHERE tenant_id::text=? AND id::text=? FOR UPDATE")){
                statement.setString(1,tenant);statement.setString(2,id);try(ResultSet rows=statement.executeQuery()){
                    if(!rows.next()||!actor.equals(rows.getString("created_by")))throw missing();
                    if(!"READY".equals(rows.getString("upload_status")))throw new DomainException(422,"STATE_NOT_ALLOWED","只能绑定已完成存储的照片");
                    String bound=rows.getString("ticket_id");if(bound!=null&&!ticket.equals(bound))throw new DomainException(409,"FILE_ALREADY_ATTACHED","照片已绑定其他报障登记");if(bound==null)additions.add(id);
                }
            }}
            if(existing+additions.size()>3)throw new DomainException(422,"STATE_NOT_ALLOWED","每条报障登记最多绑定3张照片");
            for(String id:additions){try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_file SET ticket_id=?::uuid,version=version+1,updated_at=clock_timestamp() WHERE tenant_id::text=? AND id::text=? AND created_by::text=? AND upload_status='READY' AND ticket_id IS NULL")){
                statement.setString(1,ticket);statement.setString(2,tenant);statement.setString(3,id);statement.setString(4,actor);if(statement.executeUpdate()!=1)throw new DomainException(409,"VERSION_CONFLICT","照片绑定已变化");
            }}return null;
        });return !additions.isEmpty();
    }
    @Override public Map<String,Object> attachPhotos(String tenant,String actor,String id,long expected,List<String> ids){
        if(transaction.get()==null)throw new IllegalStateException("Photo attachment requires idempotent transaction");
        requirePhotoSchema();photoReporter(tenant,actor);PhotoRules.attachmentIds(ids);if(expected<1)throw new DomainException(400,"VALIDATION_ERROR","expected_version必须为正整数");
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT reporter_id,version FROM public.mop_ticket WHERE tenant_id::text=? AND id::text=? FOR UPDATE")){
            statement.setString(1,tenant);statement.setString(2,id);try(ResultSet rows=statement.executeQuery()){if(!rows.next()||!actor.equals(rows.getString("reporter_id")))throw missing();if(rows.getLong("version")!=expected)throw new DomainException(409,"VERSION_CONFLICT","报障版本已变化，请刷新后重试");}
        }return null;});
        int existing=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT count(*) FROM public.mop_file WHERE tenant_id::text=? AND ticket_id::text=?")){statement.setString(1,tenant);statement.setString(2,id);try(ResultSet rows=statement.executeQuery()){rows.next();return rows.getInt(1);}}});
        if(bindPhotoRows(tenant,actor,id,ids,existing)){
            Instant updated=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_ticket SET version=version+1,updated_at=clock_timestamp() WHERE tenant_id::text=? AND id::text=? AND version=? RETURNING updated_at")){statement.setString(1,tenant);statement.setString(2,id);statement.setLong(3,expected);try(ResultSet rows=statement.executeQuery()){if(!rows.next())throw new DomainException(409,"VERSION_CONFLICT","报障版本已变化");return instant(rows,"updated_at");}}});
            Map<String,Object> event=Map.of("action","ADD_ATTACHMENTS","actor_id",actor,"occurred_at",updated.toString(),"version",expected+1);
            connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_ticket_history(tenant_id,ticket_id,version,payload) VALUES (?::uuid,?::uuid,?,?::jsonb)")){statement.setString(1,tenant);statement.setString(2,id);statement.setLong(3,expected+1);statement.setString(4,mapper.valueToTree(event).toString());statement.executeUpdate();return null;}});
        }return ticket(tenant,actor,id).orElseThrow(PostgresPlatformRepository::missing);
    }
    private record PhotoJob(String id,String tenant,String file,String actor,String operation,String key,String media,String sha,byte[] content,long permissionVersion,long token,int attempts,boolean authorizedAtClaim){}
    private PhotoJob claimPhoto(String worker,Set<String> allowedFiles){return inTransaction(()->{
        requirePhotoSchema();return connection(connection->{
            String sql="SELECT j.id AS job_id,j.content,j.fencing_token,j.attempts,f.* FROM public.mop_photo_job j JOIN public.mop_file f ON f.tenant_id=j.tenant_id AND f.id=j.file_id WHERE ((j.state='PENDING' AND j.next_at<=clock_timestamp()) OR (j.state='LEASED' AND j.lease_until<=clock_timestamp())) AND f.upload_status='PENDING'"+(allowedFiles==null?"":" AND f.id::text IN ("+String.join(",",Collections.nCopies(allowedFiles.size(),"?"))+")")+" ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED";
            PhotoJob job;try(PreparedStatement statement=connection.prepareStatement(sql)){
                if(allowedFiles!=null){int index=1;for(String id:allowedFiles)statement.setString(index++,id);}
                try(ResultSet rows=statement.executeQuery()){
                    if(!rows.next())return null;
                    job=new PhotoJob(rows.getString("job_id"),rows.getString("tenant_id"),rows.getString("id"),rows.getString("created_by"),rows.getString("operation_id"),rows.getString("object_key"),rows.getString("media_type"),rows.getString("sha256"),rows.getBytes("content"),rows.getLong("permission_version"),rows.getLong("fencing_token")+1,rows.getInt("attempts")+1,true);
                }
            }
            if(!authorizedPhotoJob(job)){
                try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_photo_job SET state='FAILED',lease_owner=NULL,lease_until=NULL,fencing_token=?,attempts=?,next_at=NULL WHERE id::text=? AND tenant_id::text=? AND fencing_token=?")){
                    statement.setLong(1,job.token());statement.setInt(2,job.attempts());statement.setString(3,job.id());statement.setString(4,job.tenant());statement.setLong(5,job.token()-1);if(statement.executeUpdate()!=1)throw new LostPhotoLease();
                }
                try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_file SET upload_status='FAILED',version=version+1,updated_at=clock_timestamp() WHERE tenant_id::text=? AND id::text=? AND upload_status='PENDING'")){
                    statement.setString(1,job.tenant());statement.setString(2,job.file());if(statement.executeUpdate()!=1)throw new LostPhotoLease();
                }
                updatePhotoOperation(job,"FAILED","AUTHORIZATION_REVOKED",photoError("AUTHORIZATION_REVOKED"));
                audit(job.actor(),job.tenant(),"file.worker.failed","file",job.file(),"FAILED","AUTHORIZATION_REVOKED","photo-job-"+job.id()+"-"+job.token());
                return new PhotoJob(job.id(),job.tenant(),job.file(),job.actor(),job.operation(),job.key(),job.media(),job.sha(),job.content(),job.permissionVersion(),job.token(),job.attempts(),false);
            }
            try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_photo_job SET state='LEASED',lease_owner=?,lease_until=clock_timestamp()+interval '120 seconds',fencing_token=?,attempts=?,next_at=NULL WHERE id::text=? AND tenant_id::text=?")){
                statement.setString(1,worker);statement.setLong(2,job.token());statement.setInt(3,job.attempts());statement.setString(4,job.id());statement.setString(5,job.tenant());statement.executeUpdate();
            }
            updatePhotoOperation(job,"RUNNING","UPLOADING",null);
            return job;
        });
    });}
    private boolean authorizedPhotoJob(PhotoJob job){return inTransaction(()->{
        return membership(job.actor(),job.tenant()).filter(m->m.permissionVersion()==job.permissionVersion()&&Capabilities.forRole(m.role()).contains("tickets:report")).isPresent();
    });}
    private void updatePhotoOperation(PhotoJob job,String status,String step,Map<String,Object> error){
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_operation SET status=?,step=?,error=?::jsonb,updated_at=clock_timestamp() WHERE tenant_id::text=? AND id::text=? AND file_id::text=? AND kind='FILE_UPLOAD'")){
            statement.setString(1,status);statement.setString(2,step);statement.setString(3,error==null?null:mapper.valueToTree(error).toString());statement.setString(4,job.tenant());statement.setString(5,job.operation());statement.setString(6,job.file());if(statement.executeUpdate()!=1)throw unavailable();return null;
        }});
    }
    /** Fencing and unexpired lease guard every completion, including retries and authorization failure. */
    private boolean finishPhoto(PhotoJob job,String worker,String outcome){return inTransaction(()->{
        boolean lease=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT 1 FROM public.mop_photo_job WHERE tenant_id::text=? AND id::text=? AND state='LEASED' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() FOR UPDATE")){
            statement.setString(1,job.tenant());statement.setString(2,job.id());statement.setString(3,worker);statement.setLong(4,job.token());try(ResultSet rows=statement.executeQuery()){return rows.next();}
        }});if(!lease)return false;
        String resolved=authorizedPhotoJob(job)?outcome:"AUTHORIZATION_REVOKED";
        boolean succeeded="STORED".equals(resolved),retry="RETRY_WAIT".equals(resolved);
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_photo_job SET state=?,lease_owner=NULL,lease_until=NULL,next_at=CASE WHEN ? THEN clock_timestamp()+(? * interval '1 second') ELSE NULL END WHERE tenant_id::text=? AND id::text=? AND state='LEASED' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()")){
            statement.setString(1,succeeded?"SUCCEEDED":retry?"PENDING":"FAILED");statement.setBoolean(2,retry);statement.setInt(3,PhotoRules.retrySeconds(job.attempts()));statement.setString(4,job.tenant());statement.setString(5,job.id());statement.setString(6,worker);statement.setLong(7,job.token());if(statement.executeUpdate()!=1)throw new LostPhotoLease();
        }
            try(PreparedStatement statement=connection.prepareStatement("UPDATE public.mop_file SET upload_status=?,version=version+1,updated_at=clock_timestamp() WHERE tenant_id::text=? AND id::text=? AND upload_status='PENDING'")){
                statement.setString(1,succeeded?"READY":retry?"PENDING":"FAILED");statement.setString(2,job.tenant());statement.setString(3,job.file());if(statement.executeUpdate()!=1)throw new LostPhotoLease();
            }return null;
        });
        Map<String,Object> error=photoError(resolved);
        updatePhotoOperation(job,succeeded?"SUCCEEDED":retry?"PENDING":"FAILED",resolved,error);
        audit(job.actor(),job.tenant(),"file.worker."+(succeeded?"stored":retry?"retry":"failed"),"file",job.file(),succeeded?"SUCCEEDED":retry?"PENDING":"FAILED",resolved,"photo-job-"+job.id()+"-"+job.token());return true;
    });}
    private static Map<String,Object> photoError(String outcome){
        if("STORED".equals(outcome))return null;boolean retry="RETRY_WAIT".equals(outcome),revoked="AUTHORIZATION_REVOKED".equals(outcome);
        return Map.of("code",retry?"UPSTREAM_UNAVAILABLE":revoked?"ACTION_FORBIDDEN":"FILE_INTEGRITY_CONFLICT","message",retry?"照片存储暂不可用，耐久任务等待重试":revoked?"上传授权已变化，禁止继续处理":"已有照片对象摘要不一致，禁止覆盖","retryable",retry,"question_ids",List.of());
    }
    private static final class LostPhotoLease extends RuntimeException {}
    @Override public boolean processNextPhoto(String worker,PhotoObjectStore store){return processPhoto(worker,store,null);}
    /** Package-local integration seam: injected stores can only consume explicit newly-created test file IDs. */
    boolean processNextPhoto(String worker,PhotoObjectStore store,Set<String> allowedFileIds){
        if(allowedFileIds==null||allowedFileIds.isEmpty()||allowedFileIds.size()>100)throw new IllegalArgumentException("A nonempty explicit photo test scope is required");
        TreeSet<String> scope=new TreeSet<>();for(String id:allowedFileIds){if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Photo test scope requires canonical UUIDs");scope.add(id);}
        return processPhoto(worker,store,Collections.unmodifiableSet(scope));
    }
    private boolean processPhoto(String worker,PhotoObjectStore store,Set<String> allowedFiles){
        if(worker==null||!worker.matches("[A-Za-z0-9_-]{1,64}")||store==null)throw new IllegalArgumentException("Photo worker ID and store are required");
        PhotoJob job=claimPhoto(worker,allowedFiles);if(job==null)return false;if(!job.authorizedAtClaim())return true;String outcome;
        if(!authorizedPhotoJob(job))outcome="AUTHORIZATION_REVOKED";
        else try{PhotoRules.validateStoredContent(job.content(),job.sha());store.storeImmutable(job.key(),job.content(),job.media(),job.sha());outcome="STORED";}
        catch(DomainException error){outcome="FILE_INTEGRITY_CONFLICT".equals(error.code)?"STORAGE_CONFLICT":"RETRY_WAIT";}
        catch(RuntimeException error){outcome="RETRY_WAIT";}
        try{finishPhoto(job,worker,outcome);}catch(LostPhotoLease stale){/* The newer lease owns all database writes. */}
        return true;
    }
    @Override public void audit(String actor,String tenant,String action,String resourceType,String resourceId,String result,String reason,String trace){inTransaction(()->{
        String namespace=tenant==null?"platform":tenant;lock("audit|"+namespace);
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",UUID.randomUUID().toString());row.put("occurred_at",Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());row.put("actor_id",actor);row.put("actor_name",actor==null?"未认证主体":user(actor).map(FixtureUser::displayName).orElse("未认证主体"));row.put("tenant_id",tenant);row.put("action",action);row.put("resource_type",resourceType);row.put("resource_id",resourceId);row.put("result",result);row.put("reason",reason);row.put("trace_id",trace);row.put("source","postgresql");
        Object[] previous=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT sequence,entry_hash FROM public.mop_audit WHERE namespace=? ORDER BY sequence DESC LIMIT 1")){statement.setString(1,namespace);try(ResultSet rows=statement.executeQuery()){return rows.next()?new Object[]{rows.getLong(1),rows.getString(2)}:new Object[]{0L,"0".repeat(64)};}}});
        long sequence=(Long)previous[0]+1;String prev=previous[1].toString();row.put("sequence",sequence);row.put("previous_hash",prev);String entryHash=digest(prev+mapper.valueToTree(new TreeMap<>(row)).toString());row.put("entry_hash",entryHash);
        connection(connection->{try(PreparedStatement statement=connection.prepareStatement("INSERT INTO public.mop_audit(id,namespace,tenant_id,sequence,previous_hash,entry_hash,payload) VALUES (?::uuid,?,?::uuid,?,?,?,?::jsonb)")){statement.setString(1,row.get("id").toString());statement.setString(2,namespace);statement.setString(3,tenant);statement.setLong(4,sequence);statement.setString(5,prev);statement.setString(6,entryHash);statement.setString(7,mapper.valueToTree(row).toString());statement.executeUpdate();return null;}});return null;
    });}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException(exception);}}
    @Override public List<Map<String,Object>> audits(String tenant){return connection(connection->{List<Map<String,Object>> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement("SELECT payload FROM public.mop_audit WHERE tenant_id::text=? ORDER BY sequence DESC LIMIT 100")){statement.setString(1,tenant);try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(mapper.convertValue(json(rows.getString(1)),LinkedHashMap.class));}}return result;});}
}
