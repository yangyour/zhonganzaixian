package cn.mop.api;

import cn.mop.domain.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared validation for the historical draft and tenant revision endpoints. */
final class ConfigurationValueValidator {
    private ConfigurationValueValidator() {}

    static void validate(Map<String, Object> entry, JsonNode value) {
        if (value == null || value.isNull()) throw invalid("proposed_value 必须非null且符合登记类型");
        boolean valid = switch (String.valueOf(entry.get("type"))) {
            case "integer" -> value.isIntegralNumber() && value.canConvertToLong();
            case "number" -> value.isNumber() && Double.isFinite(value.doubleValue());
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual() && value.asText().codePointCount(0, value.asText().length()) <= 2000;
            default -> false;
        };
        if (!valid || value.toString().length() > 16000) throw invalid("proposed_value 与配置登记类型不符或过长");
        inspect(value);
    }

    private static void inspect(JsonNode value) {
        if (value.isObject()) value.fields().forEachRemaining(field -> {
            String key = field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (Set.of("password", "secret", "token", "privatekey", "credential", "actorid", "tenantid", "accesstoken", "refreshtoken", "idtoken", "csrftoken", "clientsecret", "apikey", "apitoken", "bearertoken", "clientpassword", "secretkey").contains(key))
                throw invalid("草稿不得保存秘密、主体或租户字段");
            if (field.getKey().codePoints().anyMatch(ConfigurationValueValidator::invalidCharacter)) throw invalid("proposed_value 不得含控制字符或无效Unicode");
            inspect(field.getValue());
        });
        else if (value.isArray()) value.forEach(ConfigurationValueValidator::inspect);
        else if (value.isTextual() && value.asText().codePoints().anyMatch(ConfigurationValueValidator::invalidCharacter)) throw invalid("proposed_value 不得含控制字符或无效Unicode");
    }

    static boolean invalidCharacter(int value) { return Character.isISOControl(value) || Character.getType(value) == Character.SURROGATE; }

    private static DomainException invalid(String message) { return new DomainException(400, "VALIDATION_ERROR", message); }
}
