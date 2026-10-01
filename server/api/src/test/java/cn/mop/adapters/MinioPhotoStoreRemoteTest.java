package cn.mop.adapters;

import cn.mop.domain.DomainException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit designated-MinIO integration only. Retains MOP-FAKE objects; never deletes them.
 * This verifies storage behavior, not PostgreSQL/HTTP/OIDC/mobile flow or malware scanning. */
@EnabledIfEnvironmentVariable(named="MOP_RUN_REMOTE_TESTS",matches="true")
class MinioPhotoStoreRemoteTest {
    private static String setting(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing designated photo setting: "+name);return value;}
    private MinioPhotoStore store(){return new MinioPhotoStore(setting("MOP_S3_ENDPOINT"),setting("MOP_S3_BUCKET"),setting("MOP_S3_ACCESS_KEY"),setting("MOP_S3_SECRET_KEY"));}
    private static String key(){return "photos/00000000-0000-4000-8000-000000000099/"+UUID.randomUUID();}
    private static byte[] fakePhoto(int color)throws Exception{var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,color);var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",output))throw new IllegalStateException("PNG decoder unavailable");return output.toByteArray();}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void designatedStorageReturnsOriginalBytesAndClaimsSameHashWithoutReplacement()throws Exception{
        MinioPhotoStore store=store();assertTrue(store.ready(),"Designated project MinIO must be available");String key=key();byte[] original=fakePhoto(0x123456);String hash=sha(original);store.storeImmutable(key,original,"image/png",hash);assertArrayEquals(original,store.read(key,original.length,hash));store.storeImmutable(key,original,"image/png",hash);assertArrayEquals(original,store.read(key,original.length,hash));
    }
    @Test void existingProjectOriginalRejectsDifferentContentAndRemainsUnchanged()throws Exception{
        MinioPhotoStore store=store();assertTrue(store.ready(),"Designated project MinIO must be available");String key=key();byte[] original=fakePhoto(0x123456),different=fakePhoto(0xabcdef);String hash=sha(original);store.storeImmutable(key,original,"image/png",hash);assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.storeImmutable(key,different,"image/png",sha(different))).code);assertArrayEquals(original,store.read(key,original.length,hash));assertEquals("FILE_INTEGRITY_CONFLICT",assertThrows(DomainException.class,()->store.read(key,original.length+1,hash)).code);
    }
    @Test void designatedServerEnforcesConditionalPutRatherThanOnlyAdapterPreflight()throws Exception{
        MinioPhotoStore store=store();String key=key();byte[] original=fakePhoto(0x123456),different=fakePhoto(0xabcdef);String hash=sha(original);store.storeImmutable(key,original,"image/png",hash);
        MinioClient client=MinioClient.builder().endpoint(setting("MOP_S3_ENDPOINT")).credentials(setting("MOP_S3_ACCESS_KEY"),setting("MOP_S3_SECRET_KEY")).build();boolean denied=false;
        try{client.putObject(PutObjectArgs.builder().bucket(setting("MOP_S3_BUCKET")).object(key).stream(new ByteArrayInputStream(different),(long)different.length,-1L).contentType("image/png").extraHeaders(Map.of("If-None-Match","*")).build());}
        catch(ErrorResponseException error){denied="PreconditionFailed".equals(error.errorResponse().code());}
        catch(Exception error){throw new IllegalStateException("Conditional create check failed exception_type="+error.getClass().getName());}
        assertTrue(denied,"Designated server must refuse replacement under If-None-Match");assertArrayEquals(original,store.read(key,original.length,hash));
    }
}
