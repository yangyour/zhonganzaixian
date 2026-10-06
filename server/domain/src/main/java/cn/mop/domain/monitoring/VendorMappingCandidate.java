package cn.mop.domain.monitoring;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;

/** Unpublished, unverified mapping data. A syntactically valid candidate is never a device capability. */
public record VendorMappingCandidate(String revision, String rollbackRevision, String brand,
        String model, String firmware, List<Metric> metrics, String sourceSha256) {
    public VendorMappingCandidate {
        text(revision); if (rollbackRevision != null) { text(rollbackRevision); if (rollbackRevision.equals(revision)) throw invalid(); }
        text(brand); text(model); text(firmware);
        if (metrics == null || metrics.isEmpty() || metrics.size() > 512 || sourceSha256 == null || !sourceSha256.matches("[0-9a-f]{64}")) throw invalid();
        metrics = List.copyOf(metrics);
        var ids = new HashSet<String>();
        for (var metric : metrics) if (metric == null || !ids.add(metric.id())) throw invalid();
    }
    public boolean effective() { return false; }
    public boolean sourceVerified() { return false; }
    public enum ValueType { GAUGE, COUNTER }
    public record Metric(String id, String oid, ValueType type, String sourceUnit, String targetUnit,
                         BigDecimal scale, BigDecimal offset, BigDecimal minimum, BigDecimal maximum) {
        public Metric {
            if (id == null || !id.matches("[a-z][a-z0-9_.-]{0,63}") || type == null) throw invalid();
            validateOid(oid); text(sourceUnit); text(targetUnit);
            bounded(scale); bounded(offset); bounded(minimum); bounded(maximum);
            if (scale.signum() <= 0 || minimum.compareTo(maximum) > 0) throw invalid();
        }
        /** Exact supplied affine conversion only. No rate, percent denominator, rounding or unit inference. */
        public BigDecimal convert(String raw) {
            BigDecimal input = decimal(raw);
            if (type == ValueType.COUNTER && (input.signum() < 0 || input.stripTrailingZeros().scale() > 0)) throw invalid();
            BigDecimal result = input.multiply(scale).add(offset);
            bounded(result);
            if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) throw invalid();
            return result;
        }
    }
    public static BigDecimal decimal(String value) {
        if (value == null || value.length() > 82 || !value.matches("-?(?:0|[1-9][0-9]{0,39})(?:\\.[0-9]{1,40})?")) throw invalid();
        BigDecimal result = new BigDecimal(value); bounded(result); return result;
    }
    private static void bounded(BigDecimal value) {
        if (value == null || value.precision() > 80 || value.scale() < 0 || value.scale() > 40 || value.precision() - value.scale() > 40) throw invalid();
    }
    private static void text(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    }
    private static void validateOid(String value) {
        if (value == null || value.length() > 1408 || !value.matches("[0-2](?:\\.(?:0|[1-9][0-9]{0,9})){1,127}")) throw invalid();
        String[] arcs = value.split("\\.");
        if (!arcs[0].equals("2") && new BigInteger(arcs[1]).compareTo(BigInteger.valueOf(39)) > 0) throw invalid();
        for (String arc : arcs) if (new BigInteger(arc).compareTo(new BigInteger("4294967295")) > 0) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid unpublished vendor mapping candidate"); }
}
