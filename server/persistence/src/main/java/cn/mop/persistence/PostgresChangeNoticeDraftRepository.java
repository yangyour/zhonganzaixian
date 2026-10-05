package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.change.ChangeNoticeDraft;
import cn.mop.domain.change.ChangeNoticeDraftRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Current padmin draft maintenance only; grants no approval, suppression, event matching or device authority. */
public final class PostgresChangeNoticeDraftRepository implements ChangeNoticeDraftRepository {
    private static final long MAX_VERSION=9_007_199_254_740_990L;
    private static final String SELECT="SELECT h.id::text,h.applicant_id,h.created_at,h.updated_at,r.* FROM public.mop_change_notice_draft h JOIN public.mop_change_notice_revision r ON (r.tenant_id,r.notice_id,r.version)=(h.tenant_id,h.id,h.version)";
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresChangeNoticeDraftRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public void authorizeCurrentAccess(Access a,String id){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);if(id!=null)get(c,a,uuid(id));}catch(SQLException e){throw unavailable();}}
    @Override public Snapshot create(Access a,ChangeNoticeDraft draft){
        Connection c=platform.idempotentCommandConnection();if(draft==null)throw invalid();
        try{authorize(c,a,true);if(!draft.applicantId().toString().equals(a.actorId()))throw invalid();targets(c,a,draft);String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_change_notice_draft(id,tenant_id,applicant_id) VALUES (?::uuid,?::uuid,?::uuid)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());if(s.executeUpdate()!=1)throw unavailable();}
            revision(c,a,id,1,"CREATE",draft);audit(a,id,"create");return get(c,a,id);
        }catch(SQLException e){throw unavailable();}
    }
    @Override public Snapshot update(Access a,String id,long expected,ChangeNoticeDraft draft){if(draft==null)throw invalid();return change(a,id,expected,"UPDATE",draft);}
    @Override public Snapshot archive(Access a,String id,long expected){return change(a,id,expected,"ARCHIVE",null);}
    @Override public Snapshot restore(Access a,String id,long expected){return change(a,id,expected,"RESTORE",null);}
    private Snapshot change(Access a,String id,long expected,String action,ChangeNoticeDraft supplied){
        Connection c=platform.idempotentCommandConnection();String key=uuid(id);if(expected<1||expected>=MAX_VERSION)throw invalid();
        try{authorize(c,a,true);
            try(var s=statement(c,"SELECT version FROM public.mop_change_notice_draft WHERE tenant_id=?::uuid AND id=?::uuid FOR UPDATE")){s.setString(1,a.tenantId());s.setString(2,key);try(var r=s.executeQuery()){if(!r.next())throw missing();if(r.getLong(1)!=expected)throw conflict();}}
            Snapshot before=get(c,a,key);if(before.archived()!=action.equals("RESTORE"))throw new DomainException(409,"CHANGE_NOTICE_LIFECYCLE_CONFLICT","归档草稿须明确恢复；恢复不会批准报备");
            ChangeNoticeDraft value=action.equals("UPDATE")?supplied:before.draft();if(!value.applicantId().equals(before.draft().applicantId()))throw invalid();targets(c,a,value);
            try(var s=statement(c,"UPDATE public.mop_change_notice_draft SET version=version+1,updated_at=statement_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")){s.setString(1,a.tenantId());s.setString(2,key);s.setLong(3,expected);if(s.executeUpdate()!=1)throw conflict();}
            revision(c,a,key,expected+1,action,value);audit(a,key,action.toLowerCase(Locale.ROOT));return get(c,a,key);
        }catch(SQLException e){throw unavailable();}
    }
    private static void targets(Connection c,Access a,ChangeNoticeDraft d)throws SQLException{
        if(!d.tenantId().toString().equals(a.tenantId()))throw missing();
        if(d.ticketId()!=null)try(var s=statement(c,"SELECT id FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid FOR SHARE")){s.setString(1,a.tenantId());s.setString(2,d.ticketId().toString());try(var r=s.executeQuery()){if(!r.next())throw missing();}}
        var array=c.createArrayOf("uuid",d.assetIds().toArray());
        try(var s=statement(c,"SELECT id FROM public.mop_asset WHERE tenant_id=?::uuid AND id=ANY(?) ORDER BY id FOR SHARE")){
            s.setString(1,a.tenantId());s.setArray(2,array);Set<UUID> actual=new HashSet<>();try(var r=s.executeQuery()){while(r.next())actual.add(r.getObject(1,UUID.class));}if(!actual.equals(new HashSet<>(d.assetIds())))throw missing();
        }finally{array.free();}
    }
    private static void revision(Connection c,Access a,String id,long version,String action,ChangeNoticeDraft d)throws SQLException{
        try(var s=statement(c,"INSERT INTO public.mop_change_notice_revision(tenant_id,notice_id,version,previous_version,recorded_by,action,archived,ticket_id,change_summary,planned_start_at,planned_end_at,asset_count,content_sha256) VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?,?::uuid,?,?,?, ?,?)")){
            s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setLong(4,version-1);s.setString(5,a.actorId());s.setString(6,action);s.setBoolean(7,action.equals("ARCHIVE"));s.setString(8,d.ticketId()==null?null:d.ticketId().toString());s.setString(9,d.changeSummary());s.setObject(10,OffsetDateTime.ofInstant(d.plannedStartAt(),ZoneOffset.UTC));if(d.plannedEndAt()==null)s.setNull(11,Types.TIMESTAMP_WITH_TIMEZONE);else s.setObject(11,OffsetDateTime.ofInstant(d.plannedEndAt(),ZoneOffset.UTC));s.setInt(12,d.assetIds().size());s.setString(13,d.fingerprint());if(s.executeUpdate()!=1)throw unavailable();
        }
        try(var s=statement(c,"INSERT INTO public.mop_change_notice_asset(tenant_id,notice_id,version,position,asset_id) VALUES (?::uuid,?::uuid,?,?,?::uuid)")){
            int n=0;for(var idValue:d.assetIds()){s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);s.setInt(4,++n);s.setString(5,idValue.toString());s.addBatch();}int[] counts=s.executeBatch();if(counts.length!=n)throw unavailable();for(int count:counts)if(count!=1&&count!=Statement.SUCCESS_NO_INFO)throw unavailable();
        }
    }
    private void audit(Access a,String id,String action){platform.audit(a.actorId(),a.tenantId(),"change_notice.draft."+action,"change_notice_draft",id,"SUCCEEDED","UNREVIEWED_NO_APPROVAL_OR_SUPPRESSION",a.traceId());}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T readonly(Access a,Read<T> f){platform.requireStandaloneRead();try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a,false);T value=f.run(c);c.commit();return value;}catch(SQLException|RuntimeException|Error e){try{c.rollback();}catch(SQLException ignored){}throw e;}}catch(SQLException e){throw unavailable();}}
    @Override public Snapshot get(Access a,String id){String key=uuid(id);return readonly(a,c->get(c,a,key));}
    private static Snapshot get(Connection c,Access a,String id)throws SQLException{try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return snapshot(c,a,r);}}}
    @Override public List<Snapshot> list(Access a,int limit,Instant before,String beforeId){
        limit(limit);if((before==null)!=(beforeId==null))throw invalid();String boundary=beforeId==null?null:uuid(beforeId);if(before!=null)bounded(before);
        return readonly(a,c->{List<Snapshot> values=new ArrayList<>();try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid"+(before==null?"":" AND (h.created_at,h.id)<(?,?::uuid)")+" ORDER BY h.created_at DESC,h.id DESC LIMIT ?")){s.setString(1,a.tenantId());int p=2;if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,boundary);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())values.add(snapshot(c,a,r));}}return List.copyOf(values);});
    }
    @Override public List<Revision> history(Access a,String id,int limit,Long before){String key=uuid(id);limit(limit);if(before!=null&&(before<1||before>MAX_VERSION))throw invalid();return readonly(a,c->{get(c,a,key);List<Revision> values=new ArrayList<>();try(var s=statement(c,"SELECT h.applicant_id,r.* FROM public.mop_change_notice_revision r JOIN public.mop_change_notice_draft h ON (h.tenant_id,h.id)=(r.tenant_id,r.notice_id) WHERE r.tenant_id=?::uuid AND r.notice_id=?::uuid"+(before==null?"":" AND r.version<?")+" ORDER BY r.version DESC LIMIT ?")){s.setString(1,a.tenantId());s.setString(2,key);int p=3;if(before!=null)s.setLong(p++,before);s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next()){validate(r);values.add(new Revision(key,a.tenantId(),r.getLong("version"),r.getString("action"),draft(c,a,r),r.getBoolean("archived"),r.getString("recorded_by"),time(r,"recorded_at")));}}}return List.copyOf(values);});}
    private static Snapshot snapshot(Connection c,Access a,ResultSet r)throws SQLException{validate(r);Instant created=time(r,"created_at"),updated=time(r,"updated_at"),recorded=time(r,"recorded_at");if(updated.isBefore(created)||recorded.isBefore(created))throw unavailable();return new Snapshot(r.getString("id"),a.tenantId(),r.getLong("version"),draft(c,a,r),r.getBoolean("archived"),r.getString("recorded_by"),created,updated,recorded);}
    private static void validate(ResultSet r)throws SQLException{
        long v=r.getLong("version");Long prev=(Long)r.getObject("previous_version");String action=r.getString("action");boolean archived=r.getBoolean("archived");boolean nullArchived=r.wasNull();
        if(action==null||nullArchived||v<1||v>MAX_VERSION||v==1&&(prev!=null||!"CREATE".equals(action))||v>1&&(prev==null||prev!=v-1||!Set.of("UPDATE","ARCHIVE","RESTORE").contains(action))||archived!=action.equals("ARCHIVE")||!"DRAFT".equals(r.getString("lifecycle_status"))||r.getString("approval_status")!=null||r.getString("automatic_p3_decision")!=null||!"CONFIG_PENDING".equals(r.getString("approval_workflow_status"))||!"CONFIG_PENDING".equals(r.getString("matching_policy_status"))||!"UNREVIEWED_CHANGE_NOTICE_DRAFT".equals(r.getString("record_source"))||!"Asia/Shanghai".equals(r.getString("timezone"))||r.getBoolean("approval_evidence_verified")||r.wasNull()||r.getBoolean("effective")||r.wasNull()||r.getBoolean("device_execution_allowed")||r.wasNull()||r.getBoolean("alert_suppression_allowed")||r.wasNull())throw unavailable();
    }
    private static ChangeNoticeDraft draft(Connection c,Access a,ResultSet r)throws SQLException{
        if(!a.tenantId().equals(r.getString("tenant_id")))throw unavailable();int count=r.getInt("asset_count");if(count<1||count>ChangeNoticeDraft.MAX_ASSETS)throw unavailable();List<UUID> targets=new ArrayList<>();
        try(var s=statement(c,"SELECT position,asset_id FROM public.mop_change_notice_asset WHERE tenant_id=?::uuid AND notice_id=?::uuid AND version=? ORDER BY position LIMIT 1001")){s.setString(1,a.tenantId());s.setString(2,r.getString("notice_id"));s.setLong(3,r.getLong("version"));try(var rows=s.executeQuery()){while(rows.next()){if(rows.getInt(1)!=targets.size()+1)throw unavailable();targets.add(rows.getObject(2,UUID.class));}}}
        if(targets.size()!=count)throw unavailable();try{var end=r.getObject("planned_end_at",OffsetDateTime.class);var value=new ChangeNoticeDraft(UUID.fromString(a.tenantId()),r.getObject("applicant_id",UUID.class),r.getObject("ticket_id",UUID.class),r.getString("change_summary"),time(r,"planned_start_at"),end==null?null:end.toInstant(),targets);if(!value.fingerprint().equals(r.getString("content_sha256")))throw unavailable();return value;}catch(RuntimeException e){throw unavailable();}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"change_notice_drafts:write":"change_notice_drafts:read");

        if(a==null||a.permissionVersion()<1)throw expired();if(!uuid(a.actorId()).equals(a.actorId())||!uuid(a.tenantId()).equals(a.tenantId())||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","未生效变更报备草稿维护仅限当前授权平台管理员");}}
    }
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);ChangeNoticeSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException e){return List.of("CHANGE_NOTICE_DRAFT_STRUCTURE_UNAVAILABLE");}}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static String uuid(String s){if(s==null||!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(s).toString();}
    private static void limit(int n){if(n<1||n>101)throw invalid();}
    private static void bounded(Instant value){if(value.getNano()%1000000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static Instant time(ResultSet r,String name)throws SQLException{var value=r.getObject(name,OffsetDateTime.class);if(value==null)throw unavailable();try{bounded(value.toInstant());return value.toInstant();}catch(RuntimeException e){throw unavailable();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","变更报备草稿输入、版本或查询边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","变更报备草稿已变化，请刷新核对后提交");}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","变更报备草稿结构、数据或数据库未就绪，未确认写入成功");}
}
