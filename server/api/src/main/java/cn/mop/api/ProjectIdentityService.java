package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.persistence.ProjectIdentityRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ProjectIdentityService {
    private final ProjectIdentityRepository repository;
    private final ObjectMapper mapper;
    private final byte[] commandKey;
    private final boolean oidcEnabled;
    public ProjectIdentityService(ProjectIdentityRepository repository,ObjectMapper mapper,String commandKey,boolean oidcEnabled){this.repository=repository;this.mapper=mapper;this.commandKey=HexFormat.of().parseHex(commandKey);this.oidcEnabled=oidcEnabled;}
    public ProjectIdentityRepository repository(){return repository;}
    public boolean ready(){return repository.ready();}
    public boolean oidcEnabled(){return oidcEnabled;}
    public Map<String,Object> options(){return ProjectIdentityRepository.result("mode","remote_test","identity_source","project_local","local_enabled",true,"sso_enabled",oidcEnabled,"login_url",oidcEnabled?"/oauth2/authorization/mop":null,"local_login_url","/api/v1/auth/local/login","csrf_url","/api/v1/auth/csrf","sso_protocol","OIDC");}
    public Map<String,Object> readiness(){return ProjectIdentityRepository.result("mode","PROJECT_LOCAL","ready",ready(),"sso_enabled",oidcEnabled,"sso_protocol","OIDC");}
    public record LocalPrincipal(String userId,long securityVersion) implements ProjectSessionIdentity {
        public String getName(){return userId;}
        public String identitySource(){return "project_local";}
    }
    public LocalPrincipal principal(ProjectIdentityRepository.Authentication identity){return new LocalPrincipal(identity.userId(),identity.securityVersion());}
    public String commandHash(JsonNode body){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(commandKey,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(mapper.writeValueAsBytes(canonical(body))));}
        catch(Exception failure){throw new IllegalStateException("Sensitive command fingerprint unavailable");}
    }
    private JsonNode canonical(JsonNode node){
        if(node.isObject()){ObjectNode value=mapper.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(name->value.set(name,canonical(node.get(name))));return value;}
        if(node.isArray()){ArrayNode value=mapper.createArrayNode();node.forEach(item->value.add(canonical(item)));return value;}
        return node;
    }
    static String key(HttpServletRequest request){String value=request.getHeader("Idempotency-Key");if(value==null||!value.matches("[A-Za-z0-9._:-]{1,128}"))throw new DomainException(400,"VALIDATION_ERROR","需要有效的原请求标识");return value;}
}
