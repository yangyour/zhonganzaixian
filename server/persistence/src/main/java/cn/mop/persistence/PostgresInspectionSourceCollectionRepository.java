package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionSourceCollectionRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Protected append-only source documents; the same current authorization precedes every original receipt replay. */
public final class PostgresInspectionSourceCollectionRepository implements InspectionSourceCollectionRepository {
    private static final String TABLE="public.mop_inspection_source_collection";
    private static final String SELECT="SELECT id::text,tenant_id::text,created_by::text,label,recorded_at,version,source_count,document_format,document_sha256,source_manifest_sha256,record_source,verification_status,report_published,formal_report_generatable";
    private static final System.Logger LOG=System.getLogger(PostgresInspectionSourceCollectionRepository.class.getName());
    private final DataSource source;private final PostgresPlatformRepository platform;private final PostgresInspectionWorksheetRepository worksheets;
    public PostgresInspectionSourceCollectionRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);worksheets=new PostgresInspectionWorksheetRepository(source,platform);}
    @Override public void authorizeWrite(Access a){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Summary create(Access a,long expectedVersion,Creation input){
        Connection c=platform.idempotentCommandConnection();if(expectedVersion!=0||input==null)throw invalid();
        try{authorize(c,a,true);var selections=input.sources().stream().map(s->new InspectionWorksheetRepository.Selection(s.worksheetId(),s.version())).toList();var captured=worksheets.captureForProtectedWrite(new InspectionWorksheetRepository.Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId()),selections);
            var expected=new HashMap<String,String>();for(var s:input.sources())expected.put(s.worksheetId()+"/"+s.version(),s.expectedSourceSha256());for(var s:captured.sources())if(!s.fingerprint().equals(expected.get(s.worksheetId()+"/"+s.version())))throw new DomainException(409,"INSPECTION_SOURCE_HASH_CONFLICT","所选来源摘要与明确预期不同；没有自动替换或保存集合");
            byte[] document=InspectionSourceDocumentCodec.encode(captured);String id=UUID.randomUUID().toString();Instant now=now(c);
            try(var s=statement(c,"INSERT INTO "+TABLE+"(id,tenant_id,created_by,label,recorded_at,source_count,document,source_manifest_sha256) VALUES(?::uuid,?::uuid,?::uuid,?,?,?,?,?)")){s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());s.setString(4,input.label());s.setObject(5,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));s.setInt(6,captured.selectedSourceCount());s.setBytes(7,document);s.setString(8,captured.fingerprint());if(s.executeUpdate()!=1)throw corrupt();}
            try(var s=statement(c,"INSERT INTO public.mop_inspection_source_collection_item(tenant_id,collection_id,position,worksheet_id,worksheet_version,source_snapshot_sha256) VALUES(?::uuid,?::uuid,?,?::uuid,?,?)")){int position=0;for(var item:captured.sources()){s.setString(1,a.tenantId());s.setString(2,id);s.setInt(3,++position);s.setString(4,item.worksheetId().toString());s.setLong(5,item.version());s.setString(6,item.fingerprint());s.addBatch();}for(int count:s.executeBatch())if(count!=1&&count!=Statement.SUCCESS_NO_INFO)throw corrupt();}
            platform.audit(a.actorId(),a.tenantId(),"inspection_source_collection.create","inspection_source_collection",id,"SUCCEEDED","IMMUTABLE_SOURCE_DRAFT_NOT_REPORT",a.traceId());return get(c,a,id).summary();
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T readonly(Access a,Read<T> work){platform.requireStandaloneRead();try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a,false);T value=work.run(c);c.commit();return value;}catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Stored get(Access a,String id){String record=uuid(id);return readonly(a,c->get(c,a,record));}
    private static Stored get(Connection c,Access a,String id)throws SQLException{try(var s=statement(c,SELECT+",document FROM "+TABLE+" WHERE tenant_id=?::uuid AND id=?::uuid")){s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();var summary=summary(r,a);var manifest=InspectionSourceDocumentCodec.decode(r.getBytes("document"),UUID.fromString(a.tenantId()),summary.documentSha256(),summary.sourceManifestSha256());if(manifest.selectedSourceCount()!=summary.sourceCount()||manifest.sources().stream().anyMatch(item->item.recordedAt().isAfter(summary.recordedAt())))throw corrupt();return new Stored(summary,manifest);}}}
    @Override public List<Summary> list(Access a,int limit,Instant before,String beforeId){
        if(limit<1||limit>21||(before==null)!=(beforeId==null))throw invalid();String id=beforeId==null?null:uuid(beforeId);if(before!=null)bounded(before);
        return readonly(a,c->{var values=new ArrayList<Summary>();try(var s=statement(c,SELECT+" FROM "+TABLE+" WHERE tenant_id=?::uuid"+(before==null?"":" AND (recorded_at,id)<(?::timestamptz,?::uuid)")+" ORDER BY recorded_at DESC,id DESC LIMIT ?")){s.setString(1,a.tenantId());int p=2;if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,id);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())values.add(summary(r,a));}}return List.copyOf(values);});
    }
    private static Summary summary(ResultSet r,Access a)throws SQLException{
        if(!a.tenantId().equals(r.getString("tenant_id"))||r.getLong("version")!=1||!InspectionSourceDocumentCodec.FORMAT.equals(r.getString("document_format"))||!"EXPLICIT_IMMUTABLE_SOURCE_COLLECTION".equals(r.getString("record_source"))||!"SELF_REPORTED_UNVERIFIED".equals(r.getString("verification_status"))||r.getBoolean("report_published")||r.getBoolean("formal_report_generatable"))throw corrupt();
        try{String id=uuid(r.getString("id")),creator=uuid(r.getString("created_by")),label=r.getString("label"),documentHash=r.getString("document_sha256"),manifestHash=r.getString("source_manifest_sha256");new Creation(label,List.of());Instant recorded=r.getObject("recorded_at",OffsetDateTime.class).toInstant();bounded(recorded);int count=r.getInt("source_count");if(count<0||count>1000||documentHash==null||!documentHash.matches("[0-9a-f]{64}")||manifestHash==null||!manifestHash.matches("[0-9a-f]{64}"))throw corrupt();return new Summary(id,a.tenantId(),creator,recorded,1,label,count,documentHash,manifestHash);}catch(DomainException|NullPointerException failure){throw corrupt();}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null||a.permissionVersion()<1)throw expired();if(!uuid(a.actorId()).equals(a.actorId())||!uuid(a.tenantId()).equals(a.tenantId())||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","独立巡检来源集合仅限当前授权平台管理员");}}
    }
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){if(!r.next())throw corrupt();Instant time=r.getObject(1,OffsetDateTime.class).toInstant();bounded(time);return time;}}
    private static void bounded(Instant time){if(time==null||time.getNano()%1_000_000!=0||time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||time.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","来源集合字段、创建版本或查询边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","冻结来源集合、时间或摘要不一致，未提供成功结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Inspection source collection PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","来源集合数据库或结构不可用，未确认写入成功");}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);InspectionSourceCollectionSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException failure){return List.of("INSPECTION_SOURCE_COLLECTION_STRUCTURE_UNAVAILABLE");}}
}
