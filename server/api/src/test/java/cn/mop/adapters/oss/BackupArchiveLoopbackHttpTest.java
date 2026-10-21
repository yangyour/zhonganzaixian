package cn.mop.adapters.oss;

import cn.mop.adapters.backup.*;
import cn.mop.adapters.backup.BackupEnvelopeEncryption.*;
import static cn.mop.adapters.backup.BackupArchiveFixture.*;
import cn.mop.domain.DomainException;
import com.aliyun.oss.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.time.*;import java.time.format.DateTimeFormatter;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK-to-loopback HTTP only. Synthetic protocol emulator, not cloud OSS/IAM/KMS or durability proof. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_BACKUP_ARCHIVE_HTTP_TESTS",matches="true")
class BackupArchiveLoopbackHttpTest {
 static final String BUCKET="mop-synthetic-backup-bucket",PREFIX="mop-niko-test/79000000-0000-4000-8000-000000000010/";
 static OssStorageSettings settings(){return new OssStorageSettings("https://oss-cn-beijing.aliyuncs.com","https://oss-cn-beijing.aliyuncs.com","cn-beijing",BUCKET,"SYNTHETIC_ID_NOT_VALID","SYNTHETIC_SECRET_NOT_VALID",null,PREFIX,60);}
 static String md5(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value));}
 static final class Emulator implements AutoCloseable {
  final HttpServer server;final ExecutorService executor=Executors.newFixedThreadPool(4);final Map<String,byte[]> objects=new ConcurrentHashMap<>();final AtomicInteger puts=new AtomicInteger(),gets=new AtomicInteger();final AtomicBoolean loseNextReply=new AtomicBoolean(),versioned=new AtomicBoolean(),publicObject=new AtomicBoolean(),versionAfterPut=new AtomicBoolean();
  Emulator()throws IOException{server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);server.setExecutor(executor);server.createContext("/",this::handle);server.start();}
  String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  String path(Scope s){return "/"+BUCKET+"/"+PREFIX+"backup-envelopes/"+s.tenantId()+"/"+s.versionId();}
  void xml(HttpExchange x,int status,String body)throws IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/xml");x.getResponseHeaders().set("x-oss-request-id","SYNTHETIC");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
  void error(HttpExchange x,int status,String code)throws IOException{xml(x,status,"<Error><Code>"+code+"</Code><Message>SYNTHETIC</Message><RequestId>SYNTHETIC</RequestId><HostId>loopback</HostId></Error>");}
  void handle(HttpExchange x)throws IOException{
   try{String query=x.getRequestURI().getRawQuery(),path=x.getRequestURI().getPath(),method=x.getRequestMethod();
    if("versioning".equals(query)){xml(x,200,versioned.get()?"<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>":"<VersioningConfiguration/>");return;}
    if("acl".equals(query)){boolean object=path.contains("/backup-envelopes/");if(object&&!objects.containsKey(path)){error(x,404,"NoSuchKey");return;}String grant=object&&publicObject.get()?"public-read":"private";xml(x,200,"<AccessControlPolicy><Owner><ID>synthetic</ID><DisplayName>synthetic</DisplayName></Owner><AccessControlList><Grant>"+grant+"</Grant></AccessControlList></AccessControlPolicy>");return;}
    if(method.equals("PUT")){
     puts.incrementAndGet();if(!"true".equals(x.getRequestHeaders().getFirst("x-oss-forbid-overwrite"))||!"private".equals(x.getRequestHeaders().getFirst("x-oss-object-acl"))){error(x,400,"InvalidArgument");return;}
     byte[] bytes=x.getRequestBody().readNBytes(BackupEnvelopeEncryption.MAX_ENVELOPE_BYTES+1);if(bytes.length>BackupEnvelopeEncryption.MAX_ENVELOPE_BYTES){error(x,400,"EntityTooLarge");return;}
     if(objects.putIfAbsent(path,bytes)!=null){error(x,409,"FileAlreadyExists");return;}
     if(versionAfterPut.getAndSet(false))versioned.set(true);
     if(loseNextReply.getAndSet(false))return;
     x.getResponseHeaders().set("ETag","\""+md5(bytes)+"\"");x.getResponseHeaders().set("x-oss-request-id","SYNTHETIC");x.sendResponseHeaders(200,-1);return;
    }
    byte[] bytes=objects.get(path);if(bytes==null){error(x,404,"NoSuchKey");return;}
    x.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));x.getResponseHeaders().set("Content-Type","application/octet-stream");x.getResponseHeaders().set("ETag","\""+md5(bytes)+"\"");x.getResponseHeaders().set("Last-Modified",DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));x.getResponseHeaders().set("x-oss-request-id","SYNTHETIC");
    if(method.equals("HEAD")){x.sendResponseHeaders(200,-1);return;}
    if(method.equals("GET")){gets.incrementAndGet();x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);return;}
    error(x,405,"MethodNotAllowed");
   }catch(Exception rejected){try{error(x,500,"SyntheticEmulatorFailure");}catch(Exception ignored){}}finally{x.close();}
  }
  public void close(){server.stop(0);executor.shutdownNow();objects.clear();}
 }
 @Test void sdkHttpChecksUseOnlyDisposableSyntheticServer()throws Exception{
  try(var fake=new Emulator()){
   var config=OssProjectStore.configuration();config.setSLDEnabled(true);
   OSS client=OSSClientBuilder.create().endpoint(fake.endpoint()).region("cn-beijing").credentialsProvider(com.aliyun.oss.common.auth.CredentialsProviderFactory.newDefaultCredentialProvider("SYNTHETIC_ID_NOT_VALID","SYNTHETIC_SECRET_NOT_VALID")).clientConfiguration(config).build();
   try(var project=new OssProjectStore(client,settings())){
    var keys=new Keys();var crypto=crypto(keys);var archive=new BackupEnvelopeArchive(crypto,project.backups(),(a,s,o)->true);int passed=0;
    Scope s=scope();Envelope envelope=crypto.seal(ACCESS,s,PLAIN);var receipt=archive.store(ACCESS,s,envelope);assertEquals(envelope.sha256(),receipt.encryptedObjectSha256());assertEquals(1,fake.puts.get());assertFalse(new String(fake.objects.get(fake.path(s)),StandardCharsets.ISO_8859_1).contains(new String(PLAIN,StandardCharsets.US_ASCII)));System.out.println("PASS SDK_HTTP_ENCRYPTED_PUT_AND_AUTHENTICATED_READBACK");passed++;
    archive.store(ACCESS,s,envelope);assertEquals(1,fake.puts.get());System.out.println("PASS SDK_HTTP_IDENTICAL_REPLAY_NO_SECOND_PUT");passed++;
    Envelope different=crypto.seal(ACCESS,s,PLAIN);assertEquals("BACKUP_ARCHIVE_WRITE_UNCONFIRMED",assertThrows(DomainException.class,()->archive.store(ACCESS,s,different)).code);assertEquals(1,fake.puts.get());assertArrayEquals(envelope.encoded(),fake.objects.get(fake.path(s)));System.out.println("PASS SDK_HTTP_CONFLICT_NEVER_OVERWRITES");passed++;
    Scope lost=scope();Envelope lostEnvelope=crypto.seal(ACCESS,lost,PLAIN);fake.loseNextReply.set(true);int before=fake.puts.get();assertEquals("BACKUP_ARCHIVE_WRITE_UNCONFIRMED",assertThrows(DomainException.class,()->archive.store(ACCESS,lost,lostEnvelope)).code);assertEquals(before+1,fake.puts.get());assertArrayEquals(lostEnvelope.encoded(),fake.objects.get(fake.path(lost)));archive.verifyExisting(ACCESS,lost,lostEnvelope.encoded().length,lostEnvelope.sha256());assertEquals(before+1,fake.puts.get());System.out.println("PASS SDK_HTTP_LOST_PUT_RESPONSE_READONLY_RECONCILIATION");passed++;
    fake.publicObject.set(true);int reads=fake.gets.get();assertThrows(DomainException.class,()->archive.verifyExisting(ACCESS,s,envelope.encoded().length,envelope.sha256()));assertEquals(reads,fake.gets.get());fake.publicObject.set(false);System.out.println("PASS SDK_HTTP_PUBLIC_OBJECT_REJECTED_BEFORE_CONTENT_READ");passed++;
    fake.versioned.set(true);Scope blocked=scope();Envelope blockedEnvelope=crypto.seal(ACCESS,blocked,PLAIN);before=fake.puts.get();int unwraps=keys.unwraps;assertThrows(DomainException.class,()->archive.store(ACCESS,blocked,blockedEnvelope));assertEquals(before,fake.puts.get());assertEquals(unwraps,keys.unwraps);fake.versioned.set(false);System.out.println("PASS SDK_HTTP_VERSIONED_BUCKET_NO_KEY_UNWRAP_OR_PUT");passed++;
    Scope race=scope();Envelope raceEnvelope=crypto.seal(ACCESS,race,PLAIN);fake.versionAfterPut.set(true);assertEquals("BACKUP_ARCHIVE_WRITE_UNCONFIRMED",assertThrows(DomainException.class,()->archive.store(ACCESS,race,raceEnvelope)).code);assertTrue(fake.objects.containsKey(fake.path(race)));fake.versioned.set(false);System.out.println("PASS SDK_HTTP_CHANGED_BUCKET_PREREQUISITE_NO_SUCCESS_RECEIPT");passed++;
    byte[] tampered=fake.objects.get(fake.path(s)).clone();tampered[tampered.length-1]^=1;fake.objects.put(fake.path(s),tampered);before=fake.puts.get();assertThrows(DomainException.class,()->archive.verifyExisting(ACCESS,s,envelope.encoded().length,envelope.sha256()));assertEquals(before,fake.puts.get());System.out.println("PASS SDK_HTTP_TAMPERED_OBJECT_NOT_ACCEPTED_OR_REPAIRED");passed++;
    System.out.println("BACKUP_ARCHIVE_HTTP_PASS="+passed+" REAL_OSS=false REAL_KMS=false PRODUCTION_KEYS=false");
   }
  }
  System.out.println("BACKUP_ARCHIVE_LOOPBACK_STOPPED=true");
 }
}
