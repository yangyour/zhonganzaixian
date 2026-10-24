package cn.mop.api;

import cn.mop.domain.Asset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import static cn.mop.api.ApiResponse.map;

/** Pure plan compilation. Asset addresses and collector references come from trusted server inputs. */
final class MonitoringOnboardingPlanner {
    private static final UUID NAMESPACE = UUID.fromString("f2358881-b5ce-58e5-a986-af76126c9ecb");
    private static final String SYMBOL = "[a-zA-Z_][a-zA-Z0-9_-]{0,63}";
    private final JsonNode catalog;
    private final String catalogHash;
    private final Map<String, JsonNode> presets;
    private final Map<String, Map<String, Collector>> collectors;

    record Collector(String name, String kind, String endpoint, String authProfile) {
        @Override public String toString() { return "MonitoringCollector[redacted]"; }
    }
    private static final class InvalidProfile extends RuntimeException {
        InvalidProfile(String code) { super(code, null, false, false); }
    }
    private static InvalidProfile bad(String code) { return new InvalidProfile(code); }

    MonitoringOnboardingPlanner(String rawCollectors) {
        try (var resource = MonitoringOnboardingPlanner.class.getResourceAsStream("/monitoring/presets.v1.json")) {
            if (resource == null) throw MonitoringPresetRegistry.invalid();
            byte[] bytes = resource.readAllBytes();
            catalog = MonitoringPresetRegistry.JSON.readTree(bytes);
            if (!catalog.path("version").isIntegralNumber() || !catalog.path("version").canConvertToInt() || catalog.path("version").intValue()!=1 || !catalog.path("presets").isArray() || catalog.path("presets").isEmpty() || catalog.path("presets").size()>64) throw MonitoringPresetRegistry.invalid();
            catalogHash = sha(bytes);
            Map<String, JsonNode> definitions = new TreeMap<>();
            for (JsonNode preset : catalog.get("presets")) if(definitions.put(preset.get("id").asText(), preset.deepCopy())!=null) throw MonitoringPresetRegistry.invalid();
            presets = Collections.unmodifiableMap(definitions);
            JsonNode root = MonitoringPresetRegistry.parse(rawCollectors);
            MonitoringPresetRegistry.fields(root, Set.of("version", "collectors"));
            if (!root.get("version").isIntegralNumber() || !root.get("version").canConvertToInt() || root.get("version").intValue() != 1 ||
                !root.get("collectors").isArray() || root.get("collectors").size() > 64) throw MonitoringPresetRegistry.invalid();
            Map<String, Map<String, Collector>> scoped = new HashMap<>();
            for (JsonNode row : root.get("collectors")) {
                MonitoringPresetRegistry.fields(row, Set.of("name", "tenant_id", "kind", "endpoint", "auth_profile"));
                String name = MonitoringPresetRegistry.text(row, "name"), tenant = MonitoringPresetRegistry.text(row, "tenant_id"),
                       kind = MonitoringPresetRegistry.text(row, "kind"), endpoint = MonitoringPresetRegistry.text(row, "endpoint"),
                       auth = MonitoringPresetRegistry.nullable(row, "auth_profile");
                if (!name.matches(SYMBOL) || !MonitoringPresetRegistry.uuid(tenant) || !Set.of("blackbox", "snmp").contains(kind)) throw MonitoringPresetRegistry.invalid();
                MonitoringPresetRegistry.endpoint(endpoint, MonitoringPresetRegistry.presets().get(kind.equals("snmp") ? "network-snmp" : "icmp-device"));
                if (kind.equals("snmp") ? auth == null || !auth.matches(SYMBOL) : auth != null) throw MonitoringPresetRegistry.invalid();
                if (scoped.computeIfAbsent(tenant, ignored -> new TreeMap<>()).put(name, new Collector(name, kind, endpoint, auth)) != null) throw MonitoringPresetRegistry.invalid();
            }
            Map<String, Map<String, Collector>> immutable = new HashMap<>();
            scoped.forEach((tenant, values) -> immutable.put(tenant, Map.copyOf(values)));
            collectors = Map.copyOf(immutable);
        } catch (Exception error) { throw MonitoringPresetRegistry.invalid(); }
    }

    String catalogSha256() { return catalogHash; }

    Map<String, Object> catalog(String tenant) {
        var configured = collectors.getOrDefault(tenant, Map.of());
        List<Object> definitions = new ArrayList<>();
        for (JsonNode raw : catalog.get("presets")) {
            ObjectNode copy = raw.deepCopy();
            String kind = raw.get("test_kind").asText();
            boolean required = Set.of("blackbox", "snmp").contains(kind);
            var names = configured.values().stream().filter(value -> value.kind().equals(kind)).map(Collector::name).sorted().toList();
            copy.set("profile_schema", MonitoringPresetRegistry.JSON.valueToTree(profileSchema(raw, names)));
            copy.put("server_collector_required", required);
            copy.put("server_collector_configured", !required || !names.isEmpty());
            copy.put("device_setup_verified", false);
            definitions.add(copy);
        }
        return map("version", 1, "catalog_sha256", catalogHash, "presets", definitions);
    }

    private Map<String, Object> profileSchema(JsonNode preset, List<String> collectorNames) {
        String id = preset.get("id").asText(), kind = preset.get("test_kind").asText();
        Map<String, Object> properties = map("preset_id", Map.of("const", id), "key", map("type", "string", "pattern", "^" + SYMBOL + "$", "default", id));
        List<String> required = new ArrayList<>(List.of("preset_id"));
        if (Set.of("metrics", "tcp", "configuration_only").contains(kind) || Set.of("http-service", "tcp-service").contains(id)) {
            var port = map("type", "integer", "minimum", 1, "maximum", 65535);
            if (Set.of("metrics", "tcp", "configuration_only").contains(kind)) port.put("default", preset.get("suggested_port").intValue());
            properties.put("port", port);
        }
        if (kind.equals("metrics") || id.equals("http-service")) properties.put("scheme", map("type", "string", "enum", List.of("http", "https")));
        if (id.equals("http-service")) {
            required.add("scheme");
            properties.put("path", map("type", "string", "maxLength", 512, "pattern", "^/(?!/)[A-Za-z0-9._~!$&'()*+,;=:@/-]*$", "default", "/"));
        }
        if (id.equals("tcp-service")) required.add("port");
        if (Set.of("blackbox", "snmp").contains(kind)) {
            var collectorRule=map("type", "string");
            if(collectorNames.isEmpty())collectorRule.put("not",Map.of());else collectorRule.put("enum",collectorNames);
            properties.put("collector", collectorRule);
            if (!collectorNames.contains(kind)) required.add("collector");
        }
        return map("type", "object", "additionalProperties", false, "properties", properties, "required", required);
    }

    Map<String, Object> plan(String tenant, JsonNode request, Function<String, Asset> assets) {
        validateRequest(request);
        JsonNode defaults = request.path("defaults");
        Integer interval;
        int timeout;
        String exporterScheme;
        try {
            interval = defaults.path("interval_seconds").isMissingNode() || defaults.path("interval_seconds").isNull() ? null : number(defaults.get("interval_seconds"), 5, 3600, "INVALID_INTERVAL");
            timeout = defaults.has("timeout_seconds") ? number(defaults.get("timeout_seconds"), 1, 10, "INVALID_TIMEOUT") : 5;
            if (interval != null && timeout >= interval) throw bad("TIMEOUT_MUST_BE_SHORTER");
            exporterScheme = defaults.has("exporter_scheme") ? text(defaults.get("exporter_scheme"), "INVALID_EXPORTER_SCHEME") : "http";
            if (!Set.of("http", "https").contains(exporterScheme)) throw bad("INVALID_EXPORTER_SCHEME");
        } catch (InvalidProfile error) { return invalidPlan(List.of(map("code", error.getMessage()))); }
        List<Map<String, Object>> rows = new ArrayList<>(), diagnostics = new ArrayList<>();
        int assetIndex = 0;
        for (JsonNode item : request.get("assets")) {
            assetIndex++;
            Asset asset = assets.apply(item.get("asset_id").asText());
            if(asset==null||!asset.id().equals(item.get("asset_id").asText())||!asset.tenantId().equals(tenant))throw MonitoringOnboardingAccess.notFound();
            int profileIndex = 0;
            Set<String> keys = new HashSet<>();
            for (JsonNode raw : item.get("profiles")) {
                profileIndex++;
                try {
                    var result = target(tenant, asset, raw, interval, timeout, exporterScheme);
                    if (!keys.add((String) result.remove("_key"))) throw bad("DUPLICATE_LOGICAL_SERVICE_KEY");
                    rows.add(result);
                } catch (InvalidProfile error) {
                    diagnostics.add(map("asset_index", assetIndex, "profile_index", profileIndex, "code", error.getMessage()));
                }
            }
        }
        if (!diagnostics.isEmpty()) return invalidPlan(diagnostics);
        rows.sort(Comparator.comparing(row -> (String) row.get("id")));
        var configuration = map("version", 1, "targets", rows);
        try {
            String canonical = MonitoringPresetRegistry.JSON.writeValueAsString(configuration);
            // Reuse the same destination, duplicate, timeout and scope-shape validator.
            new MonitoringPresetRegistry(canonical, "[]", "[]");
            return map("valid", true, "diagnostics", List.of(), "configuration", configuration,
                       "configuration_sha256", sha(canonical.getBytes(StandardCharsets.UTF_8)),
                       "catalog_sha256", catalogHash, "target_count", rows.size(), "network_accessed", false,
                       "applied", false, "continuous_collection_verified", false, "device_identity_verified", false);
        } catch (Exception error) { return invalidPlan(List.of(map("code", "DUPLICATE_OR_INVALID_TARGET_CONFIGURATION"))); }
    }

    private Map<String, Object> invalidPlan(List<?> diagnostics) {
        return map("valid", false, "diagnostics", diagnostics, "configuration", null, "configuration_sha256", null,
                   "catalog_sha256", catalogHash, "target_count", 0, "network_accessed", false,
                   "applied", false, "continuous_collection_verified", false, "device_identity_verified", false);
    }

    private Map<String, Object> target(String tenant, Asset asset, JsonNode raw, Integer interval, int timeout, String exporterScheme) {
        JsonNode profile = raw.isTextual() ? MonitoringPresetRegistry.JSON.valueToTree(Map.of("preset_id", raw.asText())) : raw;
        String id = text(profile.get("preset_id"), "UNKNOWN_PRESET");
        JsonNode definition = presets.get(id);
        if (definition == null) throw bad("UNKNOWN_PRESET");
        String kind = definition.get("test_kind").asText();
        String key = profile.has("key") ? text(profile.get("key"), "INVALID_SERVICE_KEY") : id;
        if (!key.matches(SYMBOL)) throw bad("INVALID_SERVICE_KEY");
        Set<String> allowed = new HashSet<>(List.of("preset_id", "key"));
        if (kind.equals("metrics")) allowed.addAll(List.of("port", "scheme"));
        else if (Set.of("tcp", "configuration_only").contains(kind)) allowed.add("port");
        else if (id.equals("http-service")) allowed.addAll(List.of("port", "scheme", "path", "collector"));
        else if (id.equals("tcp-service")) allowed.addAll(List.of("port", "collector"));
        else allowed.add("collector");
        profile.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw bad("PROFILE_FIELD_NOT_APPLICABLE"); });
        String address = asset.managementIp();
        if (!MonitoringPresetRegistry.ip(address)) throw bad("ASSET_ADDRESS_UNSUPPORTED");
        String endpoint, target = null, auth = null;
        if (Set.of("blackbox", "snmp").contains(kind)) {
            String name = profile.has("collector") ? text(profile.get("collector"), "COLLECTOR_REQUIRED") : kind;
            Collector collector = collectors.getOrDefault(tenant, Map.of()).get(name);
            if (collector == null) throw bad("COLLECTOR_REQUIRED");
            if (!collector.kind().equals(kind)) throw bad("COLLECTOR_KIND_MISMATCH");
            endpoint = collector.endpoint();
            if (id.equals("http-service")) {
                String scheme = text(profile.get("scheme"), "HTTP_SCHEME_REQUIRED");
                if (!Set.of("http", "https").contains(scheme)) throw bad("HTTP_SCHEME_REQUIRED");
                int port = profile.has("port") ? number(profile.get("port"), 1, 65535, "INVALID_SERVICE_PORT") : scheme.equals("https") ? 443 : 80;
                String path = profile.has("path") ? text(profile.get("path"), "INVALID_HTTP_PATH") : "/";
                if (!path.startsWith("/") || path.startsWith("//") || !path.matches("[A-Za-z0-9._~!$&'()*+,;=:@/-]*")) throw bad("INVALID_HTTP_PATH");
                target = scheme + "://" + address + ":" + port + path;
            } else if (id.equals("tcp-service")) target = address + ":" + number(profile.get("port"), 1, 65535, "SERVICE_PORT_REQUIRED");
            else target = address;
            auth = collector.authProfile();
        } else {
            int port = profile.has("port") ? number(profile.get("port"), 1, 65535, "INVALID_EXPORTER_PORT") : definition.get("suggested_port").intValue();
            String scheme = kind.equals("tcp") ? "tcp" : kind.equals("configuration_only") ? "udp" : profile.has("scheme") ? text(profile.get("scheme"), "INVALID_EXPORTER_SCHEME") : exporterScheme;
            if (kind.equals("metrics") && !Set.of("http", "https").contains(scheme)) throw bad("INVALID_EXPORTER_SCHEME");
            endpoint = scheme + "://" + address + ":" + port + (definition.get("path").isNull() ? "" : definition.get("path").asText());
        }
        return map("id", identifier(tenant, asset.id(), key), "tenant_id", tenant, "asset_id", asset.id(), "preset_id", id,
                   "enabled", false, "endpoint", endpoint, "target", target, "auth_profile", auth,
                   "interval_seconds", interval, "timeout_seconds", timeout, "_key", key);
    }

    private static void validateRequest(JsonNode request) {
        try {
            fields(request, Set.of("version", "assets"), Set.of("defaults"));
            if (!request.get("version").isIntegralNumber() || !request.get("version").canConvertToInt() || request.get("version").intValue() != 1 ||
                !request.get("assets").isArray() || request.get("assets").isEmpty() || request.get("assets").size() > 64) throw MonitoringPresetRegistry.invalid();
            if (request.has("defaults")) {
                JsonNode defaults=request.get("defaults");
                fields(defaults, Set.of(), Set.of("interval_seconds", "timeout_seconds", "exporter_scheme"));
                if(defaults.has("interval_seconds")&&!defaults.get("interval_seconds").isNull()&&!defaults.get("interval_seconds").isIntegralNumber() || defaults.has("timeout_seconds")&&!defaults.get("timeout_seconds").isIntegralNumber() || defaults.has("exporter_scheme")&&(!defaults.get("exporter_scheme").isTextual()||defaults.get("exporter_scheme").asText().length()>64)) throw MonitoringPresetRegistry.invalid();
            }
            Set<String> ids = new HashSet<>();
            int total = 0;
            for (JsonNode asset : request.get("assets")) {
                MonitoringPresetRegistry.fields(asset, Set.of("asset_id", "profiles"));
                String id = MonitoringPresetRegistry.text(asset, "asset_id");
                if (!MonitoringPresetRegistry.uuid(id) || !ids.add(id) || !asset.get("profiles").isArray() || asset.get("profiles").isEmpty() || asset.get("profiles").size() > 16) throw MonitoringPresetRegistry.invalid();
                total += asset.get("profiles").size();
                if (total > 64) throw MonitoringPresetRegistry.invalid();
                for (JsonNode profile : asset.get("profiles")) {
                    if (profile.isTextual()) { if (profile.asText().isEmpty() || profile.asText().length() > 64) throw MonitoringPresetRegistry.invalid(); continue; }
                    fields(profile, Set.of("preset_id"), Set.of("key", "port", "scheme", "path", "collector"));
                    for (String key : List.of("preset_id", "key", "scheme", "path", "collector")) if (profile.has(key) && (!profile.get(key).isTextual() || profile.get(key).asText().length() > (key.equals("path") ? 512 : 64))) throw MonitoringPresetRegistry.invalid();
                    if (profile.has("port") && !profile.get("port").isIntegralNumber()) throw MonitoringPresetRegistry.invalid();
                }
            }
        } catch (Exception error) { throw MonitoringOnboardingAccess.invalid(); }
    }

    private static void fields(JsonNode value, Set<String> required, Set<String> optional) {
        if (value == null || !value.isObject()) throw MonitoringPresetRegistry.invalid();
        for (String name : required) if (!value.has(name)) throw MonitoringPresetRegistry.invalid();
        value.fieldNames().forEachRemaining(name -> { if (!required.contains(name) && !optional.contains(name)) throw MonitoringPresetRegistry.invalid(); });
    }
    private static String text(JsonNode value, String code) { if (value == null || !value.isTextual()) throw bad(code); return value.asText(); }
    private static int number(JsonNode value, int low, int high, String code) { if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < low || value.intValue() > high) throw bad(code); return value.intValue(); }
    static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception error) { throw MonitoringPresetRegistry.invalid(); } }
    static String identifier(String tenant, String asset, String key) {
        try {
            byte[] name = MonitoringPresetRegistry.JSON.writeValueAsBytes(List.of(tenant, asset, key));
            var hash = MessageDigest.getInstance("SHA-1");
            hash.update(ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits()).putLong(NAMESPACE.getLeastSignificantBits()).array());
            byte[] bytes = hash.digest(name);
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong()).toString();
        } catch (Exception error) { throw MonitoringPresetRegistry.invalid(); }
    }
}
