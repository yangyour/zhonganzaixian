package cn.mop.adapters.http;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import okhttp3.*;

/** Per-client TLS policy. No JVM trust store, hostname verifier or system property is modified. */
public final class DeviceHttpsTransport implements AutoCloseable {
    public enum Mode {SYSTEM,CA_FILE,TEST_INSECURE}
    public static final int MAX_BODY=2*1024*1024;
    public static final class Failure extends RuntimeException {
        public final String reason;
        public Failure(String reason){super("Device HTTPS: "+reason);this.reason=reason;}
    }
    public record Response(int status,String contentType,byte[] bytes){
        public Response{bytes=bytes.clone();}public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "DeviceResponse[status="+status+", body redacted]";}
    }
    private final URI origin;
    private final Mode mode;
    private final OkHttpClient client;
    public DeviceHttpsTransport(URI origin,Mode mode,Path caFile){
        this.origin=origin(origin);this.mode=Objects.requireNonNull(mode);
        if(mode==Mode.TEST_INSECURE&&!Set.of("https://192.168.1.179","https://192.168.1.179:443","https://192.169.11.1","https://192.169.11.1:443").contains(this.origin.toString()))throw new Failure("TEST_TLS_TARGET_NOT_APPROVED");
        if((mode==Mode.CA_FILE)!=(caFile!=null))throw new Failure("INVALID_CA_CONFIGURATION");
        try{
            var builder=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                .protocols(List.of(Protocol.HTTP_1_1))
                .addNetworkInterceptor(chain->withoutImmediateRetry(chain.proceed(chain.request())));
            if(mode!=Mode.SYSTEM){
                X509TrustManager trust;
                if(mode==Mode.TEST_INSECURE){
                    trust=new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                        public void checkClientTrusted(X509Certificate[] chain,String authType){}
                        public void checkServerTrusted(X509Certificate[] chain,String authType){}};
                    builder.hostnameVerifier((host,session)->true);
                }else trust=caTrust(caFile);
                SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},new SecureRandom());
                builder.sslSocketFactory(context.getSocketFactory(),trust);
            }
            client=builder.build();
        }catch(Failure failure){throw failure;}catch(Exception failure){throw new Failure("TLS_CONFIGURATION_FAILED");}
    }
    private static URI origin(URI uri){
        if(uri==null||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
            ||!Set.of("","/").contains(uri.getRawPath())||uri.getPort()==0||uri.getPort()>65535)throw new Failure("INVALID_HTTPS_ORIGIN");
        return URI.create("https://"+uri.getRawAuthority());
    }
    private static X509TrustManager caTrust(Path file)throws Exception{
        if(!file.isAbsolute())throw new Failure("INVALID_CA_FILE");
        byte[] bytes;try(var in=Files.newInputStream(file)){bytes=in.readNBytes(262145);}
        if(bytes.length>262144)throw new Failure("INVALID_CA_FILE");
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
        if(certificates.isEmpty()||certificates.size()>16)throw new Failure("INVALID_CA_FILE");
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);int i=0;
        for(var cert:certificates)store.setCertificateEntry("device-ca-"+(i++),cert);
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store);
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance).map(X509TrustManager.class::cast).findFirst().orElseThrow();
    }
    public Response execute(String method,String target,Map<String,String> headers,byte[] body){
        Request request=request(method,target,headers,body);
        try(var response=client.newCall(request).execute()){
            byte[] bytes=response.body()==null?new byte[0]:readBounded(response.body().byteStream());
            return new Response(response.code(),response.header("Content-Type",""),bytes);
        }catch(Failure failure){throw failure;}
        catch(Exception failure){
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
                if(cause instanceof CertificateException||cause instanceof SSLPeerUnverifiedException||cause instanceof CertPathValidatorException)throw new Failure("TLS_NOT_VERIFIED");
                if(cause instanceof InterruptedIOException)throw new Failure("HTTPS_TIMEOUT");
            }
            throw new Failure("HTTPS_TRANSPORT_UNAVAILABLE");
        }
    }
    Request request(String method,String target,Map<String,String> headers,byte[] body){
        URI uri;
        try{uri=origin.resolve(target);}catch(Exception failure){throw new Failure("INVALID_REQUEST_TARGET");}
        if(target==null||!target.startsWith("/")||target.startsWith("//")||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
            ||!origin.getRawAuthority().equals(uri.getRawAuthority())||!"https".equals(uri.getScheme()))throw new Failure("ORIGIN_MISMATCH");
        if(!method.equals("GET")&&!(method.equals("POST")&&(uri.getRawPath().equals("/janus/authenticate")
            ||uri.getRawPath().matches("/api/v1/namespaces/[A-Za-z0-9_-]{1,15}/(?:login|logout)"))))throw new Failure("WRITE_METHOD_NOT_ALLOWED");
        if(body==null||body.length>32768||method.equals("GET")&&body.length!=0)throw new Failure("INVALID_REQUEST_BODY");
        if(headers==null||!Set.of("Authorization","X-Amz-Date","Cookie").containsAll(headers.keySet())
            ||headers.values().stream().anyMatch(v->v==null||v.length()>8192||v.chars().anyMatch(c->c<32||c>126)))throw new Failure("HEADER_NOT_ALLOWED");
        try{
            var builder=new Request.Builder().url(uri.toString()).header("Accept","application/json").header("Connection","close");
            headers.forEach(builder::header);
            if(method.equals("GET"))builder.get();else builder.post(new RequestBody(){
                public MediaType contentType(){return MediaType.get("application/json; charset=utf-8");}
                public long contentLength(){return body.length;}
                public boolean isOneShot(){return true;}
                public void writeTo(okio.BufferedSink sink)throws IOException{sink.write(body);}
            });
            return builder.build();
        }catch(Exception failure){throw new Failure("INVALID_REQUEST");}
    }
    // OkHttp can follow a 503 Retry-After: 0 even with retryOnConnectionFailure disabled.
    static okhttp3.Response withoutImmediateRetry(okhttp3.Response response){return response.code()==503?response.newBuilder().removeHeader("Retry-After").build():response;}
    static byte[] readBounded(InputStream input)throws IOException{
        byte[] bytes=input.readNBytes(MAX_BODY+1);if(bytes.length>MAX_BODY)throw new Failure("RESPONSE_TOO_LARGE");return bytes;
    }
    public Mode mode(){return mode;}
    @Override public void close(){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    @Override public String toString(){return "DeviceHttpsTransport[mode="+mode+", configuration redacted]";}
}
