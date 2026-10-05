package cn.mop.api;

import cn.mop.api.identity.ProjectPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProjectPasswordEncoderTest {
    private final ProjectPasswordEncoder encoder = new ProjectPasswordEncoder();
    private static final String EXAMPLE = "synthetic-unit-password-not-an-account";
    @Test void verifiesIndependentLibsodiumGoldenVector() {
        // PyNaCl 1.6.2 Argon2id kdf: 32 bytes, opslimit=2, memlimit=19456*1024,
        // public synthetic input below, fixed test-only salt b"0123456789abcdef".
        String encoded = "{mop-argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$MDEyMzQ1Njc4OWFiY2RlZg$3Edzk/dqtatdaIuE907mbMla/4XPa0rqomC30b3S+8w";
        assertTrue(encoder.matches("synthetic-cross-implementation-only", encoded));
        assertFalse(encoder.matches("synthetic-cross-implementation-only!", encoded));
    }
    @Test void producesVersionedArgon2idWithFreshSaltAndVerifies() {
        String first = encoder.encode(EXAMPLE), second = encoder.encode(EXAMPLE);
        assertNotEquals(first, second); assertTrue(first.startsWith("{mop-argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(encoder.matches(EXAMPLE, first)); assertTrue(encoder.matches(EXAMPLE, second));
        assertFalse(encoder.matches(EXAMPLE + "!", first)); assertFalse(encoder.upgradeEncoding(first));
        assertFalse(first.contains(EXAMPLE));
    }
    @Test void preservesWhitespaceUnicodeAndCharactersBeyondBcryptLimit() {
        String raw = "  密码🔒" + "x".repeat(100) + "甲\n\t ";
        String encoded = encoder.encode(raw);
        assertTrue(encoder.matches(raw, encoded)); assertFalse(encoder.matches(raw.strip(), encoded));
        assertFalse(encoder.matches(raw.replace("甲", "乙"), encoded));
    }
    @Test void doesNotNormalizeUnicodeOrTreatNulAsStringTerminator() {
        String raw = "e\u0301\0suffix", encoded = encoder.encode(raw);
        assertTrue(encoder.matches(raw, encoded)); assertFalse(encoder.matches("é\0suffix", encoded));
        assertFalse(encoder.matches("e\u0301", encoded));
    }
    @Test void doesNotEnableSuggestedMinimumLengthOrCharacterCompositionRules() {
        for (String raw : new String[]{"a", " "}) assertTrue(encoder.matches(raw, encoder.encode(raw)));
    }
    @Test void boundedMaximumIsNotSilentlyTruncated() {
        String raw = "x".repeat(ProjectPasswordEncoder.MAX_INPUT_CODE_UNITS), encoded = encoder.encode(raw);
        assertTrue(encoder.matches(raw, encoded)); assertFalse(encoder.matches(raw + "x", encoded));
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(raw + "x"));
    }
    @ParameterizedTest @ValueSource(strings = {"", "\uD800", "\uDC00", "a\uD800b"})
    void malformedOrMissingInputsFailWithoutEcho(String raw) {
        var failure = assertThrows(IllegalArgumentException.class, () -> encoder.encode(raw));
        assertEquals("Password input or stored encoding is invalid", failure.getMessage());
        assertFalse(encoder.matches(raw, "invalid"));
    }
    @Test void nullInputsNeverMatch() {
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(null));
        assertFalse(encoder.matches(null, null)); assertFalse(encoder.matches(EXAMPLE, null));
    }
    @ParameterizedTest @ValueSource(strings = {"plain", "{noop}plain", "{sha256}abc", "{bcrypt}$2a$10$invalid", "{unknown}$argon2id", "{mop-argon2id-v1}$argon2i", ""})
    void unsupportedStoredFormatsNeverFallback(String encoded) {
        assertFalse(encoder.matches(EXAMPLE, encoded));
        assertThrows(IllegalArgumentException.class, () -> encoder.upgradeEncoding(encoded));
    }
    @Test void storedCostAndAlgorithmTamperingFailClosed() {
        String encoded = encoder.encode(EXAMPLE);
        for (String changed : new String[]{encoded.replace("m=19456", "m=2147483647"), encoded.replace("t=2", "t=9999999"), encoded.replace("p=1", "p=999"), encoded.replace("v=19", "v=16"), encoded.replace("argon2id$", "argon2i$"), encoded + "=", encoded + "\n", "x".repeat(161)}) {
            assertFalse(encoder.matches(EXAMPLE, changed));
            assertThrows(IllegalArgumentException.class, () -> encoder.upgradeEncoding(changed));
        }
    }
    @Test void rejectsNonCanonicalBase64DespiteEquivalentDecodedBytes() {
        String encoded = encoder.encode(EXAMPLE), alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int index = encoded.length() - 1;
        char original = encoded.charAt(index), replacement = alphabet.charAt(alphabet.indexOf(original) + 1);
        String changed = encoded.substring(0, index) + replacement;
        String originalDigest = encoded.substring(encoded.lastIndexOf('$') + 1), changedDigest = changed.substring(changed.lastIndexOf('$') + 1);
        assertArrayEquals(java.util.Base64.getDecoder().decode(originalDigest), java.util.Base64.getDecoder().decode(changedDigest));
        assertFalse(encoder.matches(EXAMPLE, changed));
    }
    @Test void concurrentCallsDoNotShareSaltsOrMutableGeneratorState() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(index -> (java.util.concurrent.Callable<String>) () -> encoder.encode(EXAMPLE)).toList();
            var hashes = new java.util.HashSet<String>();
            for (var result : pool.invokeAll(tasks)) {
                String hash = result.get(); assertTrue(hashes.add(hash)); assertTrue(encoder.matches(EXAMPLE, hash));
            }
        } finally { pool.shutdownNow(); }
    }
    @Test void malformedCharSequenceCannotLeakItsException() {
        CharSequence raw = new CharSequence() {
            public int length() { throw new IllegalStateException("private-test-detail"); }
            public char charAt(int index) { return 'x'; }
            public CharSequence subSequence(int start, int end) { return this; }
            public String toString() { throw new IllegalStateException("private-test-detail"); }
        };
        var failure = assertThrows(IllegalArgumentException.class, () -> encoder.encode(raw));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-test-detail"));
        assertFalse(encoder.matches(raw, "invalid"));
    }
}
