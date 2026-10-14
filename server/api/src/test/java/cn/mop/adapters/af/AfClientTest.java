package cn.mop.adapters.af;

import cn.mop.adapters.http.DeviceHttpsTransport;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AfClientTest {
    static DeviceHttpsTransport.Response result(int code,Object data){try{return new DeviceHttpsTransport.Response(200,"application/json",AfClient.JSON.writeValueAsBytes(Map.of("code",code,"message","PRIVATE_SOURCE_TEXT","data",data)));}catch(Exception e){throw new AssertionError(e);}}
    static void failure(String reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrows(AfClient.Failure.class,action).reason);}
    static class Exchange implements AfClient.Exchange {
        List<String> calls=new ArrayList<>();List<Map<String,String>> headers=new ArrayList<>();int versionCode=0,loginCode=0;boolean logoutFail=false;
        public DeviceHttpsTransport.Response execute(String method,String path,Map<String,String> headers,byte[] body){
            calls.add(method+" "+path);this.headers.add(headers);
            if(path.endsWith("/login"))return result(loginCode,Map.of("loginResult",Map.of("token","SYNTHETIC_SESSION")));
            assertEquals("token=SYNTHETIC_SESSION",headers.get("Cookie"));
            if(path.endsWith("/logout"))return result(logoutFail?13:0,Map.of());
            if(path.endsWith("/systemversion"))return result(versionCode,Map.of("full","AF-synthetic"));
            if(path.endsWith("/cpuusage"))return result(0,Map.of("cpuCurrent","14.7100"));
            if(path.endsWith("/memoryusage"))return result(0,Map.of("memoryUsage",25));
            throw new AssertionError("Unexpected path");
        }
    }
    static AfClient client(Exchange e){return new AfClient("public","SYNTHETIC_USER","SYNTHETIC_PASSWORD",e);}
    @Test void successfulReadReusesLoginAndLogsOut(){
        Exchange e=new Exchange();var c=client(e);assertTrue(e.calls.isEmpty());assertEquals("AF-synthetic",c.version().get("full"));
        assertEquals(Map.of("cpu_current_percent","14.7100","memory_percent","25"),c.cpuAndMemory());assertTrue(c.authenticated());c.close();assertTrue(c.logoutSucceeded());assertEquals(5,e.calls.size());assertEquals(1,e.calls.stream().filter(x->x.endsWith("/login")).count());
    }
    @Test void expiredPasswordIsNotEmptyMetricsAndIsNotRetried(){
        Exchange e=new Exchange();e.versionCode=1010;var c=client(e);failure("PASSWORD_EXPIRED",c::version);assertTrue(c.authenticated());failure("PASSWORD_EXPIRED",c::cpuAndMemory);assertEquals(2,e.calls.size());c.close();assertTrue(c.logoutSucceeded());assertEquals(3,e.calls.size());
    }
    @Test void failedLoginNeverReplaysOrAttemptsLogout(){
        Exchange e=new Exchange();e.loginCode=1;var c=client(e);failure("AUTHENTICATION_REJECTED",c::version);failure("AUTHENTICATION_REJECTED",c::version);c.close();assertFalse(c.authenticated());assertEquals(1,e.calls.size());
    }
    @Test void failureDuringLogoutDoesNotChangeOriginalBlocker(){Exchange e=new Exchange();e.versionCode=1010;e.logoutFail=true;var c=client(e);failure("PASSWORD_EXPIRED",c::version);assertDoesNotThrow(c::close);assertFalse(c.logoutSucceeded());}
    @Test void nestedSuccessEnvelopeDoesNotHidePermissionFailure(){failure("ACCESS_DENIED",()->AfClient.decode(result(0,Map.of("code",13,"data","","message","SECRET"))));}
    @Test void providerAndHttpFailuresHaveStableRedactedReasons(){
        for(int code:List.of(13,1003,1010,1012,1002,22)){var e=assertThrows(AfClient.Failure.class,()->AfClient.decode(result(code,Map.of())));assertFalse(e.toString().contains("PRIVATE"));}
        failure("ACCESS_DENIED",()->AfClient.decode(new DeviceHttpsTransport.Response(403,"text/html","PRIVATE".getBytes())));
    }
    @Test void observedMimeTypesStillRequireValidJson(){
        for(String mime:List.of("application/json;charset=UTF-8","application/octet-stream","text/html"))assertTrue(AfClient.decode(new DeviceHttpsTransport.Response(200,mime,result(0,Map.of()).bytes())).isObject());
        failure("INVALID_JSON",()->AfClient.decode(new DeviceHttpsTransport.Response(200,"text/html","<html>login</html>".getBytes())));
    }
    @Test void duplicateTrailingAndInvalidUtf8FailClosed(){
        for(String raw:List.of("{\"code\":0,\"code\":13,\"data\":{}}","{\"code\":0,\"data\":{}} {}"))failure("INVALID_JSON",()->AfClient.decode(new DeviceHttpsTransport.Response(200,"application/json",raw.getBytes(StandardCharsets.UTF_8))));
        failure("INVALID_JSON",()->AfClient.decode(new DeviceHttpsTransport.Response(200,"application/json",new byte[]{(byte)255})));
    }
    @Test void hugeBusinessCodeCannotOverflowIntoSuccess(){failure("INVALID_ENVELOPE",()->AfClient.decode(new DeviceHttpsTransport.Response(200,"application/json","{\"code\":4294967296,\"data\":{}}".getBytes())));}
    @Test void metricUnknownAndInvalidValuesNeverBecomeZero(){
        failure("METRIC_NOT_PROVIDED",()->AfClient.percent(AfClient.JSON.valueToTree(Map.of()),"cpuCurrent",true));
        for(String value:List.of("","NaN","-1","100.1","01","1e2"))failure("INVALID_METRIC",()->AfClient.percent(AfClient.JSON.valueToTree(Map.of("cpuCurrent",value)),"cpuCurrent",true));
        failure("INVALID_METRIC",()->AfClient.percent(AfClient.JSON.valueToTree(Map.of("memoryUsage","25")),"memoryUsage",false));
    }
    @Test void malformedNamespaceCannotReachExchange(){failure("INVALID_PRIVATE_CONFIGURATION",()->new AfClient("../public","user","password",(m,p,h,b)->{throw new AssertionError();}));}
    @Test void tokensCannotInjectHeaders(){var c=new AfClient("public","user","password",(m,p,h,b)->result(0,Map.of("loginResult",Map.of("token","x\r\nInjected:1"))));failure("INVALID_SESSION",c::version);assertFalse(c.authenticated());c.close();}
}
