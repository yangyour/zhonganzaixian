package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionWorksheetRepository.*;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Padmin-only unpublished manual drafts. Current membership is checked inside the protected command before replay. */
public final class PostgresInspectionWorksheetRepository implements InspectionWorksheetRepository {
    private static final long MAX_VERSION=9_007_199_254_740_990L;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final System.Logger LOG=System.getLogger(PostgresInspectionWorksheetRepository.class.getName());
    private static final String SELECT="SELECT h.id::text,h.tenant_id::text,h.created_by::text,h.asset_id::text,h.asset_name,h.template_id::text,h.template_version,h.template_content_sha256,h.record_source,h.created_at,h.updated_at,r.version,r.previous_version,r.recorded_by::text,r.recorded_at,r.action,r.archived,r.reported_performed_at,r.observations::text,r.answered_count,r.content_sha256,r.verification_status FROM public.mop_inspection_worksheet h JOIN public.mop_inspection_worksheet_revision r ON r.tenant_id=h.tenant_id AND r.worksheet_id=h.id";
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    public PostgresInspectionWorksheetRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public void authorizeWrite(Access a,String id){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);if(id!=null)get(c,a,uuid(id));}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Entry create(Access a,long expectedVersion,Creation input){
        Connection c=platform.idempotentCommandConnection();if(expectedVersion!=0||input==null||input.content()==null||input.templateVersion()<1||input.templateVersion()>MAX_VERSION)throw invalid();
        String asset=uuid(input.assetId()),template=uuid(input.templateId());
        try{authorize(c,a,true);String name;
            try(var s=statement(c,"SELECT name FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid FOR SHARE")){s.setString(1,a.tenantId());s.setString(2,asset);try(var r=s.executeQuery()){if(!r.next())throw missing();name=r.getString(1);}}
            try(var s=statement(c,"SELECT h.version,r.archived FROM public.mop_inspection_template h JOIN public.mop_inspection_template_revision r ON r.tenant_id=h.tenant_id AND r.template_id=h.id AND r.version=h.version WHERE h.tenant_id=?::uuid AND h.id=?::uuid FOR SHARE OF h")){s.setString(1,a.tenantId());s.setString(2,template);try(var r=s.executeQuery()){if(!r.next())throw missing();if(r.getLong(1)!=input.templateVersion())throw new DomainException(409,"INSPECTION_TEMPLATE_VERSION_CONFLICT","模板候选已变化，请刷新后明确选择版本");if(r.getBoolean(2))throw new DomainException(409,"INSPECTION_TEMPLATE_ARCHIVED","不能使用已归档模板创建执行单");}}
            var snapshot=PostgresInspectionTemplateRepository.capturedSnapshot(c,a.tenantId(),template,input.templateVersion());
            var content=new InspectionWorksheetContent(new InspectionWorksheet(UUID.fromString(a.tenantId()),UUID.fromString(asset),name,snapshot,input.content().observations()),input.content().reportedPerformedAt());
            Instant now=now(c);content.requireNotFuture(now);String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_inspection_worksheet(id,tenant_id,created_by,asset_id,asset_name,template_id,template_version,template_content_sha256,created_at,updated_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?,?)")){
                s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());s.setString(4,asset);s.setString(5,name);s.setString(6,template);s.setLong(7,input.templateVersion());s.setString(8,snapshot.contentSha256());timestamp(s,9,now);timestamp(s,10,now);if(s.executeUpdate()!=1)throw corrupt();}
            revision(c,a,id,1,"CREATE",content,now);audit(a,id,"create");return get(c,a,id);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Entry update(Access a,String id,long expected,Content content){if(content==null)throw invalid();return change(a,id,expected,"UPDATE",content);}
    @Override public Entry archive(Access a,String id,long expected){return change(a,id,expected,"ARCHIVE",null);}
    @Override public Entry restore(Access a,String id,long expected,Content content){if(content==null)throw invalid();return change(a,id,expected,"RESTORE",content);}
    private Entry change(Access a,String id,long expected,String action,Content supplied){
        Connection c=platform.idempotentCommandConnection();String record=uuid(id);if(expected<1||expected>=MAX_VERSION)throw invalid();
        try{authorize(c,a,true);
            try(var s=statement(c,"SELECT version FROM public.mop_inspection_worksheet WHERE tenant_id=?::uuid AND id=?::uuid FOR UPDATE")){s.setString(1,a.tenantId());s.setString(2,record);try(var r=s.executeQuery()){if(!r.next())throw missing();if(r.getLong(1)!=expected)throw conflict();}}
            Entry before=get(c,a,record);if(before.archived()!=action.equals("RESTORE"))throw new DomainException(409,"INSPECTION_WORKSHEET_LIFECYCLE_CONFLICT","归档执行单须明确恢复，未归档执行单不能重复恢复");
            var captured=before.content().worksheet();var content=action.equals("ARCHIVE")?before.content():new InspectionWorksheetContent(new InspectionWorksheet(captured.tenantId(),captured.assetId(),captured.assetName(),captured.template(),supplied.observations()),supplied.reportedPerformedAt());
            Instant now=now(c);if(now.isBefore(before.updatedAt())||now.isBefore(before.recordedAt()))throw corrupt();content.requireNotFuture(now);
            try(var s=statement(c,"UPDATE public.mop_inspection_worksheet SET version=version+1,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")){timestamp(s,1,now);s.setString(2,a.tenantId());s.setString(3,record);s.setLong(4,expected);if(s.executeUpdate()!=1)throw conflict();}
            revision(c,a,record,expected+1,action,content,now);audit(a,record,action.toLowerCase(Locale.ROOT));return get(c,a,record);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void revision(Connection c,Access a,String id,long version,String action,InspectionWorksheetContent content,Instant now)throws SQLException{
        var rows=JSON.createArrayNode();for(var row:content.worksheet().rows()){if(row.observation()==null)continue;var node=rows.addObject();node.put("key",row.item().key());node.put("result",row.observation().result().name());node.put("note",row.observation().note());}
        try(var s=statement(c,"INSERT INTO public.mop_inspection_worksheet_revision(tenant_id,worksheet_id,version,previous_version,recorded_by,recorded_at,action,archived,reported_performed_at,observations,content_sha256) VALUES(?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?::jsonb,?)")){
            s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setLong(4,version-1);s.setString(5,a.actorId());timestamp(s,6,now);s.setString(7,action);s.setBoolean(8,action.equals("ARCHIVE"));timestamp(s,9,content.reportedPerformedAt());s.setString(10,rows.toString());s.setString(11,content.fingerprint());if(s.executeUpdate()!=1)throw corrupt();
        }
    }
    private void audit(Access a,String id,String action){platform.audit(a.actorId(),a.tenantId(),"inspection_worksheet."+action,"inspection_worksheet",id,"SUCCEEDED","SELF_REPORTED_DRAFT_NOT_INSPECTION_ATTESTATION",a.traceId());}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T readonly(Access a,Read<T> action){platform.requireStandaloneRead();try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a,false);T value=action.run(c);c.commit();return value;}catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Entry get(Access a,String id){String record=uuid(id);return readonly(a,c->get(c,a,record));}
    private static Entry get(Connection c,Access a,String id)throws SQLException{try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.id=?::uuid AND r.version=h.version")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(c,r,a);}}}
    @Override public List<Entry> list(Access a,String assetId,View view,int limit,Instant before,String beforeId){
        if(view==null)throw invalid();limit(limit);String asset=assetId==null?null:uuid(assetId);if((before==null)!=(beforeId==null))throw invalid();String cursor=beforeId==null?null:uuid(beforeId);if(before!=null)bounded(before);
        return readonly(a,c->{
            // A foreign/nonexistent explicit asset filter is an invisible object, not a successful empty asset view.
            if(asset!=null)try(var s=statement(c,"SELECT 1 FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,asset);try(var r=s.executeQuery()){if(!r.next())throw missing();}}
            String archived=view==View.ALL?"":view==View.ARCHIVED?" AND r.archived":" AND NOT r.archived";
            try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND r.version=h.version"+archived+(asset==null?"":" AND h.asset_id=?::uuid")+(before==null?"":" AND (h.created_at,h.id)<(?::timestamptz,?::uuid)")+" ORDER BY h.created_at DESC,h.id DESC LIMIT ?")){
                int p=1;s.setString(p++,a.tenantId());if(asset!=null)s.setString(p++,asset);if(before!=null){timestamp(s,p++,before);s.setString(p++,cursor);}s.setInt(p,limit);var values=new ArrayList<Entry>();try(var r=s.executeQuery()){while(r.next())values.add(entry(c,r,a));}return List.copyOf(values);
            }
        });
    }
    @Override public List<Revision> history(Access a,String id,int limit,Long beforeVersion){
        String record=uuid(id);limit(limit);if(beforeVersion!=null&&(beforeVersion<1||beforeVersion>MAX_VERSION))throw invalid();
        return readonly(a,c->{Entry current=get(c,a,record);var values=new ArrayList<Revision>();try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.id=?::uuid AND r.version<=?"+(beforeVersion==null?"":" AND r.version<?")+" ORDER BY r.version DESC LIMIT ?")){
            int p=1;s.setString(p++,a.tenantId());s.setString(p++,record);s.setLong(p++,current.version());if(beforeVersion!=null)s.setLong(p++,beforeVersion);s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next()){Entry entry=entry(c,r,a);values.add(new Revision(entry.version(),(Long)r.getObject("previous_version"),entry.recordedBy(),entry.recordedAt(),entry.action(),entry.archived(),entry.content()));}}}return List.copyOf(values);});
    }
    private static Entry entry(Connection c,ResultSet r,Access a)throws SQLException{
        if(!a.tenantId().equals(r.getString("tenant_id"))||!"MANUAL_INSPECTION_DRAFT".equals(r.getString("record_source"))||!"SELF_REPORTED_UNVERIFIED".equals(r.getString("verification_status")))throw corrupt();
        long version=r.getLong("version");Long previous=(Long)r.getObject("previous_version");String action=r.getString("action");boolean archived=r.getBoolean("archived");
        if(version<1||version>MAX_VERSION||action==null||!Set.of("CREATE","UPDATE","ARCHIVE","RESTORE").contains(action)||archived!=action.equals("ARCHIVE")||(version==1?previous!=null||!action.equals("CREATE"):previous==null||previous!=version-1||action.equals("CREATE")))throw corrupt();
        Instant created=instant(r,"created_at"),updated=instant(r,"updated_at"),recorded=instant(r,"recorded_at");if(updated.isBefore(created)||recorded.isBefore(created)||recorded.isAfter(updated))throw corrupt();
        var template=PostgresInspectionTemplateRepository.capturedSnapshot(c,a.tenantId(),r.getString("template_id"),r.getLong("template_version"));if(!template.contentSha256().equals(r.getString("template_content_sha256")))throw corrupt();
        InspectionWorksheetContent content;
        try{
            var json=JSON.readTree(r.getString("observations"));if(json==null||!json.isArray()||json.size()>100)throw corrupt();var observations=new LinkedHashMap<String,InspectionWorksheet.Observation>();
            for(var node:json){if(!node.isObject()||node.size()!=3||!node.path("key").isTextual()||!node.path("result").isTextual()||!node.has("note")||!(node.get("note").isNull()||node.get("note").isTextual()))throw corrupt();String key=node.get("key").asText();var value=new InspectionWorksheet.Observation(InspectionWorksheet.Result.valueOf(node.get("result").asText()),node.get("note").isNull()?null:node.get("note").asText(),List.of());if(observations.put(key,value)!=null)throw corrupt();}
            var time=r.getObject("reported_performed_at",OffsetDateTime.class);content=new InspectionWorksheetContent(new InspectionWorksheet(UUID.fromString(a.tenantId()),UUID.fromString(r.getString("asset_id")),r.getString("asset_name"),template,observations),time==null?null:time.toInstant());
            content.requireNotFuture(recorded);if(observations.size()!=r.getInt("answered_count")||!content.fingerprint().equals(r.getString("content_sha256")))throw corrupt();
        }catch(SQLException failure){throw failure;}catch(Exception failure){throw corrupt();}
        return new Entry(r.getString("id"),a.tenantId(),r.getString("created_by"),version,created,updated,r.getString("recorded_by"),recorded,action,archived,content);
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null||a.permissionVersion()<1)throw expired();if(!uuid(a.actorId()).equals(a.actorId())||!uuid(a.tenantId()).equals(a.tenantId())||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","未发布巡检执行单维护仅限当前授权平台管理员");}}
    }
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){if(!r.next())throw corrupt();Instant value=r.getObject(1,OffsetDateTime.class).toInstant();bounded(value);return value;}}
    private static Instant instant(ResultSet r,String name)throws SQLException{var value=r.getObject(name,OffsetDateTime.class);if(value==null)throw corrupt();try{bounded(value.toInstant());return value.toInstant();}catch(DomainException failure){throw corrupt();}}
    private static void timestamp(PreparedStatement s,int p,Instant value)throws SQLException{if(value==null)s.setNull(p,Types.TIMESTAMP_WITH_TIMEZONE);else s.setObject(p,OffsetDateTime.ofInstant(value,ZoneOffset.UTC));}
    private static void bounded(Instant value){if(value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static void limit(int value){if(value<1||value>21)throw invalid();} // Full content verification bounds each read to twenty rows plus one look-ahead.
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","巡检执行单字段、版本或查询边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","巡检执行单已变化，请刷新后按当前版本明确提交");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检执行单历史、时钟或内容不一致，未返回成功结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Inspection worksheet PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检执行单数据库或结构未就绪，未确认写入成功");}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);InspectionTemplateSchemaVerifier.verify(c);InspectionWorksheetSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException failure){return List.of("INSPECTION_WORKSHEET_STRUCTURE_UNAVAILABLE");}}
}
