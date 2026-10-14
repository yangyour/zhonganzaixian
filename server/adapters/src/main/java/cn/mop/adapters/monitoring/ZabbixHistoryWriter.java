package cn.mop.adapters.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.*;

/** Dedicated trapper writer. Never exposes the read client's token or arbitrary RPC methods. */
public final class ZabbixHistoryWriter implements AutoCloseable {
    private final String endpoint,token;private final Set<String> items;private final OkHttpClient client;private final AtomicLong ids=new AtomicLong();
    public ZabbixHistoryWriter(String endpoint,String token,Set<String> items){
        this.endpoint=ZabbixVersionProtocol.requireLoopbackEndpoint(URI.create(endpoint)).toString();
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,256}")||items.size()!=3||items.stream().anyMatch(i->!i.matches("[1-9][0-9]{0,19}")))throw new IllegalArgumentException("Invalid private writer configuration");
        this.token=token;this.items=Set.copyOf(items);
        client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(5,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS)
            .readTimeout(10,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }
    public void push(Map<String,String> values,Instant at){
        if(values.isEmpty()||!items.containsAll(values.keySet())||values.values().stream().anyMatch(v->v==null||v.length()>128))throw new IllegalArgumentException("Invalid owned sample");
        long id=ids.incrementAndGet();
        try{
            var rows=values.entrySet().stream().map(e->Map.of("itemid",e.getKey(),"value",e.getValue(),"clock",at.getEpochSecond(),"ns",at.getNano())).toList();
            byte[] body=ZabbixReadClient.JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",id,"method","history.push","params",rows));
            RequestBody once=new RequestBody(){public MediaType contentType(){return MediaType.get("application/json");}public long contentLength(){return body.length;}public boolean isOneShot(){return true;}public void writeTo(okio.BufferedSink sink)throws java.io.IOException{sink.write(body);}};
            var request=new Request.Builder().url(endpoint).header("Authorization","Bearer "+token).header("Connection","close").post(once).build();
            try(var response=client.newCall(request).execute()){
                byte[] raw=response.body()==null?new byte[0]:response.body().byteStream().readNBytes(65537);
                if(raw.length>65536)throw new IllegalStateException();
                decode(response.code(),response.header("Content-Type",""),raw,id,values.keySet());
            }
        }catch(Exception failure){throw new IllegalStateException("ZABBIX_SAMPLE_PUBLISH_FAILED");}
    }
    static void decode(int status,String contentType,byte[] bytes,long id,Set<String> expected){
        try{
            if(status!=200||!contentType.toLowerCase(Locale.ROOT).matches("application/json(?:-rpc)?(?:;.*)?")||bytes.length==0||bytes.length>65536)throw new IllegalStateException();
            var root=ZabbixReadClient.JSON.readTree(bytes);
            if(!root.isObject()||root.size()!=3||root.has("error")||!root.path("jsonrpc").asText().equals("2.0")||!root.path("id").isIntegralNumber()||root.get("id").longValue()!=id||!root.path("result").isObject())throw new IllegalStateException();
            validate(root.get("result"),expected);
        }catch(Exception failure){throw new IllegalStateException("ZABBIX_SAMPLE_PUBLISH_FAILED");}
    }
    static void validate(JsonNode value,Set<String> expected){
        if(!value.isObject()||!value.path("response").asText().equals("success")||!value.path("data").isArray()||value.get("data").size()!=expected.size())throw new IllegalStateException("ZABBIX_SAMPLE_PUBLISH_FAILED");
        Set<String> seen=new HashSet<>();for(var row:value.get("data"))if(row.has("error")||!(row.path("itemid").isTextual()||row.path("itemid").isIntegralNumber())||!seen.add(row.get("itemid").asText()))throw new IllegalStateException("ZABBIX_SAMPLE_PUBLISH_FAILED");
        if(!seen.equals(expected))throw new IllegalStateException("ZABBIX_SAMPLE_PUBLISH_FAILED");
    }
    public void close(){client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
}
