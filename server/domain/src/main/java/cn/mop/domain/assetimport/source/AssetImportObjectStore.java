package cn.mop.domain.assetimport.source;
/** Independent project import-purpose immutable bytes; never a photo account or a presigned URL. */
public interface AssetImportObjectStore {
    boolean ready();
    void storeImmutable(String key,byte[] original,ImportFormat declaredFormat,String sha256);
    byte[] read(String key,long expectedSize,String sha256);
}
