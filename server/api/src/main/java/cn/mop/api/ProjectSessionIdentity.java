package cn.mop.api;
import java.io.Serializable;
import java.security.Principal;

interface ProjectSessionIdentity extends Principal, Serializable {
    long securityVersion();
    String identitySource();
}
