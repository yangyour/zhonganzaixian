package cn.mop.domain.backup;

import cn.mop.domain.DomainException;
import java.time.*;
import java.util.*;

/** Immutable contract-fixed run/attempt facts. No scheduler, SSH, storage validation or automatic ticketing. */
public final class BackupRunState {
    public static final int MAX_ATTEMPTS=3; // Initial attempt plus two retries, as explicitly specified in M6.
    public static final Duration RETRY_DELAY=Duration.ofMinutes(10);
    private static final long MAX_VERSION=9007199254740991L;
    public enum State { QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED }
    public enum Outcome { RUNNING, RESULT_UNKNOWN, SUCCEEDED, FAILED }
    public enum Failure { CONNECTION_UNAVAILABLE, READ_TIMEOUT, HOST_KEY_REJECTED, AUTHORIZATION_REJECTED, COMMAND_REJECTED, STORAGE_FAILED, VERIFICATION_FAILED }
    /** A reference to independently verified encrypted persistence, never plaintext or a credential. */
    public record StoredVersion(UUID tenantId,UUID assetId,UUID versionId,String encryptedObjectSha256,long encryptedBytes,Instant persistedAt) {
        public StoredVersion {
            if(tenantId==null||assetId==null||versionId==null||encryptedObjectSha256==null||!encryptedObjectSha256.matches("[0-9a-f]{64}")||encryptedBytes<1||encryptedBytes>MAX_VERSION)throw invalid();
            instant(persistedAt);
        }
    }
    public record Attempt(int number,Instant startedAt,Instant observedAt,Instant finishedAt,Outcome outcome,Failure failure,StoredVersion storedVersion) {
        public Attempt {
            if(number<1||number>MAX_ATTEMPTS||outcome==null)throw invalid();instant(startedAt);instant(observedAt);if(observedAt.isBefore(startedAt))throw invalid();
            boolean terminal=outcome==Outcome.SUCCEEDED||outcome==Outcome.FAILED;
            if(terminal){instant(finishedAt);if(!finishedAt.equals(observedAt))throw invalid();}else if(finishedAt!=null)throw invalid();
            if((outcome==Outcome.FAILED)!=(failure!=null)||(outcome==Outcome.SUCCEEDED)!=(storedVersion!=null))throw invalid();
            if(storedVersion!=null&&(storedVersion.persistedAt().isBefore(startedAt)||storedVersion.persistedAt().isAfter(finishedAt)))throw invalid();
        }
    }
    private final UUID tenantId,assetId;
    private final Instant queuedAt;
    private final long version;
    private final List<Attempt> attempts;
    private BackupRunState(UUID tenantId,UUID assetId,Instant queuedAt,long version,List<Attempt> attempts){
        this.tenantId=tenantId;this.assetId=assetId;this.queuedAt=queuedAt;this.version=version;this.attempts=List.copyOf(attempts);
    }
    public static BackupRunState queued(UUID tenantId,UUID assetId,Instant queuedAt){
        if(tenantId==null||assetId==null)throw invalid();instant(queuedAt);return new BackupRunState(tenantId,assetId,queuedAt,0,List.of());
    }
    public UUID tenantId(){return tenantId;}public UUID assetId(){return assetId;}public Instant queuedAt(){return queuedAt;}
    public long version(){return version;}public List<Attempt> attempts(){return attempts;}
    public State state(){
        if(attempts.isEmpty())return State.QUEUED;
        return switch(last().outcome()){
            case RUNNING,RESULT_UNKNOWN->State.RUNNING;
            case SUCCEEDED->State.SUCCEEDED;
            case FAILED->attempts.size()<MAX_ATTEMPTS?State.RETRY_WAIT:State.FAILED;
        };
    }
    public Instant nextAttemptAt(){return switch(state()){case QUEUED->queuedAt;case RETRY_WAIT->plusDelay(last().finishedAt());default->null;};}
    public Instant lastAttemptAt(){return attempts.isEmpty()?null:last().startedAt();}
    public Instant lastSuccessAt(){return state()==State.SUCCEEDED?last().finishedAt():null;}
    public Instant finishedAt(){return state()==State.SUCCEEDED||state()==State.FAILED?last().finishedAt():null;}
    public long knownFailedAttemptCount(){return attempts.stream().filter(a->a.outcome()==Outcome.FAILED).count();}
    /** Null until a final run outcome exists. Not a cross-run consecutive-failure counter. */
    public Integer finalRunFailureCount(){return switch(state()){case FAILED->1;case SUCCEEDED->0;default->null;};}
    public String failureCountSemantics(){return null;} // Q22 remains unconfirmed.
    public String automaticTicketStatus(){return "CONFIG_PENDING";}
    public boolean automaticTicketAllowed(){return false;}
    public boolean reconciliationRequired(){return !attempts.isEmpty()&&last().outcome()==Outcome.RESULT_UNKNOWN;}
    public BackupRunState start(long expectedVersion,Instant now){
        expected(expectedVersion);instant(now);if((state()!=State.QUEUED&&state()!=State.RETRY_WAIT)||now.isBefore(nextAttemptAt()))throw conflict();
        var updated=new ArrayList<>(attempts);updated.add(new Attempt(updated.size()+1,now,now,null,Outcome.RUNNING,null,null));return copy(updated);
    }
    /** Unknown network/result state is not a known failure and cannot schedule a fresh attempt. */
    public BackupRunState markUnknown(long expectedVersion,Instant observedAt){
        active(expectedVersion,observedAt);return replace(new Attempt(last().number(),last().startedAt(),observedAt,null,Outcome.RESULT_UNKNOWN,null,null));
    }
    /** Caller must first verify durable encrypted object/metadata; a reference alone does not verify storage. */
    public BackupRunState succeed(long expectedVersion,Instant now,StoredVersion storedVersion){
        active(expectedVersion,now);if(storedVersion==null)throw invalid();
        if(!tenantId.equals(storedVersion.tenantId())||!assetId.equals(storedVersion.assetId()))throw new DomainException(404,"NOT_FOUND","备份版本不存在");
        return replace(new Attempt(last().number(),last().startedAt(),now,now,Outcome.SUCCEEDED,null,storedVersion));
    }
    /** Explicitly reconciled failure only. Never call this solely because an HTTP response was lost. */
    public BackupRunState fail(long expectedVersion,Instant now,Failure failure){
        active(expectedVersion,now);if(failure==null)throw invalid();if(attempts.size()<MAX_ATTEMPTS)plusDelay(now);
        return replace(new Attempt(last().number(),last().startedAt(),now,now,Outcome.FAILED,failure,null));
    }
    private Attempt last(){return attempts.get(attempts.size()-1);}
    private BackupRunState replace(Attempt value){var updated=new ArrayList<>(attempts);updated.set(updated.size()-1,value);return copy(updated);}
    private BackupRunState copy(List<Attempt> updated){if(version==MAX_VERSION)throw conflict();return new BackupRunState(tenantId,assetId,queuedAt,version+1,updated);}
    private void expected(long value){if(value!=version)throw new DomainException(409,"VERSION_CONFLICT","备份状态版本已变化");}
    private void active(long expectedVersion,Instant now){expected(expectedVersion);instant(now);if(state()!=State.RUNNING||now.isBefore(last().observedAt()))throw conflict();}
    private static Instant plusDelay(Instant value){try{Instant result=value.plus(RETRY_DELAY);instant(result);return result;}catch(DateTimeException|ArithmeticException failure){throw invalid();}}
    private static void instant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException conflict(){return new DomainException(409,"BACKUP_STATE_CONFLICT","备份状态、时间或结果尚不允许此转换");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","备份事实或已持久化版本引用无效");}
}
