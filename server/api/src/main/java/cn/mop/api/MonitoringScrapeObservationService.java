package cn.mop.api;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLException;
import static cn.mop.api.ApiResponse.map;

/** Fixed, tenant-scoped Prometheus queries. Scrape success is not device health or an SLA. */
final class MonitoringScrapeObservationService {
    record Source(String tenantId, URI endpoint, int maxAge, int lookback, int timeout) {
        @Override public String toString() { return "MonitoringSource[redacted]"; }
    }
    interface Reader { byte[] read(Source source, MonitoringPresetRegistry.Target target, Instant evaluatedAt); }
    private final Map<String, Source> sources;
    private final Reader reader;
    private final Clock clock;
    private final Semaphore slot = new Semaphore(1);
    MonitoringScrapeObservationService(Map<String, Source> sources, Reader reader, Clock clock) {
        this.sources = Map.copyOf(sources); this.reader = Objects.requireNonNull(reader); this.clock = Objects.requireNonNull(clock);
    }
    static Map<String, Source> sources(String raw, String allowedEndpoints) {
        try {
            var root = MonitoringPresetRegistry.parse(raw); MonitoringPresetRegistry.fields(root, Set.of("version", "sources"));
            if (MonitoringPresetRegistry.number(root.get("version"), 1, 1) != 1 || !root.get("sources").isArray() || root.get("sources").size() > 64) throw invalid();
            var allowed = MonitoringPresetRegistry.allowlist(allowedEndpoints); var out = new LinkedHashMap<String, Source>();
            var shape = new MonitoringPresetRegistry.Preset("prometheus-query", "prometheus", "metrics", "/api/v1/query", List.of(), null, null);
            for (var row : root.get("sources")) {
                MonitoringPresetRegistry.fields(row, Set.of("tenant_id", "endpoint", "max_age_seconds", "lookback_seconds", "timeout_seconds"));
                String tenant = MonitoringPresetRegistry.text(row, "tenant_id");
                var endpoint = MonitoringPresetRegistry.endpoint(MonitoringPresetRegistry.text(row, "endpoint"), shape);
                int age = MonitoringPresetRegistry.number(row.get("max_age_seconds"), 1, 86400);
                int lookback = MonitoringPresetRegistry.number(row.get("lookback_seconds"), age, 86400);
                int timeout = MonitoringPresetRegistry.number(row.get("timeout_seconds"), 1, 10);
                if (!MonitoringPresetRegistry.uuid(tenant) || !allowed.contains(endpoint.toString()) || out.putIfAbsent(tenant, new Source(tenant, endpoint, age, lookback, timeout)) != null) throw invalid();
            }
            return Map.copyOf(out);
        } catch (Exception failure) { throw invalid(); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid monitoring observation source configuration"); }
    Map<String, Object> read(MonitoringPresetRegistry.Target target) {
        var result = map("target_id", target.id(), "configuration_sha256", target.hash(), "observation_status", "CONFIG_PENDING",
            "reason", "SOURCE_NOT_CONFIGURED", "evaluated_at", null, "checked_at", null, "sample_at", null, "sample_age_seconds", null,
            "scrape_up", null, "max_age_seconds", null, "lookback_seconds", null, "network_attempted", false,
            "continuous_collection_verified", false, "active_configuration_verified", false, "device_identity_verified", false, "health_verified", false);
        if (!target.enabled()) return state(result, "TARGET_DISABLED", "TARGET_NOT_ENABLED");
        if (!target.preset().engine().equals("prometheus")) return state(result, "UNSUPPORTED_ENGINE", "PROMETHEUS_SOURCE_REQUIRED");
        Source source = sources.get(target.tenantId()); if (source == null) return result;
        if (!slot.tryAcquire()) throw new DomainException(429, "RATE_LIMITED", "已有采集状态查询在执行，请稍后重试");
        try {
            Instant evaluated = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            result.put("evaluated_at", evaluated.toString()); result.put("max_age_seconds", source.maxAge()); result.put("lookback_seconds", source.lookback());
            result.put("network_attempted", true);
            byte[] response = reader.read(source, target, evaluated);
            Instant checked = clock.instant().truncatedTo(ChronoUnit.MILLIS); result.put("checked_at", checked.toString());
            result.putAll(decode(response, target, evaluated, checked, source.maxAge(), source.lookback())); return result;
        } catch (InvalidResponse failure) { return state(result, "SOURCE_INVALID_RESPONSE", failure.reason); }
        catch (MonitoringHttpTransport.Failure failure) { return state(result, "SOURCE_UNAVAILABLE", failure.reason); }
        catch (RuntimeException failure) { return state(result, "SOURCE_UNAVAILABLE", "SOURCE_READ_FAILED"); }
        finally { slot.release(); }
    }
    private static Map<String, Object> state(Map<String, Object> result, String status, String reason) {
        result.put("observation_status", status); result.put("reason", reason); return result;
    }
    static final class InvalidResponse extends RuntimeException {
        final String reason; InvalidResponse(String reason) { super(reason); this.reason = reason; }
    }
    static InvalidResponse bad(String reason) { return new InvalidResponse(reason); }
    static String query(MonitoringPresetRegistry.Target target) {
        try {
            String selector = "up{mop_tenant_id=" + quote(target.tenantId()) + ",mop_asset_id=" + quote(target.assetId()) +
                ",mop_target_id=" + quote(target.id()) + ",mop_preset=" + quote(target.preset().id()) + "}";
            return "label_replace(" + selector + ",\"mop_read_kind\",\"state\",\"__name__\",\".*\") or label_replace(timestamp(" +
                selector + "),\"mop_read_kind\",\"sample_time\",\"__name__\",\".*\")";
        } catch (Exception failure) { throw invalid(); }
    }
    private static String quote(String text) throws Exception { return MonitoringPresetRegistry.JSON.writeValueAsString(text); }
    static Map<String, Object> decode(byte[] bytes, MonitoringPresetRegistry.Target target, Instant evaluated, Instant checked, int maxAge, int lookback) {
        try {
            if (bytes == null || bytes.length > MonitoringHttpTransport.MAX_BYTES) throw bad("RESPONSE_TOO_LARGE");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = MonitoringPresetRegistry.JSON.readTree(text);
            if (!root.isObject() || !root.path("status").asText().equals("success")) throw bad("SOURCE_QUERY_FAILED");
            for (String annotation : List.of("warnings", "infos"))
                if (root.has(annotation) && (!root.get(annotation).isArray() || !root.get(annotation).isEmpty())) throw bad("SOURCE_ANNOTATIONS_PRESENT");
            if (root.has("error") || root.has("errorType")) throw bad("SOURCE_QUERY_FAILED");
            var data = root.path("data");
            if (!data.isObject() || !data.path("resultType").asText().equals("vector") || !data.path("result").isArray()) throw bad("INVALID_RESULT_FORMAT");
            var rows = data.get("result");
            if (checked.isBefore(evaluated)) throw bad("LOCAL_CLOCK_MOVED_BACKWARD");
            if (rows.isEmpty()) return map("observation_status", "NO_DATA", "reason", "NO_SAMPLE_IN_LOOKBACK");
            if (rows.size() != 2) throw bad("AMBIGUOUS_OR_INCOMPLETE_SERIES");
            Map<String, String> identity = null; Map<String, BigDecimal> values = new HashMap<>();
            for (var row : rows) {
                var labels = row.path("metric"); var value = row.path("value");
                if (!labels.isObject() || labels.size() > 64 || !value.isArray() || value.size() != 2 || !value.get(0).isNumber() || !value.get(1).isTextual() || row.has("histogram")) throw bad("INVALID_SAMPLE_FORMAT");
                if (value.get(0).decimalValue().compareTo(BigDecimal.valueOf(evaluated.toEpochMilli(), 3)) != 0) throw bad("EVALUATION_TIME_MISMATCH");
                var labelMap = new TreeMap<String, String>(); var fields = labels.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (!field.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]{0,127}") || !field.getValue().isTextual() || field.getValue().textValue().length() > 512) throw bad("INVALID_LABEL_FORMAT");
                    labelMap.put(field.getKey(), field.getValue().textValue());
                }
                if (!target.tenantId().equals(labelMap.get("mop_tenant_id")) || !target.assetId().equals(labelMap.get("mop_asset_id")) ||
                    !target.id().equals(labelMap.get("mop_target_id")) || !target.preset().id().equals(labelMap.get("mop_preset"))) throw bad("TARGET_SCOPE_MISMATCH");
                String expectedInstance = target.downstream() == null ? target.endpoint().getRawAuthority() : target.downstream();
                if (!expectedInstance.equals(labelMap.get("instance")) || !labelMap.containsKey("job") || labelMap.get("job").isBlank()) throw bad("TARGET_INSTANCE_MISMATCH");
                String kind = labelMap.remove("mop_read_kind"); String metric = labelMap.remove("__name__");
                if (!Set.of("state", "sample_time").contains(kind == null ? "" : kind) || metric != null && !(kind.equals("state") && metric.equals("up"))) throw bad("INVALID_SERIES_KIND");
                if (identity == null) identity = labelMap; else if (!identity.equals(labelMap)) throw bad("AMBIGUOUS_OR_INCOMPLETE_SERIES");
                if (values.putIfAbsent(kind, decimal(value.get(1).textValue())) != null) throw bad("AMBIGUOUS_OR_INCOMPLETE_SERIES");
            }
            if (!values.keySet().equals(Set.of("state", "sample_time"))) throw bad("AMBIGUOUS_OR_INCOMPLETE_SERIES");
            BigDecimal up = values.get("state"), timestamp = values.get("sample_time");
            if (up.compareTo(BigDecimal.ZERO) != 0 && up.compareTo(BigDecimal.ONE) != 0) throw bad("INVALID_SCRAPE_VALUE");
            if (timestamp.signum() < 0 || timestamp.compareTo(BigDecimal.valueOf(evaluated.toEpochMilli(), 3)) > 0) throw bad("INVALID_SAMPLE_TIME");
            long sampleMillis = timestamp.movePointRight(3).longValueExact();
            if (Math.subtractExact(evaluated.toEpochMilli(), sampleMillis) > lookback * 1000L) throw bad("SAMPLE_OUTSIDE_LOOKBACK");
            long ageMillis = Math.subtractExact(checked.toEpochMilli(), sampleMillis);
            boolean stale = ageMillis > maxAge * 1000L;
            return map("observation_status", stale ? "STALE" : up.signum() == 0 ? "SCRAPE_FAILED" : "SCRAPE_SUCCEEDED",
                "reason", stale ? "SAMPLE_TOO_OLD" : up.signum() == 0 ? "SCRAPE_SAMPLE_FAILED" : "SCRAPE_SAMPLE_CURRENT",
                "sample_at", Instant.ofEpochMilli(sampleMillis).toString(), "sample_age_seconds", BigDecimal.valueOf(ageMillis, 3),
                "scrape_up", stale ? null : up.intValueExact());
        } catch (InvalidResponse failure) { throw failure; }
        catch (Exception failure) { throw bad("INVALID_SOURCE_RESPONSE"); }
    }
    private static BigDecimal decimal(String value) {
        if (value.length() > 64 || !value.matches("-?(?:[0-9]+(?:\\.[0-9]+)?)(?:[eE][+-]?[0-9]+)?")) throw bad("INVALID_NUMERIC_VALUE");
        BigDecimal number = new BigDecimal(value);
        if (number.precision() > 20 || number.scale() < -9 || number.scale() > 9) throw bad("INVALID_NUMERIC_VALUE");
        return number;
    }
    static Reader network() {
        HttpClient client = MonitoringHttpTransport.client();
        return (source, target, evaluated) -> {
            CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                String uri = source.endpoint() + "?query=" + MonitoringPresetProbeService.encode(query(target)) + "&time=" +
                    BigDecimal.valueOf(evaluated.toEpochMilli(), 3).toPlainString() + "&timeout=" + source.timeout() + "s&lookback_delta=" + source.lookback() + "s&limit=3";
                var request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(source.timeout()))
                    .header("Accept", "application/json").header("Accept-Encoding", "identity").GET().build();
                pending = client.sendAsync(request, ignored -> new MonitoringHttpTransport.LimitedBody());
                var response = pending.get(source.timeout(), TimeUnit.SECONDS);
                if (response.statusCode() != 200) throw new MonitoringHttpTransport.Failure("SOURCE_HTTP_REJECTED");
                if (!response.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")) throw new MonitoringHttpTransport.Failure("ENCODING_REJECTED");
                if (!response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw new MonitoringHttpTransport.Failure("CONTENT_TYPE_REJECTED");
                return response.body();
            } catch (MonitoringHttpTransport.Failure failure) { throw failure; }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new MonitoringHttpTransport.Failure("SOURCE_READ_INTERRUPTED"); }
            catch (TimeoutException failure) { throw new MonitoringHttpTransport.Failure("SOURCE_READ_TIMEOUT"); }
            catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                throw new MonitoringHttpTransport.Failure(cause instanceof MonitoringHttpTransport.Failure bounded ? bounded.reason :
                    cause instanceof HttpTimeoutException ? "SOURCE_READ_TIMEOUT" : cause instanceof SSLException ? "TLS_VERIFICATION_OR_HANDSHAKE_FAILED" : "CONNECTION_FAILED");
            } catch (Exception failure) { throw new MonitoringHttpTransport.Failure("CONNECTION_FAILED"); }
            finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
        };
    }
}
