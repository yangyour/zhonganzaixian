package cn.mop.adapters;

import java.net.URI;
import java.util.Objects;
import java.util.function.Function;

/** Operator configuration only. API callers cannot select endpoints, buckets or credentials. */
public record MinioStorageSettings(String endpoint, String bucket, String accessKey, String secretKey,
                                   String region, Scope scope) {
    public enum Scope { PHOTOS, IMPORTS }
    public MinioStorageSettings {
        Objects.requireNonNull(scope);
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && privateHttpHost(host))) throw invalid();
            if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) throw invalid();
            if (!credential(accessKey) || !credential(secretKey)) throw invalid();
            if (region != null && !region.matches("[a-z0-9][a-z0-9-]{0,62}")) throw invalid();
        } catch (RuntimeException failure) { throw invalid(); }
    }
    public static MinioStorageSettings from(Function<String, String> env, Scope scope) {
        Objects.requireNonNull(env); Objects.requireNonNull(scope);
        if (!"true".equalsIgnoreCase(env.apply("OBJECT_STORAGE_ENABLED"))
                || !"minio".equals(env.apply("OBJECT_STORAGE_PROVIDER"))) throw invalid();
        String capability = scope == Scope.PHOTOS ? "PHOTO" : "IMPORT";
        String legacy = scope == Scope.PHOTOS ? "MOP_S3_" : "MOP_IMPORT_S3_";
        return new MinioStorageSettings(first(env,"OBJECT_STORAGE_ENDPOINT",legacy+"ENDPOINT"),
                first(env,"OBJECT_STORAGE_"+capability+"_BUCKET","OBJECT_STORAGE_BUCKET",legacy+"BUCKET"),
                first(env,"OBJECT_STORAGE_"+capability+"_ACCESS_KEY","OBJECT_STORAGE_ACCESS_KEY",legacy+"ACCESS_KEY"),
                first(env,"OBJECT_STORAGE_"+capability+"_SECRET_KEY","OBJECT_STORAGE_SECRET_KEY",legacy+"SECRET_KEY"),
                env.apply("OBJECT_STORAGE_REGION"),scope);
    }
    private static String first(Function<String,String> env,String... keys) {
        for (String key:keys) { String value=env.apply(key); if(value!=null)return value; }
        return null;
    }
    private static boolean credential(String value) {
        return value!=null&&!value.isBlank()&&value.codePoints().noneMatch(c->Character.isISOControl(c)||Character.isWhitespace(c));
    }
    private static boolean privateHttpHost(String host) {
        if (host.equals("localhost")||host.equals("::1")||host.equals("[::1]")) return true;
        String[] parts=host.split("\\.",-1);
        if(parts.length!=4)return false;
        int[] octets=new int[4];
        for(int i=0;i<4;i++){
            if(!parts[i].matches("0|[1-9][0-9]{0,2}"))return false;
            octets[i]=Integer.parseInt(parts[i]);if(octets[i]>255)return false;
        }
        return octets[0]==127||octets[0]==10||octets[0]==192&&octets[1]==168||octets[0]==172&&octets[1]>=16&&octets[1]<=31;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Explicit MinIO configuration is incomplete or invalid"); }
    @Override public String toString() { return "MinioStorageSettings["+scope+",credentials=REDACTED]"; }
}
