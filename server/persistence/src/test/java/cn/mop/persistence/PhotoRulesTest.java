package cn.mop.persistence;

import cn.mop.domain.*;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure guards only. Passing these tests is not PostgreSQL/MinIO integration evidence. */
class PhotoRulesTest {
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void uploadedBytesRemainTheOriginalEvenIfBothCallerBuffersChange()throws Exception{
        byte[] input={1,2,3};PhotoUpload photo=new PhotoUpload("虚构照片.png","image/png",hash(input),input);
        input[0]=9;photo.content()[1]=9;assertArrayEquals(new byte[]{1,2,3},photo.content());assertDoesNotThrow(()->PhotoRules.validateUpload(photo));
    }
    @Test void changedHashAndInvalidSizeNeverEnterDurableAcceptance()throws Exception{
        DomainException conflict=assertThrows(DomainException.class,()->PhotoRules.validateStoredContent(new byte[]{1},"0".repeat(64)));assertEquals("FILE_INTEGRITY_CONFLICT",conflict.code);
        for(byte[] invalid:new byte[][]{null,new byte[0],new byte[5_000_001]})assertEquals(400,assertThrows(DomainException.class,()->PhotoRules.validateStoredContent(invalid,"0".repeat(64))).status);
        assertThrows(DomainException.class,()->PhotoRules.validateStoredContent(new byte[]{1},"INVALID"));
    }
    @Test void unsupportedMediaAndUnsafeNamesAreRejectedBeforeStoreAccess()throws Exception{
        byte[] bytes={1};String sha=hash(bytes);
        for(String name:Arrays.asList(null,"","../x.png","x\\photo.png","bad\u0000.png","\ud800.png","x".repeat(201)))assertThrows(DomainException.class,()->PhotoRules.validateUpload(new PhotoUpload(name,"image/png",sha,bytes)));
        for(String media:Arrays.asList(null,"image/gif","application/octet-stream"))assertThrows(DomainException.class,()->PhotoRules.validateUpload(new PhotoUpload("x.png",media,sha,bytes)));
    }
    @Test void attachmentLocksUseStableUniqueOrderAndDoNotBroadenTheThreePhotoLimit(){
        String a="55000000-0000-4000-8000-000000000001",b="55000000-0000-4000-8000-000000000002";
        assertEquals(List.of(a,b),PhotoRules.attachmentIds(List.of(b,a,b)));
        for(List<String> invalid:List.of(List.<String>of(),List.of("invalid"),List.of(a,a,a,a)))assertThrows(DomainException.class,()->PhotoRules.attachmentIds(invalid));
        assertThrows(DomainException.class,()->PhotoRules.attachmentIds(Arrays.asList(a,null)));
    }
    @Test void retryDelayIsBoundedEvenAfterManyAttempts(){
        assertEquals(5,PhotoRules.retrySeconds(1));assertEquals(10,PhotoRules.retrySeconds(2));assertEquals(300,PhotoRules.retrySeconds(1000));assertEquals(300,PhotoRules.retrySeconds(Integer.MAX_VALUE));
    }
}
