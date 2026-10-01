package cn.mop.domain.assetimport;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.URI;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bare host syntax only: no resolution, reachability, address ownership or SSRF approval. */
public final class HostFormatValidator {
    private HostFormatValidator() {}

    public enum Kind { IPV4, IPV6, DOMAIN }
    public enum DomainMode { ASCII_ONLY, ASCII_OR_ACE, UNICODE_ROUNDTRIP }
    public enum LeadingZeroMode { REJECT, DECIMAL }
    public enum MappedIpv4Mode { PRESERVE_IPV6, CANONICAL_IPV4 }
    public enum WhitespaceMode { REJECT, STRIP }
    public enum RootDotMode { REJECT, PRESERVE, REMOVE }
    public enum NumericDomainMode { REJECT_LEGACY_ADDRESS_SHAPES, ALLOW_ALPHANUMERIC_LABELS }

    /** These choices are supplied rules, not inferred Q20/Q31 customer decisions. */
    public record Policy(boolean complete, DomainMode domainMode, boolean allowSingleLabel,
                         RootDotMode rootDotMode, boolean allowBracketedIpv6,
                         LeadingZeroMode leadingZeroMode, MappedIpv4Mode mappedIpv4Mode,
                         WhitespaceMode whitespaceMode, NumericDomainMode numericDomainMode) {
        public Policy {
            Objects.requireNonNull(domainMode, "domainMode");
            Objects.requireNonNull(leadingZeroMode, "leadingZeroMode");
            Objects.requireNonNull(mappedIpv4Mode, "mappedIpv4Mode");
            Objects.requireNonNull(whitespaceMode, "whitespaceMode");
            Objects.requireNonNull(rootDotMode, "rootDotMode");
            Objects.requireNonNull(numericDomainMode, "numericDomainMode");
        }
    }

    /** Raw input is retained even on failure. Canonical values are absent on failure. */
    public record Check(String original, String canonical, Kind kind, String error, String suggestion) {
        public boolean valid() { return error == null; }
    }

    public static Check check(String original, Policy policy) {
        Objects.requireNonNull(policy, "policy");
        if (original == null || original.isBlank()) return invalid(original, "地址为空", "填写裸IPv4、IPv6或域名");
        String value = policy.whitespaceMode() == WhitespaceMode.STRIP ? original.strip() : original;
        if (value.codePoints().anyMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)
                || Character.isISOControl(cp))) return invalid(original, "地址含空白或控制字符", "删除地址中的空白及控制字符");
        if (value.length() > 1024) return invalid(original, "地址超过格式检查技术上限", "使用符合DNS长度限制的裸地址");
        if (value.startsWith("[") && value.endsWith("]") && policy.allowBracketedIpv6()) {
            value = value.substring(1, value.length() - 1);
            if (!value.contains(":")) return invalid(original, "方括号只允许包装IPv6", "去除方括号并填写裸地址");
        }
        // This excludes URI decorations, scoped zone identifiers and authority injection before URI parsing.
        if (value.indexOf(':') >= 0) return ipv6(original, value, policy);
        if (value.chars().allMatch(cp -> cp == '.' || cp >= '0' && cp <= '9')) {
            String ipv4 = ipv4(value, policy.leadingZeroMode());
            return ipv4 == null ? invalid(original, "IPv4必须为四个0至255的十进制分段", "修正IPv4分段；不使用短写或整数地址")
                    : valid(original, ipv4, Kind.IPV4);
        }
        if (value.matches("(?i)0x[0-9a-f]+") && policy.numericDomainMode() == NumericDomainMode.REJECT_LEGACY_ADDRESS_SHAPES) {
            return invalid(original, "十六进制整数地址存在歧义", "使用四段十进制IPv4");
        }
        return domain(original, value, policy);
    }

    private static Check ipv6(String original, String value, Policy policy) {
        if (!value.matches("[0-9A-Fa-f:.]+")) return invalid(original, "IPv6或地址装饰格式非法", "使用裸IPv6；移除端口、路径、方括号和区域标识");
        try {
            // java.net.URI validates IPv6 grammar locally; no host lookup API is used.
            URI uri = new URI("http://[" + value + "]/");
            if (!Objects.equals(uri.getHost(), "[" + value + "]")) return invalid(original, "IPv6格式非法", "修正IPv6压缩或分段");
            String expandedInput = value;
            if (value.indexOf('.') >= 0) {
                int split = value.lastIndexOf(':');
                String tail = ipv4(value.substring(split + 1), policy.leadingZeroMode());
                if (tail == null) return invalid(original, "IPv6内嵌IPv4格式非法", "修正末尾四段十进制IPv4");
                String[] octets = tail.split("\\.");
                expandedInput = value.substring(0, split + 1)
                        + Integer.toHexString(Integer.parseInt(octets[0]) * 256 + Integer.parseInt(octets[1]))
                        + ":" + Integer.toHexString(Integer.parseInt(octets[2]) * 256 + Integer.parseInt(octets[3]));
            }
            byte[] bytes = ipv6Bytes(expandedInput);
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            mapped &= bytes[10] == (byte) 255 && bytes[11] == (byte) 255;
            if (mapped && policy.mappedIpv4Mode() == MappedIpv4Mode.CANONICAL_IPV4) {
                String canonical = (bytes[12] & 255) + "." + (bytes[13] & 255) + "." + (bytes[14] & 255) + "." + (bytes[15] & 255);
                return valid(original, canonical, Kind.IPV4);
            }
            // getByAddress constructs an address from bytes only; it cannot perform DNS.
            return valid(original, Inet6Address.getByAddress(null, bytes, -1).getHostAddress(), Kind.IPV6);
        } catch (java.net.URISyntaxException | IllegalArgumentException | UnknownHostException exception) {
            return invalid(original, "IPv6格式非法", "修正IPv6分段或压缩；不填写端口和路径");
        }
    }

    private static byte[] ipv6Bytes(String value) {
        String[] halves = value.split("::", -1);
        List<Integer> left = hextets(halves[0]);
        List<Integer> right = halves.length == 2 ? hextets(halves[1]) : List.of();
        List<Integer> all = new ArrayList<>(left);
        if (halves.length == 2) for (int i = left.size() + right.size(); i < 8; i++) all.add(0);
        all.addAll(right);
        if (all.size() != 8) throw new IllegalArgumentException("Invalid IPv6 size");
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[2 * i] = (byte) (all.get(i) >>> 8);
            bytes[2 * i + 1] = (byte) (all.get(i) & 255);
        }
        return bytes;
    }

    private static List<Integer> hextets(String value) {
        if (value.isEmpty()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String segment : value.split(":", -1)) result.add(Integer.parseInt(segment, 16));
        return result;
    }

    private static String ipv4(String value, LeadingZeroMode zeroMode) {
        String[] segments = value.split("\\.", -1);
        if (segments.length != 4) return null;
        List<String> normalized = new ArrayList<>();
        for (String segment : segments) {
            if (!segment.matches("[0-9]{1,3}") || zeroMode == LeadingZeroMode.REJECT
                    && segment.length() > 1 && segment.charAt(0) == '0') return null;
            int number = Integer.parseInt(segment);
            if (number > 255) return null;
            normalized.add(Integer.toString(number));
        }
        return String.join(".", normalized);
    }

    private static Check domain(String original, String value, Policy policy) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)
                || value.indexOf('\u3002') >= 0 || value.indexOf('\uff0e') >= 0 || value.indexOf('\uff61') >= 0) {
            return invalid(original, "域名Unicode形式存在歧义", "使用NFC文本与ASCII句点，或提供经过确认的ASCII域名");
        }
        boolean trailingDot = value.endsWith(".");
        if (trailingDot && policy.rootDotMode() == RootDotMode.REJECT) return invalid(original, "当前输入规则不允许尾随句点", "按已确认规则填写域名");
        String name = trailingDot ? value.substring(0, value.length() - 1) : value;
        boolean unicode = name.codePoints().anyMatch(cp -> cp > 127);
        if (unicode && policy.domainMode() != DomainMode.UNICODE_ROUNDTRIP) return invalid(original, "当前输入规则不允许Unicode域名", "提供经过确认的ASCII域名或IDN策略");
        try {
            String ascii = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            boolean ace = List.of(ascii.split("\\.", -1)).stream().anyMatch(label -> label.startsWith("xn--"));
            if (ace && policy.domainMode() == DomainMode.ASCII_ONLY) return invalid(original, "当前输入规则不允许IDN编码", "确认IDN策略后使用域名");
            if (unicode && !IDN.toUnicode(ascii, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT)
                    .equals(name.toLowerCase(Locale.ROOT))) return invalid(original, "JDK IDNA映射无法无损往返", "提供经确认的ASCII域名；不静默映射歧义字符");
            String[] labels = ascii.split("\\.", -1);
            if (policy.numericDomainMode() == NumericDomainMode.REJECT_LEGACY_ADDRESS_SHAPES
                    && java.util.Arrays.stream(labels).allMatch(label -> label.matches("(?:[0-9]+|0x[0-9a-f]+)"))) {
                return invalid(original, "域名规范值与旧式数字地址存在歧义", "使用四段十进制IPv4或无数字地址歧义的域名");
            }
            if (ascii.length() > 253 || labels.length == 1 && !policy.allowSingleLabel()) return invalid(original, "域名长度或单标签形式不符合输入规则", "使用符合已确认规则的域名");
            for (String label : labels) {
                if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return invalid(original, "域名标签格式非法", "标签使用字母、数字和内部连字符，每标签最多63字符");
                if (label.startsWith("xn--")) {
                    String decoded = IDN.toUnicode(label, IDN.USE_STD3_ASCII_RULES);
                    if (decoded.equalsIgnoreCase(label) || !IDN.toASCII(decoded, IDN.USE_STD3_ASCII_RULES).equalsIgnoreCase(label)) {
                        return invalid(original, "IDN编码无法无损往返", "提供经过确认的ASCII域名或有效IDN编码");
                    }
                }
            }
            // Removing the DNS root dot changes absolute-name semantics and must be explicitly selected.
            return valid(original, trailingDot && policy.rootDotMode() == RootDotMode.PRESERVE ? ascii + "." : ascii, Kind.DOMAIN);
        } catch (IllegalArgumentException exception) {
            return invalid(original, "域名格式非法", "填写裸域名；删除端口、路径、用户信息和非法标签");
        }
    }

    private static Check invalid(String original, String error, String suggestion) {
        return new Check(original, null, null, error, suggestion);
    }

    private static Check valid(String original, String canonical, Kind kind) {
        return new Check(original, canonical, kind, null, original.equals(canonical) ? null : "使用规范地址值");
    }
}
