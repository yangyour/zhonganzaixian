package cn.mop.persistence;
import cn.mop.domain.DomainException;
import cn.mop.domain.inspection.*;
import cn.mop.domain.inspection.InspectionQuarterlyReportRepository.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
/** Protected append-only DRAFT report snapshots; current authorization precedes receipt replay. */
public final class PostgresInspectionQuarterlyReportRepository implements InspectionQuarterlyReportRepository {
    private static final String TABLE="public.mop_inspection_quarterly_report";
    private static final String SELECT="SELECT id::text,tenant_id::text,created_by::text,label,recorded_at,version,source_collection_id::text,source_document_sha256,source_manifest_sha256,year,quarter,report_sha256,document_sha256,document_format,generation_status,report_published,report_publishable,evidence_access_granted,configuration_source";
    private static final System.Logger LOG=System.getLogger(PostgresInspectionQuarterlyReportRepository.class.getName());
    private final DataSource source;private final PostgresPlatformRepository platform;private final PostgresInspectionSourceCollectionRepository collections;
    public PostgresInspectionQuarterlyReportRepository(DataSource source,PostgresPlatformRepository platform){this.source=Objects.requireNonNull(source);this.platform=Objects.requireNonNull(platform);collections=new PostgresInspectionSourceCollectionRepository(source,platform);}
    @Override public void authorizeWrite(Access a){Connection c=platform.idempotentCommandConnection();try{authorize(c,a,true);}catch(SQLException failure){throw unavailable(failure);}}

    /** Capture composes the existing immutable repositories on the same protected transaction. */
    @Override
    public void authorizeCapture(Access access) {
        authorizeWrite(access);
        collections.authorizeWrite(collectionAccess(access));
        // Preserve the existing source-capture capability, including on receipt replay after revocation.
        new PostgresInspectionWorksheetRepository(source, platform).authorizeWrite(
                new InspectionWorksheetRepository.Access(access.actorId(), access.tenantId(),
                        access.permissionVersion(), access.traceId()), null);
    }

    @Override
    public Summary capture(Access access, long expectedVersion, CaptureCreation input) {
        authorizeCapture(access);
        if (expectedVersion != 0 || input == null) {
            throw invalid();
        }
        var frozen = collections.create(collectionAccess(access), 0,
                new InspectionSourceCollectionRepository.Creation(input.label(), input.sources()));
        return create(access, 0, new Creation(input.label(), frozen.id(), frozen.documentSha256(),
                frozen.sourceManifestSha256(), input.period(), input.configuration()));
    }

    private static InspectionSourceCollectionRepository.Access collectionAccess(Access access) {
        return new InspectionSourceCollectionRepository.Access(access.actorId(), access.tenantId(),
                access.permissionVersion(), access.traceId());
    }

    @Override public Summary create(Access a,long expectedVersion,Creation input){
        Connection c=platform.idempotentCommandConnection();if(expectedVersion!=0||input==null)throw invalid();
        try{authorize(c,a,true);
            var frozen=collections.getForProtectedWrite(new InspectionSourceCollectionRepository.Access(a.actorId(),a.tenantId(),a.permissionVersion(),a.traceId()),input.sourceCollectionId());
            if(!frozen.summary().documentSha256().equals(input.expectedSourceDocumentSha256())||!frozen.summary().sourceManifestSha256().equals(input.expectedSourceManifestSha256()))
                throw new DomainException(409,"INSPECTION_REPORT_SOURCE_HASH_CONFLICT","报告草稿与明确预期摘要不符；未保存报告");
            var report=InspectionQuarterlyReport.generate(UUID.fromString(a.tenantId()),frozen.manifest(),input.period(),input.configuration(),InspectionQuarterlyReport.Completeness.COMPLETE_EXPLICIT_SELECTION);
            byte[] document=InspectionQuarterlyReportDocumentCodec.encode(input.sourceCollectionId(),report);String id=UUID.randomUUID().toString();Instant now=now(c);
            try(var s=statement(c,"INSERT INTO "+TABLE+"(id,tenant_id,created_by,label,recorded_at,source_collection_id,source_document_sha256,source_manifest_sha256,year,quarter,report_sha256,document) VALUES(?::uuid,?::uuid,?::uuid,?,?,?::uuid,?,?,?,?,?,?)")){
                s.setString(1,id);s.setString(2,a.tenantId());s.setString(3,a.actorId());s.setString(4,input.label());s.setObject(5,OffsetDateTime.ofInstant(now,ZoneOffset.UTC));s.setString(6,input.sourceCollectionId());s.setString(7,input.expectedSourceDocumentSha256());s.setString(8,input.expectedSourceManifestSha256());s.setInt(9,input.period().year());s.setInt(10,input.period().quarter());s.setString(11,report.fingerprint());s.setBytes(12,document);if(s.executeUpdate()!=1)throw corrupt();
            }
            platform.audit(a.actorId(),a.tenantId(),"inspection_quarterly_report.create","inspection_quarterly_report",id,"SUCCEEDED","IMMUTABLE_DRAFT_NOT_APPROVED_OR_PUBLISHED",a.traceId());return get(c,a,id).summary();
        }catch(SQLException failure){throw unavailable(failure);}
    }
    @FunctionalInterface private interface Read<T>{T run(Connection c)throws SQLException;}
    private <T>T readonly(Access a,Read<T> work){platform.requireStandaloneRead();try(Connection c=source.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);try{authorize(c,a,false);T value=work.run(c);c.commit();return value;}catch(SQLException|RuntimeException|Error failure){try{c.rollback();}catch(SQLException ignored){}throw failure;}}catch(SQLException failure){throw unavailable(failure);}}
    @Override public Stored get(Access a,String id){String record=uuid(id);return readonly(a,c->get(c,a,record));}
    private static Stored get(Connection c,Access a,String id)throws SQLException{
        try(var s=statement(c,SELECT+",document FROM "+TABLE+" WHERE tenant_id=?::uuid AND id=?::uuid")){
            s.setString(1,a.tenantId());s.setString(2,id);try(var r=s.executeQuery()){if(!r.next())throw missing();var meta=summary(r,a);
                String document=InspectionQuarterlyReportDocumentCodec.decode(r.getBytes("document"),UUID.fromString(a.tenantId()),meta.sourceCollectionId(),meta.sourceDocumentSha256(),meta.sourceManifestSha256(),meta.reportSha256(),meta.documentSha256(),meta.year(),meta.quarter());
                return new Stored(meta,document);
            }
        }
    }
    @Override public List<Summary> list(Access a,int limit,Instant before,String beforeId){
        if(limit<1||limit>21||(before==null)!=(beforeId==null))throw invalid();String id=beforeId==null?null:uuid(beforeId);if(before!=null)bounded(before);
        return readonly(a,c->{var values=new ArrayList<Summary>();try(var s=statement(c,SELECT+" FROM "+TABLE+" WHERE tenant_id=?::uuid"+(before==null?"":" AND (recorded_at,id)<(?::timestamptz,?::uuid)")+" ORDER BY recorded_at DESC,id DESC LIMIT ?")){s.setString(1,a.tenantId());int p=2;if(before!=null){s.setObject(p++,OffsetDateTime.ofInstant(before,ZoneOffset.UTC));s.setString(p++,id);}s.setInt(p,limit);try(var r=s.executeQuery()){while(r.next())values.add(summary(r,a));}}return List.copyOf(values);});
    }
    private static Summary summary(ResultSet r,Access a)throws SQLException{
        if(!a.tenantId().equals(r.getString("tenant_id"))||r.getLong("version")!=1||!InspectionQuarterlyReportDocumentCodec.FORMAT.equals(r.getString("document_format"))||!"DRAFT".equals(r.getString("generation_status"))||!"REQUEST_DRAFT_NOT_APPROVED".equals(r.getString("configuration_source"))||r.getBoolean("report_published")||r.getBoolean("report_publishable")||r.getBoolean("evidence_access_granted"))throw corrupt();
        try{String id=uuid(r.getString("id")),creator=uuid(r.getString("created_by")),label=r.getString("label"),collection=uuid(r.getString("source_collection_id"));
            String sourceHash=r.getString("source_document_sha256"),manifestHash=r.getString("source_manifest_sha256"),reportHash=r.getString("report_sha256"),documentHash=r.getString("document_sha256");
            int year=r.getInt("year"),quarter=r.getInt("quarter");new Creation(label,collection,sourceHash,manifestHash,new InspectionQuarterlyReport.Period(year,quarter),InspectionQuarterlyReport.Configuration.pending());
            if(reportHash==null||!reportHash.matches("[0-9a-f]{64}")||documentHash==null||!documentHash.matches("[0-9a-f]{64}"))throw corrupt();
            Instant recorded=r.getObject("recorded_at",OffsetDateTime.class).toInstant();bounded(recorded);
            return new Summary(id,a.tenantId(),creator,recorded,1,label,collection,sourceHash,manifestHash,year,quarter,reportHash,documentHash);
        }catch(DomainException|NullPointerException failure){throw corrupt();}
    }
    private static void authorize(Connection c,Access a,boolean lock)throws SQLException{
        if(a==null)throw new DomainException(401,"SESSION_EXPIRED","当前授权上下文无效");
        PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),lock?"inspection_quarterly_reports:write":"inspection_quarterly_reports:read");
        if(lock)PostgresAuthorization.require(c,a.actorId(),a.tenantId(),a.permissionVersion(),"inspection_source_collections:read");

        if(a==null||a.permissionVersion()<1)throw expired();if(!uuid(a.actorId()).equals(a.actorId())||!uuid(a.tenantId()).equals(a.tenantId())||a.traceId()==null||!a.traceId().matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        try(var s=statement(c,"SELECT m.role_code,m.permission_version FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active"+(lock?" FOR SHARE OF m,u":""))){s.setString(1,a.tenantId());s.setString(2,a.actorId());try(var r=s.executeQuery()){if(!r.next()||r.getLong(2)!=a.permissionVersion())throw expired();if(!"padmin".equals(r.getString(1)))throw new DomainException(403,"ACTION_FORBIDDEN","独立巡检报告草稿仅限当前授权平台管理员");}}
    }
    private static Instant now(Connection c)throws SQLException{try(var s=statement(c,"SELECT date_trunc('milliseconds',clock_timestamp())");var r=s.executeQuery()){if(!r.next())throw corrupt();Instant time=r.getObject(1,OffsetDateTime.class).toInstant();bounded(time);return time;}}
    private static void bounded(Instant time){if(time==null||time.getNano()%1_000_000!=0||time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||time.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{var s=c.prepareStatement(sql);s.setQueryTimeout(10);return s;}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","报告草稿字段、创建版本或查询边界无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前成员或权限版本已失效");}
    private static DomainException missing(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","冻结报告草稿、时间或摘要不一致，未提供成功结果");}
    private static DomainException unavailable(SQLException failure){String state=failure.getSQLState();LOG.log(System.Logger.Level.WARNING,"Inspection quarterly report PostgreSQL failure sql_state={0}",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNKNOWN");return new DomainException(503,"UPSTREAM_UNAVAILABLE","报告草稿数据库或结构不可用，未确认写入成功");}
    @Override public List<String> readinessProblems(){try(Connection c=source.getConnection()){c.setReadOnly(true);InspectionQuarterlyReportSchemaVerifier.verify(c);return List.of();}catch(SQLException|RuntimeException failure){return List.of("INSPECTION_QUARTERLY_REPORT_STRUCTURE_UNAVAILABLE");}}
}
