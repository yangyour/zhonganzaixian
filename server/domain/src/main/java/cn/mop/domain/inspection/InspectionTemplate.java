package cn.mop.domain.inspection;

import cn.mop.domain.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Data-defined checklist candidate. Defining a template never approves or publishes it. */
public record InspectionTemplate(String deviceType, String name, List<Item> items) {
    public static final int MAX_ITEMS = 100; // Technical input bound, not a contractual device/check quota.
    public InspectionTemplate {
        key(deviceType); text(name, 200, false, false);
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) throw invalid();
        var seen = new HashSet<String>();
        for (Item item : items) if (item == null || !seen.add(item.key())) throw invalid();
        items = List.copyOf(items);
    }
    /** Section and label are data as well: adding a device type does not require another Java enum. */
    public record Item(String key, String section, String label, String instruction) {
        public Item { InspectionTemplate.key(key); text(section, 100, false, false); text(label, 200, false, false); text(instruction, 2000, true, true); }
    }
    /** Ordered, length-delimited UTF-8. Null instruction and explicit empty instruction stay distinct. */
    public byte[] canonicalBytes() {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                field(out, "MOP_INSPECTION_TEMPLATE_V1"); field(out, deviceType); field(out, name); out.writeInt(items.size());
                for (Item item : items) { field(out, item.key()); field(out, item.section()); field(out, item.label()); field(out, item.instruction()); }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException("Inspection template encoding unavailable", impossible); }
    }
    public String fingerprint() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes())); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Inspection template fingerprint unavailable", impossible); }
    }
    public String approvalStatus() { return "UNVERIFIED"; }
    public boolean publishable() { return false; }

    /** Caller-supplied revision identity is integrity-checked, not authenticated or saved by this pure model. */
    public record Snapshot(UUID tenantId, UUID templateId, long version, InspectionTemplate definition, String contentSha256) {
        public Snapshot {
            if (tenantId == null || templateId == null || version < 1 || version > 9_007_199_254_740_990L || definition == null || contentSha256 == null || !contentSha256.equals(definition.fingerprint())) throw invalid();
        }
        public static Snapshot capture(UUID tenantId, UUID templateId, long version, InspectionTemplate definition) {
            if (definition == null) throw invalid();
            return new Snapshot(tenantId, templateId, version, definition, definition.fingerprint());
        }
    }
    static void key(String value) { if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid(); }
    static void text(String value, int maximum, boolean nullable, boolean multiline) {
        if (value == null) { if (nullable) return; throw invalid(); }
        if (value.codePointCount(0, value.length()) > maximum || !nullable && (value.isEmpty() || value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xfeff)) || value.codePoints().anyMatch(cp -> Character.getType(cp) == Character.SURROGATE || Character.isISOControl(cp) && !(multiline && (cp == '\n' || cp == '\r' || cp == '\t')))) throw invalid();
    }
    static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "巡检模板、检查项或填写结果无效；空白结果不能推断为正常"); }
    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
}
