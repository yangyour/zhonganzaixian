package cn.mop.domain.configuration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A detached JSON-shaped tree; null is preserved, and arbitrary mutable objects are rejected. */
final class ImmutableConfigurationValue {
    private ImmutableConfigurationValue() {}

    static Object freeze(Object value) { return freeze(value, new IdentityHashMap<>()); }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) return value;
        // BigInteger and BigDecimal are extensible; subclasses may carry mutable or overridden state.
        if (value.getClass() == BigInteger.class || value.getClass() == BigDecimal.class) return value;
        if (value instanceof Double number && Double.isFinite(number)) return value;
        if (value instanceof Float number && Float.isFinite(number)) return value;
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) throw new IllegalArgumentException("Configuration values must be finite JSON values");
        if (ancestors.put(value, true) != null) throw new IllegalArgumentException("Configuration value contains a cycle");
        try {
            if (value instanceof Map<?, ?> object) {
                Map<String, Object> copy = new LinkedHashMap<>();
                object.forEach((key, entry) -> {
                    if (!(key instanceof String text)) throw new IllegalArgumentException("Configuration object keys must be strings");
                    copy.put(text, freeze(entry, ancestors));
                });
                return Collections.unmodifiableMap(copy);
            }
            List<Object> copy = new ArrayList<>();
            for (Object entry : (List<?>) value) copy.add(freeze(entry, ancestors));
            return Collections.unmodifiableList(copy);
        } finally {
            ancestors.remove(value);
        }
    }
}
