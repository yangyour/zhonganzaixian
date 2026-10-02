package cn.mop.adapters;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetimport.source.*;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SDK boundary unit doubles only; no designated storage, IAM or conditional-write evidence. */
class MinioAssetImportStoreSafetyTest {
    private static final String KEY="asset-import-sources/00000000-0000-4000-8000-000000000099/55000000-0000-4000-8000-000000000009";
    private MinioClient client;private MinioAssetImportStore store;
    @BeforeEach void setup(){client=mock(MinioClient.class);store=new MinioAssetImportStore(client);}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static ErrorResponseException error(String code){var body=mock(ErrorResponse.class);when(body.code()).thenReturn(code);var error=mock(ErrorResponseException.class);when(error.errorResponse()).thenReturn(body);return error;}
    private void stored(byte[] bytes)throws Exception{var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn((long)bytes.length);when(client.statObject(any(StatObjectArgs.class))).thenReturn(stat);var response=mock(GetObjectResponse.class);when(response.readNBytes(bytes.length+1)).thenReturn(bytes);when(client.getObject(any(GetObjectArgs.class))).thenReturn(response);}
    @Test void invalidNamespaceDigestSizeAndFormatNeverReachTheSdk()throws Exception{
        byte[] bytes={1,2,3};for(String key:List.of("photos/00000000-0000-4000-8000-000000000099/55000000-0000-4000-8000-000000000009","outside/key",KEY+"/extra","asset-import-sources/------------------------------------/------------------------------------"))assertThrows(IllegalArgumentException.class,()->store.storeImmutable(key,bytes,ImportFormat.CSV,sha(bytes)));
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[(int)ImportLimits.MAX_SOURCE_BYTES+1]})assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,invalid,ImportFormat.CSV,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,bytes,null,sha(bytes)));assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,bytes,ImportFormat.CSV,"invalid"));assertThrows(IllegalArgumentException.class,()->store.read(KEY,ImportLimits.MAX_SOURCE_BYTES+1,"a".repeat(64)));verifyNoInteractions(client);
    }
    @Test void mismatchingSuppliedDigestFailsIntegrityWithoutStorageCall()throws Exception{
        assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(KEY,new byte[]{1,2,3},ImportFormat.CSV,"a".repeat(64))).code);verifyNoInteractions(client);
    }
    @Test void matchingExistingOriginalIsReadVerifiedAndNeverRewritten()throws Exception{
        byte[] bytes={1,2,3};stored(bytes);store.storeImmutable(KEY,bytes,ImportFormat.CSV,sha(bytes));verify(client,never()).putObject(any(PutObjectArgs.class));verify(client).getObject(any(GetObjectArgs.class));
    }
    @Test void mismatchedExistingSizeOrActualDigestCannotBeOverwritten()throws Exception{
        stored(new byte[]{1,2,3});assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(KEY,new byte[]{1,2},ImportFormat.CSV,sha(new byte[]{1,2}))).code);assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(KEY,new byte[]{3,2,1},ImportFormat.CSV,sha(new byte[]{3,2,1}))).code);verify(client,never()).putObject(any(PutObjectArgs.class));
    }
    @Test void sourcesLargerThanFiveMiBUseOneConditionalPutThenVerifyBytes()throws Exception{
        byte[] bytes=new byte[6*1024*1024];Arrays.fill(bytes,(byte)42);stored(bytes);var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn((long)bytes.length);var absent=error("NoSuchKey");when(client.statObject(any(StatObjectArgs.class))).thenThrow(absent).thenReturn(stat);
        store.storeImmutable(KEY,bytes,ImportFormat.XLSX,sha(bytes));var args=ArgumentCaptor.forClass(PutObjectArgs.class);verify(client).putObject(args.capture());var put=args.getValue();assertEquals(MinioAssetImportStore.BUCKET,put.bucket());assertEquals(KEY,put.object());assertTrue(put.extraHeaders().get("If-None-Match").contains("*"));assertTrue(put.extraHeaders().get("Expect").contains("100-continue"));assertEquals(1,put.partCount());assertTrue(put.partSize()>=bytes.length);verify(client).getObject(any(GetObjectArgs.class));
    }
    @Test void racingConditionalWriteReadsWinnerAndFailsIntegrityIfDifferent()throws Exception{
        byte[] bytes={1,2,3};stored(new byte[]{3,2,1});var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn(3L);var absent=error("NoSuchKey");var raced=error("PreconditionFailed");when(client.statObject(any(StatObjectArgs.class))).thenThrow(absent).thenReturn(stat);when(client.putObject(any(PutObjectArgs.class))).thenThrow(raced);assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(KEY,bytes,ImportFormat.CSV,sha(bytes))).code);verify(client).getObject(any(GetObjectArgs.class));
    }
    @Test void absentBucketAndReadFailuresRemainUnavailableAndAreNeverSuccess()throws Exception{
        var absent=error("NoSuchBucket");when(client.statObject(any(StatObjectArgs.class))).thenThrow(absent);assertEquals("UPSTREAM_UNAVAILABLE",assertThrows(DomainException.class,()->store.storeImmutable(KEY,new byte[]{1,2,3},ImportFormat.CSV,sha(new byte[]{1,2,3}))).code);verify(client,never()).putObject(any(PutObjectArgs.class));when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("PRIVATE-ISOLATED-INTERNAL")));assertFalse(store.ready());
    }
    @Test void readsBoundTheBodyAndVerifyActualBytesRatherThanUserMetadata()throws Exception{
        byte[] bytes={1,2,3};stored(bytes);assertArrayEquals(bytes,store.read(KEY,3,sha(bytes)));assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.read(KEY,3,sha(new byte[]{3,2,1}))).code);var response=client.getObject(GetObjectArgs.builder().bucket(MinioAssetImportStore.BUCKET).object(KEY).build());verify(response,times(2)).readNBytes(4);verify(response,times(2)).close();
    }
}
