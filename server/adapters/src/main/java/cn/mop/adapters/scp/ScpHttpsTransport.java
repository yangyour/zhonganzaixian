package cn.mop.adapters.scp;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.net.ssl.*;

/** CA/hostname-verified HTTPS with fixed origin, no redirects, no proxy, no cookie jar. */
public final class ScpHttpsTransport implements ScpClient.Exchange {
    private final URI origin;private final HttpClient client;
    public ScpHttpsTransport(URI origin,Path caFile){
        this.origin=ScpProtocol.origin(origin.toString());
        try{
            SSLContext tls=SSLContext.getInstance("TLS");TrustManager[] managers=null;
            if(caFile!=null){
                if(!caFile.isAbsolute()||Files.size(caFile)>262144)throw ScpProtocol.fail("INVALID_CA_FILE");
                KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
                try(InputStream input=Files.newInputStream(caFile)){
                    var certificates=CertificateFactory.getInstance("X.509").generateCertificates(input);
                    if(certificates.isEmpty()||certificates.size()>16)throw ScpProtocol.fail("INVALID_CA_FILE");int i=0;
                    for(var cert:certificates)store.setCertificateEntry("scp-ca-"+(i++),cert);
                }
                TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);managers=factory.getTrustManagers();
            }
            tls.init(null,managers,new SecureRandom());SSLParameters ssl=new SSLParameters();ssl.setEndpointIdentificationAlgorithm("HTTPS");
            client=HttpClient.newBuilder().sslContext(tls).sslParameters(ssl).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,IOException failure){}}).build();
        }catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("TLS_CONFIGURATION_FAILED");}
    }
    public ScpClient.Response execute(ScpProtocol.Request request,Map<String,String> headers){
        URI target=origin.resolve(request.target());
        if(!target.getRawAuthority().equals(origin.getRawAuthority())||!target.getScheme().equals("https"))throw ScpProtocol.fail("ORIGIN_MISMATCH");
        if(!headers.keySet().stream().allMatch(Set.of("Authorization","X-Amz-Date","Cookie")::contains))throw ScpProtocol.fail("HEADER_NOT_ALLOWED");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            HttpRequest.Builder builder=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).header("Accept","application/json").header("Content-Type","application/json");
            headers.forEach(builder::header);
            if(request.method().equals("GET"))builder.GET();
            else if(request.method().equals("POST")&&request.path().equals("/janus/authenticate"))builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
            else throw ScpProtocol.fail("WRITE_METHOD_NOT_ALLOWED");
            pending=client.sendAsync(builder.build(),ignored->new LimitedBody());var response=pending.get(10,TimeUnit.SECONDS);
            return new ScpClient.Response(response.statusCode(),response.headers().firstValue("Content-Type").orElse(""),response.body());
        }catch(ScpProtocol.Failure failure){throw failure;}catch(InterruptedException failure){Thread.currentThread().interrupt();throw ScpProtocol.fail("INTERRUPTED");}
        catch(Exception failure){throw ScpProtocol.fail("HTTPS_TRANSPORT_UNAVAILABLE");}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> chunks){if(result.isDone())return;for(ByteBuffer chunk:chunks){if(chunk.remaining()>ScpProtocol.MAX_BODY-bytes.size()){subscription.cancel();bytes.reset();result.completeExceptionally(ScpProtocol.fail("RESPONSE_TOO_LARGE"));return;}byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);}subscription.request(1);}
        public synchronized void onError(Throwable error){bytes.reset();result.completeExceptionally(ScpProtocol.fail("HTTPS_TRANSPORT_UNAVAILABLE"));}
        public synchronized void onComplete(){result.complete(bytes.toByteArray());bytes.reset();}
    }
}
