package cn.mop.adapters.monitoring;

import cn.mop.domain.monitoring.VendorMappingCandidate;
import cn.mop.domain.monitoring.VendorMappingCandidate.Metric;
import cn.mop.domain.monitoring.VendorMappingCandidate.ValueType;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;

/** Reads supplied bytes only; no directories, network, includes, environment interpolation or activation. */
public final class VendorMappingYaml {
    public static final int MAX_BYTES = 262144;
    private VendorMappingYaml() {}
    public static VendorMappingCandidate parse(byte[] input) {
        try {
            if (input == null || input.length == 0 || input.length > MAX_BYTES) throw invalid();
            byte[] bytes = input.clone();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false); options.setAllowRecursiveKeys(false);
            options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(16); options.setCodePointLimit(MAX_BYTES);
            options.setTagInspector(tag -> false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            int events = 0;
            for (var event : yaml.parse(new StringReader(text))) {
                if (++events > 20000 || event instanceof AliasEvent || event instanceof NodeEvent node && node.getAnchor() != null
                    || event instanceof ScalarEvent scalar && scalar.getTag() != null
                    || event instanceof CollectionStartEvent collection && collection.getTag() != null) throw invalid();
            }
            Map<?, ?> root = object(yaml.load(text), Set.of("schema_version", "revision", "rollback_revision", "brand", "model", "firmware", "metrics"));
            if (!"1".equals(string(root, "schema_version"))) throw invalid();
            if (!(root.get("metrics") instanceof java.util.List<?> raw) || raw.isEmpty() || raw.size() > 512) throw invalid();
            var metrics = new ArrayList<Metric>();
            for (Object item : raw) {
                Map<?, ?> m = object(item, Set.of("id", "oid", "type", "source_unit", "target_unit", "scale", "offset", "minimum", "maximum"));
                metrics.add(new Metric(string(m,"id"), string(m,"oid"), ValueType.valueOf(string(m,"type")), string(m,"source_unit"), string(m,"target_unit"),
                    VendorMappingCandidate.decimal(string(m,"scale")), VendorMappingCandidate.decimal(string(m,"offset")),
                    VendorMappingCandidate.decimal(string(m,"minimum")), VendorMappingCandidate.decimal(string(m,"maximum"))));
            }
            return new VendorMappingCandidate(string(root,"revision"), root.get("rollback_revision") == null ? null : string(root,"rollback_revision"),
                string(root,"brand"), string(root,"model"), string(root,"firmware"), metrics, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (Exception e) {
            // Parser errors may quote supplied data; never propagate a document snippet or nested cause.
            throw invalid();
        }
    }
    private static Map<?, ?> object(Object value, Set<String> keys) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(keys)) throw invalid();
        return map;
    }
    private static String string(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String value)) throw invalid();
        return value;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Vendor YAML candidate rejected; no mapping activated"); }
}
