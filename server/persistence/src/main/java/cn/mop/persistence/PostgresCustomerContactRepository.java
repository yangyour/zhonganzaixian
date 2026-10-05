package cn.mop.persistence;
import cn.mop.domain.*;
import cn.mop.domain.contact.*;
import cn.mop.domain.contact.CustomerContact.*;
import cn.mop.domain.contact.CustomerContactRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Actual contact revisions under current tenant authorization and a common tenant write lock. */
public final class PostgresCustomerContactRepository implements CustomerContactRepository {
    private static final System.Logger LOG=System.getLogger(PostgresCustomerContactRepository.class.getName());
    private static final long MAX_VERSION=9007199254740991L;
    private static final String SELECT="SELECT h.id::text,h.tenant_id::text,h.created_by::text,h.version,h.created_at,h.updated_at,r.previous_version,r.recorded_by::text,r.recorded_at,r.action,r.archived,r.name,r.roles,r.mobile,r.email,r.wecom_user_id,r.preferred_priorities,r.quiet_declared,r.quiet_enabled,r.quiet_start,r.quiet_end,r.enabled,r.record_source FROM public.mop_customer_contact h JOIN public.mop_customer_contact_revision r ON r.tenant_id=h.tenant_id AND r.contact_id=h.id AND r.version=h.version";
    private final DataSource source;private final PostgresPlatformRepository platform;
    public PostgresCustomerContactRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);}
    @Override public void authorizeWrite(Access a,String id){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);if(id!=null)get(c,a,uuid(id));}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Entry create(Access a,long expectedVersion,CustomerContact facts){Connection c=platform.idempotentCommandConnection();if(expectedVersion!=0||facts==null)throw invalid();try{authorize(c,a,true);lockTenant(c,a);CustomerContact.requireDecisionMakerAfterChange(countDecisions(c,a),null,facts);String id=UUID.randomUUID().toString();try(var s=statement(c,"INSERT INTO public.mop_customer_contact(id,tenant_id,created_by) VALUES (?::uuid,?::uuid,?::uuid)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());if(s.executeUpdate()!=1)throw corrupt();}revision(c,a,id,1,"CREATE",false,facts);audit(a,id,"create");return get(c,a,id);}catch(SQLException failure){throw unavailable(failure);}}
    @Override public List<Entry> createBatch(Access a,List<CustomerContact> explicitRows){
        Connection c=platform.idempotentCommandConnection();
        if(explicitRows==null||explicitRows.isEmpty()||explicitRows.size()>CustomerContactImportPreview.MAX_ROWS||explicitRows.stream().anyMatch(Objects::isNull))throw invalid();
        List<CustomerContact> rows=List.copyOf(explicitRows);Savepoint savepoint;
        try{authorize(c,a,true);lockTenant(c,a);long count=countDecisions(c,a);
            // Preserve the existing explicit first-contact rule and the submitted row order, never silently reorder.
            for(CustomerContact row:rows)count=CustomerContact.requireDecisionMakerAfterChange(count,null,row);
            savepoint=c.setSavepoint();
        }catch(SQLException failure){throw unavailable(failure);}
        try{List<Entry> created=new ArrayList<>(rows.size());for(CustomerContact row:rows)created.add(create(a,0,row));c.releaseSavepoint(savepoint);return List.copyOf(created);}
        catch(RuntimeException|Error failure){rollbackBatch(c,savepoint,failure);throw failure;}
        catch(SQLException failure){rollbackBatch(c,savepoint,failure);throw unavailable(failure);}
    }
    /** Also protects the batch if an outer caller catches a failure instead of aborting its command. */
    private static void rollbackBatch(Connection c,Savepoint savepoint,Throwable original){try{c.rollback(savepoint);c.releaseSavepoint(savepoint);}catch(SQLException failure){failure.addSuppressed(original);throw unavailable(failure);}}
    @Override public Entry update(Access a,String id,long expectedVersion,CustomerContact facts){if(facts==null)throw invalid();return change(a,id,expectedVersion,"UPDATE",facts);}
    @Override public Entry archive(Access a,String id,long expectedVersion){return change(a,id,expectedVersion,"ARCHIVE",null);}
    @Override public Entry restore(Access a,String id,long expectedVersion,CustomerContact explicitFacts){if(explicitFacts==null)throw invalid();return change(a,id,expectedVersion,"RESTORE",explicitFacts);}
    private Entry change(Access a,String id,long expected,String action,CustomerContact supplied){Connection c=platform.idempotentCommandConnection();String record=uuid(id);if(expected<1||expected>=MAX_VERSION)throw invalid();try{
        authorize(c,a,true);lockTenant(c,a);Entry before=get(c,a,record);if(before.version()!=expected)throw conflict();
        if(before.archived()!=action.equals("RESTORE"))throw new DomainException(409,"CONTACT_LIFECYCLE_CONFLICT",before.archived()?"联系人已归档，须明确恢复后再维护":"仅已归档联系人可恢复");
        boolean archived=action.equals("ARCHIVE");CustomerContact after=supplied;if(archived){var f=before.facts();after=new CustomerContact(f.name(),f.roles(),f.mobile(),f.email(),f.wecomUserId(),f.preferredPriorities(),f.quietWindow(),false);}
        CustomerContact.requireDecisionMakerAfterChange(countDecisions(c,a),before.archived()?null:before.facts(),archived?null:after);
        try(var s=statement(c,"UPDATE public.mop_customer_contact SET version=version+1,updated_at=statement_timestamp() WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")){s.setString(1,a.tenantId());s.setString(2,record);s.setLong(3,expected);if(s.executeUpdate()!=1)throw conflict();}
        revision(c,a,record,expected+1,action,archived,after);audit(a,record,action.toLowerCase(Locale.ROOT));return get(c,a,record);
    }catch(SQLException failure){throw unavailable(failure);}}
    private void audit(Access a,String id,String action){platform.audit(a.actorId(),a.tenantId(),"customer_contact."+action,"customer_contact",id,"SUCCEEDED","SELF_REPORTED_NO_DISPATCH_OR_IDENTITY_GRANT",a.traceId());}
    private static void lockTenant(Connection c,Access a)throws SQLException{
        try(var s=statement(c,"INSERT INTO public.mop_contact_tenant_guard(tenant_id,version) VALUES (?::uuid,0) ON CONFLICT(tenant_id) DO NOTHING")){s.setString(1,a.tenantId());s.executeUpdate();}
        try(var s=statement(c,"SELECT version FROM public.mop_contact_tenant_guard WHERE tenant_id=?::uuid FOR UPDATE")){s.setString(1,a.tenantId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(1)<0)throw corrupt();}}
    }
    private static long countDecisions(Connection c,Access a)throws SQLException{try(var s=statement(c,"SELECT count(*) FROM public.mop_customer_contact h JOIN public.mop_customer_contact_revision r ON r.tenant_id=h.tenant_id AND r.contact_id=h.id AND r.version=h.version WHERE h.tenant_id=?::uuid AND NOT r.archived AND r.enabled AND 'DECISION_MAKER'=ANY(r.roles)")){s.setString(1,a.tenantId());try(var r=s.executeQuery()){if(!r.next())throw corrupt();return r.getLong(1);}}}
    private static void revision(Connection c,Access a,String id,long version,String action,boolean archived,CustomerContact facts)throws SQLException{
        String sql="INSERT INTO public.mop_customer_contact_revision(tenant_id,contact_id,version,previous_version,recorded_by,action,name,roles,mobile,email,wecom_user_id,preferred_priorities,quiet_declared,quiet_enabled,quiet_start,quiet_end,enabled,archived) VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        java.sql.Array roles=c.createArrayOf("text",facts.roles().stream().map(Enum::name).sorted().toArray(String[]::new));java.sql.Array priorities=facts.preferredPriorities()==null?null:c.createArrayOf("text",facts.preferredPriorities().stream().map(Enum::name).sorted().toArray(String[]::new));
        try(var s=statement(c,sql)){s.setString(1,a.tenantId());s.setString(2,id);s.setLong(3,version);if(version==1)s.setNull(4,Types.BIGINT);else s.setLong(4,version-1);s.setString(5,a.actorId());s.setString(6,action);s.setString(7,facts.name());s.setArray(8,roles);s.setString(9,facts.mobile());s.setString(10,facts.email());s.setString(11,facts.wecomUserId());s.setArray(12,priorities);var q=facts.quietWindow();s.setBoolean(13,q!=null);if(q==null||q.enabled()==null)s.setNull(14,Types.BOOLEAN);else s.setBoolean(14,q.enabled());s.setString(15,q==null||q.start()==null?null:q.start().toString());s.setString(16,q==null||q.end()==null?null:q.end().toString());s.setBoolean(17,facts.enabled());s.setBoolean(18,archived);if(s.executeUpdate()!=1)throw corrupt();}finally{roles.free();if(priorities!=null)priorities.free();}
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T read(Access a,Read<T> action){try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a,false);T result=action.run(c);c.commit();return result;}catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Entry get(Access a,String id){String record=uuid(id);return read(a,c->get(c,a,record));}
    private static Entry get(Connection c,Access a,String id)throws SQLException{try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND h.id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return entry(r,a);}}}
    @Override public List<Entry> list(Access a,String query,View view,int limit,Instant before,String beforeId){if(query==null||view==null||query.codePointCount(0,query.length())>200||query.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE))throw invalid();limit(limit);if((before==null)!=(beforeId==null))throw invalid();String cursor=beforeId==null?null:uuid(beforeId);if(before!=null)boundedInstant(before);
        return read(a,c->{var result=new ArrayList<Entry>();String archive=view==View.ALL?"":view==View.ARCHIVED?" AND r.archived":" AND NOT r.archived";try(var s=statement(c,SELECT+" WHERE h.tenant_id=?::uuid AND strpos(lower(r.name COLLATE \"pg_catalog\".\"und-x-icu\"),lower(? COLLATE \"pg_catalog\".\"und-x-icu\"))>0"+archive+(before==null?"":" AND (h.created_at,h.id)<(?::timestamptz,?::uuid)")+" ORDER BY h.created_at DESC,h.id DESC LIMIT ?")){int p=1;s.setString(p++,a.tenantId());s.setString(p++,query);if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,cursor);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())result.add(entry(r,a));}}return List.copyOf(result);});
    }
    @Override public List<Revision> history(Access a,String id,int limit,Long beforeVersion){String record=uuid(id);limit(limit);if(beforeVersion!=null&&(beforeVersion<1||beforeVersion>MAX_VERSION))throw invalid();return read(a,c->{Entry current=get(c,a,record);var result=new ArrayList<Revision>();try(var s=statement(c,"SELECT * FROM public.mop_customer_contact_revision WHERE tenant_id=?::uuid AND contact_id=?::uuid AND version<=?"+(beforeVersion==null?"":" AND version<?")+" ORDER BY version DESC LIMIT ?")){int p=1;s.setString(p++,a.tenantId());s.setString(p++,record);s.setLong(p++,current.version());if(beforeVersion!=null)s.setLong(p++,beforeVersion);s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next()){CustomerContact facts=facts(r);long version=r.getLong("version");Long previous=(Long)r.getObject("previous_version");String action=r.getString("action");boolean archived=r.getBoolean("archived");validateRevision(version,previous,action,archived,facts);result.add(new Revision(version,previous,r.getString("recorded_by"),instant(r,"recorded_at"),action,archived,facts));}}}return List.copyOf(result);});}
    private static Entry entry(ResultSet r,Access a)throws SQLException{CustomerContact facts=facts(r);boolean archived=r.getBoolean("archived");String action=r.getString("action");long version=r.getLong("version");validateRevision(version,(Long)r.getObject("previous_version"),action,archived,facts);if(!a.tenantId().equals(r.getString("tenant_id")))throw corrupt();return new Entry(r.getString("id"),r.getString("tenant_id"),r.getString("created_by"),version,instant(r,"created_at"),instant(r,"updated_at"),r.getString("recorded_by"),instant(r,"recorded_at"),action,archived,facts);}
    private static void validateRevision(long version,Long previous,String action,boolean archived,CustomerContact facts){if(version<1||version>MAX_VERSION||archived&&facts.enabled()||!Set.of("CREATE","UPDATE","ARCHIVE","RESTORE").contains(action)||archived!=action.equals("ARCHIVE")||(version==1?(previous!=null||!action.equals("CREATE")):(previous==null||previous!=version-1||action.equals("CREATE"))))throw corrupt();}
    private static CustomerContact facts(ResultSet r)throws SQLException{try{
        if(!"SELF_REPORTED_CONTACT".equals(r.getString("record_source")))throw corrupt();var roles=enumSet(r,"roles",Role.class,false);var prefs=enumSet(r,"preferred_priorities",Priority.class,true);QuietWindow q=null;Boolean enabled=(Boolean)r.getObject("quiet_enabled");String start=r.getString("quiet_start"),end=r.getString("quiet_end");if(r.getBoolean("quiet_declared"))q=new QuietWindow(enabled,start==null?null:QuietWindow.parseMinute(start),end==null?null:QuietWindow.parseMinute(end));else if(enabled!=null||start!=null||end!=null)throw corrupt();return new CustomerContact(r.getString("name"),roles,r.getString("mobile"),r.getString("email"),r.getString("wecom_user_id"),prefs,q,r.getBoolean("enabled"));
    }catch(RuntimeException failure){throw corrupt();}}
    private static <E extends Enum<E>> Set<E> enumSet(ResultSet r,String column,Class<E> type,boolean nullable)throws SQLException{java.sql.Array array=r.getArray(column);if(array==null){if(nullable)return null;throw corrupt();}try{Set<E> values=EnumSet.noneOf(type);for(Object value:(Object[])array.getArray())if(!(value instanceof String text)||!values.add(Enum.valueOf(type,text)))throw corrupt();return values;}finally{array.free();}}
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"contacts:write":"contacts:read");
if(a==null||a.permissionVersion()<1)throw expired();uuid(a.actorId());uuid(a.tenantId());try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!Set.of("padmin","tadmin").contains(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前联系人维护仅开放授权租户管理员和平台管理员");}}}
    private static Instant instant(ResultSet r,String column)throws SQLException{var value=r.getObject(column,OffsetDateTime.class);if(value==null)throw corrupt();Instant instant=value.toInstant();try{boundedInstant(instant);}catch(DomainException failure){throw corrupt();}return instant;}
    private static void boundedInstant(Instant v){if(v.getNano()%1000000!=0||v.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||v.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static void limit(int value){if(value<1||value>101)throw invalid();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","联系人字段、版本或分页边界无效");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","联系人已更新，请刷新后按当前版本明确提交");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","联系人记录不一致，未返回成功数据");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Contact PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","联系人数据库或结构未就绪，未确认写入成功");}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);TestSchemaMigration.verifyCustomerContacts(c);return List.of();}catch(SQLException|RuntimeException failure){return List.of("CUSTOMER_CONTACT_SCHEMA_ACCESS_NOT_READY");}}
}
