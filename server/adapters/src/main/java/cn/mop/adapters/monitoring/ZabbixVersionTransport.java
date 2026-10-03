package cn.mop.adapters.monitoring;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLParameters;

/** Explicit, one-shot version probe. Construction never starts a request; no application wiring. */
public final class ZabbixVersionTransport {
    public enum Status { RESPONSE_RECEIVED, TIMEOUT, TRANSPORT_ERROR, RESPONSE_TOO_LARGE, INTERRUPTED }
    public record Result(Status status, ZabbixVersionProtocol.Result protocol) {
        public Result {
            if(status==null || (status==Status.RESPONSE_RECEIVED)!=(protocol!=null))
                throw new IllegalArgumentException("Invalid version transport result");
        }
        public Boolean monitoringReady(){return null;}
        public boolean credentialsUsed(){return false;}
        public boolean observationsRead(){return false;}
    }
    @FunctionalInterface interface Exchange {
        CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request,HttpResponse.BodyHandler<byte[]> handler);
    }
    private final URI endpoint;
    private final Duration timeout;
    private final Exchange exchange;

    /** Caller must separately establish approval for this exact private test target. */
    public static ZabbixVersionTransport configured(URI endpoint,Duration timeout) {
        ZabbixVersionProtocol.requireLoopbackEndpoint(endpoint); requireTimeout(timeout);
        HttpClient client=newClient(timeout);
        return new ZabbixVersionTransport(endpoint,timeout,client::sendAsync);
    }
    ZabbixVersionTransport(URI endpoint,Duration timeout,Exchange exchange) {
        this.endpoint=ZabbixVersionProtocol.requireLoopbackEndpoint(endpoint);
        this.timeout=requireTimeout(timeout);this.exchange=Objects.requireNonNull(exchange);
    }
    static HttpClient newClient(Duration timeout) {
        requireTimeout(timeout);
        SSLParameters ssl=new SSLParameters();ssl.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1).sslParameters(ssl).proxy(new ProxySelector(){
                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,IOException failure){}
            }).build(); // No authenticator or cookie handler; normal JVM trust validation remains enabled.
    }
    private static Duration requireTimeout(Duration value){
        if(value==null||value.compareTo(Duration.ofMillis(1))<0||value.compareTo(Duration.ofSeconds(30))>0||value.getNano()%1_000_000!=0)
            throw new IllegalArgumentException("Version probe timeout must be 1..30000 whole milliseconds");
        return value;
    }
    public Result probe(long requestId) {
        byte[] body=ZabbixVersionProtocol.request(requestId); // Invalid caller input fails before transport.
        if(Thread.currentThread().isInterrupted())return result(Status.INTERRUPTED);
        HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Accept","application/json").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=Objects.requireNonNull(exchange.send(request,ignored->new BoundedBody()));
            HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            String type=String.join(",",response.headers().allValues("Content-Type"));
            return new Result(Status.RESPONSE_RECEIVED,ZabbixVersionProtocol.decode(response.statusCode(),type,response.body(),requestId));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return result(Status.INTERRUPTED);
        }catch(TimeoutException timeoutFailure){return result(Status.TIMEOUT);
        }catch(Exception failure){
            Throwable cause=failure;for(int i=0;i<8&&cause.getCause()!=null;i++)cause=cause.getCause();
            return result(cause instanceof TooLarge?Status.RESPONSE_TOO_LARGE:cause instanceof HttpTimeoutException?Status.TIMEOUT:Status.TRANSPORT_ERROR);
        }finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private static Result result(Status status){return new Result(status,null);}
    static final class TooLarge extends IOException {TooLarge(){super("Version response exceeds byte limit");}}
    /** Enforces the byte limit while streaming, including chunked bodies without Content-Length. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        BoundedBody(){body.whenComplete((ignored,failure)->{if(body.isCancelled())cancel();});}
        public CompletionStage<byte[]> getBody(){return body;}
        public synchronized void onSubscribe(Flow.Subscription next){
            if(subscription!=null||body.isDone()){next.cancel();return;}subscription=next;next.request(1);
        }
        public synchronized void onNext(List<ByteBuffer> chunks){
            if(body.isDone())return;
            try{
                long incoming=0;for(ByteBuffer chunk:chunks){incoming+=chunk.remaining();if(incoming>ZabbixVersionProtocol.MAX_RESPONSE_BYTES-bytes.size())throw new TooLarge();}
                for(ByteBuffer chunk:chunks){byte[] copy=new byte[chunk.remaining()];chunk.get(copy);bytes.writeBytes(copy);}
                if(subscription!=null)subscription.request(1);
            }catch(Exception failure){fail(failure);}
        }
        public synchronized void onError(Throwable failure){fail(failure);}
        public synchronized void onComplete(){if(!body.isDone()){byte[] result=bytes.toByteArray();bytes.reset();body.complete(result);}}
        private void fail(Throwable failure){if(!body.isDone()){cancel();body.completeExceptionally(failure);}}
        private synchronized void cancel(){if(subscription!=null)subscription.cancel();bytes.reset();}
    }
}
