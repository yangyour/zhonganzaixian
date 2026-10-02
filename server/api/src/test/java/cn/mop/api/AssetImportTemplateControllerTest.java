package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.AssetImportSchema;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated resource/auth guards, not full file import or browser evidence. */
class AssetImportTemplateControllerTest {
    static final String ACTOR="55000000-0000-4000-8000-000000000001",TENANT="55000000-0000-4000-8000-000000000011";
    PlatformRepository platform;AssetImportTemplateController controller;
    @BeforeEach void setup(){platform=mock(PlatformRepository.class);controller=new AssetImportTemplateController(platform);role("msp",true);}
    void role(String role,boolean active){when(platform.membership(ACTOR,TENANT)).thenReturn(Optional.of(new Membership(ACTOR,TENANT,role,7,active)));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/v1/assets/import-template");r.setUserPrincipal(()->ACTOR);r.getSession().setAttribute("mop.tenant_id",TENANT);return r;}
    @Test void authorizedRolesReceiveVerifiedOriginalBytesAndExplicitPendingMetadata(){
        for(String role:List.of("msp","padmin","tadmin")){
            role(role,true);var response=controller.download(request());assertEquals(200,response.getStatusCode().value());assertEquals(AssetImportTemplateFile.MEDIA_TYPE,response.getHeaders().getContentType().toString());assertEquals(AssetImportTemplateFile.SIZE,response.getHeaders().getContentLength());
            assertArrayEquals(AssetImportTemplateFile.read(),response.getBody());assertEquals(AssetImportTemplateFile.SHA256,response.getHeaders().getFirst("X-MOP-Template-SHA256"));assertEquals(AssetImportSchema.VERSION,response.getHeaders().getFirst("X-MOP-Template-Schema"));assertEquals("CONFIG_PENDING",response.getHeaders().getFirst("X-MOP-Business-Status"));assertTrue(response.getHeaders().getContentDisposition().isAttachment());assertEquals(AssetImportTemplateFile.NAME,response.getHeaders().getContentDisposition().getFilename());assertNull(response.getHeaders().getCacheControl()); // Shared security filter supplies the single no-store header.
        }
        verify(platform,times(3)).membership(ACTOR,TENANT);verifyNoMoreInteractions(platform);
    }
    @Test void missingInactiveOrReadOnlyIdentitiesCannotDownload(){
        var anonymous=request();anonymous.setUserPrincipal(null);assertEquals(401,assertThrows(DomainException.class,()->controller.download(anonymous)).status);
        role("msp",false);assertEquals(401,assertThrows(DomainException.class,()->controller.download(request())).status);
        for(String role:List.of("viewer","unknown")){role(role,true);assertEquals(403,assertThrows(DomainException.class,()->controller.download(request())).status);}
    }
    @Test void queryOverridesCannotSelectAnotherResourceOrTenant(){for(String key:List.of("tenant_id","filename","path","format","schema_version")){var r=request();r.addParameter(key,"forged");assertEquals(400,assertThrows(DomainException.class,()->controller.download(r)).status);}}
    @Test void corruptedOrReplacedResourceFailsClosedAndReturnedBytesAreDetached(){
        byte[] original=AssetImportTemplateFile.read(),copy=AssetImportTemplateFile.verified(original);copy[0]^=1;assertNotEquals(copy[0],original[0]);
        for(byte[] bad:new byte[][]{null,new byte[0],new byte[AssetImportTemplateFile.SIZE+1],copy})assertEquals(503,assertThrows(DomainException.class,()->AssetImportTemplateFile.verified(bad)).status);
        assertArrayEquals(original,AssetImportTemplateFile.read());
    }
}
