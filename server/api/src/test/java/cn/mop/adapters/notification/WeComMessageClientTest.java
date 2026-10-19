package cn.mop.adapters.notification;

import cn.mop.domain.DomainException;
import cn.mop.domain.notification.ChannelSubmissionResult;
import cn.mop.domain.notification.ChannelSubmissionResult.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WeComMessageClientTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");
    private static final String TOKEN = "synthetic_test_token_never_valid";
    private static final WeComMessageClient.Settings SETTINGS = new WeComMessageClient.Settings(
            TENANT, 42L, "synthetic/wecom/token", true, Duration.ofSeconds(2), Duration.ofSeconds(5));
    private static WeComMessageClient.Command command() {
        return new WeComMessageClient.Command(TENANT, UUID.randomUUID(), UUID.randomUUID(), "Synthetic.User", "工单已建立\n合成测试");
    }
    private static WeComMessageClient client(WeComMessageClient.Exchange exchange) {
        return new WeComMessageClient(SETTINGS, (t,a,r) -> TOKEN, exchange, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static WeComMessageClient.Response response(int status, String body) {
        return new WeComMessageClient.Response(status, body.getBytes(StandardCharsets.UTF_8));
    }
    private static ChannelSubmissionResult parse(String body) { return client((t,b) -> response(200,body)).submit(command()); }

    @Test void encodesExactlyOneRecipientAndObservesAcceptanceOnly() {
        AtomicInteger calls = new AtomicInteger();
        var result = client((token, payload) -> {
            calls.incrementAndGet(); assertEquals(TOKEN, token);
            var json = new ObjectMapper().readTree(payload);
            assertEquals("Synthetic.User", json.get("touser").textValue());
            assertEquals("text", json.get("msgtype").textValue()); assertEquals(42, json.get("agentid").intValue());
            assertEquals("工单已建立\n合成测试", json.path("text").path("content").textValue());
            assertEquals(4, json.size());
            return response(200, "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":\"private_message_id\"}");
        }).submit(command());
        assertEquals(1,calls.get()); assertEquals(Outcome.ACCEPTED,result.outcome()); assertEquals(NOW,result.observedAt());
        assertFalse(result.toString().contains("private_message_id"));
    }
    @ParameterizedTest @ValueSource(strings={"{\"errcode\":40014}","{\"errcode\":42001}","{\"errcode\":45009}","{\"errcode\":-1}"})
    void explicitProviderRejectionsNeverRetry(String body) {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(Outcome.REJECTED,client((t,b)->{calls.incrementAndGet();return response(200,body);}).submit(command()).outcome());
        assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"invaliduser", "unlicenseduser"})
    void successEnvelopeWithRejectedRecipientIsFailure(String field) {
        assertEquals(Outcome.REJECTED,parse("{\"errcode\":0,\""+field+"\":\"synthetic.user\"}").outcome());
    }
    @ParameterizedTest @ValueSource(strings={"", "null", "[]", "{}", "{\"errcode\":\"0\"}", "{\"errcode\":0.0}",
            "{\"errcode\":2147483648}", "{\"errcode\":0,\"errcode\":1}", "{\"errcode\":0} {}",
            "{\"errcode\":0,\"invaliduser\":null}", "{\"errcode\":0,\"invaliduser\":\"other\"}",
            "{\"errcode\":0,\"invalidparty\":\"1\"}", "<html>error</html>"})
    void ambiguousBodiesRemainUnknown(String body) { assertEquals(Outcome.UNKNOWN,parse(body).outcome()); }
    @ParameterizedTest @ValueSource(ints={201,204,301,302,400,401,403,429,500,503})
    void non200NeverClaimsRejectionOrAcceptance(int status) {
        assertEquals(Outcome.UNKNOWN,client((t,b)->response(status,"{\"errcode\":0}")).submit(command()).outcome());
    }
    @Test void transportFailureCannotEscapeWithSecretOrTriggerRetry() {
        AtomicInteger calls = new AtomicInteger();
        var result=client((t,b)->{calls.incrementAndGet();throw new java.io.IOException("secret="+TOKEN);}).submit(command());
        assertEquals(Outcome.UNKNOWN,result.outcome());assertFalse(result.toString().contains(TOKEN));assertEquals(1,calls.get());
    }
    @Test void interruptionPreservesThreadFlagAndUnknown() {
        try { assertEquals(Outcome.UNKNOWN,client((t,b)->{throw new InterruptedException(TOKEN);}).submit(command()).outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void missingConfigurationDoesNotResolveTokenOrSend() {
        var settings=new WeComMessageClient.Settings(TENANT,null,null,null,null,null);
        var c=new WeComMessageClient(settings,(t,a,r)->{fail("Must not resolve token");return null;},
                (t,b)->{fail("Must not send");return null;},Clock.fixed(NOW,ZoneOffset.UTC));
        assertEquals(Outcome.NOT_SUBMITTED,c.submit(command()).outcome());
    }
    @ParameterizedTest @ValueSource(strings={"", "a&injected=1", "private\nsecret", "a/b", "a?b"})
    void invalidTokenDoesNotSend(String token) {
        var c=new WeComMessageClient(SETTINGS,(t,a,r)->token,(t,b)->{fail("Must not send");return null;},Clock.systemUTC());
        assertEquals(Outcome.NOT_SUBMITTED,c.submit(command()).outcome());
    }
    @Test void tokenResolverFailureIsSanitizedAndScoped() {
        var c=new WeComMessageClient(SETTINGS,(t,a,r)->{assertEquals(TENANT,t);assertEquals(42,a);assertEquals(SETTINGS.tokenReference(),r);
            throw new IllegalStateException(TOKEN);},(t,b)->{fail("Must not send");return null;},Clock.systemUTC());
        var result=c.submit(command());assertEquals(Outcome.NOT_SUBMITTED,result.outcome());assertFalse(result.toString().contains(TOKEN));
    }
    @Test void crossTenantIs404BeforeTokenOrTransport() {
        var c=new WeComMessageClient(SETTINGS,(t,a,r)->{fail("Must not resolve");return null;},(t,b)->{fail("Must not send");return null;},Clock.systemUTC());
        var cmd=new WeComMessageClient.Command(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Synthetic","text");
        assertEquals(404,assertThrows(DomainException.class,()->c.submit(cmd)).status);
    }
    @ParameterizedTest @ValueSource(strings={"@all","@ALL","a|b"," a","a\nb","https://evil",""})
    void rejectsBroadcastOrExpandedRecipients(String user) {
        assertThrows(IllegalArgumentException.class,()->new WeComMessageClient.Command(TENANT,UUID.randomUUID(),UUID.randomUUID(),user,"text"));
    }
    @Test void utf8LimitIsBytesNotCharactersAndNoSilentTruncation() {
        assertDoesNotThrow(()->new WeComMessageClient.Command(TENANT,UUID.randomUUID(),UUID.randomUUID(),"Synthetic","a".repeat(2048)));
        assertThrows(IllegalArgumentException.class,()->new WeComMessageClient.Command(TENANT,UUID.randomUUID(),UUID.randomUUID(),"Synthetic","中".repeat(683)));
        assertThrows(IllegalArgumentException.class,()->new WeComMessageClient.Command(TENANT,UUID.randomUUID(),UUID.randomUUID(),"Synthetic","bad\uD800"));
    }
    @Test void diagnosticsAndResponseCopiesAreRedacted() {
        byte[] bytes="secret".getBytes(StandardCharsets.UTF_8);var r=new WeComMessageClient.Response(200,bytes);bytes[0]=0;
        assertEquals('s',r.body()[0]);byte[] copy=r.body();copy[0]=0;assertEquals('s',r.body()[0]);
        assertFalse(r.toString().contains("secret"));assertFalse(command().toString().contains("Synthetic"));
        assertFalse(SETTINGS.toString().contains(SETTINGS.tokenReference()));assertFalse(client((t,b)->null).toString().contains(TOKEN));
    }
    @Test void oversizedAndNullResponsesAreUnknown() {
        assertEquals(Outcome.UNKNOWN,client((t,b)->new WeComMessageClient.Response(200,new byte[65537])).submit(command()).outcome());
        assertEquals(Outcome.UNKNOWN,client((t,b)->null).submit(command()).outcome());
    }
    @Test void boundedSubscriberCancelsWithoutExposingOriginalFailure() {
        AtomicInteger cancels=new AtomicInteger();var sub=new WeComHttpsTransport.BoundedBody();
        sub.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancels.incrementAndGet();}});
        sub.onNext(List.of(ByteBuffer.wrap(new byte[65536])));sub.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertEquals(1,cancels.get());assertTrue(sub.getBody().toCompletableFuture().isCompletedExceptionally());
        var other=new WeComHttpsTransport.BoundedBody();other.onError(new IllegalStateException(TOKEN));
        var e=assertThrows(Exception.class,()->other.getBody().toCompletableFuture().join());assertFalse(e.toString().contains(TOKEN));
    }
    @Test void boundedSubscriberAcceptsExactLimitAcrossChunks() {
        var sub=new WeComHttpsTransport.BoundedBody();sub.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){fail();}});
        sub.onNext(List.of(ByteBuffer.wrap(new byte[32768]),ByteBuffer.wrap(new byte[32768])));sub.onComplete();
        assertEquals(65536,sub.getBody().toCompletableFuture().join().length);
    }
    @Test void settingsRejectUnboundedTimeoutAndInvalidAgent() {
        assertThrows(IllegalArgumentException.class,()->new WeComMessageClient.Settings(TENANT,0L,"ref",true,Duration.ofSeconds(1),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new WeComMessageClient.Settings(TENANT,1L,"ref",true,Duration.ZERO,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new WeComMessageClient.Settings(TENANT,1L,"ref",true,Duration.ofSeconds(1),Duration.ofMinutes(3)));
    }
}
