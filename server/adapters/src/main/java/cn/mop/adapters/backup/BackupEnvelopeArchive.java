package cn.mop.adapters.backup;

import cn.mop.domain.DomainException;
import cn.mop.adapters.backup.BackupEnvelopeEncryption.*;
import java.util.*;

/** Object bytes/AEAD verification only. Never registers a version, completes a run, retries a PUT or decrypts for a download. */
public final class BackupEnvelopeArchive {
 public enum Operation{STORE,VERIFY_EXISTING}
 @FunctionalInterface public interface Authorizer{boolean allowed(Access access,Scope scope,Operation operation)throws Exception;}
 /** Trusted bound transport only. Implementations must prohibit overwrite and read exactly the scoped object, not a caller URL. */
 public interface ObjectStore{
  boolean ready();
  void storeImmutable(Envelope envelope);
  byte[] read(Scope scope,long expectedBytes,String expectedSha256);
 }
 public record Receipt(Scope scope,long encryptedObjectBytes,String encryptedObjectSha256){
  public boolean currentObjectBytesAndAeadVerified(){return true;}
  public boolean versionRegistered(){return false;}public boolean backupRunCompleted(){return false;}
  public boolean retentionVerified(){return false;}public boolean deviceExecutionAuthorized(){return false;}
 }
 private static final class Denied extends Exception{}
 private final BackupEnvelopeEncryption crypto;private final ObjectStore store;private final Authorizer authorizer;
 public BackupEnvelopeArchive(BackupEnvelopeEncryption crypto,ObjectStore store,Authorizer authorizer){this.crypto=Objects.requireNonNull(crypto);this.store=Objects.requireNonNull(store);this.authorizer=Objects.requireNonNull(authorizer);}
 public Receipt store(Access access,Scope scope,Envelope envelope){
  matching(access,scope);if(envelope==null||!scope.equals(envelope.scope()))throw invalid();boolean dispatched=false;
  try{
   authorize(access,scope,Operation.STORE);if(!store.ready())throw unavailable();
   authenticate(access,scope,envelope);byte[] encoded=envelope.encoded();long size=encoded.length;String hash=envelope.sha256();
   authorize(access,scope,Operation.STORE);dispatched=true;store.storeImmutable(envelope);
   Receipt receipt=readVerified(access,scope,size,hash);authorize(access,scope,Operation.STORE);return receipt;
  }catch(Denied rejected){throw dispatched?unknownWrite():denied();}
  catch(Exception rejected){if(dispatched)throw unknownWrite();if(rejected instanceof DomainException d&&d.status==403)throw denied();throw unavailable();}
 }
 /** Read-only reconciliation using the ORIGINAL scope, bytes and digest. A failed read never proves a preceding PUT did not commit. */
 public Receipt verifyExisting(Access access,Scope scope,long originalBytes,String originalSha256){
  matching(access,scope);expected(originalBytes,originalSha256);
  try{authorize(access,scope,Operation.VERIFY_EXISTING);Receipt receipt=readVerified(access,scope,originalBytes,originalSha256);authorize(access,scope,Operation.VERIFY_EXISTING);return receipt;}
  catch(Denied rejected){throw denied();}catch(Exception rejected){if(rejected instanceof DomainException d&&d.status==403)throw denied();throw unavailable();}
 }
 private Receipt readVerified(Access access,Scope scope,long bytes,String hash){
  expected(bytes,hash);if(!store.ready())throw unavailable();byte[] content=store.read(scope,bytes,hash);if(content==null||content.length!=bytes)throw unavailable();Envelope read=crypto.decode(content,hash,scope);authenticate(access,scope,read);return new Receipt(scope,bytes,hash);
 }
 private void authenticate(Access access,Scope scope,Envelope envelope){byte[] plaintext=null;try{plaintext=crypto.open(access,scope,envelope);}finally{if(plaintext!=null)Arrays.fill(plaintext,(byte)0);}}
 private void authorize(Access access,Scope scope,Operation operation)throws Exception{if(!authorizer.allowed(access,scope,operation))throw new Denied();}
 private static void matching(Access access,Scope scope){if(access==null||scope==null)throw invalid();if(!access.tenantId().equals(scope.tenantId()))throw new DomainException(404,"BACKUP_VERSION_NOT_FOUND","当前范围内没有备份版本");}
 private static void expected(long size,String hash){if(size<1||size>BackupEnvelopeEncryption.MAX_ENVELOPE_BYTES||hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid();}
 private static DomainException invalid(){return new DomainException(400,"INVALID_BACKUP_ARCHIVE_REFERENCE","备份信封范围或原对象引用无效");}
 private static DomainException denied(){return new DomainException(403,"BACKUP_ARCHIVE_ACCESS_DENIED","当前范围的备份对象核验未获授权");}
 private static DomainException unknownWrite(){return new DomainException(503,"BACKUP_ARCHIVE_WRITE_UNCONFIRMED","原对象写入结果尚未确认；保留原版本与信封摘要，只读核查，禁止重新加密后盲目覆盖");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_ARCHIVE_UNVERIFIED","备份对象或密钥完整性未核实；不能据此确认归档成功或原写入失败");}
}
