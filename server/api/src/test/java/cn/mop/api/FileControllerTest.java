package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Isolated controller contracts; these mocks do not establish PostgreSQL/MinIO integration. */
class FileControllerTest {
    private static final String ACTOR="55000000-0000-4000-8000-000000000003",TENANT="55000000-0000-4000-8000-000000000001",FILE="55000000-0000-4000-8000-000000000009",OPERATION="55000000-0000-4000-8000-000000000010",GRANT="55000000-0000-4000-8000-000000000011";
    private PlatformRepository repository;private PhotoObjectStore store;private FileController controller;private final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup(){repository=mock(PlatformRepository.class);store=mock(PhotoObjectStore.class);controller=new FileController(repository,new PhotoPolicy("image/jpeg,image/png"),store,mapper);when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"viewer",8,true)));when(store.ready()).thenReturn(true);when(repository.idempotent(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any())).thenAnswer(call->call.getArgument(6,Supplier.class).get());when(repository.acceptPhoto(eq(TENANT),eq(ACTOR),any())).thenReturn(Map.of("operation_id",OPERATION,"status","PENDING"));}
    private MockHttpServletRequest request(String method,String path){var request=new MockHttpServletRequest(method,"/api/v1"+path);var session=new MockHttpSession();session.setAttribute("mop.tenant_id",TENANT);request.setSession(session);request.setUserPrincipal(()->ACTOR);request.setAttribute("mop.trace_id","isolated-trace");request.addHeader("Idempotency-Key","isolated-photo-request");return request;}
    private MockHttpServletRequest upload(String name,byte[] original){var request=request("POST","/files");request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE);var file=new MockPart("file",name,original);file.getHeaders().setContentType(MediaType.IMAGE_PNG);request.addPart(file);request.addPart(new MockPart("purpose","customer_report_photo".getBytes(StandardCharsets.UTF_8)));return request;}
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Object response){return (Map<String,Object>)((Map<String,Object>)response).get("data");}
    private Map<String,Object> location(byte[] original)throws Exception{return ApiResponse.map("object_key","photos/"+TENANT+"/"+FILE,"byte_size",(long)original.length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),"media_type","image/png","original_name","原图.png");}

    @Test void uploadUsesPermissionVersionAndOriginalMetadataInDurableReplayIdentity()throws Exception{
        byte[] original=PhotoPolicyTest.image("png");var response=controller.upload(upload("原图.png",original));assertEquals(202,response.getStatusCode().value());assertEquals(Map.of("operation_id",OPERATION,"status","PENDING"),data(response.getBody()));
        var captured=ArgumentCaptor.forClass(PhotoUpload.class);verify(repository).acceptPhoto(eq(TENANT),eq(ACTOR),captured.capture());assertArrayEquals(original,captured.getValue().content());assertEquals("原图.png",captured.getValue().originalName());
        String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(new TreeMap<>(Map.of("purpose","customer_report_photo","original_name","原图.png","declared_media_type","image/png","sha256",captured.getValue().sha256())))));
        verify(repository).idempotent(eq(ACTOR),eq(TENANT),eq("/api/v1/files"),eq("isolated-photo-request"),eq(expected),eq(8L),any());verify(repository).audit(ACTOR,TENANT,"file.customer_report_photo.accept","operation",OPERATION,"SUCCEEDED","DURABLE_STAGING_ONLY_UNSCANNED","isolated-trace");verify(store,never()).storeImmutable(anyString(),any(),anyString(),anyString());
        controller.upload(upload("第二个名字.png",original));var digests=ArgumentCaptor.forClass(String.class);verify(repository,times(2)).idempotent(anyString(),anyString(),anyString(),anyString(),digests.capture(),anyLong(),any());assertNotEquals(digests.getAllValues().get(0),digests.getAllValues().get(1));
    }
    @Test void multipartRejectsDuplicateUnknownMissingOrQueryFieldsBeforeAdmission()throws Exception{
        byte[] original=PhotoPolicyTest.image("png");for(String extra:List.of("file","purpose","tenant_id","actor_id")){var request=upload("a.png",original);request.addPart(new MockPart(extra,"forged".getBytes(StandardCharsets.UTF_8)));assertEquals(400,assertThrows(DomainException.class,()->controller.upload(request)).status);}
        var query=upload("a.png",original);query.setQueryString("tenant_id=forged");assertThrows(DomainException.class,()->controller.upload(query));
        var missing=request("POST","/files");missing.addPart(new MockPart("purpose","customer_report_photo".getBytes(StandardCharsets.UTF_8)));assertThrows(DomainException.class,()->controller.upload(missing));
        var large=upload("a.png",new byte[PhotoPolicy.MAX_SIZE+1]);assertEquals("VALIDATION_ERROR",assertThrows(DomainException.class,()->controller.upload(large)).code);
        var noKey=upload("a.png",original);noKey.removeHeader("Idempotency-Key");assertThrows(DomainException.class,()->controller.upload(noKey));verify(repository,never()).acceptPhoto(anyString(),anyString(),any());
    }
    @Test void unavailableStoreRejectsNewUploadAndRecordedReplayRemainsReadable()throws Exception{
        when(store.ready()).thenReturn(false);var request=upload("a.png",PhotoPolicyTest.image("png"));assertEquals(503,assertThrows(DomainException.class,()->controller.upload(request)).status);verify(repository,never()).acceptPhoto(anyString(),anyString(),any());
        reset(store);when(repository.idempotent(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),any())).thenReturn(Map.of("operation_id",OPERATION,"status","PENDING"));assertEquals(202,controller.upload(request).getStatusCode().value());verifyNoInteractions(store);
    }
    @Test void currentSessionScopeAndCustomerUploadCapabilitiesAreRequired()throws Exception{
        var absent=request("GET","/files/policy");absent.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.policy(absent)).status);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.empty());assertEquals(401,assertThrows(DomainException.class,()->controller.policy(request("GET","/files/policy"))).status);
        when(repository.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,"msp",9,true)));assertEquals(403,assertThrows(DomainException.class,()->controller.upload(upload("a.png",PhotoPolicyTest.image("png")))).status);
        when(repository.file(TENANT,ACTOR,FILE)).thenReturn(Optional.empty());assertEquals(404,assertThrows(DomainException.class,()->controller.file(FILE,request("GET","/files/"+FILE))).status);verifyNoInteractions(store);
    }
    @Test void metadataProjectionAndGrantsNeverExposeStorageCoordinatesOrCallerIdentity(){
        when(repository.file(TENANT,ACTOR,FILE)).thenReturn(Optional.of(ApiResponse.map("id",FILE,"object_key","private-key","content",new byte[]{1})));Map<String,Object> metadata=data(controller.file(FILE,request("GET","/files/"+FILE)));assertEquals(FILE,metadata.get("id"));assertFalse(metadata.containsKey("object_key"));assertFalse(metadata.containsKey("content"));
        when(repository.contentGrant(TENANT,ACTOR,FILE)).thenReturn(Map.of("content_url","/api/v1/files/"+FILE+"/content?grant="+GRANT,"expires_at","2030-01-01T00:00:00Z","object_key","private-key"));assertEquals(Set.of("content_url","expires_at"),data(controller.grant(FILE,mapper.createObjectNode(),request("POST","/files/"+FILE+"/content-grants"))).keySet());
        assertThrows(DomainException.class,()->controller.grant(FILE,mapper.createObjectNode().put("actor_id",ACTOR),request("POST","/files/"+FILE+"/content-grants")));assertThrows(DomainException.class,()->controller.grant(FILE,mapper.createArrayNode(),request("POST","/files/"+FILE+"/content-grants")));
    }
    @Test void binaryContentIsReadThroughStorageThenAuthorizationIsCheckedAgain()throws Exception{
        byte[] original=PhotoPolicyTest.image("png");Map<String,Object> location=location(original);when(repository.photoContent(TENANT,ACTOR,FILE,GRANT)).thenReturn(location);when(store.read((String)location.get("object_key"),(long)original.length,(String)location.get("sha256"))).thenReturn(original);
        var request=request("GET","/files/"+FILE+"/content");request.addParameter("grant",GRANT);var response=controller.content(FILE,request);assertArrayEquals(original,response.getBody());assertEquals(MediaType.IMAGE_PNG,response.getHeaders().getContentType());assertEquals("no-store",response.getHeaders().getCacheControl());assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));assertEquals(original.length,response.getHeaders().getContentLength());verify(repository,times(2)).photoContent(TENANT,ACTOR,FILE,GRANT);
        when(repository.photoContent(TENANT,ACTOR,FILE,GRANT)).thenReturn(location).thenThrow(new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见"));assertEquals(404,assertThrows(DomainException.class,()->controller.content(FILE,request)).status);
    }
    @Test void duplicateUnknownOrMalformedGrantQueriesNeverReadStorage(){
        for(String[] values:new String[][]{{GRANT,GRANT},{"not-a-grant"}}){var request=request("GET","/files/"+FILE+"/content");request.addParameter("grant",values);assertEquals(400,assertThrows(DomainException.class,()->controller.content(FILE,request)).status);}
        var request=request("GET","/files/"+FILE+"/content");request.addParameter("grant",GRANT);request.addParameter("actor_id",ACTOR);assertThrows(DomainException.class,()->controller.content(FILE,request));verifyNoInteractions(store);verify(repository,never()).photoContent(anyString(),anyString(),anyString(),anyString());
    }
    @Test void missingOrInvalidPhotoConfigurationDisablesOnlyPhotos(){
        var configuration=new PhotoConfiguration();for(String allowlist:List.of("","image/gif")){var environment=new org.springframework.mock.env.MockEnvironment().withProperty("MOP_PHOTO_ALLOWED_MEDIA_TYPES",allowlist);PhotoPolicy disabled=configuration.photoPolicy(environment);assertFalse(disabled.configured());PhotoObjectStore unavailable=configuration.photoObjectStore(environment);assertFalse(unavailable.ready());assertEquals(503,assertThrows(DomainException.class,()->unavailable.read("unused",1,"unused")).status);}
    }
}
