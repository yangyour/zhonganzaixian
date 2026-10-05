package cn.mop.domain;

/** Implemented by the existing security framework, never by a custom password algorithm. */
public interface PasswordCodec {
    String encode(String value);
    boolean matches(String value, String encoded);
}
