package cn.mop.domain.backup;

import cn.mop.domain.DomainException;
import java.time.*;
import java.util.*;

/** Coordination facts only, never device authority or proof of a distributed lock.
 * Expiry cannot prove an SSH process stopped. No expired-lease takeover or automatic replay exists here. */
public final class BackupDeviceLeaseState {
 public static final long MAX_SAFE=9_007_199_254_740_991L;
 public static final Duration MAX_LEASE=Duration.ofMinutes(5); // Technical bound, not a default or approved runtime value.
 public enum Status { HELD,RECONCILIATION_REQUIRED,RELEASED }
 public record Snapshot(UUID tenantId,UUID assetId,UUID requestId,String owner,long fencingToken,long version,Duration duration,Instant acquiredAt,Instant observedAt,Instant leaseUntil,Instant releasedAt){}
 /** Structural rehydration only; immutable database transition history must be verified independently. */
 public static BackupDeviceLeaseState rehydrate(Snapshot s){
  if(s==null)throw invalid();identities(s.tenantId(),s.assetId(),s.requestId(),s.owner());lease(s.duration());instant(s.acquiredAt());instant(s.observedAt());instant(s.leaseUntil());
  if(s.fencingToken()<1||s.fencingToken()>MAX_SAFE||s.version()<1||s.version()>MAX_SAFE||s.observedAt().isBefore(s.acquiredAt())||!s.observedAt().isBefore(s.leaseUntil()))throw invalid();
  if(s.releasedAt()==null){if(s.fencingToken()>(s.version()+1)/2||!until(s.observedAt(),s.duration()).equals(s.leaseUntil()))throw invalid();}
  else{instant(s.releasedAt());if(!s.releasedAt().equals(s.observedAt())||s.fencingToken()>s.version()/2)throw invalid();Instant lastRenewed=s.leaseUntil().minus(s.duration());if(lastRenewed.isBefore(s.acquiredAt())||lastRenewed.isAfter(s.releasedAt()))throw invalid();}
  return new BackupDeviceLeaseState(s.tenantId(),s.assetId(),s.requestId(),s.owner(),s.fencingToken(),s.version(),s.duration(),s.acquiredAt(),s.observedAt(),s.leaseUntil(),s.releasedAt());
 }
 public Snapshot snapshot(){return new Snapshot(tenantId,assetId,requestId,owner,fencingToken,version,duration,acquiredAt,observedAt,leaseUntil,releasedAt);}
 private final UUID tenantId,assetId,requestId;private final String owner;
 private final long fencingToken,version;private final Duration duration;
 private final Instant acquiredAt,observedAt,leaseUntil,releasedAt;
 private BackupDeviceLeaseState(UUID tenant,UUID asset,UUID request,String owner,long token,long version,Duration duration,Instant acquired,Instant observed,Instant until,Instant released){tenantId=tenant;assetId=asset;requestId=request;this.owner=owner;fencingToken=token;this.version=version;this.duration=duration;acquiredAt=acquired;observedAt=observed;leaseUntil=until;releasedAt=released;}
 /** Caller must independently verify target scope, active authority, durable uniqueness and a trusted clock. */
 public static BackupDeviceLeaseState initial(UUID tenant,UUID asset,UUID request,String owner,Instant at,Duration duration){
  identities(tenant,asset,request,owner);instant(at);lease(duration);
  return new BackupDeviceLeaseState(tenant,asset,request,owner,1,1,duration,at,at,until(at,duration),null);
 }
 /** Only a known RELEASED lease can be followed by another request. An expired HELD lease stays blocked. */
 public BackupDeviceLeaseState next(long expectedVersion,UUID request,String nextOwner,Instant at,Duration duration){
  expected(expectedVersion);identities(tenantId,assetId,request,nextOwner);instant(at);lease(duration);
  if(releasedAt==null||at.isBefore(observedAt)||requestId.equals(request)||fencingToken==MAX_SAFE)throw conflict();
  return new BackupDeviceLeaseState(tenantId,assetId,request,nextOwner,fencingToken+1,increment(),duration,at,at,until(at,duration),null);
 }
 public BackupDeviceLeaseState renew(long expectedVersion,String worker,long token,Instant at){
  active(expectedVersion,worker,token,at);Instant nextUntil=until(at,duration);
  if(at.equals(observedAt))return this;
  return new BackupDeviceLeaseState(tenantId,assetId,requestId,owner,fencingToken,increment(),duration,acquiredAt,at,nextUntil,null);
 }
 /** Call only after independently confirming that this request cannot still execute on the device.
  * A timeout or lease expiry is NOT that confirmation. Expired leases require a separate, future reconciliation path. */
 public BackupDeviceLeaseState release(long expectedVersion,String worker,long token,Instant at){
  active(expectedVersion,worker,token,at);
  return new BackupDeviceLeaseState(tenantId,assetId,requestId,owner,fencingToken,increment(),duration,acquiredAt,at,leaseUntil,at);
 }
 public Status statusAt(Instant at){instant(at);if(at.isBefore(observedAt))throw invalid();return releasedAt!=null?Status.RELEASED:at.isBefore(leaseUntil)?Status.HELD:Status.RECONCILIATION_REQUIRED;}
 public boolean mayCoordinateAt(String worker,long token,Instant at){instant(at);if(at.isBefore(observedAt))throw invalid();return releasedAt==null&&owner.equals(worker)&&token==fencingToken&&at.isBefore(leaseUntil);}
 public UUID tenantId(){return tenantId;}public UUID assetId(){return assetId;}public UUID requestId(){return requestId;}public String owner(){return owner;}
 public long fencingToken(){return fencingToken;}public long version(){return version;}public Duration duration(){return duration;}
 public Instant acquiredAt(){return acquiredAt;}public Instant observedAt(){return observedAt;}public Instant leaseUntil(){return leaseUntil;}public Instant releasedAt(){return releasedAt;}
 public boolean deviceExecutionAuthorized(){return false;}public boolean distributedLockVerified(){return false;}public boolean expiredTakeoverAllowed(){return false;}
 private void active(long expectedVersion,String worker,long token,Instant at){expected(expectedVersion);instant(at);if(!owner.equals(worker)||token!=fencingToken||releasedAt!=null||at.isBefore(observedAt)||!at.isBefore(leaseUntil))throw conflict();}
 private void expected(long expected){if(expected!=version)throw new DomainException(409,"VERSION_CONFLICT","设备协调状态版本已变化");}
 private long increment(){if(version==MAX_SAFE)throw conflict();return version+1;}
 private static void identities(UUID tenant,UUID asset,UUID request,String owner){if(tenant==null||asset==null||request==null||owner==null||!owner.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();}
 private static void lease(Duration duration){if(duration==null||duration.isNegative()||duration.isZero()||duration.compareTo(MAX_LEASE)>0||duration.getNano()%1_000_000!=0)throw invalid();}
 private static Instant until(Instant at,Duration duration){try{Instant value=at.plus(duration);instant(value);return value;}catch(DateTimeException|ArithmeticException e){throw invalid();}}
 private static void instant(Instant value){if(value==null||value.getNano()%1_000_000!=0||value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||value.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();}
 private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","设备协调标识、可信观察时间或显式租期无效");}
 private static DomainException conflict(){return new DomainException(409,"BACKUP_DEVICE_LEASE_CONFLICT","协调令牌、状态或时间已失效；租约过期不证明设备执行已停止，不能自动重领");}
}
