package cn.mop.domain;
/** Immutable upload and authorized proxy read only; no deletion or bare download URL. */
public interface PhotoObjectStore {
    boolean ready();
    void storeImmutable(String key,byte[] content,String mediaType,String sha256);
    byte[] read(String key,long expectedSize,String sha256);
}
