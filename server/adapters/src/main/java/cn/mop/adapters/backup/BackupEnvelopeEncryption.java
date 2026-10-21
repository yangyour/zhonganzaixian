package cn.mop.adapters.backup;

import cn.mop.domain.DomainException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.*;
import java.util.*;

/** Bounded JCE envelope primitive only. No provider defaults, storage, SSH, download grant or production wiring. */
public final class BackupEnvelopeEncryption {
 public static final String FORMAT="MOP_BACKUP_AES256_GCM_V1",ALGORITHM="AES-256-GCM";
 public static final int MAX_PLAINTEXT_BYTES=16*1024*1024,MAX_WRAPPED_KEY_BYTES=8192,NONCE_BYTES=12,TAG_BYTES=16;
 private static final int MAX_ENVELOPE_BYTES=MAX_PLAINTEXT_BYTES+MAX_WRAPPED_KEY_BYTES+2048;
 public record Access(UUID tenantId,UUID actorId,long permissionVersion){public Access{if(tenantId==null||actorId==null||permissionVersion<1||permissionVersion>9007199254740991L)throw invalid();}}
 public record Scope(UUID tenantId,UUID assetId,UUID runId,int attemptNumber,UUID versionId){public Scope{if(tenantId==null||assetId==null||runId==null||versionId==null||attemptNumber<1||attemptNumber>3)throw invalid();}}
 public enum Operation{ENCRYPT,DECRYPT}
 /** Check current trusted authorization, not browser-supplied grants. Invoked before key access and before returning data. */
 @FunctionalInterface public interface Authorizer{boolean allowed(Access access,Scope scope,Operation operation)throws Exception;}
 /** Must use approved binding/context and a fresh 256-bit DEK per create; never cache plaintext keys or follow envelope URLs. */
 public interface DataKeyProvider{
  KeyMaterial create(Access access,Scope scope)throws Exception;
  KeyMaterial unwrap(Access access,Scope scope,String keyReference,byte[] wrappedKey)throws Exception;
 }
 /** Owns a copy of a short-lived key. Closing wipes that copy; provider/caller must also wipe their own copies. */
 public static final class KeyMaterial implements AutoCloseable{
  private final String keyReference;private final byte[] wrappedKey,key;private boolean destroyed;
  public KeyMaterial(String keyReference,byte[] wrappedKey,byte[] key){reference(keyReference);wrapped(wrappedKey);if(key==null||key.length!=32||MessageDigest.isEqual(wrappedKey,key))throw invalid();this.keyReference=keyReference;this.wrappedKey=wrappedKey.clone();this.key=key.clone();}
  private synchronized byte[] copyKey(){if(destroyed)throw invalid();return key.clone();}
  public String keyReference(){return keyReference;}public byte[] wrappedKey(){return wrappedKey.clone();}
  public synchronized boolean destroyed(){return destroyed;}
  @Override public synchronized void close(){Arrays.fill(key,(byte)0);destroyed=true;}
  @Override public String toString(){return "BackupKeyMaterial[REDACTED]";}
 }
 public record Envelope(Scope scope,String keyReference,byte[] wrappedKey,byte[] nonce,byte[] ciphertext){
  public Envelope{if(scope==null)throw invalid();reference(keyReference);wrapped(wrappedKey);if(nonce==null||nonce.length!=NONCE_BYTES||ciphertext==null||ciphertext.length<TAG_BYTES||ciphertext.length>MAX_PLAINTEXT_BYTES+TAG_BYTES)throw invalid();wrappedKey=wrappedKey.clone();nonce=nonce.clone();ciphertext=ciphertext.clone();}
  @Override public byte[] wrappedKey(){return wrappedKey.clone();}@Override public byte[] nonce(){return nonce.clone();}@Override public byte[] ciphertext(){return ciphertext.clone();}
  public String algorithm(){return ALGORITHM;}public String format(){return FORMAT;}
  public boolean storageVerified(){return false;}public boolean deviceExecutionAuthorized(){return false;}public boolean evidenceAccessGranted(){return false;}
  public byte[] encoded(){try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.write(header(scope,keyReference,wrappedKey));out.write(nonce);out.writeInt(ciphertext.length);out.write(ciphertext);out.flush();return bytes.toByteArray();}catch(IOException impossible){throw unavailable();}}
  public String sha256(){return hash(encoded());}
  @Override public String toString(){return "BackupEnvelope[AES-256-GCM; content omitted; storage not verified]";}
 }
 private static final class AccessRejected extends Exception{}
 private final DataKeyProvider keys;private final Authorizer authorizer;private final int plaintextLimit;private final SecureRandom random;
 public BackupEnvelopeEncryption(DataKeyProvider keys,Authorizer authorizer,int plaintextLimit){this(keys,authorizer,plaintextLimit,new SecureRandom());}
 // Package-private deterministic randomness is for isolated vectors only, never a runtime setting.
 BackupEnvelopeEncryption(DataKeyProvider keys,Authorizer authorizer,int plaintextLimit,SecureRandom random){this.keys=Objects.requireNonNull(keys);this.authorizer=Objects.requireNonNull(authorizer);this.random=Objects.requireNonNull(random);if(plaintextLimit<1||plaintextLimit>MAX_PLAINTEXT_BYTES)throw invalid();this.plaintextLimit=plaintextLimit;}
 public Envelope seal(Access access,Scope scope,byte[] plaintext){
  matching(access,scope);if(plaintext==null||plaintext.length>plaintextLimit)throw invalid();
  byte[] input=plaintext.clone(),key=null,encrypted=null;
  try{
   authorize(access,scope,Operation.ENCRYPT);Cipher cipher=cipher();
   try(KeyMaterial material=Objects.requireNonNull(keys.create(access,scope))){
    key=material.copyKey();byte[] nonce=new byte[NONCE_BYTES];random.nextBytes(nonce);byte[] wrapped=material.wrappedKey();
    cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(TAG_BYTES*8,nonce));cipher.updateAAD(header(scope,material.keyReference(),wrapped));encrypted=cipher.doFinal(input);
    authorize(access,scope,Operation.ENCRYPT);return new Envelope(scope,material.keyReference(),wrapped,nonce,encrypted);
   }
  }catch(AccessRejected denied){throw denied();}catch(Exception rejected){throw unavailable();}
  finally{wipe(input);wipe(key);wipe(encrypted);}
 }
 /** Returns bytes only after doFinal authenticates the entire ciphertext and a second authorization check succeeds. */
 public byte[] open(Access access,Scope expectedScope,Envelope envelope){
  matching(access,expectedScope);if(envelope==null||!expectedScope.equals(envelope.scope()))throw hidden();if(envelope.ciphertext.length-TAG_BYTES>plaintextLimit)throw invalid();
  byte[] key=null,plaintext=null;
  try{
   authorize(access,expectedScope,Operation.DECRYPT);Cipher cipher=cipher();
   try(KeyMaterial material=Objects.requireNonNull(keys.unwrap(access,expectedScope,envelope.keyReference(),envelope.wrappedKey()))){
    if(!material.keyReference().equals(envelope.keyReference())||!MessageDigest.isEqual(material.wrappedKey(),envelope.wrappedKey))throw unavailable();key=material.copyKey();
    cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(TAG_BYTES*8,envelope.nonce));cipher.updateAAD(header(expectedScope,envelope.keyReference,envelope.wrappedKey));plaintext=cipher.doFinal(envelope.ciphertext);
    authorize(access,expectedScope,Operation.DECRYPT);byte[] output=plaintext;plaintext=null;return output;
   }
  }catch(AccessRejected denied){throw denied();}catch(Exception rejected){throw unavailable();}
  finally{wipe(key);wipe(plaintext);}
 }
 /** Digest is transport integrity, not authentication. open must still verify AEAD and current authorization. */
 public Envelope decode(byte[] supplied,String expectedSha256,Scope expectedScope){
  if(supplied==null||supplied.length>MAX_ENVELOPE_BYTES||expectedScope==null||expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}"))throw unavailable();byte[] bytes=supplied.clone();if(!hash(bytes).equals(expectedSha256))throw unavailable();
  try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
   if(!FORMAT.equals(in.readUTF())||!ALGORITHM.equals(in.readUTF()))throw unavailable();Scope scope=new Scope(uuid(in),uuid(in),uuid(in),in.readInt(),uuid(in));if(!expectedScope.equals(scope))throw unavailable();String ref=in.readUTF();reference(ref);int count=in.readInt();if(count<1||count>MAX_WRAPPED_KEY_BYTES)throw unavailable();byte[] wrapped=in.readNBytes(count);if(wrapped.length!=count)throw unavailable();byte[] nonce=in.readNBytes(NONCE_BYTES);count=in.readInt();if(count<TAG_BYTES||count>plaintextLimit+TAG_BYTES)throw unavailable();byte[] encrypted=in.readNBytes(count);if(encrypted.length!=count||in.read()!=-1)throw unavailable();Envelope result=new Envelope(scope,ref,wrapped,nonce,encrypted);if(!Arrays.equals(bytes,result.encoded()))throw unavailable();return result;
  }catch(Exception rejected){throw unavailable();}
 }
 private void authorize(Access access,Scope scope,Operation operation)throws Exception{if(!authorizer.allowed(access,scope,operation))throw new AccessRejected();}
 private static Cipher cipher()throws GeneralSecurityException{if(Cipher.getMaxAllowedKeyLength("AES")<256)throw new GeneralSecurityException();return Cipher.getInstance("AES/GCM/NoPadding");}
 private static byte[] header(Scope scope,String reference,byte[] wrapped)throws IOException{
  try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){out.writeUTF(FORMAT);out.writeUTF(ALGORITHM);out.writeUTF(scope.tenantId().toString());out.writeUTF(scope.assetId().toString());out.writeUTF(scope.runId().toString());out.writeInt(scope.attemptNumber());out.writeUTF(scope.versionId().toString());out.writeUTF(reference);out.writeInt(wrapped.length);out.write(wrapped);out.flush();return bytes.toByteArray();}
 }
 private static UUID uuid(DataInputStream in)throws IOException{return UUID.fromString(in.readUTF());}
 private static void reference(String value){if(value==null||!value.matches("kms-key-ref:[A-Za-z0-9._-]{1,128}"))throw invalid();}
 private static void wrapped(byte[] value){if(value==null||value.length<1||value.length>MAX_WRAPPED_KEY_BYTES)throw invalid();}
 private static void matching(Access access,Scope scope){if(access==null||scope==null)throw invalid();if(!access.tenantId().equals(scope.tenantId()))throw hidden();}
 private static void wipe(byte[] value){if(value!=null)Arrays.fill(value,(byte)0);}
 private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw unavailable();}}
 private static DomainException invalid(){return new DomainException(400,"INVALID_BACKUP_ENCRYPTION_INPUT","备份加密范围、字节边界或密钥引用无效；未执行设备操作");}
 private static DomainException hidden(){return new DomainException(404,"BACKUP_VERSION_NOT_FOUND","当前范围内没有可读取的备份版本");}
 private static DomainException denied(){return new DomainException(403,"BACKUP_ENCRYPTION_ACCESS_DENIED","当前范围的备份加解密未获授权");}
 private static DomainException unavailable(){return new DomainException(503,"BACKUP_ENCRYPTION_UNAVAILABLE","备份加解密或完整性核查未完成；未确认对象持久化或恢复成功");}
}
