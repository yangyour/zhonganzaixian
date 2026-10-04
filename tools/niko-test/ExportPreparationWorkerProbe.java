import cn.mop.domain.*;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import cn.mop.domain.assetmetadata.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
/** Scoped real preparation-worker proof data. No migration, credentials, role grants or object store calls. */
public final class ExportPreparationWorkerProbe {
    static final String TENANT="55000000-0000-4000-8000-000000000001",SOURCE_ROUTE="POST /qa/export-worker-source",JOB_ROUTE="POST /qa/export-worker-accept";
    static void require(boolean condition){if(!condition)throw new IllegalStateException("Owned export proof invariant failed");}
    static String id(String value){require(value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));return value;}
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static String name(String run){return "SYNTHETIC_EXPORT_WORKER_"+run;}
    static void print(ObjectMapper m,Object value)throws Exception{System.out.println("MOP_EXPORT_WORKER_PROBE "+m.writeValueAsString(value));}
    public static void main(String[] args){try{run(args);}catch(Throwable failure){System.out.println("MOP_EXPORT_WORKER_PROBE {\"status\":\"FAILED\",\"exception_type\":\""+failure.getClass().getSimpleName()+"\",\"details\":\"REDACTED\"}");System.exit(1);}}
    static void run(String[] args)throws Exception{
        require(args.length>=1&&Set.of("seed","inspect","change-source","fingerprints").contains(args[0]));String action=args[0];require(action.equals("fingerprints")?args.length==1:args.length==2);String run=action.equals("fingerprints")?null:id(args[1]);
        require(Set.of("jdbc:postgresql://127.0.0.1:25432/mop_test","jdbc:postgresql://127.0.0.1:15432/mop_test").contains(System.getenv("SPRING_DATASOURCE_URL")));require("mop_test_runtime".equals(System.getenv("SPRING_DATASOURCE_USERNAME")));
        var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(3);cfg.setConnectionTimeout(5000);
        try(var pool=new HikariDataSource(cfg)){
            var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);var platform=new PostgresPlatformRepository(pool,mapper);var jobs=new PostgresAssetExportRepository(pool,platform);var metadata=new PostgresManualAssetMetadataRepository(pool,platform,mapper);require(jobs.readinessProblems().isEmpty());
            if(action.equals("fingerprints")){
                var output=new LinkedHashMap<String,Object>();
                try(var c=pool.getConnection()){c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);for(String table:List.of("mop_user","mop_membership","mop_outbox","mop_photo_job","mop_asset_import_job","mop_contact_import_job","mop_contact_import_result"))try(var s=c.createStatement()){s.setQueryTimeout(30);try(var r=s.executeQuery("SELECT count(*),encode(sha256(convert_to(COALESCE(string_agg(row_hash,E'\\n' ORDER BY row_hash),''),'UTF8')),'hex') FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') AS row_hash FROM public."+table+" t) hashes")){r.next();output.put(table,Map.of("rows",r.getLong(1),"row_hash_aggregate_sha256",r.getString(2)));}}c.commit();}print(mapper,output);return;
            }
            String actor=platform.users().stream().filter(u->platform.membership(u.id(),TENANT).filter(m->m.role().equals("padmin")).isPresent()).findFirst().orElseThrow().id();long permission=platform.membership(actor,TENANT).orElseThrow().permissionVersion();var access=new AssetExportJobRepository.Access(actor,TENANT,permission,run);var metaAccess=new ManualAssetMetadataRepository.Access(actor,TENANT,permission,run);
            if(action.equals("seed")){
                Object source=platform.idempotentAuthorized(actor,TENANT,SOURCE_ROUTE,run,hash("source-v1|"+run),permission,()->jobs.authorizeAcceptance(access),()->{
                    var asset=platform.insert(TENANT,actor,name(run),"router","192.0.2.170","site-a-main");platform.audit(actor,TENANT,"integration.export_worker.source_create","asset",asset.id(),"SUCCEEDED","SYNTHETIC_TEST_ONLY",run);
                    metadata.update(metaAccess,asset.id(),asset.version(),new ManualAssetMetadata(name(run),"router","site-a-main",null,null,null,null,null,"niko synthetic worker process proof",null,null,null,null,"proof-before"));return Map.of("asset_id",asset.id());
                });
                String assetId=mapper.valueToTree(source).path("asset_id").asText();id(assetId);var current=metadata.current(metaAccess,assetId);require(current.snapshot().asset().name().equals(name(run)));
                var filters=new Filters("router","unknown","site-a-main",name(run));var command=new Command(new AssetExportJobState.Access(UUID.fromString(TENANT),UUID.fromString(actor),permission,"padmin",true),filters);
                Object receipt=platform.idempotentAuthorized(actor,TENANT,JOB_ROUTE,run,command.fingerprint(),permission,()->jobs.authorizeAcceptance(access),()->jobs.accept(access,filters));var value=mapper.valueToTree(receipt);require(value.path("state").asText().equals("QUEUED")&&value.path("version").asLong()==0);print(mapper,Map.of("job_id",value.path("id").asText(),"asset_id",assetId,"accepted_state","QUEUED","accepted_version",0,"input_sha256",value.path("input_sha256").asText()));return;
            }
            String jobId,assetId;
            try(var c=pool.getConnection();var s=c.prepareStatement("SELECT response_json->>'id' FROM public.mop_idempotency WHERE actor_id=?::uuid AND tenant_id=?::uuid AND route=? AND request_key=?")){s.setString(1,actor);s.setString(2,TENANT);s.setString(3,JOB_ROUTE);s.setString(4,run);try(var r=s.executeQuery()){require(r.next());jobId=id(r.getString(1));}}
            try(var c=pool.getConnection();var s=c.prepareStatement("SELECT id::text FROM public.mop_asset WHERE tenant_id=?::uuid AND name=?")){s.setString(1,TENANT);s.setString(2,name(run));try(var r=s.executeQuery()){require(r.next());assetId=id(r.getString(1));}}
            var job=jobs.get(access,jobId);require(job.createdBy().equals(actor)&&job.filters().query().equals(name(run)));var current=metadata.current(metaAccess,assetId);require(current.snapshot().asset().name().equals(name(run))&&current.snapshot().asset().source().equals("manual"));
            if(action.equals("change-source")){
                var m=current.snapshot().metadata();require(m!=null&&Set.of("proof-before","proof-after").contains(m.note()));var next=new ManualAssetMetadata(m.name(),m.type(),m.siteId(),m.brand(),m.model(),m.serialNumber(),m.location(),m.ownerId(),m.businessDescription(),m.purchaseDate(),m.warrantyExpiry(),m.overdueServiceDate(),m.collectionMethodNote(),"proof-after");
                platform.idempotentAuthorized(actor,TENANT,"POST /qa/export-worker-source-change",run,hash("source-after-v1|"+run),permission,()->metadata.authorizeCurrentAsset(metaAccess,assetId),()->metadata.update(metaAccess,assetId,current.snapshot().asset().version(),next));require(metadata.current(metaAccess,assetId).snapshot().metadata().note().equals("proof-after"));print(mapper,Map.of("owned_source_changed",true,"job_id",jobId));return;
            }
            var out=new LinkedHashMap<String,Object>();out.put("state",job.state().name());out.put("version",job.version());out.put("input_sha256",job.inputSha256());out.put("source_marker_is_after",current.snapshot().metadata().note().equals("proof-after"));
            try(var c=pool.getConnection();var s=c.prepareStatement("SELECT fencing_token,file_bytes,file_sha256,file_size,row_count,selection_sha256,lease_until<=clock_timestamp() AS lease_expired FROM public.mop_asset_export_job WHERE tenant_id=?::uuid AND created_by=?::uuid AND id=?::uuid")){s.setString(1,TENANT);s.setString(2,actor);s.setString(3,jobId);try(var r=s.executeQuery()){require(r.next());out.put("fencing_token",r.getInt("fencing_token"));out.put("lease_expired",r.getBoolean("lease_expired"));byte[] bytes=r.getBytes("file_bytes");out.put("prepared",bytes!=null);out.put("file_sha256",r.getString("file_sha256"));out.put("selection_sha256",r.getString("selection_sha256"));out.put("file_size",r.getObject("file_size"));out.put("data_rows",r.getObject("row_count"));if(bytes!=null){require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(r.getString("file_sha256")));try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){var sheet=book.getSheetAt(0);require(sheet.getPhysicalNumberOfRows()==2&&sheet.getRow(0).getLastCellNum()==16);require(sheet.getRow(1).getCell(0).getStringCellValue().equals(name(run)));out.put("workbook_marker_is_before",sheet.getRow(1).getCell(14).getStringCellValue().equals("proof-before"));require(sheet.getRow(1).getCell(15).getCellType()==org.apache.poi.ss.usermodel.CellType.BLANK);out.put("actual_workbook_verified",true);}}}}
            try(var c=pool.getConnection();var s=c.prepareStatement("SELECT count(*) FROM public.mop_asset_export_attempt WHERE tenant_id=?::uuid AND job_id=?::uuid")){s.setString(1,TENANT);s.setString(2,jobId);try(var r=s.executeQuery()){r.next();out.put("attempts",r.getLong(1));}}
            print(mapper,out);
        }
    }
}
