package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringEnrollment.*;
import cn.mop.domain.monitoring.MonitoringEnrollmentRepository.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.security.MessageDigest;

/** Current-authority append-only registration, never remote deployment or synthetic first values. */
public final class PostgresMonitoringEnrollmentRepository implements MonitoringEnrollmentRepository {
 private final PostgresPlatformRepository platform;private final ObjectMapper json;private final Function<String,Policy> policies;private final Supplier<String> catalog;
 public PostgresMonitoringEnrollmentRepository(PostgresPlatformRepository p,ObjectMapper mapper,Function<String,Policy> policies,Supplier<String> catalog){platform=p;json=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.policies=policies;this.catalog=catalog;}
 @FunctionalInterface private interface Work<T>{T run(Connection c)throws Exception;}
 private <T>T transaction(Access a,boolean write,Work<T> work){
  validate(a);return platform.identityTransaction(c->{try{
   MonitoringEnrollmentSchemaVerifier.verify(c);
   for(String capability:write?List.of("assets:read","configurations:read","configurations:write"):List.of("assets:read","configurations:read"))PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),capability);
   try(var s=query(c,"SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=? FOR SHARE OF m,u")){
    s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setLong(3,a.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw expired();if(write&&!"padmin".equals(r.getString(1)))throw forbidden();}}
   return work.run(c);
  }catch(DomainException e){throw e;}catch(Exception e){throw unavailable();}});
 }
 @Override public void authorize(Access a,String id,boolean write){transaction(a,write,c->{if(id!=null)get(c,a,uuid(id),write);return null;});}
 @Override public State create(Access a,Creation input){return transaction(a,true,c->{
  if(input==null||!Objects.equals(catalog.get(),input.catalogSha256())||!hash(input.configuration()).equals(input.configurationSha256()))throw changed();
  var doc=new Document(UUID.randomUUID(),UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),now(c),input.catalogSha256(),input.configurationSha256(),input.configuration(),input.assets());
  assets(c,a,doc,true);var config=json.readTree(doc.configuration());if(!config.isObject()||config.path("version").asInt()!=1||!config.path("targets").isArray()||config.path("targets").isEmpty()||config.path("targets").size()>64)throw invalid();
  var targetIds=new HashSet<String>();for(var target:config.path("targets")){if(!a.tenantId().equals(target.path("tenant_id").asText())||!target.path("enabled").isBoolean()||target.path("enabled").asBoolean()||!targetIds.add(uuid(target.path("id").asText()))||doc.assets().stream().noneMatch(x->x.id().toString().equals(target.path("asset_id").asText())))throw invalid();}
  try(var s=query(c,"INSERT INTO public.mop_monitoring_enrollment(tenant_id,id,created_by,permission_version,document,created_at) VALUES(?::uuid,?::uuid,?::uuid,?,?::jsonb,?)")){
   s.setString(1,a.tenantId());s.setString(2,doc.id().toString());s.setString(3,a.actorId());s.setLong(4,a.permissionVersion());s.setString(5,json.writeValueAsString(doc));time(s,6,doc.createdAt());s.executeUpdate();}
  var state=MonitoringEnrollment.create(doc);append(c,a,"CREATE",state);return state;
 });}
 @Override public State get(Access a,String id){return transaction(a,false,c->get(c,a,uuid(id),false));}
 private State get(Connection c,Access a,String id,boolean lock)throws Exception{
  try(var s=query(c,"SELECT document::text FROM public.mop_monitoring_enrollment WHERE tenant_id=?::uuid AND id=?::uuid"+(lock?" FOR UPDATE":" FOR SHARE"))){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){
   if(!r.next())throw missing();var doc=json.readValue(r.getString(1),Document.class);assets(c,a,doc,false);
   try(var q=query(c,"SELECT state::text FROM public.mop_monitoring_enrollment_revision WHERE tenant_id=?::uuid AND id=?::uuid ORDER BY version DESC LIMIT 1")){q.setString(1,a.tenantId());q.setString(2,id);try(var row=q.executeQuery()){
    if(!row.next())throw unavailable();var state=json.readValue(row.getString(1),State.class);if(!state.document().equals(doc)||!a.tenantId().equals(doc.tenantId().toString())||!id.equals(doc.id().toString())||!hash(doc.configuration()).equals(doc.configurationSha256()))throw unavailable();return state;}}
  }}
 }
 @Override public List<State> list(Access a,int limit,Instant before,String beforeId){
  if(limit<1||limit>21||(before==null)!=(beforeId==null))throw invalid();if(beforeId!=null)uuid(beforeId);
  return transaction(a,false,c->{var ids=new ArrayList<String>();try(var s=query(c,"SELECT id FROM public.mop_monitoring_enrollment WHERE tenant_id=?::uuid"+(before==null?"":" AND (created_at,id)<(?,?::uuid)")+" ORDER BY created_at DESC,id DESC LIMIT ?")){
   int p=1;s.setString(p++,a.tenantId());if(before!=null){time(s,p++,before);s.setString(p++,beforeId);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}
   var out=new ArrayList<State>();for(var id:ids)out.add(get(c,a,id,false));return List.copyOf(out);
  });
 }
 @Override public State change(Access a,String id,long expected,String action,String reason){return transaction(a,true,c->{
  if(!Set.of("APPROVE","REGISTER_DISABLED","CANCEL").contains(action))throw invalid();var before=get(c,a,uuid(id),true);
  if(!action.equals("CANCEL")){if(!Objects.equals(catalog.get(),before.document().catalogSha256()))throw changed();assets(c,a,before.document(),true);}
  var policy=policies.apply(a.tenantId());if(action.equals("REGISTER_DISABLED")){reviewer(c,a,before,policy);registrationAvailable(c,a,before);}var now=now(c);var after=switch(action){case "APPROVE"->MonitoringEnrollment.approve(before,UUID.fromString(a.actorId()),expected,policy,now);case "REGISTER_DISABLED"->MonitoringEnrollment.register(before,expected,policy,now);case "CANCEL"->MonitoringEnrollment.cancel(before,expected,reason,now);default->throw invalid();};
  append(c,a,action,after);return after;
 });}
 @Override public State authorizeProbe(Access a,String id,long expected){return transaction(a,true,c->{var state=get(c,a,uuid(id),true);probeAuthority(c,a,state,expected);return state;});}
 private void probeAuthority(Connection c,Access a,State before,long expected)throws SQLException{
  if(before.phase()!=Phase.REGISTERED_DISABLED||before.version()!=expected)throw changed();
  if(!Objects.equals(catalog.get(),before.document().catalogSha256()))throw changed();assets(c,a,before.document(),true);reviewer(c,a,before,policies.apply(a.tenantId()));
 }
 private void registrationAvailable(Connection c,Access a,State state)throws Exception {
  var ids=new TreeSet<String>();for(var target:json.readTree(state.document().configuration()).path("targets"))ids.add(target.path("id").asText());
  for(var target:ids){
   try(var lock=query(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){lock.setString(1,"mop.monitoring.registration|"+a.tenantId()+"|"+target);lock.execute();}
   try(var check=query(c,"SELECT 1 FROM public.mop_monitoring_enrollment_revision r WHERE r.tenant_id=?::uuid AND r.id<>?::uuid AND r.phase='REGISTERED_DISABLED' AND NOT EXISTS(SELECT 1 FROM public.mop_monitoring_enrollment_revision n WHERE n.tenant_id=r.tenant_id AND n.id=r.id AND n.version>r.version) AND EXISTS(SELECT 1 FROM jsonb_array_elements((r.state#>>'{document,configuration}')::jsonb->'targets') t WHERE t->>'id'=?) LIMIT 1")){
    check.setString(1,a.tenantId());check.setString(2,state.document().id().toString());check.setString(3,target);try(var rows=check.executeQuery()){if(rows.next())throw new DomainException(409,"MONITORING_TARGET_ALREADY_REGISTERED","该逻辑目标已有当前登记，请核对原任务，不覆盖既有记录");}
   }
  }
 }
 private void reviewer(Connection c,Access a,State state,Policy policy)throws SQLException {
  if(policy==null||!Objects.equals(policy.reference(),state.approvalPolicy())||policy.reviewers()==null||!policy.reviewers().contains(state.reviewer()))throw new DomainException(422,"CONFIG_PENDING","登记审批配置已变化");
  try(var s=query(c,"SELECT m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=? AND m.active AND u.active AND m.role_code='padmin' FOR SHARE OF m,u")){
   s.setString(1,a.tenantId());s.setObject(2,state.reviewer());try(var r=s.executeQuery()){if(!r.next())throw forbidden();for(var capability:List.of("assets:read","configurations:read","configurations:write"))PostgresAuthorization.require(c,state.reviewer().toString(),a.tenantId(),r.getLong(1),capability);}}
 }
 @Override public State recordProbe(Access a,String id,long expected,ProbeResult probe){return transaction(a,true,c->{
  var before=get(c,a,uuid(id),true);probeAuthority(c,a,before,expected);
  if(probe==null||json.readTree(before.document().configuration()).path("targets").findValuesAsText("id").stream().noneMatch(v->v.equals(probe.targetId().toString())))throw invalid();
  JsonNode selected=null;for(var target:json.readTree(before.document().configuration()).path("targets"))if(target.path("id").asText().equals(probe.targetId().toString()))selected=target;
  String single=json.writeValueAsString(Map.of("version",1,"targets",List.of(json.convertValue(selected,Map.class))));if(!hash(single).equals(probe.configurationSha256()))throw changed();
  var receivedAt=now(c);var observation=new Probe(probe.targetId(),probe.configurationSha256(),probe.status(),probe.sampleCount(),probe.matchedMetrics(),receivedAt,probe.reason());
  var after=MonitoringEnrollment.probe(before,expected,observation,receivedAt);append(c,a,"PROBE",after);return after;
 });}
 private void assets(Connection c,Access a,Document d,boolean exact)throws SQLException{
  if(!d.tenantId().toString().equals(a.tenantId()))throw missing();
  var ids=d.assets().stream().map(x->x.id().toString()).sorted().toList();
  // Reuse the existing tenant/capability batch projection; do not perform four authorization queries per asset.
  if(platform.authorizedAssetBatch(a.tenantId(),a.actorId(),a.permissionVersion(),ids).size()!=ids.size())throw missing();
  var expected=new HashMap<String,AssetSnapshot>();for(var value:d.assets())expected.put(value.id().toString(),value);
  try(var s=query(c,"SELECT id,version,management_ip FROM public.mop_asset WHERE tenant_id=?::uuid AND id=ANY(?::uuid[]) ORDER BY id FOR SHARE")){
   s.setString(1,a.tenantId());var array=c.createArrayOf("uuid",ids.toArray());try{s.setArray(2,array);try(var r=s.executeQuery()){
    int count=0;while(r.next()){var source=expected.get(r.getString(1));if(source==null)throw missing();count++;if(exact&&(r.getLong(2)!=source.version()||!source.managementIp().equals(r.getString(3))))throw changed();}if(count!=ids.size())throw missing();
   }}finally{array.free();}
  }
 }

 private void append(Connection c,Access a,String action,State s)throws Exception{
  try(var q=query(c,"INSERT INTO public.mop_monitoring_enrollment_revision(tenant_id,id,version,action,phase,state,actor_id,permission_version,recorded_at) VALUES(?::uuid,?::uuid,?,?,?,?::jsonb,?::uuid,?,?)")){
   q.setString(1,a.tenantId());q.setString(2,s.document().id().toString());q.setLong(3,s.version());q.setString(4,action);q.setString(5,s.phase().name());q.setString(6,json.writeValueAsString(s));q.setString(7,a.actorId());q.setLong(8,a.permissionVersion());time(q,9,s.updatedAt());q.executeUpdate();}
  platform.audit(a.actorId(),a.tenantId(),"monitoring_enrollment."+action.toLowerCase(Locale.ROOT),"monitoring_enrollment",s.document().id().toString(),"SUCCEEDED","REGISTRATION_DISABLED_NOT_DEPLOYED_OR_HEALTHY",a.traceId());
 }
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
 private static Instant now(Connection c)throws SQLException{try(var s=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
 private static void time(PreparedStatement s,int i,Instant t)throws SQLException{s.setObject(i,OffsetDateTime.ofInstant(t,ZoneOffset.UTC));}
 private static void validate(Access a){if(a==null||a.permissionVersion()<1||a.permissionVersion()>9007199254740990L||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();uuid(a.actorId());uuid(a.tenantId());}
 private static String uuid(String s){if(s==null||!s.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid();return s;}
 private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw invalid();}}
 private static DomainException changed(){return new DomainException(409,"ENROLLMENT_SOURCE_CHANGED","资产版本、地址或预设目录已变化，请创建新任务，不覆盖原记录");}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","监控登记字段无效");}
 private static DomainException unavailable(){return new DomainException(503,"MONITORING_ENROLLMENT_UNAVAILABLE","监控登记结构或受限权限尚未就绪");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前监控登记授权已变化");}
 private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","监控登记或资产不存在或不可见");}
 private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前角色不能修改监控登记");}
}
