package cn.mop.adapters;

import cn.mop.adapters.wecom.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated protocol/validation tests; these do not stand in for the real provider checks. */
class WeComClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    final WeComCredentials credentials = new WeComCredentials("wwsynthetic12345678", 1000001, "synthetic-secret-not-live");
    static WeComClient.Reply reply(String json) { return new WeComClient.Reply(200, json.getBytes(StandardCharsets.UTF_8)); }
    static WeComClient.Reply token() { return reply("{\"errcode\":0,\"access_token\":\"synthetic-token\",\"expires_in\":7200}"); }
    WeComClient client(WeComClient.Transport transport) { return new WeComClient(credentials, mapper, transport, Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC)); }
    @Test void secretsNeverAppearInRepresentations() { assertFalse(credentials.toString().contains("synthetic-secret")); assertFalse(client((a,b,c,d)->token()).toString().contains("synthetic-secret")); }
    @Test void tokenCacheIsSharedByConcurrentCalls() throws Exception {
        var calls = new AtomicInteger(); var c = client((m,p,q,b)-> { if(p.endsWith("gettoken")){calls.incrementAndGet();return token();}return reply("{\"errcode\":0,\"agentid\":1000001,\"name\":\"Synthetic\",\"close\":0}"); });
        var executor=Executors.newFixedThreadPool(8); try { var jobs=new ArrayList<Future<?>>();for(int i=0;i<20;i++)jobs.add(executor.submit(c::application));for(var job:jobs)job.get(); }finally{executor.shutdownNow();}
        assertEquals(1,calls.get());
    }
    @Test void expiresInControlsRefreshRatherThanFixedTwoHours() {
        var now=new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-19T00:00:00Z"));
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get();}};
        var calls=new AtomicInteger();var c=new WeComClient(credentials,mapper,(m,p,q,b)->{if(p.endsWith("gettoken")){calls.incrementAndGet();return reply("{\"errcode\":0,\"access_token\":\"synthetic\",\"expires_in\":10}");}return reply("{\"errcode\":0,\"userid\":\"Synthetic\",\"status\":1}");},clock);
        c.member("Synthetic");now.set(now.get().plusSeconds(8));c.member("Synthetic");assertEquals(1,calls.get());now.set(now.get().plusSeconds(1));c.member("Synthetic");assertEquals(2,calls.get());
    }
    @Test void rejectedTokenRefreshesOnce() {
        var messages=new AtomicInteger();var tokens=new AtomicInteger();var c=client((m,p,q,b)->{if(p.endsWith("gettoken")){tokens.incrementAndGet();return token();}return messages.getAndIncrement()==0?reply("{\"errcode\":42001}"):reply("{\"errcode\":0,\"msgid\":\"synthetic-message\"}");});
        assertEquals("synthetic-message",c.sendText("Synthetic","test").messageId());assertEquals(2,tokens.get());assertEquals(2,messages.get());
    }
    @Test void sendTimeoutIsUnknownAndIsNeverAutomaticallyResent() {
        var sends=new AtomicInteger();var c=client((m,p,q,b)->{if(p.endsWith("gettoken"))return token();sends.incrementAndGet();throw new IOException("secret URL must not escape");});
        var error=assertThrows(WeComClient.Failure.class,()->c.sendText("Synthetic","test"));assertTrue(error.outcomeUnknown);assertEquals(1,sends.get());assertNull(error.getCause());assertFalse(error.toString().contains("secret URL"));
    }
    @Test void trustedIpFailureIsSpecificAndRedacted() {
        var c=client((m,p,q,b)->p.endsWith("gettoken")?token():reply("{\"errcode\":60020,\"errmsg\":\"sensitive raw diagnostic\"}"));
        var error=assertThrows(WeComClient.Failure.class,c::application);assertEquals("WECOM_TRUSTED_IP_REQUIRED",error.category);assertEquals(60020,error.providerCode);assertFalse(error.outcomeUnknown);assertFalse(error.toString().contains("sensitive"));
    }
    @Test void acceptanceIsNotDeliveryAndOnlyOneExplicitRecipientIsSent() {
        var c=client((m,p,q,b)->{if(p.endsWith("gettoken"))return token();var n=mapper.readTree(b);assertEquals("Synthetic",n.path("touser").asText());assertFalse(n.has("toparty"));assertEquals(1,n.path("enable_duplicate_check").intValue());assertEquals(600,n.path("duplicate_check_interval").intValue());return reply("{\"errcode\":0,\"msgid\":\"synthetic-message\"}");});
        assertEquals("synthetic-message",c.sendText("Synthetic","测试").messageId());
    }
    @Test void invalidRecipientInSuccessEnvelopeIsFailure() {
        var c=client((m,p,q,b)->p.endsWith("gettoken")?token():reply("{\"errcode\":0,\"msgid\":\"synthetic\",\"invaliduser\":\"Synthetic\"}"));
        var e=assertThrows(WeComClient.Failure.class,()->c.sendText("Synthetic","test"));assertEquals("WECOM_RECIPIENT_REJECTED",e.category);assertFalse(e.outcomeUnknown);
    }
    @Test void missingErrcodeAndOversizedResponseNeverPass() {
        for(String value:List.of("{}","{\"errcode\":\"0\"}","[]","{\"errcode\":0}"))assertThrows(WeComClient.Failure.class,()->client((m,p,q,b)->reply(value)).application());
        assertThrows(WeComClient.Failure.class,()->client((m,p,q,b)->new WeComClient.Reply(200,new byte[1_048_577])).application());
    }
    @Test void httpRedirectIsNotFollowedOrReportedAsSuccess() {var c=client((m,p,q,b)->new WeComClient.Reply(302,new byte[0]));assertThrows(WeComClient.Failure.class,c::application);}
    @Test void recipientsAndUtf8LimitsAreValidatedBeforeNetwork() {
        var c=client((m,p,q,b)->{fail("must not call provider");return null;});
        for(String user:List.of("@all","@ALL","one|two","","x\n","x".repeat(65)))assertThrows(IllegalArgumentException.class,()->c.sendText(user,"test"));
        assertThrows(IllegalArgumentException.class,()->c.sendText("Synthetic","中".repeat(683)));
    }
    @Test void identityRequiresEnterpriseUserAndRejectsOtherCorp() {
        for(String json:List.of("{\"errcode\":0,\"openid\":\"external\"}","{\"errcode\":0,\"userid\":\"Synthetic\",\"corpid\":\"other\"}"))
            assertThrows(WeComClient.Failure.class,()->client((m,p,q,b)->p.endsWith("gettoken")?token():reply(json)).identity("synthetic-code"));
        assertEquals("Synthetic",client((m,p,q,b)->p.endsWith("gettoken")?token():reply("{\"errcode\":0,\"userid\":\"Synthetic\"}")).identity("synthetic-code"));
    }
    @Test void proxyCannotExfiltrateToArbitraryHostOrCredentials() {
        for(String uri:List.of("http://example.com:8080","https://127.0.0.1:8080","http://user:pass@127.0.0.1:8080","http://127.0.0.1:8080/path","http://127.0.0.1:8080?token=x"))
            assertThrows(IllegalArgumentException.class,()->new WeComClient(credentials,mapper,URI.create(uri)));
    }
}
