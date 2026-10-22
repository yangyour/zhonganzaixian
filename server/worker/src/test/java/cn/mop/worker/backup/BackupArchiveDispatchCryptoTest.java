package cn.mop.worker.backup;

import cn.mop.adapters.backup.BackupEnvelopeEncryption;
import cn.mop.adapters.backup.BackupEnvelopeArchive;
import cn.mop.domain.backup.BackupArchivePayload;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JCE and production adapter composition, synthetic in-memory storage/key provider only. */
class BackupArchiveDispatchCryptoTest {
 final UUID tenant=UUID.fromString("75000000-0000-4000-8000-000000000001"),actor=UUID.fromString("75000000-0000-4000-8000-000000000002");
 final BackupEnvelopeEncryption.Scope scope=new BackupEnvelopeEncryption.Scope(tenant,UUID.fromString("75000000-0000-4000-8000-000000000003"),UUID.fromString("75000000-0000-4000-8000-000000000004"),1,UUID.fromString("75000000-0000-4000-8000-000000000005"));
 final BackupEnvelopeEncryption.Access cryptoAccess=new BackupEnvelopeEncryption.Access(tenant,actor,7);
 final Access access=new Access(tenant,actor,7);
 final class Keys implements BackupEnvelopeEncryption.DataKeyProvider {
  int creates,unwraps;boolean allowCreate=true;final byte[] key=new byte[32];Keys(){Arrays.fill(key,(byte)42);}
  public BackupEnvelopeEncryption.KeyMaterial create(BackupEnvelopeEncryption.Access a,BackupEnvelopeEncryption.Scope s){creates++;if(!allowCreate)throw new AssertionError("Consumer must not re-encrypt");return material();}
  public BackupEnvelopeEncryption.KeyMaterial unwrap(BackupEnvelopeEncryption.Access a,BackupEnvelopeEncryption.Scope s,String ref,byte[] wrapped){unwraps++;assertEquals(scope,s);assertEquals(cryptoAccess,a);assertEquals("kms-key-ref:synthetic",ref);assertArrayEquals(new byte[]{7},wrapped);return material();}
  BackupEnvelopeEncryption.KeyMaterial material(){return new BackupEnvelopeEncryption.KeyMaterial("kms-key-ref:synthetic",new byte[]{7},key);}
 }
 final class Store implements BackupEnvelopeArchive.ObjectStore {
  byte[] bytes;int puts,reads;boolean failWrite,corruptRead;
  public boolean ready(){return true;}
  public void storeImmutable(BackupEnvelopeEncryption.Envelope envelope){puts++;assertNull(bytes);bytes=envelope.encoded();if(failWrite)throw new IllegalStateException("synthetic lost PUT response");}
  public byte[] read(BackupEnvelopeEncryption.Scope s,long expectedBytes,String expectedSha){reads++;assertEquals(scope,s);assertEquals(bytes.length,expectedBytes);byte[] result=bytes.clone();if(corruptRead)result[result.length-1]^=1;return result;}
 }
 @Test void productionWriterUsesExactPersistedEnvelopeWithoutCreatingAKeyOrReencrypting(){
  var keys=new Keys();var crypto=new BackupEnvelopeEncryption(keys,(a,s,o)->a.equals(cryptoAccess)&&s.equals(scope),1024);
  var envelope=crypto.seal(cryptoAccess,scope,"SYNTHETIC_CONFIGURATION".getBytes(java.nio.charset.StandardCharsets.UTF_8));keys.creates=0;keys.allowCreate=false;
  var payload=BackupArchivePayload.parse(envelope.encoded(),envelope.sha256());var store=new Store();var archive=new BackupEnvelopeArchive(crypto,store,(a,s,o)->a.equals(cryptoAccess)&&s.equals(scope));
  BackupArchiveDispatch.originalWriter(crypto,archive).store(access,payload);
  assertEquals(0,keys.creates);assertEquals(2,keys.unwraps);assertEquals(1,store.puts);assertEquals(1,store.reads);assertArrayEquals(payload.bytes(),store.bytes);
 }
 @Test void productionWriterDoesNotRetryAfterAmbiguousImmutablePut(){
  var keys=new Keys();var crypto=new BackupEnvelopeEncryption(keys,(a,s,o)->true,1024);var envelope=crypto.seal(cryptoAccess,scope,new byte[]{1,2});keys.creates=0;keys.allowCreate=false;
  var store=new Store();store.failWrite=true;var archive=new BackupEnvelopeArchive(crypto,store,(a,s,o)->true);
  var failure=assertThrows(cn.mop.domain.DomainException.class,()->BackupArchiveDispatch.originalWriter(crypto,archive).store(access,BackupArchivePayload.parse(envelope.encoded(),envelope.sha256())));
  assertEquals("BACKUP_ARCHIVE_WRITE_UNCONFIRMED",failure.code);assertEquals(1,store.puts);assertEquals(0,store.reads);assertEquals(0,keys.creates);
 }
 @Test void corruptObjectReadbackCannotProduceSuccessfulWriterReturn(){
  var keys=new Keys();var crypto=new BackupEnvelopeEncryption(keys,(a,s,o)->true,1024);var envelope=crypto.seal(cryptoAccess,scope,new byte[]{1,2});keys.allowCreate=false;
  var store=new Store();store.corruptRead=true;var archive=new BackupEnvelopeArchive(crypto,store,(a,s,o)->true);
  assertThrows(cn.mop.domain.DomainException.class,()->BackupArchiveDispatch.originalWriter(crypto,archive).store(access,BackupArchivePayload.parse(envelope.encoded(),envelope.sha256())));assertEquals(1,store.puts);assertEquals(1,store.reads);
 }
 @Test void revokedCryptoAccessPreventsStorageInvocation(){
  var keys=new Keys();var sealing=new BackupEnvelopeEncryption(keys,(a,s,o)->true,1024);var envelope=sealing.seal(cryptoAccess,scope,new byte[]{1});keys.allowCreate=false;
  var denied=new BackupEnvelopeEncryption(keys,(a,s,o)->false,1024);var store=new Store();var archive=new BackupEnvelopeArchive(denied,store,(a,s,o)->true);
  assertThrows(cn.mop.domain.DomainException.class,()->BackupArchiveDispatch.originalWriter(denied,archive).store(access,BackupArchivePayload.parse(envelope.encoded(),envelope.sha256())));assertEquals(0,store.puts);assertEquals(0,keys.unwraps);
 }
}
