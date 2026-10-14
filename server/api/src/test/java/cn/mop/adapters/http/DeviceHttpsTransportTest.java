package cn.mop.adapters.http;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceHttpsTransportTest {
    static final URI SCP=URI.create("https://192.168.1.179");
    static void failure(String reason,org.junit.jupiter.api.function.Executable e){assertEquals(reason,assertThrows(DeviceHttpsTransport.Failure.class,e).reason);}
    @Test void testModeDoesNotChangeGlobalTrustOrHostnameVerification()throws Exception{
        var context=SSLContext.getDefault();var verifier=HttpsURLConnection.getDefaultHostnameVerifier();var factory=HttpsURLConnection.getDefaultSSLSocketFactory();
        String flag=System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
        try(var test=new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.TEST_INSECURE,null);var strict=new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.SYSTEM,null)){
            assertEquals(DeviceHttpsTransport.Mode.TEST_INSECURE,test.mode());assertEquals(DeviceHttpsTransport.Mode.SYSTEM,strict.mode());
            assertSame(context,SSLContext.getDefault());assertSame(verifier,HttpsURLConnection.getDefaultHostnameVerifier());assertSame(factory,HttpsURLConnection.getDefaultSSLSocketFactory());assertEquals(flag,System.getProperty("jdk.internal.httpclient.disableHostnameVerification"));
        }
    }
    @Test void insecureModeIsLimitedToBothExplicitAddressesAndPort443(){
        for(String host:List.of("https://192.168.1.180","https://192.169.11.2","https://192.168.1.179:8443","https://example.com"))failure("TEST_TLS_TARGET_NOT_APPROVED",()->new DeviceHttpsTransport(URI.create(host),DeviceHttpsTransport.Mode.TEST_INSECURE,null));
        try(var firewall=new DeviceHttpsTransport(URI.create("https://192.169.11.1:443"),DeviceHttpsTransport.Mode.TEST_INSECURE,null)){assertNotNull(firewall);}
    }
    @Test void originsCannotContainUserInfoPathsQueriesOrFragments(){
        for(String url:List.of("http://192.168.1.179","https://u:p@192.168.1.179","https://192.168.1.179/x","https://192.168.1.179?x=1","https://192.168.1.179#x"))failure("INVALID_HTTPS_ORIGIN",()->new DeviceHttpsTransport(URI.create(url),DeviceHttpsTransport.Mode.SYSTEM,null));
    }
    @Test void missingCaDoesNotFallBackToInsecure(){
        failure("INVALID_CA_CONFIGURATION",()->new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.CA_FILE,null));
        failure("INVALID_CA_FILE",()->new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.CA_FILE,Path.of("relative.pem")));
        failure("INVALID_CA_CONFIGURATION",()->new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.TEST_INSECURE,Path.of("relative.pem")));
    }
    @Test void requestValidationRejectsTargetEscapesBeforeIo(){
        try(var t=new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.TEST_INSECURE,null)){
            for(String path:List.of("//example.com/x","https://example.com/x","/x#fragment"))failure("ORIGIN_MISMATCH",()->t.request("GET",path,Map.of(),new byte[0]));
            assertEquals("192.168.1.179",t.request("GET","/janus/public-key",Map.of(),new byte[0]).url().host());
        }
    }
    @Test void onlyAuthenticationLifecycleCanPostAndHeadersCannotInject(){
        try(var t=new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.SYSTEM,null)){
            for(String method:List.of("PUT","DELETE","PATCH","POST"))failure("WRITE_METHOD_NOT_ALLOWED",()->t.request(method,"/janus/20180725/servers",Map.of(),new byte[0]));
            failure("HEADER_NOT_ALLOWED",()->t.request("GET","/janus/public-key",Map.of("Authorization","token\r\nInjected:1"),new byte[0]));
            failure("HEADER_NOT_ALLOWED",()->t.request("GET","/janus/public-key",Map.of("Host","other"),new byte[0]));
            assertEquals("POST",t.request("POST","/janus/authenticate",Map.of(),new byte[]{'{','}'}).method());
            assertTrue(t.request("POST","/janus/authenticate",Map.of(),new byte[]{'{','}'}).body().isOneShot());
        }
    }
    @Test void bodyBoundsAndGetBodiesFailClosed()throws Exception{
        assertEquals(DeviceHttpsTransport.MAX_BODY,DeviceHttpsTransport.readBounded(new ByteArrayInputStream(new byte[DeviceHttpsTransport.MAX_BODY])).length);
        failure("RESPONSE_TOO_LARGE",()->DeviceHttpsTransport.readBounded(new ByteArrayInputStream(new byte[DeviceHttpsTransport.MAX_BODY+1])));
        try(var t=new DeviceHttpsTransport(SCP,DeviceHttpsTransport.Mode.SYSTEM,null)){failure("INVALID_REQUEST_BODY",()->t.request("GET","/janus/public-key",Map.of(),new byte[]{1}));}
    }
    @Test void responseToStringNeverIncludesBody(){assertFalse(new DeviceHttpsTransport.Response(200,"application/json","PRIVATE_SOURCE_SECRET".getBytes()).toString().contains("PRIVATE"));}
    @Test void serverRetryAfterZeroCannotTriggerAnImplicitReplay(){var response=new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url(SCP.toString()).build()).protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("unavailable").header("Retry-After","0").build();assertNull(DeviceHttpsTransport.withoutImmediateRetry(response).header("Retry-After"));assertEquals(503,DeviceHttpsTransport.withoutImmediateRetry(response).code());}
}
