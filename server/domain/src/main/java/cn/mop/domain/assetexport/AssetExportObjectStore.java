package cn.mop.domain.assetexport;

/** Private immutable export objects. No public URLs, delete, or credential disclosure. */
public interface AssetExportObjectStore {
    boolean ready();
    void storeImmutable(String key, byte[] bytes, String sha256);
    byte[] read(String key, long size, String sha256);
}
