package cn.mop.api;

import cn.mop.domain.PasswordCodec;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** Use the reviewed Argon2id primitive; retain a bounded reader for V27's PBKDF2 format. */
final class ProjectPasswordCodec implements PasswordCodec {
    static final String PREFIX="{pbkdf2-sha256-v1}";
    private final Pbkdf2PasswordEncoder encoder=new Pbkdf2PasswordEncoder("",16,600000,256);
    private final cn.mop.api.identity.ProjectPasswordEncoder preferred=new cn.mop.api.identity.ProjectPasswordEncoder();
    ProjectPasswordCodec(){encoder.setAlgorithm(Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);}
    public String encode(String value){return preferred.encode(value);}
    public boolean matches(String value,String encoded){
        if(encoded!=null&&encoded.startsWith("{mop-argon2id-v1}"))return preferred.matches(value,encoded);
        return encoded!=null&&encoded.startsWith(PREFIX)&&encoded.substring(PREFIX.length()).matches("[0-9a-f]{96}")&&encoder.matches(value,encoded.substring(PREFIX.length()));
    }
}
