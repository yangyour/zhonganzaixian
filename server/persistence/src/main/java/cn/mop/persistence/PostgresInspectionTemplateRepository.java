package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.InspectionTemplate;
import cn.mop.domain.inspection.InspectionTemplateRepository;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Existing padmin membership only. This maintenance scope grants no customer-template approval or publication. */
public final class PostgresInspectionTemplateRepository implements InspectionTemplateRepository {
    private static final long MAX_VERSION=9_007_199_254_740_990L;
    private static final System.Logger LOG=System.getLogger(PostgresInspectionTemplateRepository.class.getName());
    private static final String SELECT="SELECT h.id::text,h.tenant_id::text,h.created_by::text,h.version,h.created_at,h.updated_at,r.previous_version,r.recorded_by::text,r.recorded_at,r.action,r.archived,r.device_type,r.name,r.item_count,r.content_sha256,r.approval_status FROM public.mop_inspection_template h JOIN public.mop_inspection_template_revision r ON r.tenant_id=h.tenant_id AND r.template_id=h.id AND r.version=h.version";
    private final DataSource source;
    private final PostgresPlatformRepository platform;
    public PostgresInspectionTemplateRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public void authorizeWrite(Access a,String id){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);if(id!=null)get(c,a,uuid(id));}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Entry create(Access a,long expectedVersion,InspectionTemplate definition){
        Connection c=platform.idempotentCommandConnection();if(expectedVersion!=0||definition==null)throw invalid();
        try{authorize(c,a,true);String id=UUID.randomUUID().toString();
            try(var s=statement(c,"INSERT INTO public.mop_inspection_template(id,tenant_id,created_by) VALUES (?::uuid,?::uuid,?::uuid)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());if(s.executeUpdate()!=1)throw corrupt();}
            revision(c,a,id,1,"CREATE",definition);audit(a,id,"create");return get(c,a,id);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Entry update(Access a,String id,long expectedVersion,InspectionTemplate definition){if(definition==null)throw invalid();return change(a,id,expectedVersion,"UPDATE",definition);}
    @Override public Entry archive(Access a,String id,long expectedVersion){return change(a,id,expectedVersion,"ARCHIVE",null);}
    @Override public Entry restore(Access a,String id,long expectedVersion,InspectionTemplate definition){if(definition==null)throw invalid();return change(a,id,expectedVersion,"RESTORE",definition);}
    private Entry change(Access a,String id,long expected,String action,InspectionTemplate supplied){
        Connection c=platform.idempotentCommandConnection();String record=uuid(id);if(expected<1||expected>=MAX_VERSION)throw invalid();
        try{authorize(c,a,true);
            try(var s=statement(c,"SELECT version FROM public.mop_inspection_template WHERE tenant_id=?::uuid AND id=?::uuid FOR UPDATE")){s.setString(1,a.tenantId());s.setString(2,record);try(var r=s.executeQuery()){if(!r.next())throw missing();if(r.getLong(1)!=expected)throw conflict();}}
            Entry before=get(c,a,record);
            if(before.archived()!=action.equals("RESTORE"))throw new DomainException(409,"INSPECTION_TEMPLATE_LIFECYCLE_CONFLICT","归档模板须明确恢复，未归档模板不能重复恢复");
            InspectionTemplate definition=action.equals("ARCHIVE")?before.snapshot().definition():supplied;
            try(var s=statement(c,"UPDATE public.mop_inspection_template SET version=version+1,updated_at=statement_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")){s.setString(1,a.tenantId());s.setString(2,record);s.setLong(3,expected);if(s.executeUpdate()!=1)throw conflict();}
            revision(c,a,record,expected+1,action,definition);audit(a,record,action.toLowerCase(Locale.ROOT));return get(c,a,record);
        }catch(SQLException failure){throw unavailable(failure);}
    }
    private static void revision(Connection c,Access a,String id,long version,String action,InspectionTemplate definition)throws SQLException{
        try(var s=statement(c,"INSERT INTO public.mop_inspection_template_revision(tenant_id,template_id,version,previous_version,recorded_by,action,archived,device_type,name,item_count,content_sha256) VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?)")){
            s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setLong(4,version-1);s.setString(5,a.actorId());s.setString(6,action);s.setBoolean(7,action.equals("ARCHIVE"));s.setString(8,definition.deviceType());s.setString(9,definition.name());s.setInt(10,definition.items().size());s.setString(11,definition.fingerprint());if(s.executeUpdate()!=1)throw corrupt();
        }
        try(var s=statement(c,"INSERT INTO public.mop_inspection_template_item(tenant_id,template_id,version,position,item_key,section,label,instruction) VALUES (?::uuid,?::uuid,?,?,?,?,?,?)")){
            int position=0;for(var item:definition.items()){s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);s.setInt(4,++position);s.setString(5,item.key());s.setString(6,item.section());s.setString(7,item.label());s.setString(8,item.instruction());if(s.executeUpdate()!=1)throw corrupt();}
        }
    }
    private void audit(Access a,String id,String action){platform.audit(a.actorId(),a.tenantId(),"inspection_template."+action,"inspection_template",id,"SUCCEEDED","UNPUBLISHED_TEMPLATE_NOT_CUSTOMER_APPROVAL",a.traceId());}
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T readonly(Access a,Read<T> action){
        platform.requireStandaloneRead();
        try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,a,false);T value=action.run(c);c.commit();return value;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @Override public Entry get(Access a,String id){String canonical=uuid(id);return readonly(a,c->get(c,a,canonical));}
    private static Entry get(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(c,r,a);}}
    }
    @Override public List<Entry> list(Access a,String query,String deviceType,View view,int limit,Instant before,String beforeId){
        if(query==null||view==null||query.codePointCount(0,query.length())>200||query.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)||deviceType!=null&&!deviceType.matches("[a-z][a-z0-9_-]{0,63}"))throw invalid();
        limit(limit);if((before==null)!=(beforeId==null))throw invalid();String cursor=beforeId==null?null:uuid(beforeId);if(before!=null)bounded(before);
        return readonly(a,c->{
            String archive=view==View.ALL?"":view==View.ARCHIVED?" AND r.archived":" AND NOT r.archived";
            try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND strpos(lower(r.name COLLATE pg_catalog.\"und-x-icu\"),lower(? COLLATE pg_catalog.\"und-x-icu\"))>0"+archive+(deviceType==null?"":" AND r.device_type=?")+(before==null?"":" AND (h.created_at,h.id)<(?::timestamptz,?::uuid)")+" ORDER BY h.created_at DESC,h.id DESC LIMIT ?")){
                int p=1;s.setString(p++,a.tenantId());s.setString(p++,query);if(deviceType!=null)s.setString(p++,deviceType);if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,cursor);}s.setInt(p,limit);
                var values=new ArrayList<Entry>();try(var r=s.executeQuery()){while(r.next())values.add(entry(c,r,a));}return List.copyOf(values);
            }
        });
    }
    @Override public List<Revision> history(Access a,String id,int limit,Long beforeVersion){
        String record=uuid(id);limit(limit);if(beforeVersion!=null&&(beforeVersion<1||beforeVersion>MAX_VERSION))throw invalid();
        return readonly(a,c->{Entry current=get(c,a,record);var values=new ArrayList<Revision>();
            try(var s=statement(c,"SELECT * FROM public.mop_inspection_template_revision WHERE tenant_id=?::uuid AND template_id=?::uuid AND version<=?"+(beforeVersion==null?"":" AND version<?")+" ORDER BY version DESC LIMIT ?")){
                int p=1;s.setString(p++,a.tenantId());s.setString(p++,record);s.setLong(p++,current.version());if(beforeVersion!=null)s.setLong(p++,beforeVersion);s.setInt(p,limit);
                try(var r=s.executeQuery()){while(r.next()){validateRevision(r);values.add(new Revision(r.getLong("version"),(Long)r.getObject("previous_version"),r.getString("recorded_by"),instant(r,"recorded_at"),r.getString("action"),r.getBoolean("archived"),snapshot(c,r,a.tenantId(),record)));}}
            }return List.copyOf(values);
        });
    }
    private static Entry entry(Connection c,ResultSet r,Access a)throws SQLException{
        validateRevision(r);if(!a.tenantId().equals(r.getString("tenant_id")))throw corrupt();String id=r.getString("id");
        Instant created=instant(r,"created_at"),updated=instant(r,"updated_at");if(updated.isBefore(created))throw corrupt();
        return new Entry(id,a.tenantId(),r.getString("created_by"),r.getLong("version"),created,updated,r.getString("recorded_by"),instant(r,"recorded_at"),r.getString("action"),r.getBoolean("archived"),snapshot(c,r,a.tenantId(),id));
    }
    private static void validateRevision(ResultSet r)throws SQLException{
        long version=r.getLong("version");Long previous=(Long)r.getObject("previous_version");String action=r.getString("action");
        if(version<1||version>MAX_VERSION||action==null||!Set.of("CREATE","UPDATE","ARCHIVE","RESTORE").contains(action)||r.getBoolean("archived")!=action.equals("ARCHIVE")||(version==1?previous!=null||!action.equals("CREATE"):previous==null||previous!=version-1||action.equals("CREATE"))||!"UNVERIFIED".equals(r.getString("approval_status")))throw corrupt();
    }
    private static InspectionTemplate.Snapshot snapshot(Connection c,ResultSet r,String tenant,String id)throws SQLException{
        int count=r.getInt("item_count");long version=r.getLong("version");if(count<1||count>InspectionTemplate.MAX_ITEMS)throw corrupt();
        var items=new ArrayList<InspectionTemplate.Item>();
        try(var s=statement(c,"SELECT position,item_key,section,label,instruction FROM public.mop_inspection_template_item WHERE tenant_id=?::uuid AND template_id=?::uuid AND version=? ORDER BY position LIMIT 101")){
            s.setString(1,tenant);s.setString(2,id);s.setLong(3,version);try(var rows=s.executeQuery()){while(rows.next()){if(rows.getInt("position")!=items.size()+1)throw corrupt();try{items.add(new InspectionTemplate.Item(rows.getString("item_key"),rows.getString("section"),rows.getString("label"),rows.getString("instruction")));}catch(DomainException failure){throw corrupt();}}}
        }
        if(items.size()!=count)throw corrupt();
        try{return new InspectionTemplate.Snapshot(UUID.fromString(tenant),UUID.fromString(id),version,new InspectionTemplate(r.getString("device_type"),r.getString("name"),items),r.getString("content_sha256"));}catch(RuntimeException failure){throw corrupt();}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null||a.permissionVersion()<1)throw expired();
        if(!uuid(a.actorId()).equals(a.actorId())||!uuid(a.tenantId()).equals(a.tenantId())||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","未发布巡检模板维护仅限当前授权平台管理员");}}
    }
    private static Instant instant(ResultSet r,String column)throws SQLException{var value=r.getObject(column,OffsetDateTime.class);if(value==null)throw corrupt();try{bounded(value.toInstant());return value.toInstant();}catch(DomainException failure){throw corrupt();}}
    private static void bounded(Instant value){if(value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static void limit(int value){if(value<1||value>101)throw invalid();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","巡检模板字段、版本或查询边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","巡检模板已变化，请刷新后按当前版本明确提交");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检模板历史或定义不一致，未返回成功结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Inspection template PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","巡检模板数据库或结构未就绪，未确认写入成功");}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);InspectionTemplateSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException failure){return List.of("INSPECTION_TEMPLATE_STRUCTURE_UNAVAILABLE");}}
}
