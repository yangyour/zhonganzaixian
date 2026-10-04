package cn.mop.domain.assetexport;

import cn.mop.domain.AssetReadQuery;
import cn.mop.domain.DomainException;
import java.security.*;
import java.time.*;
import java.util.*;

/** Explicit-input transition model only. No durable leases, storage probes, role grants, tokens or notifications. */
public final class AssetExportJobState {
    public static final int DOWNLOAD_SECONDS=900, MAX_ATTEMPTS=1000, MAX_DATA_ROWS=1000, MAX_FILE_BYTES=16*1024*1024;
    private static final long MAX_VERSION=9007199254740991L;
    public enum State { QUEUED, RUNNING, RECONCILING_STORAGE, SUCCEEDED, FAILED }
    public enum Failure { AUTHORIZATION_CHANGED, SOURCE_UNAVAILABLE, INPUT_LIMIT, ENCODING_FAILED, CONFIRMED_STORAGE_REJECTION }
    public record Access(UUID tenantId,UUID actorId,long permissionVersion,String role,boolean active) {}
    public record Filters(String type,String status,String siteId,String query) {
        public Filters {new AssetReadQuery(type,status,siteId,query,1,null,null);}
    }
    public record Command(Access acceptedAccess,Filters filters) {
        public Command {if(!valid(acceptedAccess)||filters==null)throw invalid();}
        /** Returns a fresh canonical byte array; the digest is consistency metadata, not authentication. */
        public byte[] canonicalBytes(){return AssetExportCommandCodec.encode(this);}
        public String fingerprint(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    }

    public record Attempt(long fence,Instant startedAt,Instant leaseUntil) {
        public Attempt {time(startedAt);time(leaseUntil);if(fence<1||fence>MAX_VERSION||!startedAt.isBefore(leaseUntil))throw invalid();}
    }
    public record Encoding(String selectionSha256,String fileSha256,long sizeBytes,int rowCount,Instant generatedAt) {
        public Encoding {if(!hash(selectionSha256)||!hash(fileSha256)||sizeBytes<1||sizeBytes>MAX_FILE_BYTES||rowCount<0||rowCount>MAX_DATA_ROWS)throw invalid();time(generatedAt);}
    }
    /** Receipt identity and hash binding, not proof that an OSS request actually completed. */
    public record StoredArtifact(UUID tenantId,UUID jobId,UUID objectId,String fileSha256,long sizeBytes,Instant storedAt) {
        public StoredArtifact {if(tenantId==null||jobId==null||objectId==null||!hash(fileSha256)||sizeBytes<1||sizeBytes>MAX_FILE_BYTES)throw invalid();time(storedAt);}
    }
    public record DownloadWindow(Instant issuedAt,Instant expiresAt) {
        public DownloadWindow {time(issuedAt);time(expiresAt);if(!Duration.between(issuedAt,expiresAt).equals(Duration.ofSeconds(DOWNLOAD_SECONDS)))throw invalid();}
        public boolean actualGrantIssued(){return false;}
        public String remainingGate(){return "CURRENT_OBJECT_AUTHORIZATION_STORAGE_AND_AUDIT_REQUIRED";}
    }
    private final UUID jobId;private final Command command;private final Instant acceptedAt,observedAt;private final long version;
    private final State state;private final List<Attempt> attempts;private final Encoding encoding;private final StoredArtifact artifact;private final Failure failure;
    private AssetExportJobState(UUID jobId,Command command,Instant acceptedAt,Instant observedAt,long version,State state,List<Attempt> attempts,Encoding encoding,StoredArtifact artifact,Failure failure){this.jobId=jobId;this.command=command;this.acceptedAt=acceptedAt;this.observedAt=observedAt;this.version=version;this.state=state;this.attempts=List.copyOf(attempts);this.encoding=encoding;this.artifact=artifact;this.failure=failure;}
    public static AssetExportJobState queued(UUID jobId,Command command,Instant acceptedAt){if(jobId==null||command==null)throw invalid();time(acceptedAt);return new AssetExportJobState(jobId,command,acceptedAt,acceptedAt,0,State.QUEUED,List.of(),null,null,null);}
    /** Reclaim only after the explicit old lease expires, preserving any prepared bytes and ambiguous storage state. */
    public AssetExportJobState claim(long expectedVersion,Access current,Instant now,Duration lease){
        authorize(current);change(expectedVersion,now);
        if(state!=State.QUEUED&&state!=State.RUNNING&&state!=State.RECONCILING_STORAGE||attempts.size()>=MAX_ATTEMPTS)throw conflict();
        if(!attempts.isEmpty()&&now.isBefore(attempts.get(attempts.size()-1).leaseUntil()))throw conflict();
        validateLease(lease);
        Instant until=plus(now,lease);var next=new ArrayList<>(attempts);next.add(new Attempt(next.size()+1L,now,until));
        return copy(now,encoding!=null?State.RECONCILING_STORAGE:State.RUNNING,next,encoding,null,null);
    }
    public AssetExportJobState prepared(long expectedVersion,long fence,Access current,Instant now,Encoding prepared){
        authorize(current);active(expectedVersion,fence,now);
        if(state!=State.RUNNING||encoding!=null||prepared==null||prepared.generatedAt().isBefore(acceptedAt)||prepared.generatedAt().isAfter(now))throw conflict();
        return copy(now,state,attempts,prepared,null,null);
    }
    public AssetExportJobState storageUnknown(long expectedVersion,long fence,Access current,Instant now){
        authorize(current);active(expectedVersion,fence,now);if(encoding==null)throw conflict();
        return copy(now,State.RECONCILING_STORAGE,attempts,encoding,null,null);
    }
    /** Only a trusted actual store probe may supply confirmed absence; 403/timeouts are not absence. */
    public AssetExportJobState confirmedStorageAbsent(long expectedVersion,long fence,Access current,Instant now){
        authorize(current);active(expectedVersion,fence,now);if(state!=State.RECONCILING_STORAGE||encoding==null)throw conflict();
        return copy(now,State.RUNNING,attempts,encoding,null,null);
    }
    /** Future worker-only denial path for queued or expired jobs, without manufacturing requester access. */
    public AssetExportJobState authorizationDenied(long expectedVersion,Access current,Instant now){
        change(expectedVersion,now);if(state!=State.QUEUED&&state!=State.RUNNING&&state!=State.RECONCILING_STORAGE)throw conflict();
        Access accepted=command.acceptedAccess();
        if(current!=null){
            if(!Objects.equals(current.tenantId(),accepted.tenantId())||!Objects.equals(current.actorId(),accepted.actorId()))throw new DomainException(404,"OBJECT_NOT_FOUND","Export not found");
            if(valid(current)&&current.permissionVersion()==accepted.permissionVersion()&&Objects.equals(current.role(),accepted.role()))throw conflict();
        }
        if(!attempts.isEmpty()&&now.isBefore(attempts.get(attempts.size()-1).leaseUntil()))throw conflict();
        return copy(now,State.FAILED,attempts,encoding,null,Failure.AUTHORIZATION_CHANGED);
    }
    public AssetExportJobState stored(long expectedVersion,long fence,Access current,Instant now,StoredArtifact receipt){
        authorize(current);active(expectedVersion,fence,now);if(encoding==null||receipt==null)throw conflict();
        if(!receipt.tenantId().equals(command.acceptedAccess().tenantId())||!receipt.jobId().equals(jobId))throw new DomainException(404,"OBJECT_NOT_FOUND","Object not found in export scope");
        if(!receipt.fileSha256().equals(encoding.fileSha256())||receipt.sizeBytes()!=encoding.sizeBytes()||receipt.storedAt().isBefore(encoding.generatedAt())||receipt.storedAt().isAfter(now))throw conflict();
        return copy(now,State.SUCCEEDED,attempts,encoding,receipt,null);
    }
    /** A trusted future worker supplies a definite failure; no automatic retry policy is selected here. */
    public AssetExportJobState failed(long expectedVersion,long fence,Instant now,Failure reason){
        active(expectedVersion,fence,now);if(reason==null||state==State.RECONCILING_STORAGE&&reason!=Failure.AUTHORIZATION_CHANGED&&reason!=Failure.CONFIRMED_STORAGE_REJECTION)throw conflict();
        return copy(now,State.FAILED,attempts,encoding,null,reason);
    }
    /** Computes only the contract window; never creates a signed URL or bypasses download reauthorization. */
    public DownloadWindow downloadWindow(Access current,Instant now){
        authorize(current);time(now);if(state!=State.SUCCEEDED||now.isBefore(observedAt))throw conflict();
        return new DownloadWindow(now,plus(now,Duration.ofSeconds(DOWNLOAD_SECONDS)));
    }
    public void authorize(Access current){
        Access accepted=command.acceptedAccess();
        if(current==null||!Objects.equals(current.tenantId(),accepted.tenantId())||!Objects.equals(current.actorId(),accepted.actorId()))throw new DomainException(404,"OBJECT_NOT_FOUND","Export not found");
        if(!valid(current)||current.permissionVersion()!=accepted.permissionVersion()||!Objects.equals(current.role(),accepted.role()))throw new DomainException(401,"SESSION_EXPIRED","Current export authorization changed");
    }
    private void active(long expectedVersion,long fence,Instant now){change(expectedVersion,now);if(state!=State.RUNNING&&state!=State.RECONCILING_STORAGE||attempts.isEmpty())throw conflict();var current=attempts.get(attempts.size()-1);if(current.fence()!=fence||!now.isBefore(current.leaseUntil()))throw new DomainException(409,"LEASE_LOST","Export lease/fence no longer current");}
    private void change(long expectedVersion,Instant now){if(version!=expectedVersion||version>=MAX_VERSION)throw new DomainException(409,"VERSION_CONFLICT","Export version changed");time(now);if(now.isBefore(observedAt))throw invalid();}
    private AssetExportJobState copy(Instant now,State next,List<Attempt> attempts,Encoding encoding,StoredArtifact artifact,Failure failure){return new AssetExportJobState(jobId,command,acceptedAt,now,version+1,next,attempts,encoding,artifact,failure);}
    public UUID jobId(){return jobId;}public Command command(){return command;}public Instant acceptedAt(){return acceptedAt;}public long version(){return version;}public State state(){return state;}public List<Attempt> attempts(){return attempts;}public Encoding encoding(){return encoding;}public StoredArtifact artifact(){return artifact;}public Failure failure(){return failure;}
    public boolean uploadAttemptAllowed(long fence,Access current,Instant now){authorize(current);active(version,fence,now);return state==State.RUNNING&&encoding!=null;}
    public boolean possibleOrphanNeedsReview(){return state==State.FAILED&&encoding!=null;}
    public boolean storageReconciliationRequired(){return state==State.RECONCILING_STORAGE;}
    public boolean persisted(){return false;}public boolean actualStoreVerified(){return false;}public boolean notificationSent(){return false;}
    public static void validateLease(Duration lease){if(lease==null||lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofMinutes(10))>0||lease.getNano()%1000000!=0)throw invalid();}
    private static boolean valid(Access a){return a!=null&&a.tenantId()!=null&&a.actorId()!=null&&a.active()&&a.permissionVersion()>0&&a.permissionVersion()<=MAX_VERSION&&Set.of("padmin","msp").contains(a.role()==null?"":a.role());}
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void time(Instant value){if(value==null||value.getNano()%1000000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static Instant plus(Instant value,Duration duration){try{Instant result=value.plus(duration);time(result);return result;}catch(DateTimeException|ArithmeticException error){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Invalid explicit export command, timestamp or bounded descriptor");}
    private static DomainException conflict(){return new DomainException(409,"EXPORT_STATE_CONFLICT","Export transition conflicts with retained facts");}
}
