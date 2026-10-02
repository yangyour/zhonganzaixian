package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.domain.assetmetadata.ManualAssetMetadataRepository.*;
import com.fasterxml.jackson.databind.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Real draft metadata/history, joined to stable asset identity; never overwrites an observed/CMDB asset. */
public final class PostgresManualAssetMetadataRepository implements ManualAssetMetadataRepository {
    private static final System.Logger LOG=System.getLogger(PostgresManualAssetMetadataRepository.class.getName());
    private final DataSource source;private final PostgresPlatformRepository platform;private final ObjectMapper mapper;
    private static final String COLUMNS="tenant_id,asset_id,asset_version,previous_metadata_version,actor_id,recorded_at,name,type,management_ip,site_id,brand,model,serial_number,location,owner_id,business_description,purchase_date,warranty_expiry,overdue_service_date,collection_method_note,note,before_snapshot,verification_status,monitoring_applied,cmdb_applied";
    public PostgresManualAssetMetadataRepository(DataSource source,PostgresPlatformRepository platform,ObjectMapper mapper){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);this.mapper=Objects.requireNonNull(mapper);}
    @FunctionalInterface private interface Read<T>{T run(Connection c,Asset asset)throws SQLException;}
    private <T>T read(Access access,String id,Read<T> action){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try{authorize(c,access,false);Asset asset=asset(c,access,uuid(id),false);T result=action.run(c,asset);c.commit();return result;}
            catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}
        }catch(SQLException failure){throw sqlFailure(failure);}
    }
    @Override public Current current(Access access,String id){return read(access,id,(c,asset)->current(c,access,asset));}
    @Override public List<Revision> history(Access access,String id,int limit,Long beforeVersion){
        return read(access,id,(c,asset)->{if(limit<1||limit>101||beforeVersion!=null&&beforeVersion<2)throw invalid();current(c,access,asset);List<Revision> values=new ArrayList<>();
            try(var s=statement(c,"SELECT "+COLUMNS+" FROM public.mop_asset_manual_revision WHERE tenant_id=?::uuid AND asset_id=?::uuid"+(beforeVersion==null?"":" AND asset_version<?")+" ORDER BY asset_version DESC LIMIT ?")){s.setString(1,access.tenantId());s.setString(2,asset.id());int next=3;if(beforeVersion!=null)s.setLong(next++,beforeVersion);s.setInt(next,limit);try(var r=s.executeQuery()){while(r.next())values.add(revision(r,access.tenantId(),asset.id()));}}
            return List.copyOf(values);
        });
    }
    @Override public Current update(Access access,String id,long expected,ManualAssetMetadata metadata){
        Connection c=platform.idempotentCommandConnection();if(expected<1||expected==Long.MAX_VALUE||metadata==null)throw invalid();
        try{
            authorize(c,access,true);Asset asset=asset(c,access,uuid(id),true);references(c,access.tenantId(),metadata);Current before=current(c,access,asset);
            if(!before.editBlockers().isEmpty())throw new DomainException(422,"ASSET_METADATA_NOT_EDITABLE","仅能维护未接入的手工草稿资料；不会覆盖CMDB或监控状态",List.of(Map.of("blockers",before.editBlockers(),"question_ids",List.of("Q19","Q20","Q31"))));
            if(asset.version()!=expected)throw conflict();
            try(var s=statement(c,"UPDATE public.mop_asset SET name=?,type=?,site_id=?,version=version+1,updated_at=statement_timestamp(),updated_by=?::uuid WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")){
                s.setString(1,metadata.name());s.setString(2,metadata.type());s.setString(3,metadata.siteId());s.setString(4,access.actorId());s.setString(5,access.tenantId());s.setString(6,asset.id());s.setLong(7,expected);if(s.executeUpdate()!=1)throw conflict();
            }catch(SQLException failure){if("23505".equals(failure.getSQLState()))throw new DomainException(409,"ASSET_NAME_CONFLICT","同租户资产名称已存在");if("23503".equals(failure.getSQLState()))throw missing();throw failure;}
            insert(c,access,asset,expected+1,metadata,before.snapshot());
            try(var s=statement(c,"INSERT INTO public.mop_asset_manual_details(tenant_id,asset_id,revision_version) VALUES (?::uuid,?::uuid,?) ON CONFLICT(tenant_id,asset_id) DO UPDATE SET revision_version=EXCLUDED.revision_version")){s.setString(1,access.tenantId());s.setString(2,asset.id());s.setLong(3,expected+1);s.executeUpdate();}
            platform.audit(access.actorId(),access.tenantId(),"asset.manual_metadata.update","asset",asset.id(),"SUCCEEDED","MANUAL_DRAFT_ONLY_IP_UNCHANGED_CMDB_NOT_APPLIED",access.traceId());
            return current(c,access,asset(c,access,asset.id(),false));
        }catch(SQLException failure){throw sqlFailure(failure);}
    }
    private Current current(Connection c,Access access,Asset asset)throws SQLException{
        Long head=null,latest=null;
        try(var s=statement(c,"SELECT revision_version FROM public.mop_asset_manual_details WHERE tenant_id=?::uuid AND asset_id=?::uuid")){s.setString(1,access.tenantId());s.setString(2,asset.id());try(var r=s.executeQuery()){if(r.next())head=r.getLong(1);}}
        try(var s=statement(c,"SELECT max(asset_version) FROM public.mop_asset_manual_revision WHERE tenant_id=?::uuid AND asset_id=?::uuid")){s.setString(1,access.tenantId());s.setString(2,asset.id());try(var r=s.executeQuery()){r.next();long value=r.getLong(1);if(!r.wasNull())latest=value;}}
        if(!Objects.equals(head,latest)||head!=null&&head>asset.version())throw corrupt();
        List<String> blockers=new ArrayList<>(ManualAssetMetadata.editBlockers(asset));if(head==null)return new Current(new Snapshot(asset,null,null),null,null,blockers);
        Revision revision;
        try(var s=statement(c,"SELECT "+COLUMNS+" FROM public.mop_asset_manual_revision WHERE tenant_id=?::uuid AND asset_id=?::uuid AND asset_version=?")){s.setString(1,access.tenantId());s.setString(2,asset.id());s.setLong(3,head);try(var r=s.executeQuery()){if(!r.next())throw corrupt();revision=revision(r,access.tenantId(),asset.id());}}
        ManualAssetMetadata metadata=revision.metadata();
        if(!asset.name().equals(metadata.name())||!asset.type().equals(metadata.type())||!asset.siteId().equals(metadata.siteId())||!asset.managementIp().equals(revision.managementIp()))blockers.add("MANUAL_METADATA_CORE_DRIFT");
        return new Current(new Snapshot(asset,metadata,head),revision.actorId(),revision.recordedAt(),blockers);
    }
    private Revision revision(ResultSet r,String tenant,String id)throws SQLException{
        try{
            long version=r.getLong("asset_version"),prior=r.getLong("previous_metadata_version");Long previous=r.wasNull()?null:prior;
            if(!tenant.equals(r.getString("tenant_id"))||!id.equals(r.getString("asset_id"))||!"SELF_REPORTED".equals(r.getString("verification_status"))||r.getBoolean("monitoring_applied")||r.getBoolean("cmdb_applied"))throw corrupt();
            Snapshot before=mapper.readValue(r.getString("before_snapshot"),Snapshot.class);ManualAssetMetadata metadata=metadata(r);
            if(!before.asset().tenantId().equals(tenant)||!before.asset().id().equals(id)||before.asset().version()!=version-1||!Objects.equals(before.metadataVersion(),previous)||!ManualAssetMetadata.editBlockers(before.asset()).isEmpty()||!before.asset().managementIp().equals(r.getString("management_ip")))throw corrupt();
            if(before.metadata()!=null&&(!before.asset().name().equals(before.metadata().name())||!before.asset().type().equals(before.metadata().type())||!before.asset().siteId().equals(before.metadata().siteId())))throw corrupt();
            return new Revision(version,previous,r.getString("actor_id"),instant(r,"recorded_at"),before,metadata,r.getString("management_ip"));
        }catch(com.fasterxml.jackson.core.JsonProcessingException|RuntimeException failure){throw corrupt();}
    }
    private static ManualAssetMetadata metadata(ResultSet r)throws SQLException{return new ManualAssetMetadata(r.getString("name"),r.getString("type"),r.getString("site_id"),r.getString("brand"),r.getString("model"),r.getString("serial_number"),r.getString("location"),r.getString("owner_id"),r.getString("business_description"),r.getObject("purchase_date",LocalDate.class),r.getObject("warranty_expiry",LocalDate.class),r.getObject("overdue_service_date",LocalDate.class),r.getString("collection_method_note"),r.getString("note"));}
    private void insert(Connection c,Access access,Asset asset,long version,ManualAssetMetadata m,Snapshot before)throws SQLException{
        String encoded;try{encoded=mapper.writeValueAsString(before);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw corrupt();}
        try(var s=statement(c,"INSERT INTO public.mop_asset_manual_revision(tenant_id,asset_id,asset_version,previous_metadata_version,actor_id,name,type,management_ip,site_id,brand,model,serial_number,location,owner_id,business_description,purchase_date,warranty_expiry,overdue_service_date,collection_method_note,note,before_snapshot) VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?,?,?,?::uuid,?,?,?,?,?,?,?::jsonb)")){
            int i=1;s.setString(i++,access.tenantId());s.setString(i++,asset.id());s.setLong(i++,version);if(before.metadataVersion()==null)s.setNull(i++,Types.BIGINT);else s.setLong(i++,before.metadataVersion());s.setString(i++,access.actorId());s.setString(i++,m.name());s.setString(i++,m.type());s.setString(i++,asset.managementIp());s.setString(i++,m.siteId());s.setString(i++,m.brand());s.setString(i++,m.model());s.setString(i++,m.serialNumber());s.setString(i++,m.location());s.setString(i++,m.ownerId());s.setString(i++,m.businessDescription());s.setObject(i++,m.purchaseDate());s.setObject(i++,m.warrantyExpiry());s.setObject(i++,m.overdueServiceDate());s.setString(i++,m.collectionMethodNote());s.setString(i++,m.note());s.setString(i,encoded);s.executeUpdate();
        }
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null||a.actorId()==null||a.tenantId()==null||a.permissionVersion()<1)throw expired();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id::text=? AND m.user_id::text=? AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!Set.of("padmin","msp").contains(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","当前手工草稿资料维护仅开放授权内部角色；客户维护范围待确认");}}
    }
    private static Asset asset(Connection c,Access a,String id,boolean lock)throws SQLException{
        try(var s=statement(c,"SELECT * FROM public.mop_asset WHERE tenant_id=?::uuid AND id=?::uuid"+(lock?" FOR UPDATE":""))){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();return new Asset(r.getString("id"),r.getString("tenant_id"),r.getString("name"),r.getString("type"),r.getString("management_ip"),r.getString("site_id"),r.getString("status"),r.getString("lifecycle_status"),r.getString("source"),instant(r,"sampled_at"),r.getString("unknown_reason"),r.getLong("version"),instant(r,"created_at"),instant(r,"updated_at"),r.getString("availability"),r.getString("sync_state"));}}
    }
    private static void references(Connection c,String tenant,ManualAssetMetadata m)throws SQLException{
        // Runtime intentionally has no site UPDATE right, so an explicit row-lock SELECT is forbidden.
        // The asset/revision composite FK takes the necessary reference lock during the write.
        try(var s=statement(c,"SELECT id FROM public.mop_site WHERE tenant_id=?::uuid AND id=?")){s.setString(1,tenant);s.setString(2,m.siteId());try(var r=s.executeQuery()){if(!r.next())throw missing();}}
        if(m.ownerId()!=null)try(var s=statement(c,"SELECT m.user_id FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active FOR SHARE OF m,u")){s.setString(1,tenant);s.setString(2,m.ownerId());try(var r=s.executeQuery()){if(!r.next())throw missing();}}
    }
    private static Instant instant(ResultSet r,String name)throws SQLException{var value=r.getObject(name,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","草稿资料版本、身份或分页参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员关系或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException conflict(){return new DomainException(409,"VERSION_CONFLICT","资产版本已变化，请刷新后明确重试");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","手工资料快照或当前指针不一致，未返回成功结论");}
    private static DomainException sqlFailure(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Manual asset metadata PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return unavailable();}
    private static DomainException unavailable(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","手工资产资料数据库或结构未就绪，未取得写入成功证据");}
    @Override public List<String> readinessProblems(){
        try(Connection c=source.getConnection()){
            c.setReadOnly(true);try(var s=statement(c,"SELECT "+COLUMNS+" FROM public.mop_asset_manual_revision WHERE false")){s.executeQuery().close();}try(var s=statement(c,"SELECT tenant_id,asset_id,revision_version FROM public.mop_asset_manual_details WHERE false")){s.executeQuery().close();}
            for(String table:List.of("mop_asset","mop_asset_manual_revision","mop_asset_manual_details")){
                try(var s=statement(c,"SELECT current_database()='mop_test' AND current_user='mop_test_runtime' AND has_table_privilege(current_user,?,'SELECT') AND has_table_privilege(current_user,?,'INSERT') AND NOT has_table_privilege(current_user,?,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT EXISTS(SELECT 1 FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=?::regclass AND a.grantee=0)")){for(int i=1;i<=4;i++)s.setString(i,"public."+table);try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))return List.of("ASSET_METADATA_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
                Set<String> updates=table.equals("mop_asset")?Set.of("name","type","site_id","version","updated_at","updated_by"):table.equals("mop_asset_manual_details")?Set.of("revision_version"):Set.of();
                try(var s=statement(c,"SELECT attname,has_column_privilege(current_user,attrelid,attnum,'UPDATE') FROM pg_attribute WHERE attrelid=?::regclass AND attnum>0 AND NOT attisdropped")){s.setString(1,"public."+table);try(var r=s.executeQuery()){while(r.next())if(r.getBoolean(2)!=updates.contains(r.getString(1)))return List.of("ASSET_METADATA_SCHEMA_OR_ACCESS_UNAVAILABLE");}}
            }return List.of();
        }catch(SQLException|RuntimeException failure){return List.of("ASSET_METADATA_SCHEMA_OR_ACCESS_UNAVAILABLE");}
    }
}
