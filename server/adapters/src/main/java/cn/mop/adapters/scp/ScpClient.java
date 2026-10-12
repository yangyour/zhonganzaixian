package cn.mop.adapters.scp;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Internal adapter only. Construction does not authenticate or start a polling worker. */
public final class ScpClient {
    public record Response(int status,String contentType,byte[] bytes){
        public Response{bytes=bytes.clone();}public byte[] bytes(){return bytes.clone();}
        @Override public String toString(){return "ScpResponse[status="+status+", body redacted]";}
    }
    @FunctionalInterface public interface Exchange {Response execute(ScpProtocol.Request request,Map<String,String> headers);}
    private final ScpSettings settings;private final Function<String,String> secrets;private final Exchange exchange;private final Clock clock;
    private final ScpProtocol.Scope scope;private ScpAuthentication.Token token;private Instant retryAfter=Instant.MIN;
    private final String legacyCookie="aCMPAuthToken="+UUID.randomUUID().toString().replace("-","");
    public static ScpClient configured(ScpSettings settings,Function<String,String> secrets){
        requireConfigured(settings,secrets);
        try{
            Path ca=settings.tls==ScpSettings.Tls.CA_FILE?Path.of(secret(secrets,settings.caFileRef)):null;
            return new ScpClient(settings,secrets,new ScpHttpsTransport(settings.baseUrl,ca),Clock.systemUTC());
        }catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("PRIVATE_CONFIGURATION_UNAVAILABLE");}
    }
    /** Injected exchange is for isolated protocol tests or another separately verified transport. */
    public ScpClient(ScpSettings settings,Function<String,String> secrets,Exchange exchange,Clock clock){
        requireConfigured(settings,secrets);this.settings=settings;this.secrets=secrets;this.exchange=Objects.requireNonNull(exchange);this.clock=Objects.requireNonNull(clock);scope=new ScpProtocol.Scope(settings.poolId,settings.projectId);
    }
    private static void requireConfigured(ScpSettings settings,Function<String,String> secrets){if(settings==null||secrets==null||!settings.problems(secrets).isEmpty())throw ScpProtocol.fail("CONFIG_PENDING");}
    private static String secret(Function<String,String> resolver,String ref){try{String value=ref==null?null:resolver.apply(ref);if(value==null||value.isEmpty())throw ScpProtocol.fail("CREDENTIAL_UNAVAILABLE");return value;}catch(Exception failure){throw ScpProtocol.fail("CREDENTIAL_UNAVAILABLE");}}
    private JsonNode exchange(ScpProtocol.Request request,Map<String,String> headers){
        try{var response=exchange.execute(request,Map.copyOf(headers));return ScpProtocol.envelope(response.status(),response.contentType(),response.bytes());}
        catch(ScpProtocol.Failure failure){throw failure;}catch(Exception failure){throw ScpProtocol.fail("HTTPS_TRANSPORT_UNAVAILABLE");}
    }
    private synchronized Map<String,String> headers(ScpProtocol.Request request){
        Instant now=clock.instant();if(now.isBefore(retryAfter))throw ScpProtocol.fail("AUTHENTICATION_BACKOFF");
        Map<String,String> headers=new HashMap<>();if(Boolean.TRUE.equals(settings.legacyCookieRequired))headers.put("Cookie",legacyCookie);
        if(settings.auth==ScpSettings.Auth.EC2){
            char[] key=secret(secrets,settings.secretKeyRef).toCharArray();
            try{headers.putAll(ScpAuthentication.ec2(request,secret(secrets,settings.accessKeyRef),key,settings.region,settings.service,now));}finally{Arrays.fill(key,'\0');}
            return headers;
        }
        if(token==null||!token.usableAt(now)){
            token=null;retryAfter=now.plusSeconds(60); // Technical throttle, not a customer account lock policy.
            boolean v2="V2".equals(settings.publicKeyApi);
            Map<String,String> keyHeaders=v2?Map.of():headers;
            String modulus=ScpProtocol.modulus(exchange(ScpProtocol.publicKey(v2),keyHeaders));
            char[] password=secret(secrets,settings.passwordRef).toCharArray();String encrypted;
            try{encrypted=ScpAuthentication.encryptPassword(password,modulus);}finally{Arrays.fill(password,'\0');}
            token=ScpAuthentication.token(exchange(ScpProtocol.authenticate(secret(secrets,settings.usernameRef),encrypted),headers),clock.instant());
            retryAfter=Instant.MIN;
        }
        headers.put("Authorization",token.authorization(clock.instant()));return headers;
    }
    private JsonNode read(ScpProtocol.Request request){
        try{return exchange(request,headers(request));}
        catch(ScpProtocol.Failure failure){
            if(Set.of("AUTHENTICATION_REJECTED","ACCESS_DENIED","RATE_LIMITED").contains(failure.reason)){
                synchronized(this){if(failure.reason.equals("AUTHENTICATION_REJECTED"))token=null;retryAfter=clock.instant().plusSeconds(60);}
            }
            throw failure; // No hidden retry or fallback on authentication/authorization failures.
        }
    }
    public Map<String,String> version(){JsonNode data=read(ScpProtocol.version());return Map.of("build_version",ScpProtocol.requiredText(data,"build_version",256));}
    public Map<String,String> resourcePool(){return ScpProtocol.decodePool(read(ScpProtocol.pool(scope)),scope);}
    public ScpProtocol.Page page(ScpProtocol.Kind kind,int pageNumber,int pageSize){
        var request=ScpProtocol.page(kind,scope,pageNumber,pageSize);
        return ScpProtocol.decodePage(read(request),kind,scope,pageNumber,pageSize,clock.instant());
    }
    @Override public String toString(){return "ScpClient[private settings, secrets and session redacted]";}
}
