package cn.mop.persistence;

import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.RecoveryObservation;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Strict consumer for the recovery_observation section of the published monitor.threshold_matrix. */
public final class MonitoringRecoveryRuleSet {
    private static final Set<String> RULE_FIELDS = Set.of("asset_id", "rule_code", "metric", "comparison",
        "threshold", "unit", "hold_seconds", "max_gap_seconds", "minimum_samples");

    private MonitoringRecoveryRuleSet() {}

    /** Other threshold consumers may own other root sections; this namespace never interprets their values. */
    public static void validate(JsonNode matrix) {
        if (matrix != null && matrix.isObject() && matrix.has("recovery_observation")) {
            parse(matrix, null, null);
        }
    }

    public static RecoveryObservation.Rule resolve(JsonNode matrix, String assetId, String ruleCode) {
        var selected = parse(matrix, assetId, ruleCode);
        if (selected == null || !selected.complete()) {
            throw new DomainException(422, "CONFIG_PENDING", "当前资产和规则的恢复观察策略尚未完整发布");
        }
        return selected;
    }

    private static RecoveryObservation.Rule parse(JsonNode matrix, String assetId, String ruleCode) {
        if (matrix == null || !matrix.isObject() || !matrix.has("recovery_observation")) {
            throw new DomainException(422, "CONFIG_PENDING", "已发布阈值矩阵缺少恢复观察策略");
        }
        JsonNode section = matrix.get("recovery_observation");
        exact(section, Set.of("schema_version", "rules"));
        if (!section.path("schema_version").isIntegralNumber() || !section.path("schema_version").canConvertToInt()
                || section.path("schema_version").intValue() != 1
                || !section.path("rules").isArray() || section.path("rules").size() > 1000) {
            throw invalid();
        }
        var seen = new HashSet<String>();
        RecoveryObservation.Rule selected = null;
        for (JsonNode row : section.get("rules")) {
            exact(row, RULE_FIELDS);
            String asset = text(row.get("asset_id"));
            String code = text(row.get("rule_code"));
            try {
                if (!UUID.fromString(asset).toString().equals(asset)) {
                    throw invalid();
                }
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }
            if (code.isBlank() || code.length() > 64 || !code.equals(code.strip().toUpperCase(Locale.ROOT))
                    || code.codePoints().anyMatch(Character::isISOControl) || !seen.add(asset + "|" + code)) {
                throw invalid();
            }
            RecoveryObservation.Comparison comparison = null;
            if (!row.get("comparison").isNull()) {
                try {
                    comparison = RecoveryObservation.Comparison.valueOf(text(row.get("comparison")));
                } catch (IllegalArgumentException failure) {
                    throw invalid();
                }
            }
            JsonNode minimum = row.get("minimum_samples");
            if (!minimum.isNull() && (!minimum.isIntegralNumber() || !minimum.canConvertToInt())) {
                throw invalid();
            }
            var rule = new RecoveryObservation.Rule(text(row.get("metric")), comparison,
                decimal(row.get("threshold")), row.get("unit").isNull() ? null : text(row.get("unit")),
                decimal(row.get("hold_seconds")), decimal(row.get("max_gap_seconds")),
                minimum.isNull() ? null : minimum.intValue());
            if (asset.equals(assetId) && code.equals(ruleCode)) {
                selected = rule;
            }
        }
        return selected;
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        String raw = text(value);
        if (!raw.matches("-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?") || raw.matches("-0(?:\\.0+)?")) {
            throw invalid();
        }
        return new BigDecimal(raw);
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static void exact(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject() || value.size() != keys.size() || !keys.stream().allMatch(value::has)) {
            throw invalid();
        }
    }

    private static DomainException invalid() {
        return new DomainException(400, "RECOVERY_POLICY_INVALID", "恢复观察策略字段、数值或资产规则绑定无效");
    }
}
