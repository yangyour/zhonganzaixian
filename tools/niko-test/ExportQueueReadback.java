import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.persistence.*;
import com.fasterxml.jackson.databind.*;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;
/** Explicit read/replay proof in a new JVM. Never accepts a new job, migrates or starts a Worker. */
public final class ExportQueueReadback {
    public static void main(String[] args){
        if(!Arrays.equals(args,new String[]{"--verify-existing-export-receipts"})){System.err.println("Explicit existing-receipt verification required");System.exit(2);}
        try{run();}catch(Exception failure){System.err.println("EXPORT_QUEUE_READBACK status=FAILED exception_type="+failure.getClass().getSimpleName()+" details=REDACTED");System.exit(1);}
    }
    static void require(boolean value){if(!value)throw new IllegalStateException("Existing export receipt assertion failed");}
    static void run()throws Exception{
        require("jdbc:postgresql://127.0.0.1:25432/mop_test".equals(System.getenv("SPRING_DATASOURCE_URL")));require("mop_test_runtime".equals(System.getenv("SPRING_DATASOURCE_USERNAME")));
        var cfg=new HikariConfig();cfg.setJdbcUrl(System.getenv("SPRING_DATASOURCE_URL"));cfg.setUsername(System.getenv("SPRING_DATASOURCE_USERNAME"));cfg.setPassword(System.getenv("SPRING_DATASOURCE_PASSWORD"));cfg.setMaximumPoolSize(3);cfg.setConnectionTimeout(5000);
        try(var pool=new HikariDataSource(cfg)){
            var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);var platform=new PostgresPlatformRepository(pool,mapper);var exports=new PostgresAssetExportRepository(pool,platform);require(exports.readinessProblems().isEmpty());
            record Receipt(String actor,String tenant,String key,String hash,JsonNode body){}
            var receipts=new ArrayList<Receipt>();String route="POST /integration/asset-export-accept";
            try(var c=pool.getConnection();var s=c.prepareStatement("SELECT actor_id::text,tenant_id::text,request_key,payload_hash,response_json::text FROM public.mop_idempotency WHERE route=? ORDER BY actor_id,request_key")){c.setReadOnly(true);s.setString(1,route);s.setQueryTimeout(10);try(var r=s.executeQuery()){while(r.next())receipts.add(new Receipt(r.getString(1),r.getString(2),r.getString(3),r.getString(4),mapper.readTree(r.getString(5))));}}
            require(!receipts.isEmpty());var ids=new HashSet<String>();
            for(var receipt:receipts){
                Job original=mapper.treeToValue(receipt.body(),Job.class);require(original.tenantId().equals(receipt.tenant())&&original.createdBy().equals(receipt.actor()));require(ids.add(original.id()));
                var a=new Access(receipt.actor(),receipt.tenant(),original.permissionVersion(),"export-graceful-readback");require(exports.get(a,original.id()).equals(original));
                Object replay=platform.idempotentAuthorized(a.actorId(),a.tenantId(),route,receipt.key(),receipt.hash(),a.permissionVersion(),()->exports.authorizeAcceptance(a),()->{throw new IllegalStateException("Replay must never enter new acceptance");});
                require(mapper.valueToTree(replay).equals(receipt.body()));
            }
            try(var c=pool.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM public.mop_asset_export_job")){r.next();require(r.getLong(1)==ids.size());}
            System.out.println("EXPORT_QUEUE_READBACK status=PASS distinct_jobs="+ids.size()+" original_receipts_replayed="+receipts.size()+" new_acceptance_callbacks=0 worker_runs=0 store_requests=0");
        }
    }
}
