package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
/** Pure projection checks, not object-storage verification. */
class AssetExportProgressTest {
    final Instant time=Instant.parse("2026-10-04T00:00:00Z");
    Job job(State state){return new Job("id","tenant","actor",1,"msp","a".repeat(64),new Filters(null,null,null,null),state,state==State.QUEUED?0:2,time,time.plusSeconds(1));}
    Encoding file(){return new Encoding("a".repeat(64),"b".repeat(64),4,0,time);}
    @Test void nullPreparedAndExplicitEmptyFileAreDifferent(){assertNull(new Progress(job(State.RUNNING),1,null,null).prepared());assertEquals(0,new Progress(job(State.RUNNING),1,file(),null).prepared().rowCount());}
    @Test void failedStateRequiresExactErrorAndNoAttemptOnlyAllowsRevokedAuthorization(){assertThrows(DomainException.class,()->new Progress(job(State.FAILED),1,null,null));assertThrows(DomainException.class,()->new Progress(job(State.RUNNING),1,null,Failure.INPUT_LIMIT));assertThrows(DomainException.class,()->new Progress(job(State.FAILED),0,null,Failure.SOURCE_UNAVAILABLE));assertThrows(DomainException.class,()->new Progress(job(State.FAILED),0,file(),Failure.AUTHORIZATION_CHANGED));assertEquals(Failure.AUTHORIZATION_CHANGED,new Progress(job(State.FAILED),0,null,Failure.AUTHORIZATION_CHANGED).error());}
    @Test void reconciliationAndSuccessRequirePreparationButDoNotGrantDownloads(){for(State state:new State[]{State.RECONCILING_STORAGE,State.SUCCEEDED}){assertThrows(DomainException.class,()->new Progress(job(state),1,null,null));var p=new Progress(job(state),1,file(),null);assertFalse(p.downloadAllowed());assertNull(p.workerLiveness());assertEquals("NOT_CHECKED_BY_PROGRESS_READER",p.objectVerification());}}
    @Test void attemptBoundsAndQueuedContradictionsFailClosed(){for(int n:new int[]{-1,1001})assertThrows(DomainException.class,()->new Progress(job(State.RUNNING),n,null,null));assertThrows(DomainException.class,()->new Progress(job(State.QUEUED),1,null,null));assertThrows(DomainException.class,()->new Progress(job(State.QUEUED),0,file(),null));assertThrows(DomainException.class,()->new Progress(job(State.RUNNING),0,null,null));}
    @Test void descriptorOutsideOriginalTimelineIsRejected(){for(Instant t:new Instant[]{time.minusMillis(1),time.plusSeconds(2)})assertEquals(503,assertThrows(DomainException.class,()->new Progress(job(State.RUNNING),1,new Encoding("a".repeat(64),"b".repeat(64),4,1,t),null)).status);}
    @Test void failedPreparationIsRetainedForInvestigation(){var p=new Progress(job(State.FAILED),1,file(),Failure.CONFIRMED_STORAGE_REJECTION);assertEquals(file(),p.prepared());assertFalse(p.downloadAllowed());}
}
