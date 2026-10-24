package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.monitoring.*;
import cn.mop.domain.monitoring.MonitoringObjectBinding.*;
import cn.mop.domain.monitoring.MonitoringObjectBindingRepository.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Append-only mapping publication, raw capture and explicitly requested problem intake; no source provisioning or dispatch. */
public final class PostgresMonitoringObjectBindingRepository implements MonitoringObjectBindingRepository {
 private final PostgresPlatformRepository platform;
 private final ObjectMapper json;
 private final Function<String,Policy> policies;
 private final Source source;
 /** Shares the caller's idempotent transaction; absent only for raw-only historical assemblies. */
 private final PostgresMonitoringEventCenterRepository events;

    public PostgresMonitoringObjectBindingRepository(
        PostgresPlatformRepository platform,
        ObjectMapper json,
        Function<String, Policy> policies,
        Source source
    ) {
        this(platform, json, policies, source, null);
    }

    public PostgresMonitoringObjectBindingRepository(
        PostgresPlatformRepository platform,
        ObjectMapper json,
        Function<String, Policy> policies,
        Source source,
        PostgresMonitoringEventCenterRepository events
    ) {
        this.platform = Objects.requireNonNull(platform);
        this.json = json
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.policies = Objects.requireNonNull(policies);
        this.source = Objects.requireNonNull(source);
        this.events = events;
    }
 @FunctionalInterface private interface Work<T>{T run(Connection connection)throws Exception;}

    private <T> T transaction(Access a, boolean write, Work<T> work) {
        validate(a);
        if (write) {
            platform.idempotentCommandConnection();
        }
        return platform.identityTransaction(c -> {
            try {
                MonitoringObjectBindingSchemaVerifier.verify(c);
                authority(c, a, write);
                T result = work.run(c);
                authority(c, a, write);
                return result;
            } catch (DomainException e) {
                throw e;
            } catch (Exception e) {
                throw unavailable(e);
            }
        });
    }
 private static void authority(Connection c,Access a,boolean write)throws SQLException{
  for(String capability:write?List.of("assets:read","configurations:read","configurations:write"):List.of("assets:read","configurations:read"))PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),capability);
  try(var s=query(c,"SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=? FOR SHARE OF m,u")){
   s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setLong(3,a.permissionVersion());try(var r=s.executeQuery()){if(!r.next())throw new DomainException(401,"SESSION_EXPIRED","映射读取授权已变化");if(write&&!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能修改映射");}
  }
 }

    @Override
    public void authorize(Access a, UUID id, boolean write) {
        // Write authorization locks the root directly; a shared-to-exclusive upgrade can deadlock concurrent intakes.
        // This preflight also runs before the idempotency scope exists; it never mutates the root.
        validate(a);
        platform.identityTransaction(c -> {
            try {
                MonitoringObjectBindingSchemaVerifier.verify(c);
                authority(c, a, write);
                if (id != null) {
                    load(c, a, id, write);
                }
                return null;
            } catch (DomainException e) {
                throw e;
            } catch (Exception e) {
                throw unavailable(e);
            }
        });
    }
 @Override public View get(Access a,UUID id){return transaction(a,false,c->view(c,a,load(c,a,Objects.requireNonNull(id),false)));}
 @Override public Page list(Access a,UUID assetId,Cursor before){return transaction(a,false,c->{
  asset(c,a,Objects.requireNonNull(assetId));if(before!=null&&(before.id()==null||before.createdAt()==null))throw invalid();
  var ids=new ArrayList<UUID>();
  try(var s=query(c,"SELECT b.id FROM public.mop_monitoring_object_binding b WHERE b.tenant_id=?::uuid AND EXISTS(SELECT 1 FROM public.mop_monitoring_object_binding_revision r WHERE r.tenant_id=b.tenant_id AND r.id=b.id AND (r.draft_asset_id=? OR r.active_asset_id=?))"+(before==null?"":" AND (b.created_at,b.id)<(?,?)")+" ORDER BY b.created_at DESC,b.id DESC LIMIT 21")){
   s.setString(1,a.tenantId());s.setObject(2,assetId);s.setObject(3,assetId);if(before!=null){time(s,4,before.createdAt());s.setObject(5,before.id());}try(var r=s.executeQuery()){while(r.next())ids.add(r.getObject(1,UUID.class));}
  }
  var result=new ArrayList<View>();for(var id:ids.subList(0,Math.min(ids.size(),20)))result.add(view(c,a,load(c,a,id,false)));
  Cursor next=null;if(ids.size()>20){var last=result.get(result.size()-1).state().document();next=new Cursor(last.createdAt(),last.id());}return new Page(result,next);
 });}
 private State load(Connection c,Access a,UUID id,boolean write)throws Exception{
  try(var s=query(c,"SELECT document::text FROM public.mop_monitoring_object_binding WHERE tenant_id=?::uuid AND id=?"+(write?" FOR UPDATE":" FOR SHARE"))){s.setString(1,a.tenantId());s.setObject(2,id);try(var r=s.executeQuery()){
   if(!r.next())throw missing();var doc=json.readValue(r.getString(1),Document.class);
   try(var q=query(c,"SELECT version,state::text FROM public.mop_monitoring_object_binding_revision WHERE tenant_id=?::uuid AND id=? ORDER BY version DESC LIMIT 1")){q.setString(1,a.tenantId());q.setObject(2,id);try(var row=q.executeQuery()){
    if(!row.next())throw unavailable();var state=json.readValue(row.getString(2),State.class);if(!doc.equals(state.document())||state.version()!=row.getLong(1)||!id.equals(doc.id())||!a.tenantId().equals(doc.tenantId().toString()))throw unavailable();return state;
   }}
  }}
 }
 private View view(Connection c,Access a,State state)throws SQLException{
  var policy=policy(a);String definition=source.definitionSha256(state.document().key().poolId());
  boolean draftCurrent=state.draft()!=null&&asset(c,a,state.draft().assetId())==state.draft().assetVersion();
  long currentVersion=state.active()==null?0:asset(c,a,state.active().approvedDraft().assetId());
  boolean reviewer=state.active()!=null&&reviewer(c,a,state.active().activatedBy());
  boolean canActivate=draftCurrent&&policy.configured()&&policy.pools().contains(state.document().key().poolId())&&policy.reviewers().contains(UUID.fromString(a.actorId()))&&(policy.selfApprovalAllowed()||!a.actorId().equals(state.draft().submittedBy().toString()))&&definition!=null&&reviewer(c,a,UUID.fromString(a.actorId()));
  return new View(state,MonitoringObjectBinding.availability(state,policy,definition,currentVersion,reviewer,now(c)),draftCurrent,canActivate,policy.reference(),definition);
 }
 @Override public Receipt create(Access a,UUID assetId,long assetVersion,MonitoringObjectMapping.Key key,String reason){return transaction(a,true,c->{
  var policy=policy(a);policy.requirePool(key.poolId());if(asset(c,a,assetId)!=assetVersion)throw changed();String provider=source.provider(key.poolId());if(provider==null)throw pending();
  try(var s=query(c,"SELECT 1 FROM public.mop_monitoring_object_binding WHERE tenant_id=?::uuid AND pool_id=? AND object_type=? AND external_id=?")){s.setString(1,a.tenantId());s.setObject(2,key.poolId());s.setString(3,key.objectType().name());s.setString(4,key.externalId());try(var r=s.executeQuery()){if(r.next())throw changed();}}
  var doc=new Document(UUID.randomUUID(),UUID.fromString(a.tenantId()),key,provider,UUID.fromString(a.actorId()),now(c));var state=MonitoringObjectBinding.create(doc,assetId,assetVersion,reason);
  try(var s=query(c,"INSERT INTO public.mop_monitoring_object_binding(tenant_id,id,pool_id,object_type,external_id,provider,document,created_by,permission_version,created_at) VALUES(?::uuid,?,?,?,?,?,?::jsonb,?::uuid,?,?)")){
   s.setString(1,a.tenantId());s.setObject(2,doc.id());s.setObject(3,key.poolId());s.setString(4,key.objectType().name());s.setString(5,key.externalId());s.setString(6,provider);s.setString(7,json.writeValueAsString(doc));s.setString(8,a.actorId());s.setLong(9,a.permissionVersion());time(s,10,doc.createdAt());s.executeUpdate();
  }
  append(c,a,Action.CREATE,state);return receipt(state,Action.CREATE);
 });}
 @Override public Receipt change(Access a,UUID id,Action action,long expected,UUID assetId,Long assetVersion,String expectedPolicy,String expectedDefinition,String reason){return transaction(a,true,c->{
  var before=load(c,a,id,true);if(before.version()!=expected)throw changed();State after;
  switch(action){
   case SUBMIT_DRAFT -> {policy(a).requirePool(before.document().key().poolId());if(assetId==null||assetVersion==null||asset(c,a,assetId)!=assetVersion)throw changed();after=MonitoringObjectBinding.submit(before,expected,UUID.fromString(a.actorId()),assetId,assetVersion,now(c),reason);}
   case CANCEL_DRAFT -> after=MonitoringObjectBinding.cancelDraft(before,expected,now(c),reason);
   case REVOKE -> after=MonitoringObjectBinding.revoke(before,expected,now(c),reason);
   case ACTIVATE -> {
    var policy=policy(a);if(before.draft()==null)throw changed();policy.requirePool(before.document().key().poolId());policy.requireReviewer(UUID.fromString(a.actorId()),before.draft());
    String definition=source.definitionSha256(before.document().key().poolId());if(definition==null)throw pending();if(!Objects.equals(expectedDefinition,definition)||!Objects.equals(expectedPolicy,policy.reference())||asset(c,a,before.draft().assetId())!=before.draft().assetVersion())throw changed();
    keyAvailable(c,before);var metadata=source.verify(before.document().key());
    // The source call is bounded. Never publish using a policy or asset snapshot changed during it.
    if(!Objects.equals(policy,policy(a))||!Objects.equals(definition,source.definitionSha256(before.document().key().poolId()))||asset(c,a,before.draft().assetId())!=before.draft().assetVersion())throw changed();authority(c,a,true);
    after=MonitoringObjectBinding.activate(before,expected,UUID.fromString(a.actorId()),policy,expectedPolicy,expectedDefinition,metadata,now(c),reason);
   }
   default -> throw invalid();
  }
  append(c,a,action,after);return receipt(after,action);
 });}


    @Override
    public CaptureReceipt capture(Access a, UUID id, long expected, String eventId, String expectedDefinition) {
        return transaction(a, true, c -> capture(c, a, id, expected, eventId, expectedDefinition, false).receipt());
    }

 /** Current ownership, exact raw acceptance and event/ticket effects commit or roll back together. */

    @Override
    public ProblemIntakeReceipt intakeProblem(
        Access a,
        UUID id,
        long expected,
        String eventId,
        String expectedDefinition
    ) {
        if (events == null) {
            throw new DomainException(503, "MAPPING_PROBLEM_INTAKE_DISABLED", "来源问题事件消费尚未装配");
        }
        return transaction(a, true, c -> {
            var captured = capture(c, a, id, expected, eventId, expectedDefinition, true);
            if (captured.receipt().outcome().startsWith("CONFLICT")) {
                return new ProblemIntakeReceipt(captured.receipt(), null);
            }
            var command = MappedMonitoringProblem.decode(captured.raw(), captured.sourceHost());
            var event = events.ingestMappedProblem(
                new MonitoringEventCenterRepository.Access(
                    a.tenantId(),
                    a.actorId(),
                    a.permissionVersion(),
                    a.traceId()
                ),
                command,
                captured.receipt().recordId()
            );
            platform.audit(
                a.actorId(),
                a.tenantId(),
                "monitoring_mapping.problem_intake",
                "monitoring_event",
                event.id(),
                "SUCCEEDED",
                "RAW:" + captured.receipt().recordId() + ";MAPPING:" + id + ";VERSION:" + expected,
                a.traceId()
            );
            return new ProblemIntakeReceipt(captured.receipt(), event);
        });
    }

 private record Captured(CaptureReceipt receipt,RawMonitoringEvent raw,String sourceHost){}

    /** The page, pending raw facts, event members, tickets and receipt share one durable command transaction. */


    @Override
    public MappedSourcePage.Receipt intakePage(
        Access a,
        UUID id,
        long expected,
        String expectedDefinition,
        MappedSourcePage.Window window
    ) {
        if (events == null) {
            throw new DomainException(503, "MAPPING_PAGE_INTAKE_DISABLED", "来源分页消费尚未装配");
        }
        Objects.requireNonNull(window);
        return transaction(a, true, c -> {
            var state = load(c, a, id, true);
            if (state.version() != expected) {
                throw changed();
            }
            var current = view(c, a, state);
            if (!"CURRENT_RECORDED_MAPPING".equals(current.availability())) {
                throw new DomainException(409, "MAPPING_NOT_CURRENT", "映射或归属证明已失效，请重新复核");
            }
            var key = state.document().key();
            if (key.objectType() != MonitoringObjectMapping.ObjectType.HOST) {
                throw new DomainException(422, "MAPPING_CAPTURE_HOST_REQUIRED", "分页消费须绑定明确主机");
            }
            if (!Objects.equals(expectedDefinition, current.sourceDefinitionSha256())) {
                throw changed();
            }
            var policy = policy(a);
            var metadata = source.verify(key);
            if (!state.active().source().equals(metadata)) {
                throw missing();
            }
            var page = source.readPage(key, window);
            if (
                page == null ||
                !window.equals(page.window()) ||
                page
                    .events()
                    .stream()
                    .anyMatch(event -> !metadata.sourceHostId().equals(event.sourceHostId()))
            ) {
                throw new DomainException(503, "MAPPING_SOURCE_PAGE_UNAVAILABLE", "来源页范围或主机归属不一致");
            }
            // Upstream reads are not atomic. Local ownership must still be current before the first write.
            if (
                !Objects.equals(policy, policy(a)) ||
                !Objects.equals(expectedDefinition, source.definitionSha256(key.poolId())) ||
                !"CURRENT_RECORDED_MAPPING".equals(view(c, a, state).availability())
            ) {
                throw changed();
            }
            authority(c, a, true);
            RawMonitoringEventSchemaVerifier.verify(c);
            MonitoringQuarantineSchemaVerifier.verify(c);
            durableSettings(c);
            var received = now(c);
            var items = new ArrayList<MappedSourcePage.Item>();
            for (var fetched : page.events()) {
                var raw = new RawMonitoringEvent(
                    new RawMonitoringEvent.Key(key.poolId(), fetched.sourceEventId(), fetched.phase()),
                    new RawMonitoringEvent.Binding(
                        state.document().tenantId(),
                        state.active().approvedDraft().assetId()
                    ),
                    fetched.occurredAt(),
                    received,
                    fetched.payload()
                );
                var capture = store(c, id, expected, fetched, raw);
                if (capture.outcome().startsWith("CONFLICT")) {
                    items.add(new MappedSourcePage.Item(capture, null, null, null, "SOURCE_KEY_CONFLICT"));
                } else if (fetched.phase() == RawMonitoringEvent.Phase.RECOVERED) {
                    items.add(new MappedSourcePage.Item(capture, null, null, null, "RECOVERY_OBSERVATION_REQUIRED"));
                } else {
                    try {
                        var command = MappedMonitoringProblem.decode(raw, metadata.sourceHostId());
                        var event = events.ingestMappedProblem(
                            new MonitoringEventCenterRepository.Access(
                                a.tenantId(),
                                a.actorId(),
                                a.permissionVersion(),
                                a.traceId()
                            ),
                            command,
                            capture.recordId()
                        );
                        items.add(
                            new MappedSourcePage.Item(capture, event.id(), event.version(), event.ticketId(), null)
                        );
                    } catch (DomainException failure) {
                        // These checks occur before business mutation. Every other error rolls back the entire page.
                        if (
                            failure.status != 422 ||
                            !Set.of(
                                "SOURCE_SEVERITY_UNCLASSIFIED",
                                "SOURCE_CLOCK_AHEAD",
                                "EVENT_SIGNAL_OUT_OF_ORDER"
                            ).contains(failure.code)
                        ) {
                            throw failure;
                        }
                        items.add(new MappedSourcePage.Item(capture, null, null, null, failure.code));
                    }
                }
            }
            // A long page cannot commit using a proof that expired while business effects were assembled.
            if (
                !Objects.equals(policy, policy(a)) ||
                !Objects.equals(expectedDefinition, source.definitionSha256(key.poolId())) ||
                !"CURRENT_RECORDED_MAPPING".equals(view(c, a, state).availability())
            ) {
                throw changed();
            }
            platform.audit(
                a.actorId(),
                a.tenantId(),
                "monitoring_mapping.page_intake",
                "monitoring_mapping",
                id.toString(),
                "SUCCEEDED",
                "VERSION:" +
                    expected +
                    ";COUNT:" +
                    items.size() +
                    ";PROCESSED:" +
                    items.stream().filter(MappedSourcePage.Item::businessProcessed).count(),
                a.traceId()
            );
            return new MappedSourcePage.Receipt(
                state.document().tenantId(),
                id,
                expected,
                window,
                items,
                page.nextEventId()
            );
        });
    }


    private Captured capture(
        Connection c,
        Access a,
        UUID id,
        long expected,
        String eventId,
        String expectedDefinition,
        boolean problemOnly
    ) throws Exception {
        var state = load(c, a, id, true);
        if (state.version() != expected) {
            throw changed();
        }
        var view = view(c, a, state);
        if (!"CURRENT_RECORDED_MAPPING".equals(view.availability())) {
            throw new DomainException(409, "MAPPING_NOT_CURRENT", "当前映射、资产或归属证明已失效，请重新复核");
        }
        var key = state.document().key();
        if (key.objectType() != MonitoringObjectMapping.ObjectType.HOST) {
            throw new DomainException(422, "MAPPING_CAPTURE_HOST_REQUIRED", "当前只支持明确主机映射的来源事件保存");
        }
        if (eventId == null || !eventId.matches("[1-9][0-9]{0,19}") || expectedDefinition == null) {
            throw invalid();
        }
        if (!Objects.equals(expectedDefinition, view.sourceDefinitionSha256())) {
            throw changed();
        }
        var policy = policy(a);
        RawMonitoringEventSchemaVerifier.verify(c);
        MonitoringQuarantineSchemaVerifier.verify(c);
        var metadata = source.verify(key);
        if (!state.active().source().equals(metadata)) {
            throw missing();
        }
        var fetched = source.readEvent(key, eventId);
        if (
            fetched == null ||
            !eventId.equals(fetched.sourceEventId()) ||
            !metadata.sourceHostId().equals(fetched.sourceHostId())
        ) {
            throw missing();
        }
        if (problemOnly && fetched.phase() != RawMonitoringEvent.Phase.PROBLEM) {
            throw new DomainException(
                422,
                "RECOVERY_REQUIRES_OBSERVATION",
                "原始恢复信号须通过当前周期观察证据核验，不能在问题事件入口确认恢复"
            );
        }
        // Source reads are not an upstream transaction; recheck the local authority after both bounded reads.
        if (
            !Objects.equals(policy, policy(a)) ||
            !Objects.equals(expectedDefinition, source.definitionSha256(key.poolId())) ||
            !"CURRENT_RECORDED_MAPPING".equals(view(c, a, state).availability())
        ) {
            throw changed();
        }
        authority(c, a, true);
        var received = now(c);
        var incoming = new RawMonitoringEvent(
            new RawMonitoringEvent.Key(key.poolId(), eventId, fetched.phase()),
            new RawMonitoringEvent.Binding(state.document().tenantId(), state.active().approvedDraft().assetId()),
            fetched.occurredAt(),
            received,
            fetched.payload()
        );
        durableSettings(c);
        var receipt = store(c, id, expected, fetched, incoming);
        platform.audit(
            a.actorId(),
            a.tenantId(),
            "monitoring_mapping.capture",
            "monitoring_mapping",
            id.toString(),
            "SUCCEEDED",
            "UNVERIFIED_RAW_ONLY_" + receipt.outcome(),
            a.traceId()
        );
        return new Captured(receipt, incoming, metadata.sourceHostId());
    }


    private static void durableSettings(Connection c) throws SQLException {
        try (var settings = c.createStatement()) {
            settings.setQueryTimeout(10);
            settings.execute("SET LOCAL synchronous_commit='on'");
            settings.execute("SET LOCAL lock_timeout='10s'");
            settings.execute("SET LOCAL statement_timeout='10s'");
        }
    }


    private static CaptureReceipt store(
        Connection c,
        UUID id,
        long expected,
        FetchedEvent fetched,
        RawMonitoringEvent incoming
    ) throws SQLException {
        String eventId = fetched.sourceEventId();
        var stored = PostgresMonitoringEventStorageCoordinator.storeInTransaction(c, incoming);
        CaptureReceipt receipt;
        if (stored instanceof PostgresMonitoringEventStorageCoordinator.RawResult raw) {
            var r = raw.receipt();
            receipt = new CaptureReceipt(
                id,
                expected,
                eventId,
                fetched.phase(),
                r.outcome() == PostgresRawMonitoringEventRepository.Outcome.STORED
                    ? "RAW_STORED"
                    : "RAW_ALREADY_PRESENT",
                r.storedId(),
                r.firstReceivedAt()
            );
        } else if (stored instanceof PostgresMonitoringEventStorageCoordinator.HeldResult held) {
            var r = held.receipt();
            receipt = new CaptureReceipt(
                id,
                expected,
                eventId,
                fetched.phase(),
                r.outcome() == PostgresMonitoringQuarantineRepository.Outcome.HELD
                    ? "CONFLICT_HELD"
                    : "CONFLICT_ALREADY_HELD",
                r.quarantineId(),
                r.firstReceivedAt()
            );
        } else {
            throw unavailable();
        }
        return receipt;
    }
 private void keyAvailable(Connection c,State state)throws SQLException{
  var key=state.document().key();try(var s=query(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"mop.mapping.key|"+key.poolId()+"|"+key.objectType()+"|"+key.externalId());s.execute();}
  try(var s=query(c,"SELECT 1 FROM public.mop_monitoring_object_binding b JOIN LATERAL (SELECT r.state FROM public.mop_monitoring_object_binding_revision r WHERE r.tenant_id=b.tenant_id AND r.id=b.id ORDER BY r.version DESC LIMIT 1) latest ON true WHERE b.pool_id=? AND b.object_type=? AND b.external_id=? AND (b.tenant_id,b.id)<>(?,?) AND latest.state->'active'<>'null'::jsonb LIMIT 1")){
   s.setObject(1,key.poolId());s.setString(2,key.objectType().name());s.setString(3,key.externalId());s.setObject(4,state.document().tenantId());s.setObject(5,state.document().id());try(var r=s.executeQuery()){if(r.next())throw missing();}
  }
 }
 private long asset(Connection c,Access a,UUID id)throws SQLException{
  if(id==null)throw invalid();if(platform.authorizedAssetBatch(a.tenantId(),a.actorId(),a.permissionVersion(),List.of(id.toString())).size()!=1)throw missing();
  try(var s=query(c,"SELECT version FROM public.mop_asset WHERE tenant_id=?::uuid AND id=? FOR SHARE")){s.setString(1,a.tenantId());s.setObject(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return r.getLong(1);}}
 }
 private boolean reviewer(Connection c,Access a,UUID actor)throws SQLException{
  try(var s=query(c,"SELECT m.permission_version,(ARRAY['assets:read','configurations:read','configurations:write']<@public.mop_effective_capabilities(m.user_id,m.tenant_id)) FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=? AND m.active AND u.active AND m.role_code='padmin' FOR SHARE OF m,u")){
   s.setString(1,a.tenantId());s.setObject(2,actor);try(var r=s.executeQuery()){return r.next()&&r.getBoolean(2);}
  }
 }
 private void append(Connection c,Access a,Action action,State state)throws Exception{
  try(var s=query(c,"INSERT INTO public.mop_monitoring_object_binding_revision(tenant_id,id,version,action,state,actor_id,permission_version,recorded_at) VALUES(?::uuid,?,?,?,?::jsonb,?::uuid,?,?)")){
   s.setString(1,a.tenantId());s.setObject(2,state.document().id());s.setLong(3,state.version());s.setString(4,action.name());s.setString(5,json.writeValueAsString(state));s.setString(6,a.actorId());s.setLong(7,a.permissionVersion());time(s,8,state.updatedAt());s.executeUpdate();
  }
  platform.audit(a.actorId(),a.tenantId(),"monitoring_mapping."+action.name().toLowerCase(Locale.ROOT),"monitoring_mapping",state.document().id().toString(),"SUCCEEDED","MAPPING_ONLY_NOT_COLLECTION_OR_DISPATCH",a.traceId());
 }
 private Policy policy(Access a){var value=policies.apply(a.tenantId());return value==null?new Policy(null,null,null,null,null):value;}
 private static Receipt receipt(State s,Action action){return new Receipt(s.document().id(),s.version(),action);}
 private static PreparedStatement query(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
 private static Instant now(Connection c)throws SQLException{try(var s=query(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){if(!r.next())throw unavailable();return r.getObject(1,OffsetDateTime.class).toInstant();}}
 private static void time(PreparedStatement s,int i,Instant t)throws SQLException{s.setObject(i,OffsetDateTime.ofInstant(t,ZoneOffset.UTC));}
 private static void validate(Access a){if(a==null||a.permissionVersion()<1||a.permissionVersion()>MonitoringObjectBinding.MAX_VERSION||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();for(var value:List.of(a.tenantId(),a.actorId()))if(!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw invalid();}
 private static DomainException invalid(){return new DomainException(400,"INVALID_MONITORING_MAPPING","映射字段无效");}
 private static DomainException changed(){return new DomainException(409,"MAPPING_VERSION_CONFLICT","映射、资产或审批来源已变化，请重新读取");}
 private static DomainException pending(){return new DomainException(503,"MONITORING_MAPPING_SOURCE_PENDING","映射来源尚未明确配置");}
 private static DomainException missing(){return new DomainException(404,"MONITORING_MAPPING_NOT_FOUND","当前范围内没有该映射或资产");}
 /** Keeps diagnostic type, SQL state and stack location without retaining driver messages or source payloads. */

    private static DomainException unavailable(Exception cause) {
        var failure = unavailable();
        var safe = new IllegalStateException(
            "mapping_failure_type=" +
                cause.getClass().getSimpleName() +
                ";sql_state=" +
                (cause instanceof SQLException sql ? sql.getSQLState() : "NONE")
        );
        safe.setStackTrace(cause.getStackTrace());
        failure.initCause(safe);
        return failure;
    }

    private static DomainException unavailable() {
        return new DomainException(503, "MONITORING_MAPPING_UNAVAILABLE", "映射结构、来源或受限权限尚未就绪");
    }
}
