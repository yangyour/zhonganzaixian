package cn.mop.adapters.monitoring;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Flow;

/** Fixed read methods, bounded authenticated loopback transport. No provider error text escapes. */
public final class ZabbixReadClient {
    public static final class ReadFailure extends IllegalStateException {
        public final String reason;
        ReadFailure(String reason){super("Zabbix read unavailable: "+reason);this.reason=reason;}
    }
    public static final int MAX_BYTES=2*1024*1024;
    private static final Set<String> METHODS=Set.of("host.get","item.get","history.get");
    private final URI endpoint;
    private final String token;
    private final HttpClient client;
    private final AtomicLong sequence=new AtomicLong();
    public static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(8192).maxNumberLength(100).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ZabbixReadClient(String endpoint,String token){
        this.endpoint=ZabbixVersionProtocol.requireLoopbackEndpoint(URI.create(endpoint));
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid private reader token");
        this.token=token;this.client=ZabbixVersionTransport.newClient(Duration.ofSeconds(5));
    }
    public JsonNode call(String method,Map<String,Object> params){
        if(!METHODS.contains(method))throw new IllegalArgumentException("Read method not allowed");
        long id=sequence.incrementAndGet();CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            byte[] body=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params));
            HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending=client.sendAsync(request,ignored->new LimitedBody());
            HttpResponse<byte[]> response=pending.get(10,TimeUnit.SECONDS);
            return decode(response.statusCode(),response.headers().firstValue("Content-Type").orElse(""),response.body(),id);
        }catch(ReadFailure failure){throw failure;}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failure){throw unavailable();}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    public static JsonNode decode(int status,String contentType,byte[] bytes,long id){
        try{
            if(status!=200)throw new ReadFailure("HTTP_STATUS_REJECTED");
            if(!contentType.toLowerCase(Locale.ROOT).matches("application/json(?:-rpc)?(?:;.*)?"))throw new ReadFailure("CONTENT_TYPE_REJECTED");
            if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new ReadFailure("RESPONSE_SIZE_REJECTED");
            JsonNode root=JSON.readTree(bytes);
            if(root.has("error"))throw new ReadFailure("PROVIDER_API_REJECTED");
            if(!root.isObject()||root.size()!=3||(!root.path("jsonrpc").isTextual()||!root.path("jsonrpc").textValue().equals("2.0"))
                ||!root.path("id").isIntegralNumber()||!root.path("id").canConvertToLong()||root.path("id").longValue()!=id||!root.path("result").isArray())throw new ReadFailure("ENVELOPE_REJECTED");
            return root.get("result");
        }catch(ReadFailure failure){throw failure;}catch(Exception failure){throw new ReadFailure("JSON_REJECTED");}
    }
    private static ReadFailure unavailable(){return new ReadFailure("TRANSPORT_FAILED");}
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public synchronized void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> chunks){
            for(ByteBuffer chunk:chunks){
                if(chunk.remaining()>MAX_BYTES-bytes.size()){subscription.cancel();bytes.reset();result.completeExceptionally(unavailable());return;}
                byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public synchronized void onError(Throwable failure){bytes.reset();result.completeExceptionally(unavailable());}
        public synchronized void onComplete(){result.complete(bytes.toByteArray());bytes.reset();}
    }
}
