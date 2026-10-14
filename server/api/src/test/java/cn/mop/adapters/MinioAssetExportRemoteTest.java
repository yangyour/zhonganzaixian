package cn.mop.adapters;
import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit real designated-server MinIO; synthetic storage bytes are not claimed to be an XLSX. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_EXPORT_MINIO_TESTS",matches="true")
class MinioAssetExportRemoteTest {
    final MinioAssetExportStore store=new MinioAssetExportStore(MinioStorageSettings.from(System::getenv,MinioStorageSettings.Scope.EXPORTS));
    static String key(){return "asset-exports/00000000-0000-4000-8000-000000000099/"+UUID.randomUUID();}
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    @Test void immutableRoundTripAndReplay()throws Exception{assertTrue(store.ready());String k=key();byte[] b="MOP-FAKE export storage verification".getBytes(java.nio.charset.StandardCharsets.UTF_8);String h=hash(b);store.storeImmutable(k,b,h);assertArrayEquals(b,store.read(k,b.length,h));store.storeImmutable(k,b,h);byte[] other=b.clone();other[0]='X';assertEquals(409,assertThrows(DomainException.class,()->store.storeImmutable(k,other,hash(other))).status);assertArrayEquals(b,store.read(k,b.length,h));}
    @Test void concurrentDifferentContentHasOneWinnerAndNeverReplacesIt()throws Exception{String k=key();byte[] a=new byte[1024],b=new byte[1024];Arrays.fill(a,(byte)7);Arrays.fill(b,(byte)8);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var fa=pool.submit(()->attempt(k,a,start));var fb=pool.submit(()->attempt(k,b,start));start.countDown();int ra=fa.get(30,TimeUnit.SECONDS),rb=fb.get(30,TimeUnit.SECONDS);assertEquals(Set.of(200,409),Set.of(ra,rb));byte[] winner=ra==200?a:b;assertArrayEquals(winner,store.read(k,winner.length,hash(winner)));}finally{pool.shutdownNow();}}
    static int attempt(String key,byte[] bytes,CountDownLatch start)throws Exception{start.await();var store=new MinioAssetExportStore(MinioStorageSettings.from(System::getenv,MinioStorageSettings.Scope.EXPORTS));try{store.storeImmutable(key,bytes,hash(bytes));return 200;}catch(DomainException e){return e.status;}}
    @Test void overFiveMiBStillUsesConditionalImmutableUpload()throws Exception{String k=key();byte[] bytes=new byte[5_242_881];new Random(14).nextBytes(bytes);String h=hash(bytes);store.storeImmutable(k,bytes,h);assertArrayEquals(bytes,store.read(k,bytes.length,h));byte[] different=bytes.clone();different[different.length-1]^=1;assertEquals(409,assertThrows(DomainException.class,()->store.storeImmutable(k,different,hash(different))).status);assertArrayEquals(bytes,store.read(k,bytes.length,h));}
}
