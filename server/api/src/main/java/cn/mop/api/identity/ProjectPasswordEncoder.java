package cn.mop.api.identity;

import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Local-password primitive only. Not registered as a bean; no login, account creation or persistence. */
public final class ProjectPasswordEncoder implements PasswordEncoder {
    public static final String ENCODING_ID = "mop-argon2id-v1";
    /** Resource bound, not an approved minimum-length/history/lockout business policy. */
    public static final int MAX_INPUT_CODE_UNITS = 4096;
    private static final Pattern FORMAT = Pattern.compile("^\\{mop-argon2id-v1}\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})$");
    private final PasswordEncoder delegate = new DelegatingPasswordEncoder(ENCODING_ID,
        Map.of(ENCODING_ID, new Argon2PasswordEncoder(16, 32, 1, 19456, 2)));

    @Override public String encode(CharSequence rawPassword) {
        String raw = snapshot(rawPassword);
        if (raw == null) throw invalid();
        try { return delegate.encode(raw); }
        catch (RuntimeException failure) { throw new IllegalStateException("Password encoding unavailable"); }
    }
    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
        String raw = snapshot(rawPassword);
        if (raw == null || !supported(encodedPassword)) return false;
        try { return delegate.matches(raw, encodedPassword); }
        catch (RuntimeException failure) { return false; }
    }
    @Override public boolean upgradeEncoding(String encodedPassword) {
        if (!supported(encodedPassword)) throw invalid();
        return delegate.upgradeEncoding(encodedPassword);
    }
    /** Exact allowlist prevents corrupt stored PHC parameters from allocating unbounded memory/CPU. */
    private static boolean supported(String encoded) {
        if (encoded == null || encoded.length() > 160) return false;
        var match = FORMAT.matcher(encoded);
        if (!match.matches()) return false;
        try {
            for (int index = 1; index <= 2; index++) {
                var segment = match.group(index);
                byte[] decoded = Base64.getDecoder().decode(segment);
                if (decoded.length != (index == 1 ? 16 : 32)
                    || !Base64.getEncoder().withoutPadding().encodeToString(decoded).equals(segment)) return false;
            }
            return true;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String snapshot(CharSequence value) {
        if (value == null) return null;
        String raw;
        try {
            if (value.length() < 1 || value.length() > MAX_INPUT_CODE_UNITS) return null;
            raw = value.toString();
        } catch (RuntimeException invalid) { return null; }
        if (raw == null || raw.isEmpty() || raw.length() > MAX_INPUT_CODE_UNITS) return null;
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (Character.isHighSurrogate(c)) {
                if (++index >= raw.length() || !Character.isLowSurrogate(raw.charAt(index))) return null;
            } else if (Character.isLowSurrogate(c)) return null;
        }
        return raw;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Password input or stored encoding is invalid");
    }
}
