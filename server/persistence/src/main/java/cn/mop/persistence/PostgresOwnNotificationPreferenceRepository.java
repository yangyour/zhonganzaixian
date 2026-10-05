package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.contact.CustomerContact.QuietWindow;
import cn.mop.domain.notification.DeliveryReceiptState.Channel;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft;
import cn.mop.domain.notification.OwnNotificationPreferenceDraft.LevelPreference;
import cn.mop.domain.notification.OwnNotificationPreferenceRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Tenant and current-owner isolation; no administrator override or notification provider access. */
public final class PostgresOwnNotificationPreferenceRepository implements OwnNotificationPreferenceRepository {
    private static final long MAX_VERSION=9_007_199_254_740_990L;
    private static final String SELECT="SELECT h.id::text,h.created_at,h.updated_at,r.* FROM public.mop_notification_preference_draft h JOIN public.mop_notification_preference_revision r ON (r.tenant_id,r.preference_id,r.version)=(h.tenant_id,h.id,h.version)";
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    public PostgresOwnNotificationPreferenceRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}

    @Override public void authorizeCurrentAccess(Access a,String id){
        Connection c=platform.idempotentCommandConnection();
        try{authorize(c,a,true);if(id!=null)get(c,a,uuid(id));}catch(SQLException e){throw unavailable();}
    }
    @Override public Snapshot create(Access a,OwnNotificationPreferenceDraft draft){
        Connection c=platform.idempotentCommandConnection();
        try{
            authorize(c,a,true);scope(a,draft);
            // A missing singleton has no row to lock. Serialize the owner slot without upgrading membership SHARE locks.
            try(var s=statement(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){s.setString(1,"own_notification_preference|"+a.tenantId()+"|"+a.actorId());s.execute();}
            try(var s=statement(c,"SELECT id FROM public.mop_notification_preference_draft WHERE tenant_id=?::uuid AND owner_id=?::uuid")){
                s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(r.next())throw conflict();}
            }
            String id=UUID.randomUUID().toString();Instant recorded;
            try(var s=statement(c,"INSERT INTO public.mop_notification_preference_draft(id,tenant_id,owner_id) VALUES (?::uuid,?::uuid,?::uuid) RETURNING created_at")){
                s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());try(var r=s.executeQuery()){if(!r.next())throw unavailable();recorded=time(r,"created_at");}
            }
            revision(c,a,id,1,recorded,draft);audit(a,id,"create");return get(c,a,id);
        }catch(SQLException e){throw unavailable();}
    }
    @Override public Snapshot update(Access a,String id,long expected,OwnNotificationPreferenceDraft draft){
        Connection c=platform.idempotentCommandConnection();String key=uuid(id);if(expected<1||expected>=MAX_VERSION)throw invalid();
        try{
            authorize(c,a,true);scope(a,draft);
            try(var s=statement(c,"SELECT version FROM public.mop_notification_preference_draft WHERE tenant_id=?::uuid AND owner_id=?::uuid AND id=?::uuid FOR UPDATE")){
                s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setString(3,key);try(var r=s.executeQuery()){if(!r.next())throw missing();if(r.getLong(1)!=expected)throw conflict();}
            }
            // Validate the existing complete revision before appending another one.
            get(c,a,key);Instant recorded;
            try(var s=statement(c,"UPDATE public.mop_notification_preference_draft SET version=version+1,updated_at=GREATEST(updated_at,clock_timestamp()::timestamptz(3)) WHERE tenant_id=?::uuid AND owner_id=?::uuid AND id=?::uuid AND version=? RETURNING updated_at")){
                s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setString(3,key);s.setLong(4,expected);try(var r=s.executeQuery()){if(!r.next())throw conflict();recorded=time(r,"updated_at");}
            }
            revision(c,a,key,expected+1,recorded,draft);audit(a,key,"update");return get(c,a,key);
        }catch(SQLException e){throw unavailable();}
    }
    private static void scope(Access a,OwnNotificationPreferenceDraft d){if(d==null)throw invalid();if(!a.tenantId().equals(d.tenantId().toString())||!a.actorId().equals(d.ownerId().toString()))throw missing();}
    private static void revision(Connection c,Access a,String id,long version,Instant recorded,OwnNotificationPreferenceDraft d)throws SQLException{
        java.sql.Array channels=d.requestedChannels()==null?null:c.createArrayOf("text",d.requestedChannels().stream().map(Enum::name).toArray(String[]::new));
        try(var s=statement(c,"INSERT INTO public.mop_notification_preference_revision(tenant_id,preference_id,owner_id,version,previous_version,recorded_by,recorded_permission_version,recorded_at,action,requested_level,requested_channels,quiet_window_supplied,quiet_enabled,quiet_start,quiet_end,content_sha256) VALUES (?::uuid,?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?,?,?,?,?)")){
            s.setString(1,a.tenantId());s.setString(2,id);s.setString(3,a.actorId());s.setLong(4,version);if(version==1)s.setNull(5,Types.BIGINT);else s.setLong(5,version-1);
            s.setString(6,a.actorId());s.setLong(7,a.permissionVersion());s.setObject(8,OffsetDateTime.ofInstant(recorded,ZoneOffset.UTC));s.setString(9,version==1?"CREATE":"UPDATE");s.setString(10,d.requestedLevel()==null?null:d.requestedLevel().name());if(channels==null)s.setNull(11,Types.ARRAY);else s.setArray(11,channels);
            QuietWindow q=d.requestedQuietWindow();s.setBoolean(12,q!=null);if(q==null||q.enabled()==null)s.setNull(13,Types.BOOLEAN);else s.setBoolean(13,q.enabled());s.setString(14,q==null||q.start()==null?null:q.start().toString());s.setString(15,q==null||q.end()==null?null:q.end().toString());s.setString(16,d.fingerprint());if(s.executeUpdate()!=1)throw unavailable();
        }finally{if(channels!=null)channels.free();}
    }
    private void audit(Access a,String id,String action){platform.audit(a.actorId(),a.tenantId(),"notification_preference.draft."+action,"notification_preference_draft",id,"SUCCEEDED","OWNER_REQUEST_ONLY_NO_DISPATCH",a.traceId());}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T readonly(Access a,Read<T> f){
        platform.requireStandaloneRead();
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,a,false);T value=f.run(c);c.commit();return value;}
            catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}
        }catch(SQLException e){throw unavailable();}
    }
    @Override public Optional<Snapshot> current(Access a){return readonly(a,c->{try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.owner_id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){return r.next()?Optional.of(snapshot(a,r)):Optional.empty();}}});}
    @Override public Snapshot get(Access a,String id){String key=uuid(id);return readonly(a,c->get(c,a,key));}
    private static Snapshot get(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.owner_id=?::uuid AND h.id=?::uuid")){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setString(3,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return snapshot(a,r);}
        }
    }
    @Override public List<Revision> history(Access a,String id,int limit,Long before){
        String key=uuid(id);if(limit<1||limit>101||before!=null&&(before<1||before>MAX_VERSION))throw invalid();
        return readonly(a,c->{get(c,a,key);List<Revision> values=new ArrayList<>();
            try(var s=statement(c,"SELECT r.* FROM public.mop_notification_preference_revision r WHERE r.tenant_id=?::uuid AND r.owner_id=?::uuid AND r.preference_id=?::uuid"+(before==null?"":" AND r.version<?")+" ORDER BY r.version DESC LIMIT ?")){
                s.setString(1,a.tenantId());s.setString(2,a.actorId());s.setString(3,key);int p=4;if(before!=null)s.setLong(p++,before);s.setInt(p,limit);
                try(var r=s.executeQuery()){while(r.next()){validate(a,r);values.add(new Revision(key,a.tenantId(),r.getLong("version"),r.getString("action"),draft(a,r),r.getString("recorded_by"),r.getLong("recorded_permission_version"),time(r,"recorded_at")));}}
            }return List.copyOf(values);
        });
    }
    private static Snapshot snapshot(Access a,ResultSet r)throws SQLException{
        validate(a,r);Instant created=time(r,"created_at"),updated=time(r,"updated_at"),recorded=time(r,"recorded_at");
        if(updated.isBefore(created)||!updated.equals(recorded)||r.getLong("version")==1&&!created.equals(recorded))throw unavailable();
        return new Snapshot(r.getString("id"),a.tenantId(),r.getLong("version"),draft(a,r),r.getString("recorded_by"),r.getLong("recorded_permission_version"),created,updated);
    }
    private static void validate(Access a,ResultSet r)throws SQLException{
        long v=r.getLong("version"),pv=r.getLong("recorded_permission_version");Long previous=(Long)r.getObject("previous_version");
        if(v<1||v>MAX_VERSION||pv<1||pv>MAX_VERSION+1||!a.tenantId().equals(r.getString("tenant_id"))||!a.actorId().equals(r.getString("owner_id"))||!a.actorId().equals(r.getString("recorded_by"))
            ||v==1&&(previous!=null||!"CREATE".equals(r.getString("action")))||v>1&&(previous==null||previous!=v-1||!"UPDATE".equals(r.getString("action")))
            ||!"Asia/Shanghai".equals(r.getString("timezone"))||!"CONFIG_PENDING".equals(r.getString("configuration_status"))||!"NOT_VERIFIED".equals(r.getString("receiver_binding_status"))||!"OWN_NOTIFICATION_PREFERENCE_DRAFT".equals(r.getString("record_source")))throw unavailable();
        for(String name:List.of("effective","notification_dispatched"))if(!Boolean.FALSE.equals(r.getObject(name)))throw unavailable();
        for(String name:List.of("effective_level","effective_channels","effective_quiet_window","verified_receiver_id","delivery_decision"))if(r.getObject(name)!=null)throw unavailable();
    }
    private static OwnNotificationPreferenceDraft draft(Access a,ResultSet r)throws SQLException{
        try{
            String level=r.getString("requested_level");List<Channel> channels=null;java.sql.Array array=r.getArray("requested_channels");
            if(array!=null)try{Object raw=array.getArray();if(!(raw instanceof String[] names))throw unavailable();channels=Arrays.stream(names).map(Channel::valueOf).toList();}finally{array.free();}
            Boolean supplied=(Boolean)r.getObject("quiet_window_supplied"),enabled=(Boolean)r.getObject("quiet_enabled");String start=r.getString("quiet_start"),end=r.getString("quiet_end");
            if(supplied==null||!supplied&&(enabled!=null||start!=null||end!=null))throw unavailable();
            QuietWindow quiet=supplied?new QuietWindow(enabled,start==null?null:QuietWindow.parseMinute(start),end==null?null:QuietWindow.parseMinute(end)):null;
            var value=new OwnNotificationPreferenceDraft(UUID.fromString(a.tenantId()),UUID.fromString(a.actorId()),level==null?null:LevelPreference.valueOf(level),channels,quiet);
            if(!Objects.equals(channels,value.requestedChannels())||!value.fingerprint().equals(r.getString("content_sha256")))throw unavailable();return value;
        }catch(RuntimeException e){throw unavailable();}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"notification_preferences:write_own":"notification_preferences:read_own");

        if(a==null||a.permissionVersion()<1||a.permissionVersion()>MAX_VERSION+1)throw expired();
        if(!uuid(a.actorId()).equals(a.actorId())||!uuid(a.tenantId()).equals(a.tenantId())||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){
            s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!Set.of("padmin","msp","tadmin","viewer").contains(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色不能访问本人通知偏好草稿");}
        }
    }
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);NotificationPreferenceSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException e){return List.of("NOTIFICATION_PREFERENCE_DRAFT_STRUCTURE_UNAVAILABLE");}}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static String uuid(String s){if(s==null||!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(s).toString();}
    private static Instant time(ResultSet r,String key)throws SQLException{var t=r.getObject(key,OffsetDateTime.class);if(t==null)throw unavailable();Instant v=t.toInstant();if(v.getNano()%1000000!=0||v.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||v.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw unavailable();return v;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","本人通知偏好请求、版本或查询边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","本人通知偏好草稿已变化，请刷新核对后提交");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","本人通知偏好草稿结构、数据或数据库未就绪，未确认写入成功");}
}
