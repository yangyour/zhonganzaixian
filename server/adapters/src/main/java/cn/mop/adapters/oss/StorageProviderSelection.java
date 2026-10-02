package cn.mop.adapters.oss;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** One fail-closed selector shared by API and workers. Partial OSS settings never select legacy MinIO. */
public record StorageProviderSelection(Mode mode,OssStorageSettings ossSettings) {
    public enum Mode { LEGACY_MINIO, DISABLED, OSS }
    private static final List<String> KEYS=List.of("OBJECT_STORAGE_ENABLED","OBJECT_STORAGE_PROVIDER","OBJECT_STORAGE_ENDPOINT",
            "OBJECT_STORAGE_PUBLIC_ENDPOINT","OBJECT_STORAGE_REGION","OBJECT_STORAGE_BUCKET","OBJECT_STORAGE_ACCESS_KEY",
            "OBJECT_STORAGE_SECRET_KEY","OBJECT_STORAGE_SECURITY_TOKEN","OBJECT_STORAGE_PRESIGN_EXPIRES","MOP_OBJECT_STORAGE_PREFIX");
    public StorageProviderSelection {
        Objects.requireNonNull(mode);
        if((mode==Mode.OSS)!=(ossSettings!=null))throw new IllegalArgumentException("Inconsistent storage provider selection");
    }
    public static boolean explicitlyConfigured(Function<String,String> env){return KEYS.stream().anyMatch(key->env.apply(key)!=null);}
    public static boolean capabilityRequested(Function<String,String> env){return explicitlyConfigured(env)&&!"false".equalsIgnoreCase(env.apply("OBJECT_STORAGE_ENABLED"));}
    public static StorageProviderSelection from(Function<String,String> env){
        Objects.requireNonNull(env);
        if(!explicitlyConfigured(env))return new StorageProviderSelection(Mode.LEGACY_MINIO,null);
        if("false".equalsIgnoreCase(env.apply("OBJECT_STORAGE_ENABLED")))return new StorageProviderSelection(Mode.DISABLED,null);
        return new StorageProviderSelection(Mode.OSS,OssStorageSettings.from(env));
    }
    @Override public String toString(){return "StorageProviderSelection["+mode+",credentials=REDACTED]";}
}
