package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupRunState;
import cn.mop.domain.backup.BackupRunState.*;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Bounded canonical journal payload, not a database, signature, encryption or execution permission. */
public final class BackupRunSnapshotCodec {
 public static final String FORMAT="MOP_BACKUP_RUN_SNAPSHOT_V1";
 public static final int MAX_BYTES=4096;
 public record Document(byte[] bytes,String sha256){public Document{if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES||sha256==null)throw invalid();bytes=bytes.clone();if(!hash(bytes).equals(sha256))throw invalid();}@Override public byte[] bytes(){return bytes.clone();}}
 public Document encode(UUID runId,BackupRunState state){
  if(runId==null||state==null)throw invalid();var snapshot=BackupRunState.rehydrate(state.snapshot()).snapshot();
  try(var buffer=new ByteArrayOutputStream();var out=new DataOutputStream(buffer)){
   out.writeUTF(FORMAT);uuid(out,runId);uuid(out,snapshot.tenantId());uuid(out,snapshot.assetId());time(out,snapshot.queuedAt());out.writeLong(snapshot.version());out.writeInt(snapshot.attempts().size());
   for(var a:snapshot.attempts()){
    out.writeInt(a.number());time(out,a.startedAt());time(out,a.observedAt());optionalTime(out,a.finishedAt());out.writeUTF(a.outcome().name());out.writeBoolean(a.failure()!=null);if(a.failure()!=null)out.writeUTF(a.failure().name());out.writeBoolean(a.storedVersion()!=null);
    if(a.storedVersion()!=null){var v=a.storedVersion();uuid(out,v.tenantId());uuid(out,v.assetId());uuid(out,v.versionId());out.writeUTF(v.encryptedObjectSha256());out.writeLong(v.encryptedBytes());time(out,v.persistedAt());}
   }
   out.flush();byte[] bytes=buffer.toByteArray();if(bytes.length>MAX_BYTES)throw invalid();return new Document(bytes,hash(bytes));
  }catch(IOException impossible){throw invalid();}
 }
 public BackupRunState decode(byte[] supplied,String expectedSha256,UUID expectedRun,UUID expectedTenant,UUID expectedAsset){
  if(supplied==null||supplied.length==0||supplied.length>MAX_BYTES||expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}")||expectedRun==null||expectedTenant==null||expectedAsset==null)throw invalid();
  byte[] bytes=supplied.clone();if(!hash(bytes).equals(expectedSha256))throw invalid();
  try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
   if(!FORMAT.equals(in.readUTF()))throw invalid();UUID run=uuid(in),tenant=uuid(in),asset=uuid(in);if(!run.equals(expectedRun)||!tenant.equals(expectedTenant)||!asset.equals(expectedAsset))throw invalid();
   Instant queued=time(in);long version=in.readLong();int count=in.readInt();if(count<0||count>BackupRunState.MAX_ATTEMPTS)throw invalid();var attempts=new ArrayList<Attempt>();
   for(int i=0;i<count;i++){
    int number=in.readInt();Instant started=time(in),observed=time(in),finished=optionalTime(in);Outcome outcome=Outcome.valueOf(in.readUTF());Failure failure=in.readBoolean()?Failure.valueOf(in.readUTF()):null;StoredVersion stored=null;
    if(in.readBoolean())stored=new StoredVersion(uuid(in),uuid(in),uuid(in),in.readUTF(),in.readLong(),time(in));
    attempts.add(new Attempt(number,started,observed,finished,outcome,failure,stored));
   }
   if(in.read()!=-1)throw invalid();BackupRunState state=BackupRunState.rehydrate(new Snapshot(tenant,asset,queued,version,attempts));
   if(!Arrays.equals(bytes,encode(run,state).bytes()))throw invalid();return state;
  }catch(IOException|IllegalArgumentException|DateTimeException|DomainException rejected){throw invalid();}
 }
 private static void uuid(DataOutputStream out,UUID value)throws IOException{out.writeUTF(value.toString());}
 private static UUID uuid(DataInputStream in)throws IOException{return UUID.fromString(in.readUTF());}
 private static void time(DataOutputStream out,Instant value)throws IOException{out.writeLong(value.toEpochMilli());}
 private static Instant time(DataInputStream in)throws IOException{return Instant.ofEpochMilli(in.readLong());}
 private static void optionalTime(DataOutputStream out,Instant value)throws IOException{out.writeBoolean(value!=null);if(value!=null)time(out,value);}
 private static Instant optionalTime(DataInputStream in)throws IOException{return in.readBoolean()?time(in):null;}
 private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
 private static DomainException invalid(){return new DomainException(503,"BACKUP_RUN_SNAPSHOT_INVALID","备份运行快照的范围、摘要、格式或状态链未通过核验；未恢复执行权限");}
}
