package cn.mop.persistence;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/** Ephemeral fixture only; deliberately has no production component annotation. */
public final class FixtureRepository implements PlatformRepository {
    public static final String TENANT_A = "55000000-0000-4000-8000-000000000001";
    public static final String TENANT_B = "55000000-0000-4000-8000-000000000002";
    private final Map<String, Tenant> tenants = new LinkedHashMap<>();
    private final Map<String, FixtureUser> users = new LinkedHashMap<>();
    private final Map<String, Membership> memberships = new LinkedHashMap<>();
    private final Map<String, Asset> assets = new LinkedHashMap<>();
    private final Map<String, Map<String,Object>> configurations = new LinkedHashMap<>();
    private final Map<String, SavedResponse> idempotency = new HashMap<>();
    private final List<Map<String,Object>> audit = new ArrayList<>();
    private record SavedResponse(String hash, Object value) {}

    @Override public RepositoryMetadata metadata(){return new RepositoryMetadata("local_fixture","fixture",false,"fixture");}
    @Override public boolean dependencyReady(){return true;}
    @Override public Optional<FixtureUser> resolveIdentity(String source,String subject){return source.equals("fixture")?user(subject):Optional.empty();}

    public FixtureRepository(ObjectMapper mapper) {
        tenants.put(TENANT_A, new Tenant(TENANT_A, "虚构星湾 A（Fixture）"));
        tenants.put(TENANT_B, new Tenant(TENANT_B, "虚构云岬 B（Fixture）"));
        addUser("padmin", "平台管理员（Fixture）", List.of(TENANT_A,TENANT_B));
        addUser("msp", "服务工程师（Fixture）", List.of(TENANT_A,TENANT_B));
        addUser("tadmin", "租户 A 管理员（Fixture）", List.of(TENANT_A));
        addUser("viewerA", "租户 A 只读用户（Fixture）", List.of(TENANT_A));
        addUser("viewerB", "租户 B 只读用户（Fixture）", List.of(TENANT_B));
        for (String tenantId : tenants.keySet()) {
            for (int i=1;i<=18;i++) {
                Instant at=Instant.parse("2026-09-30T00:00:00Z").plusSeconds(i);
                String id=UUID.nameUUIDFromBytes((tenantId+"asset"+i).getBytes(StandardCharsets.UTF_8)).toString();
                String site=tenantId.equals(TENANT_A)?"site-a-main":"site-b-main";
                Asset asset=new Asset(id,tenantId,"虚构设备 "+String.format("%02d",i), i%2==0?"switch":"router",
                    tenantId.equals(TENANT_A)?"192.0.2."+i:"198.51.100."+i,site,"unknown","DRAFT","fixture",null,
                    "Fixture 未接入监控，没有采样事实",1,at,at,"UNKNOWN","PENDING_ONBOARDING");
                assets.put(key(tenantId,id),asset);
            }
        }
        try (InputStream source=getClass().getResourceAsStream("/config-baseline.json")) {
            if (source==null) throw new IllegalStateException("config-baseline.json must be packaged; no configuration defaults are invented");
            JsonNode root=mapper.readTree(source);
            if (!"design_registry_not_runtime_config".equals(root.path("status").asText())) throw new IllegalStateException("Unexpected registry status");
            for(JsonNode setting:root.path("settings")) {
                Map<String,Object> entry=mapper.convertValue(setting,LinkedHashMap.class);
                entry.put("registered_value",entry.get("value")); entry.put("effective_value",null); entry.put("draft_value",null);
                entry.put("version",1L); entry.put("lifecycle_status","NONE");
                entry.put("mode","local_fixture"); entry.put("source_registry_status","design_registry_not_runtime_config");
                configurations.put(entry.get("key").toString(),entry);
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot load design registry",e); }
    }
    private static String key(String tenantId,String id) {return tenantId+":"+id;}
    private void addUser(String role,String displayName,List<String> allowed) {
        String id=UUID.nameUUIDFromBytes(("mop-fixture-user-"+role).getBytes(StandardCharsets.UTF_8)).toString();
        String canonicalRole=role.startsWith("viewer")?"viewer":role;
        users.put(id,new FixtureUser(id,displayName,canonicalRole));
        allowed.forEach(tenant->memberships.put(key(tenant,id),new Membership(id,tenant,canonicalRole,1,true)));
    }
    public synchronized List<FixtureUser> users(){return List.copyOf(users.values());}
    public synchronized Optional<FixtureUser> user(String id){return Optional.ofNullable(users.get(id));}
    public synchronized List<Tenant> allowedTenants(String userId){return tenants.values().stream().filter(t->membership(userId,t.id()).isPresent()).toList();}
    public synchronized Optional<Membership> membership(String userId,String tenantId){return Optional.ofNullable(memberships.get(key(tenantId,userId))).filter(Membership::active);}
    public synchronized Tenant tenant(String id){return tenants.get(id);}
    /** Test/support hook. No HTTP endpoint exists. Revocation increments the version immediately. */
    public synchronized void revoke(String userId,String tenantId){
        Membership prior=memberships.get(key(tenantId,userId));
        if(prior!=null) memberships.put(key(tenantId,userId),new Membership(userId,tenantId,prior.role(),prior.permissionVersion()+1,false));
    }
    /** Historical isolated MockMvc adapter only. Real PostgreSQL never calls or falls back to this class. */
    @Override public synchronized List<Asset> authorizedAssets(String tenant,String actor,long permissionVersion,AssetReadQuery query){
        fixtureAssetReadAccess(tenant,actor,permissionVersion);return list(tenant).stream().filter(a->query.type()==null||a.type().equals(query.type())).filter(a->query.status()==null||a.status().equals(query.status())).filter(a->query.siteId()==null||a.siteId().equals(query.siteId())).filter(a->query.query()==null||a.name().toLowerCase(Locale.ROOT).contains(query.query().toLowerCase(Locale.ROOT))||a.managementIp().contains(query.query())).filter(a->query.afterCreatedAt()==null||a.createdAt().isAfter(query.afterCreatedAt())||a.createdAt().equals(query.afterCreatedAt())&&a.id().compareTo(query.afterId())>0).limit(query.limit()).toList();
    }
    @Override public synchronized Optional<Asset> authorizedAsset(String tenant,String actor,long permissionVersion,String id){fixtureAssetReadAccess(tenant,actor,permissionVersion);return id==null?Optional.empty():find(tenant,id.toLowerCase(Locale.ROOT));}
    private void fixtureAssetReadAccess(String tenant,String actor,long permissionVersion){Membership member=membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","Historical fixture membership unavailable"));if(member.permissionVersion()!=permissionVersion)throw new DomainException(401,"SESSION_EXPIRED","Historical fixture version changed");if(!Capabilities.forRole(member.role()).contains("assets:read"))throw new DomainException(403,"ACTION_FORBIDDEN","Historical fixture role denied");}
    @Override public synchronized List<Asset> list(String tenantId){return assets.values().stream().filter(a->a.tenantId().equals(tenantId)).sorted(Comparator.comparing(Asset::createdAt).thenComparing(Asset::id)).toList();}
    @Override public synchronized Optional<Asset> find(String tenantId,String id){return Optional.ofNullable(assets.get(key(tenantId,id)));}
    @Override public boolean siteBelongsTo(String tenantId,String siteId){return (tenantId.equals(TENANT_A)?"site-a-main":"site-b-main").equals(siteId);}
    @Override public synchronized Asset insert(String tenantId,String actorId,String name,String type,String ip,String siteId){
        if(list(tenantId).stream().anyMatch(a->a.name().equals(name))) throw new DomainException(409,"ASSET_NAME_CONFLICT","同租户资产名称已存在");
        if(!siteBelongsTo(tenantId,siteId)) throw new DomainException(404,"OBJECT_NOT_FOUND","站点不存在或不可见");
        Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS); String id=UUID.randomUUID().toString();
        Asset asset=new Asset(id,tenantId,name,type,ip,siteId,"unknown","DRAFT","fixture",null,"尚未接入，未取得监控采样",1,now,now,"UNKNOWN","PENDING_ONBOARDING");
        assets.put(key(tenantId,id),asset); return asset;
    }
    /** One monitor covers the business write and its replay record, including concurrent requests. */
    public synchronized Object idempotent(String actor,String tenant,String route,String requestKey,String hash,long expectedPermissionVersion,Supplier<Object> command){
        Membership current=membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效，禁止重放或写入"));
        if(expectedPermissionVersion>0&&current.permissionVersion()!=expectedPermissionVersion)throw new DomainException(401,"SESSION_EXPIRED","权限已变化，请刷新会话");
        String key=actor+"|"+tenant+"|"+route+"|"+requestKey;
        SavedResponse saved=idempotency.get(key);
        if(saved!=null){if(!saved.hash().equals(hash)) throw new DomainException(409,"IDEMPOTENCY_CONFLICT","同一幂等键不能用于不同请求内容");return saved.value();}
        Object value=command.get(); idempotency.put(key,new SavedResponse(hash,value)); return value;
    }
    public synchronized List<Map<String,Object>> configurations(){return configurations.values().stream().map(LinkedHashMap::new).map(m->(Map<String,Object>)m).toList();}
    public synchronized Map<String,Object> configuration(String key){Map<String,Object> item=configurations.get(key);if(item==null)throw new DomainException(404,"OBJECT_NOT_FOUND","配置键不存在");return new LinkedHashMap<>(item);}
    public synchronized Map<String,Object> draftConfiguration(String key,Object proposed,long expectedVersion){
        Map<String,Object> current=configurations.get(key); if(current==null)throw new DomainException(404,"OBJECT_NOT_FOUND","配置键不存在");
        if("fixed_constraint".equals(current.get("contract_value_kind"))) throw new DomainException(422,"STATE_NOT_ALLOWED","合同固定约束不能修改");
        if(((Number)current.get("version")).longValue()!=expectedVersion) throw new DomainException(409,"VERSION_CONFLICT","配置草稿版本已变化");
        current.put("draft_value",proposed);current.put("version",expectedVersion+1);current.put("lifecycle_status","DRAFT");
        return new LinkedHashMap<>(current);
    }
    public synchronized void audit(String actor,String tenant,String action,String resourceType,String resourceId,String result,String reason,String trace){
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",UUID.randomUUID().toString());row.put("occurred_at",Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        row.put("actor_name",user(actor).map(FixtureUser::displayName).orElse("未认证主体"));row.put("tenant_id",tenant);
        row.put("action",action);row.put("resource_type",resourceType);row.put("resource_id",resourceId);row.put("result",result);row.put("reason",reason);row.put("trace_id",trace);
        row.put("source","fixture");audit.add(row);
    }
    public synchronized List<Map<String,Object>> audits(String tenant){List<Map<String,Object>> result=new ArrayList<>();for(int i=audit.size()-1;i>=0&&result.size()<100;i--){Map<String,Object> row=audit.get(i);if(Objects.equals(tenant,row.get("tenant_id")))result.add(new LinkedHashMap<>(row));}return result;}
}
