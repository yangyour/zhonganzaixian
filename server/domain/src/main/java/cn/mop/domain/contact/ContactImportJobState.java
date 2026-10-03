package cn.mop.domain.contact;

import cn.mop.domain.DomainException;
import java.time.*;
import java.util.Optional;

/** Pure transition checks for a future durable create-only import job. Does not claim DB locking or authorization. */
public record ContactImportJobState(State state,int rowCount,long attempts,long fencingToken,
                                    String leaseOwner,Instant leaseStartedAt,Instant leaseUntil,Instant nextAt,
                                    Integer completedRows,Failure error) {
    public enum State { PENDING, LEASED, SUCCEEDED, FAILED }
    public enum Failure { UPSTREAM_UNAVAILABLE, AUTHORIZATION_REVOKED, PAYLOAD_INVALID, LAST_ENABLED_DECISION_MAKER }
    public static final long MAX_COUNTER=9007199254740991L;
    public ContactImportJobState {
        if(state==null||rowCount<1||rowCount>CustomerContactImportPreview.MAX_ROWS||attempts<0||attempts>MAX_COUNTER||fencingToken!=attempts)throw invalid();
        if(leaseStartedAt!=null)instant(leaseStartedAt);if(leaseUntil!=null)instant(leaseUntil);if(nextAt!=null)instant(nextAt);
        boolean valid=switch(state){
            case PENDING -> leaseOwner==null&&leaseStartedAt==null&&leaseUntil==null&&nextAt!=null&&completedRows==null&&(attempts==0?error==null:error==Failure.UPSTREAM_UNAVAILABLE);
            case LEASED -> owner(leaseOwner)&&leaseStartedAt!=null&&leaseUntil!=null&&leaseUntil.isAfter(leaseStartedAt)&&nextAt==null&&completedRows==null&&error==null&&attempts>0;
            case SUCCEEDED -> leaseOwner==null&&leaseStartedAt==null&&leaseUntil==null&&nextAt==null&&completedRows!=null&&completedRows==rowCount&&error==null&&attempts>0;
            case FAILED -> leaseOwner==null&&leaseStartedAt==null&&leaseUntil==null&&nextAt==null&&completedRows==null&&error!=null&&error!=Failure.UPSTREAM_UNAVAILABLE&&attempts>0;
        };if(!valid)throw invalid();
    }
    public static ContactImportJobState queued(int rowCount,Instant nextAt){return new ContactImportJobState(State.PENDING,rowCount,0,0,null,null,null,nextAt,null,null);}
    public String step(){return switch(state){case PENDING->attempts==0?"QUEUED":"RETRY_WAIT";case LEASED->"APPLYING";case SUCCEEDED->"APPLIED";case FAILED->error.name();};}
    public boolean terminal(){return state==State.SUCCEEDED||state==State.FAILED;}
    /** Caller must atomically compare-and-swap the same durable row and use a trusted database clock. */
    public Optional<ContactImportJobState> claim(String worker,Instant now,Duration duration){
        instant(now);if(!owner(worker)||duration==null||duration.isNegative()||duration.isZero()||duration.getNano()%1_000_000!=0)throw invalid();
        long millis;try{millis=duration.toMillis();}catch(ArithmeticException failure){throw invalid();}if(millis<1||millis>3600000)throw invalid();
        if(terminal()||state==State.PENDING&&nextAt.isAfter(now)||state==State.LEASED&&leaseUntil.isAfter(now))return Optional.empty();
        if(attempts==MAX_COUNTER)throw new DomainException(409,"IMPORT_LEASE_COUNTER_EXHAUSTED","导入领取计数已达技术边界，未重新执行");
        Instant until;try{until=now.plusMillis(millis);}catch(DateTimeException|ArithmeticException failure){throw invalid();}instant(until);
        return Optional.of(new ContactImportJobState(State.LEASED,rowCount,attempts+1,fencingToken+1,worker,now,until,null,null,null));
    }
    public ContactImportJobState succeed(String worker,long fence,Instant now,int createdRows){owned(worker,fence,now);if(createdRows!=rowCount)throw invalid();return new ContactImportJobState(State.SUCCEEDED,rowCount,attempts,fencingToken,null,null,null,null,rowCount,null);}
    public ContactImportJobState retry(String worker,long fence,Instant now,Instant retryAt){owned(worker,fence,now);instant(retryAt);if(!retryAt.isAfter(now))throw invalid();return new ContactImportJobState(State.PENDING,rowCount,attempts,fencingToken,null,null,null,retryAt,null,Failure.UPSTREAM_UNAVAILABLE);}
    public ContactImportJobState fail(String worker,long fence,Instant now,Failure failure){owned(worker,fence,now);if(failure==null||failure==Failure.UPSTREAM_UNAVAILABLE)throw invalid();return new ContactImportJobState(State.FAILED,rowCount,attempts,fencingToken,null,null,null,null,null,failure);}
    private void owned(String worker,long fence,Instant now){instant(now);if(state!=State.LEASED||!owner(worker)||!worker.equals(leaseOwner)||fence!=fencingToken||now.isBefore(leaseStartedAt)||!now.isBefore(leaseUntil))throw new DomainException(409,"STALE_IMPORT_LEASE","导入租约已过期或被替代，旧执行者不能提交结果");}
    private static boolean owner(String value){return value!=null&&value.matches("[A-Za-z0-9._:-]{1,64}");}
    private static void instant(Instant value){if(value==null||value.getNano()%1000000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","导入作业状态、租约边界或完成行数无效；不推断成功或部分入库");}
}
