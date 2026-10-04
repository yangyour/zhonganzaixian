package cn.mop.exportworker;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import cn.mop.assetexport.AssetLedgerWorkbook;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Isolated orchestration only. No database, credentials, worker subprocess or store request. */
class ExportPreparationWorkerRuntimeTest {
    final Instant time=Instant.parse("2026-10-04T00:00:00Z");
    ExportPreparationWorkerSettings settings(){return ExportPreparationWorkerSettings.from(ExportPreparationWorkerSettingsTest.args(),ExportPreparationWorkerSettingsTest.valid()::get);}
    class Jobs implements AssetExportJobRepository {
        public Progress progress(AssetExportJobRepository.Access a,String id){throw new AssertionError("Worker must not use the public progress projection");}
        String target;int nextCalls,loads,prepares;boolean empty;DomainException failure;AssetExportPreparedFile retained;
        final Command command=new Command(new AssetExportJobState.Access(UUID.randomUUID(),UUID.randomUUID(),1,"msp",true),new Filters(null,null,null,null));Claim current;
        public Optional<Claim> claim(String id,String worker,Duration lease){target=id;if(failure!=null)throw failure;if(empty)return Optional.empty();current=new Claim(id,worker,retained==null?1:2,retained==null?1:3,time.plus(lease),command,retained!=null);return Optional.of(current);}
        public Optional<Claim> claimNext(String w,Duration d){nextCalls++;throw new AssertionError("Must never drain another job");}
        public Work loadWork(Claim c){loads++;return new Work(current,time.plusMillis(loads),retained);}
        public Work prepare(Claim c,AssetExportPreparedFile f){prepares++;retained=f;current=new Claim(c.jobId(),c.worker(),c.fencingToken(),c.version()+1,c.leaseUntil(),c.command(),false);return new Work(current,time.plusMillis(loads+1),f);}
        public void authorizeAcceptance(AssetExportJobRepository.Access a){throw new AssertionError();}public Job accept(AssetExportJobRepository.Access a,Filters f){throw new AssertionError();}public Job get(AssetExportJobRepository.Access a,String id){throw new AssertionError();}public List<Job> list(AssetExportJobRepository.Access a,int l,Instant t,String id){throw new AssertionError();}public List<String> readinessProblems(){throw new AssertionError();}
    }
    @Test void unavailableClaimDoesNotConsultSourceOrPretendCompleted()throws Exception{var jobs=new Jobs();jobs.empty=true;var result=ExportPreparationWorkerApplication.processOne(settings(),jobs,c->{throw new AssertionError();});assertEquals("NOT_CLAIMED",result.get("status"));assertEquals(false,result.get("published"));assertEquals(0,jobs.loads);assertEquals(0,jobs.nextCalls);assertEquals(ExportPreparationWorkerSettingsTest.ID,jobs.target);}
    @Test void singleTargetPreparationReturnsOnlyUnpublishedMetadata()throws Exception{var jobs=new Jobs();var result=ExportPreparationWorkerApplication.processOne(settings(),jobs,c->new AssetExportSnapshotRepository.Selection(c,time,List.of()));assertEquals("PREPARED_STORAGE_NOT_ATTEMPTED",result.get("status"));assertEquals(0,result.get("data_rows"));assertEquals(false,result.get("published"));assertEquals(0,result.get("store_requests"));assertEquals(0,result.get("download_grants"));assertEquals(0,result.get("notifications_sent"));assertEquals(1,jobs.prepares);assertEquals(0,jobs.nextCalls);String json=ExportPreparationWorkerApplication.mapper().writeValueAsString(result);assertFalse(json.contains("UNIT_ONLY_SECRET"));assertFalse(result.containsKey("file_bytes"));assertFalse(result.containsKey("jdbc_url"));assertThrows(UnsupportedOperationException.class,()->result.put("published",true));}
    @Test void retainedFileRequiresStorageReconciliationWithoutRequeryingSource()throws Exception{var jobs=new Jobs();byte[] bytes=AssetLedgerWorkbook.encode(jobs.command.acceptedAccess().tenantId(),List.of(),true,time).bytes();jobs.retained=AssetExportPreparedFile.create(jobs.command,"a".repeat(64),0,time,bytes);String hash=jobs.retained.encoding().fileSha256();var result=ExportPreparationWorkerApplication.processOne(settings(),jobs,c->{throw new AssertionError();});assertEquals("RETAINED_REQUIRES_STORAGE_RECONCILIATION",result.get("status"));assertEquals(true,result.get("storage_reconciliation_required"));assertEquals(hash,result.get("file_sha256"));assertEquals(0,jobs.prepares);assertEquals(1,jobs.loads);}
    @Test void claimFailurePropagatesInsteadOfBecomingNoWork(){var jobs=new Jobs();jobs.failure=new DomainException(503,"UPSTREAM_UNAVAILABLE","synthetic failure");assertEquals(503,assertThrows(DomainException.class,()->ExportPreparationWorkerApplication.processOne(settings(),jobs,c->{throw new AssertionError();})).status);assertEquals(0,jobs.loads);assertEquals(0,jobs.prepares);}
    @Test void missingActualSourceCannotProduceAFile(){var jobs=new Jobs();assertEquals(503,assertThrows(DomainException.class,()->ExportPreparationWorkerApplication.processOne(settings(),jobs,c->{throw new DomainException(503,"EXPORT_SOURCE_NOT_READY","synthetic source failure");})).status);assertEquals(0,jobs.prepares);}
}
