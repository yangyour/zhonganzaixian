package cn.mop.exportworker;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.assetexport.AssetExportPreparationProcessor;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import java.io.IOException;
import java.util.*;
/** Single explicit target. No Spring context, web server, Flyway startup, storage client or notification consumer. */
public final class ExportPreparationWorkerApplication {
    private ExportPreparationWorkerApplication(){}
    public static void main(String[] args){
        int exit;
        try{var settings=ExportPreparationWorkerSettings.from(args,System::getenv);var mapper=mapper();Map<String,Object> result=run(settings,mapper);System.out.println(mapper.writeValueAsString(result));exit="NOT_CLAIMED".equals(result.get("status"))?3:0;}
        catch(Exception failure){System.out.println("{\"status\":\"FAILED\",\"error_code\":\""+safeCode(failure)+"\",\"details\":\"REDACTED\"}");exit=1;}
        if(exit!=0)System.exit(exit);
    }
    static String safeCode(Exception failure){if(failure instanceof DomainException domain&&domain.code!=null&&domain.code.matches("[A-Z0-9_]{1,64}"))return domain.code;return failure instanceof IllegalStateException?"CONFIGURATION_OR_COMPONENT_NOT_READY":"EXPORT_PREPARATION_FAILED";}
    static ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
    static Map<String,Object> run(ExportPreparationWorkerSettings settings,ObjectMapper mapper)throws IOException{
        var config=new HikariConfig();config.setJdbcUrl(settings.jdbcUrl());config.setUsername(settings.user());config.setPassword(settings.password());config.setMaximumPoolSize(2);config.setMinimumIdle(0);config.setConnectionTimeout(5000);config.setValidationTimeout(3000);config.setInitializationFailTimeout(5000);config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");config.addDataSourceProperty("tcpKeepAlive","true");config.addDataSourceProperty("ApplicationName","mop-export-preparation-worker");
        try(var pool=new HikariDataSource(config)){
            var platform=new PostgresPlatformRepository(pool,mapper);var jobs=new PostgresAssetExportRepository(pool,platform);var source=new PostgresManualAssetMetadataRepository(pool,platform,mapper);
            if(!platform.dependencyReady()||!jobs.readinessProblems().isEmpty())throw new DomainException(503,"EXPORT_COMPONENT_NOT_READY","Restricted export structure unavailable");
            AssetExportSnapshotRepository verifiedSource=command->{if(!source.readinessProblems().isEmpty())throw new DomainException(503,"EXPORT_SOURCE_NOT_READY","Actual ledger source unavailable");return source.select(command);};
            return processOne(settings,jobs,verifiedSource);
        }
    }
    static Map<String,Object> processOne(ExportPreparationWorkerSettings settings,AssetExportJobRepository jobs,AssetExportSnapshotRepository source)throws IOException{
        var claimed=jobs.claim(settings.jobId(),settings.workerId(),settings.lease());
        if(claimed.isEmpty())return Map.of("status","NOT_CLAIMED","published",false,"store_requests",0);
        Work result=new AssetExportPreparationProcessor(jobs,source).prepare(claimed.get());var file=result.prepared();
        if(file==null)throw new DomainException(503,"EXPORT_PREPARATION_MISSING","No prepared file returned");
        var output=new LinkedHashMap<String,Object>();output.put("status",result.claim().storageReconciliationRequired()?"RETAINED_REQUIRES_STORAGE_RECONCILIATION":"PREPARED_STORAGE_NOT_ATTEMPTED");output.put("job_id",result.claim().jobId());output.put("fencing_token",result.claim().fencingToken());output.put("version",result.claim().version());output.put("data_rows",file.encoding().rowCount());output.put("file_size",file.encoding().sizeBytes());output.put("file_sha256",file.encoding().fileSha256());output.put("selection_sha256",file.encoding().selectionSha256());output.put("storage_reconciliation_required",result.claim().storageReconciliationRequired());output.put("published",false);output.put("store_requests",0);output.put("download_grants",0);output.put("notifications_sent",0);return Collections.unmodifiableMap(output);
    }
}
