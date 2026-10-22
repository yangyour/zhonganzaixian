package cn.mop.api;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLParameters;

/** Shared monitoring transport: no redirects, ambient proxy, unbounded body or TLS bypass. */
final class MonitoringHttpTransport {
    private MonitoringHttpTransport() {}
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static class Failure extends RuntimeException {
        final String reason;
        Failure(String reason) { super(reason); this.reason = reason; }
    }
    static HttpClient client() {
        var tls = new SSLParameters(); tls.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().sslParameters(tls).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
            }).build();
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public synchronized void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public synchronized void onNext(List<ByteBuffer> chunks) {
            if (body.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); bytes.reset(); body.completeExceptionally(new Failure("RESPONSE_TOO_LARGE")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public synchronized void onError(Throwable error) { bytes.reset(); body.completeExceptionally(new Failure("CONNECTION_FAILED")); }
        public synchronized void onComplete() { body.complete(bytes.toByteArray()); bytes.reset(); }
    }
}
