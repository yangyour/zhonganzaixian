package cn.mop.adapters.notification;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Fixed official origin, default JVM TLS validation, no redirects, cookies, authentication or retry loop. */
public final class WeComHttpsTransport implements WeComMessageClient.Exchange {
    private final HttpClient client;
    private final Duration timeout;

    public WeComHttpsTransport(WeComMessageClient.Settings settings) {
        if (settings == null || !settings.configured()) throw new IllegalArgumentException("WeCom configuration is incomplete");
        timeout = settings.requestTimeout();
        client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    @Override public WeComMessageClient.Response post(String accessToken, byte[] json) throws Exception {
        if (accessToken == null || !accessToken.matches("[A-Za-z0-9_-]{1,4096}"))
            throw new IllegalArgumentException("Token unavailable");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                    "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" + accessToken))
                    .timeout(timeout).header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
            var pending = client.sendAsync(request, info -> new BoundedBody());
            try {
                // Explicit whole-response deadline also covers stalled/slow response bodies.
                HttpResponse<byte[]> response = pending.get(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
                return new WeComMessageClient.Response(response.statusCode(), response.body());
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new InterruptedException("WeCom transport interrupted; outcome unknown");
        } catch (Exception failure) {
            // HttpClient exceptions may contain the URI/token. Never retain their message or cause.
            throw new java.io.IOException("WeCom transport failed; outcome unknown");
        }
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription s) {
            if (subscription != null) { s.cancel(); return; }
            subscription = s; s.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > WeComMessageClient.MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new java.io.IOException("Provider response limit exceeded")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { body.completeExceptionally(new java.io.IOException("Provider response incomplete")); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
    @Override public String toString() { return "WeComHttpsTransport[request details redacted]"; }
}
