package cn.mop.api;

import cn.mop.domain.DomainException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLException;
import static cn.mop.api.ApiResponse.map;

/** One explicitly registered target per invocation; never scans, redirects or uses ambient proxies. */
final class MonitoringPresetProbeService {
    interface Runner { Map<String,Object> run(MonitoringPresetRegistry.Target target); }
    final MonitoringPresetRegistry registry;
    private final Runner runner;
    private final Semaphore slot=new Semaphore(1);
    MonitoringPresetProbeService(MonitoringPresetRegistry registry,Runner runner){this.registry=registry;this.runner=Objects.requireNonNull(runner);}
    boolean enabled(){return registry!=null;}
    Map<String,Object> test(MonitoringPresetRegistry.Target t){
        if(!enabled()||!registry.approved(t))throw new DomainException(503,"CONFIG_PENDING","该目标尚未配置精确端点与下游测试授权");
        if(!slot.tryAcquire())throw new DomainException(429,"RATE_LIMITED","已有单次探测在执行，请稍后重试");
        try{return runner.run(t);}finally{slot.release();}
    }
    static Map<String,Object> base(MonitoringPresetRegistry.Target t){return map("target_id",t.id(),"configuration_sha256",t.hash(),"probe_status","NOT_RUN","continuous_collection_verified",false,"device_identity_verified",false,"health_verified",false,"sample_count",null,"matched_metrics",List.of());}
    static Map<String,Object> outcome(MonitoringPresetRegistry.Target t,String state,String reason){var r=base(t);r.put("probe_status",state);if(reason!=null)r.put("reason",reason);return r;}
    static Runner network(){return new NetworkRunner();}
    static final int MAX_BYTES=2*1024*1024;
    static final class ProbeFailure extends RuntimeException {final String reason;ProbeFailure(String reason){super(reason);this.reason=reason;}}
    static ProbeFailure failure(String reason){return new ProbeFailure(reason);}
    static final class NetworkRunner implements Runner {
        private static HttpClient client(){SSLParameters tls=new SSLParameters();tls.setEndpointIdentificationAlgorithm("HTTPS");return HttpClient.newBuilder().sslParameters(tls).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5))
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();}
        private final HttpClient client=client();
        public Map<String,Object> run(MonitoringPresetRegistry.Target t){
            CompletableFuture<HttpResponse<byte[]>> pending=null;
            try{
                if(t.preset().kind().equals("configuration_only"))return outcome(t,"NOT_RUN","SNMP_TEST_REQUIRES_CONFIGURED_PROVIDER");
                if(t.preset().kind().equals("tcp")){
                    try(var socket=new Socket()){socket.connect(new InetSocketAddress(t.endpoint().getHost(),t.endpoint().getPort()),t.timeout()*1000);}
                    return outcome(t,"TCP_REACHABLE_PROTOCOL_UNVERIFIED","NO_AGENT_AUTHENTICATION_OR_SAMPLE_VERIFIED");
                }
                String path=t.endpoint().toString();if(t.downstream()!=null){path+="?target="+encode(t.downstream())+"&module="+encode(t.preset().module());if(t.authProfile()!=null)path+="&auth="+encode(t.authProfile());}
                HttpRequest request=HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(t.timeout())).header("Accept","text/plain; version=0.0.4").header("Accept-Encoding","identity").GET().build();
                pending=client.sendAsync(request,ignored->new LimitedBody());HttpResponse<byte[]> response=pending.get(t.timeout(),TimeUnit.SECONDS);
                if(response.statusCode()!=200)throw failure("HTTP_STATUS_REJECTED");
                if(!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity"))throw failure("ENCODING_REJECTED");
                String type=response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                if(!Set.of("text/plain","application/openmetrics-text").contains(type))throw failure("CONTENT_TYPE_REJECTED");
                var summary=metrics(response.body(),t.preset());var result=base(t);result.putAll(summary);result.put("probe_status","SAMPLE_VERIFIED_ONCE");return result;
            }catch(ProbeFailure ex){return outcome(t,"FAILED",ex.reason);}
            catch(TimeoutException|java.net.http.HttpTimeoutException|java.net.SocketTimeoutException ex){return outcome(t,"FAILED","PROBE_TIMEOUT");}
            catch(InterruptedException ex){Thread.currentThread().interrupt();return outcome(t,"FAILED","PROBE_INTERRUPTED");}
            catch(ExecutionException ex){Throwable cause=ex.getCause();return outcome(t,"FAILED",cause instanceof ProbeFailure f?f.reason:cause instanceof HttpTimeoutException?"PROBE_TIMEOUT":cause instanceof SSLException?"TLS_VERIFICATION_OR_HANDSHAKE_FAILED":"CONNECTION_FAILED");}
            catch(Exception ex){return outcome(t,"FAILED","CONNECTION_FAILED");}
            finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
        }
    }
    static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static final Pattern SAMPLE=Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{.*\\})?[ \\t]+([^ \\t]+)(?:[ \\t]+[-+]?[0-9]+(?:\\.[0-9]+)?)?[ \\t]*(?:#.*)?");
    static final Pattern VALUE=Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?Inf|NaN");
    static final Pattern LABEL=Pattern.compile("\\s*([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\[\\\\\"n])*)\"\\s*");
    static String labels(String raw){if(raw==null||raw.equals("{}"))return "{}";String s=raw.substring(1,raw.length()-1);TreeMap<String,String> values=new TreeMap<>();int offset=0;if(s.isBlank())return "{}";
        while(offset<s.length()){Matcher m=LABEL.matcher(s);m.region(offset,s.length());if(!m.lookingAt()||values.putIfAbsent(m.group(1),m.group(2))!=null)throw failure("INVALID_METRIC_LABELS");offset=m.end();if(offset==s.length())break;if(s.charAt(offset++)!=',')throw failure("INVALID_METRIC_LABELS");if(s.substring(offset).isBlank())break;}
        try{return MonitoringPresetRegistry.JSON.writeValueAsString(values);}catch(Exception ex){throw failure("INVALID_METRIC_LABELS");}}
    static Map<String,Object> metrics(byte[] bytes,MonitoringPresetRegistry.Preset p){
        if(bytes==null||bytes.length>MAX_BYTES)throw failure("RESPONSE_TOO_LARGE");String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException ex){throw failure("INVALID_METRICS_ENCODING");}
        Set<String> seen=new HashSet<>(),matched=new TreeSet<>();Map<String,List<Double>> values=new HashMap<>();int count=0;
        for(String raw:text.split("\\R",-1)){String line=raw.strip();if(line.isEmpty()||line.startsWith("#"))continue;if(line.length()>65536)throw failure("METRIC_LINE_TOO_LONG");Matcher m=SAMPLE.matcher(line);if(!m.matches()||!VALUE.matcher(m.group(3)).matches())throw failure("INVALID_METRICS_FORMAT");
            if(!seen.add(m.group(1)+labels(m.group(2))))throw failure("DUPLICATE_METRIC_SERIES");if(++count>100000)throw failure("TOO_MANY_METRICS");
            if(p.metrics().contains(m.group(1))){double value;try{value=Double.parseDouble(m.group(3));}catch(NumberFormatException ex){throw failure("NONFINITE_REQUIRED_METRIC");}if(!Double.isFinite(value))throw failure("NONFINITE_REQUIRED_METRIC");matched.add(m.group(1));values.computeIfAbsent(m.group(1),k->new ArrayList<>()).add(value);}
        }
        if(matched.isEmpty())throw failure("EXPECTED_METRICS_MISSING");if(p.successMetric()!=null){var v=values.get(p.successMetric());if(v==null||v.size()!=1||v.get(0)!=1d)throw failure("TARGET_PROBE_FAILED");}
        return map("sample_count",count,"matched_metrics",List.copyOf(matched),"metric_values_stored",false);
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> body=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public synchronized void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public synchronized void onNext(List<ByteBuffer> chunks){if(body.isDone())return;for(ByteBuffer chunk:chunks){if(chunk.remaining()>MAX_BYTES-bytes.size()){subscription.cancel();bytes.reset();body.completeExceptionally(failure("RESPONSE_TOO_LARGE"));return;}byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);}subscription.request(1);}
        public synchronized void onError(Throwable error){bytes.reset();body.completeExceptionally(failure("CONNECTION_FAILED"));}
        public synchronized void onComplete(){body.complete(bytes.toByteArray());bytes.reset();}
    }
}
