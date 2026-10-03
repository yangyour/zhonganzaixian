package cn.mop.adapters.monitoring;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated futures, subscribers and request construction only. No HTTP server or provider is contacted. */
class ZabbixVersionTransportTest {
    final URI endpoint=URI.create("http://127.0.0.1:18100/api_jsonrpc.php");
    final Duration timeout=Duration.ofMillis(100);
    byte[] good(){return "{\"jsonrpc\":\"2.0\",\"result\":\"7.0.31\",\"id\":1}".getBytes(StandardCharsets.UTF_8);}
    @SuppressWarnings("unchecked") HttpResponse<byte[]> response(int status,List<String> types,byte[] body){
        HttpResponse<byte[]> r=mock(HttpResponse.class);when(r.statusCode()).thenReturn(status);when(r.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",types),(a,b)->true));when(r.body()).thenReturn(body);return r;
    }
    ZabbixVersionTransport probe(ZabbixVersionTransport.Exchange exchange){return new ZabbixVersionTransport(endpoint,timeout,exchange);}
    @Test void fixedRequestHasNoAuthCookieOrArbitraryMethodAndConstructionDoesNotSend(){
        AtomicInteger calls=new AtomicInteger();var transport=probe((request,handler)->{
            calls.incrementAndGet();assertEquals(endpoint,request.uri());assertEquals("POST",request.method());assertEquals(Optional.of(timeout),request.timeout());
            assertEquals(Set.of("accept","content-type"),new TreeSet<>(request.headers().map().keySet().stream().map(String::toLowerCase).toList()));
            assertTrue(handler.apply(null) instanceof ZabbixVersionTransport.BoundedBody);
            return CompletableFuture.completedFuture(response(200,List.of("application/json"),good()));
        });assertEquals(0,calls.get());var result=transport.probe(1);assertEquals(1,calls.get());assertEquals(ZabbixVersionTransport.Status.RESPONSE_RECEIVED,result.status());assertEquals("7.0.31",result.protocol().apiVersion());assertNull(result.monitoringReady());assertFalse(result.credentialsUsed());assertFalse(result.observationsRead());
    }
    @Test void defaultClientNeverFollowsRedirectsUsesCookiesAuthOrAmbientProxy(){var client=ZabbixVersionTransport.newClient(timeout);assertEquals(HttpClient.Redirect.NEVER,client.followRedirects());assertTrue(client.cookieHandler().isEmpty());assertTrue(client.authenticator().isEmpty());assertEquals(List.of(Proxy.NO_PROXY),client.proxy().orElseThrow().select(endpoint));assertEquals("HTTPS",client.sslParameters().getEndpointIdentificationAlgorithm());assertEquals(Optional.of(timeout),client.connectTimeout());}
    @Test void endpointValidationPrecedesAnyExchange(){AtomicInteger calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->new ZabbixVersionTransport(URI.create("http://remote.invalid:80/api_jsonrpc.php"),timeout,(r,h)->{calls.incrementAndGet();return null;}));assertEquals(0,calls.get());}
    @Test void timeoutMustBeExplicitBoundedWholeMilliseconds(){for(Duration value:Arrays.asList(null,Duration.ZERO,Duration.ofMillis(-1),Duration.ofSeconds(31),Duration.ofNanos(1_500_000)))assertThrows(IllegalArgumentException.class,()->new ZabbixVersionTransport(endpoint,value,(r,h)->null));}
    @Test void invalidRequestIdNeverCallsExchange(){AtomicInteger calls=new AtomicInteger();assertThrows(IllegalArgumentException.class,()->probe((r,h)->{calls.incrementAndGet();return null;}).probe(0));assertEquals(0,calls.get());}
    @Test void incompleteBodyIsBoundedByOverallDeadlineAndCancelledWithoutRetry(){AtomicInteger calls=new AtomicInteger();var future=new CompletableFuture<HttpResponse<byte[]>>();var result=new ZabbixVersionTransport(endpoint,Duration.ofMillis(10),(r,h)->{calls.incrementAndGet();return future;}).probe(1);assertEquals(ZabbixVersionTransport.Status.TIMEOUT,result.status());assertTrue(future.isCancelled());assertEquals(1,calls.get());assertNull(result.protocol());}
    @Test void synchronousAndFutureFailuresNeverLeakExceptionDetails(){for(boolean sync:List.of(true,false)){var result=probe((r,h)->{var error=new IllegalStateException("SYNTHETIC_SECRET");if(sync)throw error;return CompletableFuture.failedFuture(error);}).probe(1);assertEquals(ZabbixVersionTransport.Status.TRANSPORT_ERROR,result.status());assertFalse(result.toString().contains("SYNTHETIC"));}}
    @Test void clientTimeoutClassificationIsPreserved(){assertEquals(ZabbixVersionTransport.Status.TIMEOUT,probe((r,h)->CompletableFuture.failedFuture(new HttpTimeoutException("SYNTHETIC"))).probe(1).status());}
    @Test void interruptionBeforeSendPreservedWithoutRequest(){AtomicInteger calls=new AtomicInteger();try{Thread.currentThread().interrupt();assertEquals(ZabbixVersionTransport.Status.INTERRUPTED,probe((r,h)->{calls.incrementAndGet();return null;}).probe(1).status());assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,calls.get());}finally{Thread.interrupted();}}
    @Test void interruptionDuringWaitCancelsAndPreservesFlag(){var future=new CompletableFuture<HttpResponse<byte[]>>();try{assertEquals(ZabbixVersionTransport.Status.INTERRUPTED,probe((r,h)->{Thread.currentThread().interrupt();return future;}).probe(1).status());assertTrue(future.isCancelled());assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
    @Test void redirectsRemainHttpErrorsWithoutRetry(){AtomicInteger calls=new AtomicInteger();var result=probe((r,h)->{calls.incrementAndGet();return CompletableFuture.completedFuture(response(302,List.of("application/json"),good()));}).probe(1);assertEquals(1,calls.get());assertEquals(ZabbixVersionProtocol.Outcome.HTTP_ERROR,result.protocol().outcome());assertNull(result.monitoringReady());}
    @Test void multipleContentTypesCannotSmuggleSuccess(){assertEquals(ZabbixVersionProtocol.Outcome.PROTOCOL_ERROR,probe((r,h)->CompletableFuture.completedFuture(response(200,List.of("application/json","text/html"),good()))).probe(1).protocol().outcome());}
    @Test void subscriberAcceptsExactLimitInMultipleChunksAndDoesNotMutateAfterComplete()throws Exception{var s=new ZabbixVersionTransport.BoundedBody();var subscription=mock(Flow.Subscription.class);s.onSubscribe(subscription);s.onNext(List.of(ByteBuffer.wrap(new byte[8000])));s.onNext(List.of(ByteBuffer.wrap(new byte[8384])));s.onComplete();assertEquals(16384,s.getBody().toCompletableFuture().get().length);s.onNext(List.of(ByteBuffer.wrap(new byte[1])));s.onComplete();assertEquals(16384,s.getBody().toCompletableFuture().get().length);verify(subscription,times(3)).request(1);verify(subscription,never()).cancel();}
    @Test void subscriberCancelsOversizeChunkedBodyBeforeCopyingOverflow(){var s=new ZabbixVersionTransport.BoundedBody();var subscription=mock(Flow.Subscription.class);s.onSubscribe(subscription);s.onNext(List.of(ByteBuffer.wrap(new byte[16384])));ByteBuffer excess=ByteBuffer.wrap(new byte[1]);s.onNext(List.of(excess));assertEquals(0,excess.position());var failure=assertThrows(CompletionException.class,()->s.getBody().toCompletableFuture().join());assertInstanceOf(ZabbixVersionTransport.TooLarge.class,failure.getCause());verify(subscription).cancel();s.onComplete();assertTrue(s.getBody().toCompletableFuture().isCompletedExceptionally());}
    @Test void entireIncomingBatchCheckedBeforeAnyBufferCopy(){var s=new ZabbixVersionTransport.BoundedBody();ByteBuffer first=ByteBuffer.wrap(new byte[16000]);s.onNext(List.of(first,ByteBuffer.wrap(new byte[385])));assertEquals(0,first.position());assertTrue(s.getBody().toCompletableFuture().isCompletedExceptionally());}
    @Test void duplicateSubscriptionAndSubscriberCancellationStopUpstream(){var s=new ZabbixVersionTransport.BoundedBody();var first=mock(Flow.Subscription.class);var second=mock(Flow.Subscription.class);s.onSubscribe(first);s.onSubscribe(second);verify(second).cancel();s.getBody().toCompletableFuture().cancel(true);verify(first).cancel();s.onNext(List.of(ByteBuffer.wrap(good())));assertTrue(s.getBody().toCompletableFuture().isCancelled());}
    @Test void cancelledBeforeSubscriptionRejectsLateSubscription(){var s=new ZabbixVersionTransport.BoundedBody();s.getBody().toCompletableFuture().cancel(true);var next=mock(Flow.Subscription.class);s.onSubscribe(next);verify(next).cancel();verify(next,never()).request(anyLong());}
    @Test void upstreamErrorNeverBecomesPartialResponse(){var s=new ZabbixVersionTransport.BoundedBody();s.onNext(List.of(ByteBuffer.wrap(good())));s.onError(new IllegalStateException("SYNTHETIC"));s.onComplete();assertTrue(s.getBody().toCompletableFuture().isCompletedExceptionally());}
    @Test void streamingOverflowHasItsOwnSafeClassification(){var result=probe((r,h)->CompletableFuture.failedFuture(new ZabbixVersionTransport.TooLarge())).probe(1);assertEquals(ZabbixVersionTransport.Status.RESPONSE_TOO_LARGE,result.status());assertNull(result.protocol());assertNull(result.monitoringReady());}
    @Test void resultCannotClaimAResponseWithoutProtocol(){assertThrows(IllegalArgumentException.class,()->new ZabbixVersionTransport.Result(ZabbixVersionTransport.Status.RESPONSE_RECEIVED,null));assertThrows(IllegalArgumentException.class,()->new ZabbixVersionTransport.Result(ZabbixVersionTransport.Status.TIMEOUT,ZabbixVersionProtocol.decode(200,"application/json",good(),1)));}
}
