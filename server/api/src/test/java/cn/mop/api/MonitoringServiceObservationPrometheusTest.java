package cn.mop.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in official local Prometheus, temporary TSDB and loopback-only synthetic exporter. No business writes. */
class MonitoringServiceObservationPrometheusTest {
    @Test void officialEngineSeparatesExporterAndServiceFailures() throws Exception {
        String binary=System.getenv("MOP_PROMETHEUS_BIN"); assumeTrue(binary!=null&&!binary.isBlank(),"Explicit local Prometheus binary is not configured");
        assertTrue(Path.of(binary).isAbsolute()&&Files.isExecutable(Path.of(binary)),"Configured Prometheus binary must be executable");
        Path dir=Files.createTempDirectory("mop-observation-isolated-"); HttpServer exporter=null; Process prometheus=null;
        AtomicInteger status=new AtomicInteger(200),hits=new AtomicInteger(),signal=new AtomicInteger(1);
        try {
            exporter=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            exporter.createContext("/metrics",exchange->{hits.incrementAndGet();try{byte[] bytes=("redis_up "+signal.get()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; version=0.0.4");exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});exporter.start();
            String address="127.0.0.1:"+exporter.getAddress().getPort();
            var config=MonitoringScrapeObservationTest.config("redis",true);MonitoringPresetTest.row(config).put("endpoint","http://"+address+"/metrics");
            var target=MonitoringPresetTest.first(MonitoringPresetTest.registry(config),config);
            var labels=Map.of("mop_tenant_id",target.tenantId(),"mop_asset_id",target.assetId(),"mop_target_id",target.id(),"mop_preset",target.preset().id());
            String staticGroup=MonitoringPresetRegistry.JSON.writeValueAsString(List.of(Map.of("targets",List.of(address),"labels",labels)));
            Path duplicate=dir.resolve("duplicate.json"); Files.writeString(duplicate,"[]");
            String yaml="global:\n  scrape_interval: 1s\n  scrape_timeout: 500ms\nscrape_configs:\n"+
                "  - job_name: isolated_primary\n    honor_labels: false\n    honor_timestamps: false\n    static_configs: "+staticGroup+"\n"+
                "  - job_name: isolated_duplicate\n    honor_labels: false\n    honor_timestamps: false\n    file_sd_configs:\n      - files: ["+MonitoringPresetRegistry.JSON.writeValueAsString(duplicate.toString())+"]\n        refresh_interval: 1s\n";
            Path configuration=dir.resolve("prometheus.yml");Files.writeString(configuration,yaml);
            int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
            prometheus=new ProcessBuilder(binary,"--config.file="+configuration,"--storage.tsdb.path="+dir.resolve("data"),"--web.listen-address=127.0.0.1:"+port,"--log.level=error")
                .redirectErrorStream(true).redirectOutput(dir.resolve("engine.log").toFile()).start();
            var source=new MonitoringScrapeObservationService.Source(target.tenantId(),URI.create("http://127.0.0.1:"+port+"/api/v1/query"),60,300,3);
            var service=new MonitoringScrapeObservationService(Map.of(target.tenantId(),source),MonitoringScrapeObservationService.network(),MonitoringScrapeObservationService.serviceNetwork(),Clock.systemUTC());
            awaitSignal(service,target,"SIGNAL_SUCCESS",prometheus);assertTrue(hits.get()>0);
            signal.set(0);var failedService=awaitSignal(service,target,"SIGNAL_FAILURE",prometheus);assertEquals("SCRAPE_SUCCEEDED",failedService.get("observation_status"));
            status.set(503);var failedScrape=await(service,target,"SCRAPE_FAILED",prometheus);assertEquals("BLOCKED_SCRAPE",((Map<?,?>)failedScrape.get("service_signal")).get("status"));
            signal.set(1);status.set(200);awaitSignal(service,target,"SIGNAL_SUCCESS",prometheus);
            var missing=config.deepCopy();MonitoringPresetTest.row(missing).put("id","79000000-0000-4000-8000-000000000099");
            var missingTarget=MonitoringPresetTest.first(MonitoringPresetTest.registry(missing),missing);
            assertEquals("NO_DATA",service.readService(missingTarget).get("observation_status"));
            Path staged=dir.resolve("duplicate.next.json");Files.writeString(staged,staticGroup);Files.move(staged,duplicate,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            var ambiguous=await(service,target,"SOURCE_INVALID_RESPONSE",prometheus);
            assertTrue(Set.of("SOURCE_ANNOTATIONS_PRESENT","AMBIGUOUS_OR_INCOMPLETE_SERIES","AMBIGUOUS_OR_INVALID_RESULT").contains(ambiguous.get("reason")));
            assertNull(ambiguous.get("scrape_up"));
            prometheus.destroy();assertTrue(prometheus.waitFor(5,TimeUnit.SECONDS));
            assertEquals("SOURCE_UNAVAILABLE",service.readService(target).get("observation_status"));
        } finally {
            if(prometheus!=null&&prometheus.isAlive()){prometheus.destroy();if(!prometheus.waitFor(3,TimeUnit.SECONDS)){prometheus.destroyForcibly();assertTrue(prometheus.waitFor(5,TimeUnit.SECONDS));}}
            if(exporter!=null)exporter.stop(0);
            try(var paths=Files.walk(dir)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        assertFalse(Files.exists(dir));
    }
    static Map<String,Object> await(MonitoringScrapeObservationService service,MonitoringPresetRegistry.Target target,String expected,Process process)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);Map<String,Object> result=Map.of();
        while(System.nanoTime()<deadline){assertTrue(process.isAlive(),"Isolated Prometheus exited unexpectedly");result=service.readService(target);if(expected.equals(result.get("observation_status")))return result;Thread.sleep(200);}
        fail("Expected "+expected+" but got sanitized status "+result.get("observation_status")+" / "+result.get("reason"));return result;
    }
    static Map<String,Object> awaitSignal(MonitoringScrapeObservationService service,MonitoringPresetRegistry.Target target,String expected,Process process)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){assertTrue(process.isAlive());var result=service.readService(target);var signal=(Map<?,?>)result.get("service_signal");if(expected.equals(signal.get("status")))return result;Thread.sleep(200);}
        fail("Expected isolated service signal "+expected);return Map.of();
    }

}
