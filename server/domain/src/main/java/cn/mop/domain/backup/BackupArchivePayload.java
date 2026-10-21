package cn.mop.domain.backup;

import cn.mop.domain.DomainException;
import java.io.*;
import java.security.*;
import java.util.*;

/** Canonical encrypted-envelope structure only. Never authenticates ciphertext or grants storage/crypto access. */
public final class BackupArchivePayload {
 public static final String FORMAT="MOP_BACKUP_AES256_GCM_V1",ALGORITHM="AES-256-GCM";
 public static final int MAX_PLAINTEXT_BYTES=16*1024*1024,MAX_WRAPPED_KEY_BYTES=8192,NONCE_BYTES=12,TAG_BYTES=16,MAX_ENVELOPE_BYTES=MAX_PLAINTEXT_BYTES+MAX_WRAPPED_KEY_BYTES+2048;
 public record Scope(UUID tenantId,UUID assetId,UUID runId,int attemptNumber,UUID versionId){public Scope{if(tenantId==null||assetId==null||runId==null||versionId==null||attemptNumber<1||attemptNumber>BackupRunState.MAX_ATTEMPTS)throw invalid();}}
 private final Scope scope;private final String keyReference,sha256;private final byte[] encoded;
 private BackupArchivePayload(Scope scope,String keyReference,byte[] encoded,String hash){this.scope=scope;this.keyReference=keyReference;this.encoded=encoded.clone();this.sha256=hash;}
 public Scope scope(){return scope;}public String keyReference(){return keyReference;}public String sha256(){return sha256;}public int size(){return encoded.length;}public byte[] bytes(){return encoded.clone();}
 public boolean ciphertextAuthenticationVerified(){return false;}public boolean deviceExecutionAuthorized(){return false;}
 @Override public String toString(){return "BackupArchivePayload[encoded content omitted; AEAD verification still required]";}
 public static BackupArchivePayload parse(byte[] supplied,String expectedSha256){
  if(supplied==null||supplied.length>MAX_ENVELOPE_BYTES||expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}"))throw invalid();byte[] bytes=supplied.clone();if(!hash(bytes).equals(expectedSha256))throw invalid();
  try(var in=new DataInputStream(new ByteArrayInputStream(bytes));var canonical=new ByteArrayOutputStream();var out=new DataOutputStream(canonical)){
   String format=in.readUTF(),algorithm=in.readUTF();if(!FORMAT.equals(format)||!ALGORITHM.equals(algorithm))throw invalid();out.writeUTF(format);out.writeUTF(algorithm);
   UUID tenant=uuid(in,out),asset=uuid(in,out),run=uuid(in,out);int attempt=in.readInt();out.writeInt(attempt);UUID version=uuid(in,out);Scope scope=new Scope(tenant,asset,run,attempt,version);
   String ref=in.readUTF();if(!ref.matches("kms-key-ref:[A-Za-z0-9._-]{1,128}"))throw invalid();out.writeUTF(ref);
   int count=in.readInt();if(count<1||count>MAX_WRAPPED_KEY_BYTES)throw invalid();byte[] wrapped=in.readNBytes(count);if(wrapped.length!=count)throw invalid();out.writeInt(count);out.write(wrapped);
   byte[] nonce=in.readNBytes(NONCE_BYTES);if(nonce.length!=NONCE_BYTES)throw invalid();out.write(nonce);
   count=in.readInt();if(count<TAG_BYTES||count>MAX_PLAINTEXT_BYTES+TAG_BYTES)throw invalid();byte[] encrypted=in.readNBytes(count);if(encrypted.length!=count||in.read()!=-1)throw invalid();out.writeInt(count);out.write(encrypted);out.flush();if(!Arrays.equals(bytes,canonical.toByteArray()))throw invalid();
   return new BackupArchivePayload(scope,ref,bytes,expectedSha256);
  }catch(Exception rejected){throw invalid();}
 }
 private static UUID uuid(DataInputStream in,DataOutputStream out)throws IOException{String raw=in.readUTF();UUID value=UUID.fromString(raw);out.writeUTF(value.toString());return value;}
 private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}}
 private static DomainException invalid(){return new DomainException(400,"INVALID_BACKUP_ARCHIVE_PAYLOAD","加密信封结构、范围或摘要无效；未核验密文真实性或授予执行权限");}
}
