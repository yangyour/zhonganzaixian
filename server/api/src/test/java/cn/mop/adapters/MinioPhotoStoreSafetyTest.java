package cn.mop.adapters;

import cn.mop.domain.DomainException;
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

/** MinIO SDK boundary mocks; no remote IAM, conditional PUT or storage evidence is claimed. */
class MinioPhotoStoreSafetyTest {
    private static final String KEY="photos/55000000-0000-4000-8000-000000000001/55000000-0000-4000-8000-000000000009";
    private MinioClient client;private MinioPhotoStore store;
    @BeforeEach void setup(){client=mock(MinioClient.class);store=new MinioPhotoStore(client,"mop-test-photos");}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private void stored(byte[] bytes)throws Exception{var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn((long)bytes.length);when(client.statObject(any(StatObjectArgs.class))).thenReturn(stat);var response=mock(GetObjectResponse.class);when(response.readNBytes(bytes.length+1)).thenReturn(bytes);when(client.getObject(any(GetObjectArgs.class))).thenReturn(response);}
    @Test void invalidAcceptedMetadataNeverReachesStorage()throws Exception{
        byte[] original={1,2,3};for(String key:List.of("photos/------------------------------------/------------------------------------","outside/key",KEY+"/path"))assertThrows(IllegalArgumentException.class,()->store.storeImmutable(key,original,"image/png",sha(original)));
        for(byte[] bytes:new byte[][]{null,new byte[0],new byte[5000001]})assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,bytes,"image/png","a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,original,"image/gif",sha(original)));assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,original,"image/png","not-a-digest"));assertThrows(IllegalArgumentException.class,()->store.storeImmutable(KEY,original,"image/png","a".repeat(64)));verifyNoInteractions(client);
    }
    @Test void matchingExistingOriginalIsReadVerifiedAndNeverWrittenAgain()throws Exception{
        byte[] original={1,2,3};stored(original);store.storeImmutable(KEY,original,"image/png",sha(original));verify(client,never()).putObject(any(PutObjectArgs.class));verify(client).getObject(any(GetObjectArgs.class));
    }
    @Test void existingDifferentSizeOrHashCannotBeOverwritten()throws Exception{
        stored(new byte[]{1,2,3});assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(KEY,new byte[]{1,2},"image/png",sha(new byte[]{1,2}))).code);
        assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(KEY,new byte[]{3,2,1},"image/png",sha(new byte[]{3,2,1}))).code);verify(client,never()).putObject(any(PutObjectArgs.class));
    }
    @Test void absentOriginalUsesOneConditionalSdkPutThenVerifiesActualBytes()throws Exception{
        byte[] original={1,2,3};stored(original);var error=mock(ErrorResponse.class);when(error.code()).thenReturn("NoSuchKey");var absent=mock(ErrorResponseException.class);when(absent.errorResponse()).thenReturn(error);var stat=mock(StatObjectResponse.class);when(stat.size()).thenReturn(3L);when(client.statObject(any(StatObjectArgs.class))).thenThrow(absent).thenReturn(stat);store.storeImmutable(KEY,original,"image/png",sha(original));var args=ArgumentCaptor.forClass(PutObjectArgs.class);verify(client).putObject(args.capture());assertTrue(args.getValue().extraHeaders().get("If-None-Match").contains("*"));assertEquals(KEY,args.getValue().object());assertEquals("mop-test-photos",args.getValue().bucket());verify(client).getObject(any(GetObjectArgs.class));
    }
    @Test void readsAreBoundedAndCompareActualHashRatherThanMetadata()throws Exception{
        byte[] original={1,2,3};stored(original);assertArrayEquals(original,store.read(KEY,3,sha(original)));assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.read(KEY,3,sha(new byte[]{3,2,1}))).code);
        assertThrows(IllegalArgumentException.class,()->store.read(KEY,5000001,sha(original)));var content=client.getObject(GetObjectArgs.builder().bucket("mop-test-photos").object(KEY).build());verify(content,times(2)).readNBytes(4);
    }
}
