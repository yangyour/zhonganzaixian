package cn.mop.adapters.oss;

import java.util.Objects;
import java.util.function.Function;

/** Explicit owner-supplied OSS settings. No credentials or fallback endpoint in diagnostics. */
public record OssStorageSettings(String endpoint, String publicEndpoint, String region, String bucket,
        String accessKey, String secretKey, String securityToken, String prefix, int presignExpiresSeconds) {
    public OssStorageSettings {
        if (!"cn-beijing".equals(region) || !"https://oss-cn-beijing.aliyuncs.com".equals(endpoint)
                || !endpoint.equals(publicEndpoint)) throw invalid();
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw invalid();
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) throw invalid();
        if (securityToken != null && securityToken.isBlank()) throw invalid();
        // A fresh explicitly chosen deployment namespace, never the bucket root or an arbitrary prefix.
        if (prefix == null || !prefix.matches("mop-niko-test/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/")) throw invalid();
        if (presignExpiresSeconds < 1 || presignExpiresSeconds > 3600) throw invalid();
    }
    public static OssStorageSettings from(Function<String,String> env) {
        Objects.requireNonNull(env);
        if (!"true".equalsIgnoreCase(env.apply("OBJECT_STORAGE_ENABLED")) || !"oss".equals(env.apply("OBJECT_STORAGE_PROVIDER"))) throw invalid();
        try {
            return new OssStorageSettings(env.apply("OBJECT_STORAGE_ENDPOINT"), env.apply("OBJECT_STORAGE_PUBLIC_ENDPOINT"),
                    env.apply("OBJECT_STORAGE_REGION"), env.apply("OBJECT_STORAGE_BUCKET"), env.apply("OBJECT_STORAGE_ACCESS_KEY"),
                    env.apply("OBJECT_STORAGE_SECRET_KEY"), env.apply("OBJECT_STORAGE_SECURITY_TOKEN"), env.apply("MOP_OBJECT_STORAGE_PREFIX"),
                    Integer.parseInt(env.apply("OBJECT_STORAGE_PRESIGN_EXPIRES")));
        } catch (RuntimeException failure) { throw invalid(); }
    }
    @Override public String toString() { return "OssStorageSettings[credentials=REDACTED]"; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Explicit scoped OSS configuration is incomplete or invalid"); }
}
