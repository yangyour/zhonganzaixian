package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.change.*;
import cn.mop.domain.change.ChangeNoticeApproval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Append-only review evidence; reads and writes never confer runtime execution or suppression. */
public final class PostgresChangeNoticeApprovalRepository implements ChangeNoticeApprovalRepository {
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    private final ObjectMapper mapper;
    public PostgresChangeNoticeApprovalRepository(DataSource source, PostgresPlatformRepository platform, ObjectMapper mapper) {
        this.source=Objects.requireNonNull(source); this.platform=Objects.requireNonNull(platform); this.mapper=Objects.requireNonNull(mapper);
    }
    @Override public void authorizeCurrentAccess(Access a,String id,long revision,Action action) {
        Connection c=platform.idempotentCommandConnection();
        try { membership(c,a,true); var initial=initial(c,a,id,revision,true); authority(c,a,initial,action); }
        catch(SQLException e) { throw unavailable(); }
    }
    @Override public Snapshot transition(Access a,String id,long revision,long expected,Action action,String note) {
        Connection c=platform.idempotentCommandConnection();
        try {
            membership(c,a,true);
            Snapshot initial=initial(c,a,id,revision,true);
            Actor actor=authority(c,a,initial,action);
            Snapshot before=replay(c,a,initial);
            Transition transition=ChangeNoticeApproval.apply(before,expected,action,actor,note,now(c));
            Event e=transition.event();
            Instant recordedAt;
            try(var s=statement(c,"INSERT INTO public.mop_change_notice_approval_event(tenant_id,notice_id,draft_revision,content_sha256,version,from_state,to_state,action,actor_id,permission_version,review_policy_release_id,note) VALUES (?::uuid,?::uuid,?,?, ?,?,?,?,?::uuid,?,?::uuid,?) RETURNING occurred_at")) {
                s.setString(1,a.tenantId()); s.setString(2,id); s.setLong(3,revision); s.setString(4,e.fingerprint()); s.setLong(5,e.version());
                s.setString(6,e.from().name()); s.setString(7,e.to().name()); s.setString(8,e.action().name()); s.setString(9,a.actorId()); s.setLong(10,a.permissionVersion());
                s.setObject(11,e.reviewPolicyReleaseId()); s.setString(12,e.note());
                try(var row=s.executeQuery()){if(!row.next())throw unavailable();recordedAt=instant(row,"occurred_at");if(row.next())throw unavailable();}
            }
            platform.audit(a.actorId(),a.tenantId(),"change_notice.approval."+action.name().toLowerCase(Locale.ROOT),"change_notice_draft",id,"SUCCEEDED","REVISION_REVIEW_ONLY_NO_RUNTIME_ACTIVATION",a.traceId());
            // Receipt and subsequent readback must use the exact authoritative persisted timestamp.
            return ChangeNoticeApproval.apply(before,expected,action,actor,note,recordedAt).snapshot();
        } catch(SQLException e) { throw unavailable(); }
    }
    @Override public View view(Access a,String id,long revision) {
        return read(a,c->{
            Snapshot snapshot=replay(c,a,initial(c,a,id,revision,false));
            boolean eligible;
            try(var q=statement(c,"SELECT h.version=r.version AND NOT r.archived FROM public.mop_change_notice_draft h JOIN public.mop_change_notice_revision r ON (h.tenant_id,h.id)=(r.tenant_id,r.notice_id) WHERE h.tenant_id=?::uuid AND h.id=?::uuid AND r.version=?")) {
                q.setString(1,a.tenantId());q.setString(2,id);q.setLong(3,revision);try(var rows=q.executeQuery()){if(!rows.next())throw missing();eligible=rows.getBoolean(1);}
            }
            if(!eligible||!PostgresAuthorization.capabilities(c,a.actorId(),a.tenantId()).contains("change_notice_drafts:write"))return new View(snapshot,List.of());
            Instant observed=now(c);List<Action> actions=new ArrayList<>();
            for(Action action:Action.values()) {
                boolean stateAllows=switch(action) {
                    case SUBMIT -> snapshot.state()==State.DRAFT;
                    case WITHDRAW -> snapshot.state()==State.DRAFT||snapshot.state()==State.IN_REVIEW;
                    case APPROVE,REJECT -> snapshot.state()==State.IN_REVIEW;
                };
                if(!stateAllows)continue;
                try { authority(c,a,snapshot,action,false,observed);actions.add(action); }
                catch(DomainException denied) {if(denied.status!=403&&!(denied.status==422&&"CONFIGURATION_PENDING".equals(denied.code)))throw denied;}
            }
            return new View(snapshot,actions);
        });
    }
    @Override public Snapshot get(Access a,String id,long revision) { return read(a,c->replay(c,a,initial(c,a,id,revision,false))); }
    @Override public List<Event> history(Access a,String id,long revision,int limit,Long beforeVersion) {
        if(limit<1||limit>101||beforeVersion!=null&&(beforeVersion<2||beforeVersion>9007199254740990L))throw invalid();
        return read(a,c->{Snapshot initial=initial(c,a,id,revision,false); replay(c,a,initial); List<Event> events=new ArrayList<>();
            try(var s=statement(c,"SELECT * FROM public.mop_change_notice_approval_event WHERE tenant_id=?::uuid AND notice_id=?::uuid AND draft_revision=?"+(beforeVersion==null?"":" AND version<?")+" ORDER BY version DESC LIMIT ?")) {
                s.setString(1,a.tenantId()); s.setString(2,id); s.setLong(3,revision); int p=4; if(beforeVersion!=null)s.setLong(p++,beforeVersion); s.setInt(p,limit);
                try(var r=s.executeQuery()) { while(r.next())events.add(event(r)); }
            } return List.copyOf(events);
        });
    }
    private Snapshot initial(Connection c,Access a,String id,long revision,boolean lock)throws SQLException {
        uuid(id); if(revision<1||revision>9007199254740990L)throw invalid();
        // This same head lock is taken by draft edits and by the SQL event guard.
        try(var s=statement(c,"SELECT version FROM public.mop_change_notice_draft WHERE tenant_id=?::uuid AND id=?::uuid"+(lock?" FOR UPDATE":""))) {
            s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();if(lock&&r.getLong(1)!=revision)throw new DomainException(409,"VERSION_CONFLICT","报备草稿修订已变化，请刷新后重新提交审批");}
        }
        try(var s=statement(c,"SELECT h.applicant_id,r.* FROM public.mop_change_notice_revision r JOIN public.mop_change_notice_draft h ON (h.tenant_id,h.id)=(r.tenant_id,r.notice_id) WHERE r.tenant_id=?::uuid AND r.notice_id=?::uuid AND r.version=?")) {
            s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,revision);
            try(var r=s.executeQuery()) {
                if(!r.next())throw missing();
                if(lock&&r.getBoolean("archived"))throw new DomainException(422,"STATE_NOT_ALLOWED","归档修订不能进入审批");
                List<UUID> assets=new ArrayList<>();
                try(var targets=statement(c,"SELECT position,asset_id FROM public.mop_change_notice_asset WHERE tenant_id=?::uuid AND notice_id=?::uuid AND version=? ORDER BY position LIMIT 1001")) {
                    targets.setString(1,a.tenantId());targets.setString(2,id);targets.setLong(3,revision);
                    try(var rows=targets.executeQuery()){while(rows.next()){if(rows.getInt(1)!=assets.size()+1)throw unavailable();assets.add(rows.getObject(2,UUID.class));}}
                }
                if(assets.size()!=r.getInt("asset_count"))throw unavailable();
                try {
                    var draft=new ChangeNoticeDraft(UUID.fromString(a.tenantId()),r.getObject("applicant_id",UUID.class),r.getObject("ticket_id",UUID.class),r.getString("change_summary"),instant(r,"planned_start_at"),instant(r,"planned_end_at"),assets);
                    if(!draft.fingerprint().equals(r.getString("content_sha256")))throw unavailable();
                    return ChangeNoticeApproval.create(UUID.fromString(id),revision,draft,instant(r,"recorded_at"));
                } catch(RuntimeException corruption){throw unavailable();}
            }
        }
    }
    private Snapshot replay(Connection c,Access a,Snapshot initial)throws SQLException {
        Snapshot state=initial;
        try(var s=statement(c,"SELECT * FROM public.mop_change_notice_approval_event WHERE tenant_id=?::uuid AND notice_id=?::uuid AND draft_revision=? ORDER BY version LIMIT 4")) {
            s.setString(1,a.tenantId());s.setObject(2,initial.noticeId());s.setLong(3,initial.draftRevision());
            try(var r=s.executeQuery()) { while(r.next()) {
                try {
                    Event saved=event(r);
                    if(!saved.noticeId().equals(initial.noticeId())||saved.draftRevision()!=initial.draftRevision()||!saved.fingerprint().equals(initial.fingerprint())||saved.from()!=state.state()||saved.version()!=state.version()+1)throw unavailable();
                    Actor actor=new Actor(initial.draft().tenantId(),saved.actorId(),saved.permissionVersion(),true,saved.reviewPolicyReleaseId(),saved.reviewPolicyReleaseId()!=null);
                    Transition calculated=ChangeNoticeApproval.apply(state,state.version(),saved.action(),actor,saved.note(),saved.occurredAt());
                    if(!calculated.event().equals(saved))throw unavailable();
                    state=calculated.snapshot();
                } catch(RuntimeException corruption) {throw unavailable();}
            }}
        } return state;
    }
    private Actor authority(Connection c,Access a,Snapshot initial,Action action)throws SQLException { return authority(c,a,initial,action,true,now(c)); }
    private Actor authority(Connection c,Access a,Snapshot initial,Action action,boolean lockPolicy,Instant observed)throws SQLException {
        if(action==null)throw invalid();
        boolean applicant=initial.draft().applicantId().toString().equals(a.actorId());
        boolean review=action==Action.APPROVE||action==Action.REJECT;
        if(review==applicant)throw forbidden();
        UUID release=null; boolean allowed=false;
        if(review) {
            // Matches the runtime publisher's serialization key; a concurrent disable cannot be missed.
            if(lockPolicy)try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"mop.runtime.configuration|"+a.tenantId()+"|"+REVIEW_POLICY_KEY);s.execute();}
            if(lockPolicy)observed=now(c); // Sample after a waiting publisher lock, including receipt replay.
            try(var s=statement(c,"SELECT id,action,value_json,expires_at FROM public.mop_runtime_configuration_release WHERE tenant_id=?::uuid AND configuration_key=? AND effective_at<=? ORDER BY version DESC LIMIT 1")) {
                s.setString(1,a.tenantId());s.setString(2,REVIEW_POLICY_KEY);s.setObject(3,OffsetDateTime.ofInstant(observed,ZoneOffset.UTC));
                try(var r=s.executeQuery()) {
                    if(r.next()&&!"DISABLE".equals(r.getString("action"))&&(instant(r,"expires_at")==null||observed.isBefore(instant(r,"expires_at")))) {
                        Set<UUID> reviewers=reviewers(r.getString("value_json"),mapper);
                        release=r.getObject("id",UUID.class); allowed=reviewers.contains(UUID.fromString(a.actorId()));
                    }
                }
            }
            if(release==null)throw new DomainException(422,"CONFIGURATION_PENDING","Q09报备审批岗位尚未配置生效");
            if(!allowed)throw forbidden();
        }
        return new Actor(UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),a.permissionVersion(),true,release,allowed);
    }
    static Set<UUID> reviewers(String json,ObjectMapper mapper) {
        try {
            com.fasterxml.jackson.databind.JsonNode root;
            try(var parser=mapper.getFactory().createParser(json)) { parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION); root=mapper.readTree(parser); if(parser.nextToken()!=null)throw invalid(); }
            if(root==null||!root.isObject()||root.size()!=1||!root.has("reviewer_user_ids")||!root.get("reviewer_user_ids").isArray()||root.get("reviewer_user_ids").isEmpty()||root.get("reviewer_user_ids").size()>1000)throw invalid();
            Set<UUID> ids=new HashSet<>(); for(var n:root.get("reviewer_user_ids")) {if(!n.isTextual()||!ids.add(UUID.fromString(uuid(n.textValue()))))throw invalid();}
            return Set.copyOf(ids);
        } catch(Exception malformed) {throw new DomainException(422,"CONFIGURATION_PENDING","Q09报备审批配置格式无效，未授予审批权限");}
    }
    private static void membership(Connection c,Access a,boolean lock)throws SQLException {
        if(a==null||a.permissionVersion()<1)throw expired();uuid(a.actorId());uuid(a.tenantId());
        if(a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))) {
            s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(1)!=a.permissionVersion())throw expired();}
        }
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"change_notice_drafts:write":"change_notice_drafts:read");
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access a,Read<T> work) {
        platform.requireStandaloneRead();
        try(Connection c=source.getConnection()) {c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{membership(c,a,false);T result=work.run(c);c.commit();return result;}catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}
        }catch(SQLException e){throw unavailable();}
    }
    private static Event event(ResultSet r)throws SQLException {
        for(String flag:List.of("effective","device_execution_allowed","alert_suppression_allowed"))if(r.getBoolean(flag)||r.wasNull())throw unavailable();
        return new Event(r.getObject("notice_id",UUID.class),r.getLong("draft_revision"),r.getString("content_sha256"),r.getLong("version"),State.valueOf(r.getString("from_state")),State.valueOf(r.getString("to_state")),Action.valueOf(r.getString("action")),r.getObject("actor_id",UUID.class),r.getLong("permission_version"),r.getObject("review_policy_release_id",UUID.class),r.getString("note"),instant(r,"occurred_at"));}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);ChangeNoticeApprovalSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException e){return List.of("CHANGE_NOTICE_APPROVAL_STRUCTURE_UNAVAILABLE");}}
    private static Instant now(Connection c)throws SQLException {try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){r.next();return r.getObject(1,OffsetDateTime.class).toInstant();}}
    private static Instant instant(ResultSet r,String column)throws SQLException {var value=r.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static String uuid(String value){try{if(value==null||!UUID.fromString(value).toString().equals(value))throw invalid();return value;}catch(IllegalArgumentException e){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","报备审批参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException forbidden(){return new DomainException(403,"ACTION_FORBIDDEN","当前身份不能执行此报备审批动作");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","报备审批结构或数据未就绪，未确认写入成功");}
}
