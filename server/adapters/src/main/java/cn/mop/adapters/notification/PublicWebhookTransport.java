package cn.mop.adapters.notification;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** Restricted one-shot HTTP transport. Not a provider adapter, signer, dispatch gate or delivery receipt. */
public final class PublicWebhookTransport {
    public static final int MAX_BODY=65536, MAX_RESPONSE=65536;
    public static final class Failure extends RuntimeException {
        public final String reason;
        public Failure(String reason){super("Webhook transport: "+reason);this.reason=reason;}
    }
    public record Observation(int httpStatus,byte[] body) {
        public Observation{body=body.clone();}
        public byte[] body(){return body.clone();}
        public boolean delivered(){return false;}
        @Override public String toString(){return "WebhookObservation[httpStatus="+httpStatus+", body redacted]";}
    }
    private static final ExecutorService DNS_EXECUTOR=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"webhook-dns");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final long dnsTimeoutMillis;
    private final URI endpoint;
    private final Dns resolver;
    public PublicWebhookTransport(URI endpoint,Set<String> approvedDomains){this(endpoint,approvedDomains,Dns.SYSTEM);}
    /** Fake DNS is package-private and only used by isolated tests; runtime always resolves normally. */
    PublicWebhookTransport(URI endpoint,Set<String> approvedDomains,Dns resolver){
        this(endpoint,approvedDomains,resolver,5000);
    }
    PublicWebhookTransport(URI endpoint,Set<String> approvedDomains,Dns resolver,long dnsTimeoutMillis){
        if(dnsTimeoutMillis<1||dnsTimeoutMillis>5000)throw fail("INVALID_DNS_TIMEOUT");
        this.endpoint=validate(endpoint,approvedDomains);this.resolver=Objects.requireNonNull(resolver);this.dnsTimeoutMillis=dnsTimeoutMillis;
    }
    static URI validate(URI uri,Set<String> allowlist){
        if(uri==null||allowlist==null||allowlist.isEmpty()||allowlist.size()>100||!"https".equals(uri.getScheme())||
                uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||
                uri.getPort()!=-1&&uri.getPort()!=443||uri.toASCIIString().length()>2048)throw fail("ENDPOINT_NOT_APPROVED");
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        if(!domain(host))throw fail("ENDPOINT_NOT_APPROVED");
        for(String allowed:allowlist)if(allowed==null||!domain(allowed)||!allowed.equals(allowed.toLowerCase(Locale.ROOT)))throw fail("INVALID_DOMAIN_ALLOWLIST");
        if(!allowlist.contains(host))throw fail("ENDPOINT_NOT_APPROVED");
        String raw=uri.getRawPath();if(raw==null||raw.contains("\\")||raw.chars().anyMatch(c->c<32||c==127))throw fail("ENDPOINT_NOT_APPROVED");
        return uri;
    }
    private static boolean domain(String value){
        if(value.length()>253||!value.contains(".")||value.matches("[0-9.]+")||value.endsWith("."))return false;
        for(String part:value.split("\\.",-1))if(!part.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))return false;
        return true;
    }
    /** Conservative IPv4-only policy: IPv6 and mixed-family answers are blocked, never downgraded silently. */
    static boolean allowedAddress(InetAddress address){
        if(!(address instanceof Inet4Address))return false;
        byte[] b=address.getAddress();int a=b[0]&255,c=b[1]&255,d=b[2]&255;
        if(a==0||a==10||a==127||a>=224||a==100&&c>=64&&c<=127||a==169&&c==254||a==172&&c>=16&&c<=31)return false;
        if(a==192&&(c==168||c==0&&(d==0||d==2)||c==88&&d==99||c==31&&d==196||c==52&&d==193||c==175&&d==48))return false;
        return !(a==198&&(c==18||c==19||c==51&&d==100)||a==203&&c==0&&d==113);
    }
    List<InetAddress> resolve(){
        Future<List<InetAddress>> lookup=null;
        try{
            lookup=DNS_EXECUTOR.submit(()->resolver.lookup(endpoint.getHost().toLowerCase(Locale.ROOT)));
            List<InetAddress> addresses=lookup.get(dnsTimeoutMillis,TimeUnit.MILLISECONDS);
            if(addresses==null||addresses.isEmpty()||addresses.size()>16||addresses.stream().anyMatch(a->a==null||!allowedAddress(a)))throw fail("DNS_TARGET_REJECTED");
            return List.copyOf(addresses);
        }catch(Failure f){throw f;}catch(InterruptedException f){Thread.currentThread().interrupt();throw fail("DNS_INTERRUPTED");}
        catch(Exception f){throw fail("DNS_UNAVAILABLE");}
        finally{if(lookup!=null&&!lookup.isDone())lookup.cancel(true);}
    }
    OkHttpClient client(List<InetAddress> pinned){
        String host=endpoint.getHost().toLowerCase(Locale.ROOT);
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(name->{if(!host.equals(name.toLowerCase(Locale.ROOT)))throw new UnknownHostException("Unexpected destination");return pinned;})
                .connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                .protocols(List.of(Protocol.HTTP_1_1))
                .addNetworkInterceptor(chain->{var response=chain.proceed(chain.request());return response.code()==503?response.newBuilder().removeHeader("Retry-After").build():response;})
                .build();
    }
    Request request(UUID requestId,byte[] body,Map<String,String> signatureHeaders){
        if(requestId==null||body==null||body.length==0||body.length>MAX_BODY||signatureHeaders==null||signatureHeaders.isEmpty()||
                !Set.of("X-Notification-Signature","X-Notification-Timestamp","X-Notification-Key-Version").containsAll(signatureHeaders.keySet())||
                !signatureHeaders.containsKey("X-Notification-Signature")||signatureHeaders.values().stream().anyMatch(v->v==null||v.isBlank()||v.length()>4096||v.chars().anyMatch(c->c<32||c>126)))throw fail("INVALID_SIGNED_REQUEST");
        byte[] immutable=body.clone();
        var b=new Request.Builder().url(endpoint.toString()).header("Accept","application/json").header("Connection","close").header("X-Notification-Request-Id",requestId.toString());
        signatureHeaders.forEach(b::header);
        b.post(new RequestBody(){public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}public long contentLength(){return immutable.length;}public boolean isOneShot(){return true;}public void writeTo(okio.BufferedSink sink)throws IOException{sink.write(immutable);}});
        return b.build();
    }
    /** Caller must already authorize, sign and durably record this exact attempt. Exceptions after connection mean UNKNOWN. */
    public Observation execute(UUID requestId,byte[] body,Map<String,String> signatureHeaders){
        Request request=request(requestId,body,signatureHeaders);
        // Resolve once, validate every answer, then connect only to this immutable list while retaining TLS hostname checks.
        OkHttpClient client=client(resolve());
        try(var response=client.newCall(request).execute()){
            byte[] bytes=response.body()==null?new byte[0]:response.body().byteStream().readNBytes(MAX_RESPONSE+1);
            if(bytes.length>MAX_RESPONSE)throw fail("RESPONSE_TOO_LARGE_OUTCOME_UNKNOWN");
            // Neither HTTP 2xx nor an error is interpreted as a provider receipt or permission to replay.
            return new Observation(response.code(),bytes);
        }catch(Failure f){throw f;}catch(Exception f){throw fail("TRANSPORT_OUTCOME_UNKNOWN");}
        finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
    private static Failure fail(String reason){return new Failure(reason);}
    @Override public String toString(){return "PublicWebhookTransport[configuration redacted]";}
}
