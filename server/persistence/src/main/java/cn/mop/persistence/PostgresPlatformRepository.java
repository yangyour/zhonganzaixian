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
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final ThreadLocal<Connection> transaction = new ThreadLocal<>();
    public PostgresPlatformRepository(DataSource dataSource,ObjectMapper mapper){this.dataSource=dataSource;this.mapper=mapper;}
    @Override public RepositoryMetadata metadata(){return new RepositoryMetadata("remote_test","postgresql",true,"test_oidc");}
    @Override public boolean dependencyReady(){
        try(Connection connection=dataSource.getConnection();PreparedStatement statement=connection.prepareStatement("SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND EXISTS(SELECT 1 FROM public.mop_tenant)")){statement.setQueryTimeout(3);try(ResultSet rows=statement.executeQuery()){return rows.next()&&rows.getBoolean(1);}}
        catch(SQLException exception){return false;}
    }
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws SQLException;}
    private <T>T connection(SqlWork<T> work){
        Connection active=transaction.get();
        try{if(active!=null)return work.run(active);try(Connection connection=dataSource.getConnection()){return work.run(connection);}}
        catch(SQLException exception){throw unavailable();}
    }
    private <T>T inTransaction(Supplier<T> work){
        if(transaction.get()!=null)return work.get();
        try(Connection connection=dataSource.getConnection()){
            connection.setAutoCommit(false);transaction.set(connection);
            try{T value=work.get();connection.commit();return value;}
            catch(RuntimeException|Error exception){try{connection.rollback();}catch(SQLException ignored){}throw exception;}
            finally{transaction.remove();}
        }catch(SQLException exception){throw unavailable();}
    }
    private void lock(String scope){connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){statement.setString(1,scope);statement.execute();return null;}});}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","测试环境 PostgreSQL 不可用，未受理请求");}
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
    @Override public Object idempotent(String actor,String tenant,String route,String key,String hash,long expectedPermissionVersion,Supplier<Object> command){return inTransaction(()->{
        lock("idempotency|"+actor+"|"+tenant+"|"+route+"|"+key);
        Membership current=membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效，禁止重放或写入"));
        if(expectedPermissionVersion>0&&current.permissionVersion()!=expectedPermissionVersion)throw new DomainException(401,"SESSION_EXPIRED","权限已变化，请刷新会话");
        Object saved=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT payload_hash,response_json FROM public.mop_idempotency WHERE actor_id::text=? AND tenant_id::text=? AND route=? AND request_key=?")){statement.setString(1,actor);statement.setString(2,tenant);statement.setString(3,route);statement.setString(4,key);try(ResultSet rows=statement.executeQuery()){if(!rows.next())return null;if(!rows.getString(1).equals(hash))throw new DomainException(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同请求内容");return json(rows.getString(2));}}});
        if(saved!=null)return saved;
        Object result=command.get();JsonNode response=mapper.valueToTree(result);rejectSessionSecrets(response);
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
        try(PreparedStatement statement=connection.prepareStatement("SELECT * FROM public.mop_file WHERE tenant_id::text=? AND ticket_id::text=? ORDER BY created_at,id")){statement.setString(1,rows.getString("tenant_id"));statement.setString(2,rows.getString("id"));try(ResultSet files=statement.executeQuery()){while(files.next())attachments.add(fileProjection(files));}}
        result.put("attachments",attachments);return result;
    }
    @Override public List<Map<String,Object>> tickets(String tenant,String actor,String category,String urgency,String query,Instant afterCreated,String afterId,int limit){
        Membership membership=ticketAccess(tenant,actor);if(limit<1||limit>101||(afterCreated==null)!=(afterId==null))throw new DomainException(400,"VALIDATION_ERROR","工单分页参数无效");
        String sql="SELECT t.* FROM public.mop_ticket t WHERE t.tenant_id::text=?"+ownTicketPredicate(membership)+CURRENT_TICKET_ACCESS+(category==null?"":" AND t.category=?")+(urgency==null?"":" AND t.urgency=?")+(query==null?"":" AND position(lower(?) in lower(t.receipt_no||' '||t.title||' '||t.description))>0")+(afterCreated==null?"":" AND (t.created_at,t.id)>(?::timestamptz,?::uuid)")+" ORDER BY t.created_at,t.id LIMIT ?";
        return connection(connection->{List<Map<String,Object>> result=new ArrayList<>();try(PreparedStatement statement=connection.prepareStatement(sql)){
            int index=bindTicketScope(statement,tenant,actor,membership);if(category!=null)statement.setString(index++,category);if(urgency!=null)statement.setString(index++,urgency);if(query!=null)statement.setString(index++,query);if(afterCreated!=null){statement.setString(index++,afterCreated.toString());statement.setString(index++,afterId);}statement.setInt(index,limit);
            try(ResultSet rows=statement.executeQuery()){while(rows.next())result.add(ticketProjection(connection,rows));}
        }return result;});
    }
    @Override public Optional<Map<String,Object>> ticket(String tenant,String actor,String id){Membership membership=ticketAccess(tenant,actor);return connection(connection->{
        try(PreparedStatement statement=connection.prepareStatement("SELECT t.* FROM public.mop_ticket t WHERE t.tenant_id::text=?"+ownTicketPredicate(membership)+CURRENT_TICKET_ACCESS+" AND t.id::text=?")){
            int index=bindTicketScope(statement,tenant,actor,membership);statement.setString(index,id);try(ResultSet rows=statement.executeQuery()){return rows.next()?Optional.of(ticketProjection(connection,rows)):Optional.empty();}
        }
    });}
    @Override public Optional<Map<String,Object>> operation(String tenant,String actor,String id){Membership membership=ticketAccess(tenant,actor);return connection(connection->{
        try(PreparedStatement statement=connection.prepareStatement("SELECT o.* FROM public.mop_operation o JOIN public.mop_ticket t ON t.tenant_id=o.tenant_id AND t.id=o.ticket_id AND t.operation_id=o.id WHERE t.tenant_id::text=?"+ownTicketPredicate(membership)+CURRENT_TICKET_ACCESS+" AND o.id::text=?")){
            int index=bindTicketScope(statement,tenant,actor,membership);statement.setString(index,id);try(ResultSet rows=statement.executeQuery()){
                if(!rows.next())return Optional.empty();Map<String,Object> result=new LinkedHashMap<>();for(String key:List.of("id","tenant_id","kind","status","step","result_ref"))result.put(key,rows.getString(key));result.put("progress",null);result.put("error",mapper.convertValue(json(rows.getString("error")),LinkedHashMap.class));result.put("created_at",instant(rows,"created_at").toString());result.put("updated_at",instant(rows,"updated_at").toString());return Optional.of(result);
            }
        }
    });}
    @Override public CustomerReportRegistration registerCustomerReport(String tenant,String actor,CustomerReport report){
        if(transaction.get()==null)throw new IllegalStateException("Customer report writes require idempotent transaction");Membership current=ticketAccess(tenant,actor);
        if(!Capabilities.forRole(current.role()).contains("tickets:report"))throw new DomainException(403,"ACTION_FORBIDDEN","内部角色不能代客户报障");
        Asset asset=report.assetId()==null?null:find(tenant,report.assetId()).orElseThrow(PostgresPlatformRepository::missing);
        if(asset!=null){
            lock("ticket-duplicate|"+tenant+"|"+actor+"|"+asset.id()+"|"+report.category());
            CustomerReportRegistration duplicate=connection(connection->{try(PreparedStatement statement=connection.prepareStatement("SELECT id,operation_id FROM public.mop_ticket WHERE tenant_id::text=? AND reporter_id::text=? AND asset_id::text=? AND category=? AND created_at>=clock_timestamp()-interval '30 seconds' AND created_at<=clock_timestamp() ORDER BY created_at DESC,id DESC LIMIT 1")){
                statement.setString(1,tenant);statement.setString(2,actor);statement.setString(3,asset.id());statement.setString(4,report.category());try(ResultSet rows=statement.executeQuery()){return rows.next()?new CustomerReportRegistration(rows.getString("operation_id"),rows.getString("id"),false):null;}
            }});if(duplicate!=null)return duplicate;
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
        return new CustomerReportRegistration(operationId,id,true);
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
