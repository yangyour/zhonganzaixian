package cn.mop.api;

import cn.mop.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

@Service @Profile({"local_fixture","remote_test"})
public class SessionService {
    private final PlatformRepository repository;
    private final ObjectMapper mapper;
    private final SecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrfRepository;
    private final ObjectProvider<OidcClientInitiatedLogoutSuccessHandler> oidcLogout;
    private record Access(FixtureUser user,Tenant tenant,Membership membership,List<String> capabilities){}
    public SessionService(PlatformRepository repository,ObjectMapper mapper,SecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrfRepository,ObjectProvider<OidcClientInitiatedLogoutSuccessHandler> oidcLogout){
        this.repository=repository;this.mapper=mapper;this.contexts=contexts;this.csrfRepository=csrfRepository;this.oidcLogout=oidcLogout;
    }
    public Map<String,Object> authOptions(){
        boolean remote=repository.metadata().mode().equals("remote_test");
        return map("mode",repository.metadata().mode(),"identity_source",repository.metadata().identitySource(),"login_url",remote?"/oauth2/authorization/mop":null);
    }
    public void establishOidcSession(Authentication authentication,HttpServletRequest request,HttpServletResponse response){
        if(!(authentication instanceof OAuth2AuthenticationToken))throw new DomainException(401,"SESSION_EXPIRED","认证会话无效");
        String actor=authentication.getName();
        repository.user(actor).orElseThrow(SessionService::notFound);
        List<Tenant> tenants=repository.allowedTenants(actor);
        if(tenants.isEmpty())throw new DomainException(401,"SESSION_EXPIRED","此身份没有有效成员关系");
        String tenant=tenants.get(0).id();
        validMembership(repository.membership(actor,tenant).orElseThrow(SessionService::notFound));
        repository.audit(actor,tenant,"oidc.login","session",null,"SUCCEEDED","KEYCLOAK_VERIFIED_IDENTITY",trace(request));
        var browserSession=request.getSession(true);
        int timeout=request.getServletContext().getSessionTimeout();
        browserSession.setMaxInactiveInterval(timeout>0?timeout*60:1800);
        browserSession.setAttribute("mop.tenant_id",tenant);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        CsrfToken token=csrfRepository.generateToken(request);csrfRepository.saveToken(token,request,response);
    }
    public Object fixtureLogin(JsonNode body,HttpServletRequest request,HttpServletResponse response){
        if(!repository.metadata().mode().equals("local_fixture"))throw notFound();
        fields(body,Set.of("user_id"));sameOrigin(request);String userId=text(body,"user_id",36);
        FixtureUser user=repository.user(userId).orElseThrow(SessionService::notFound);
        List<Tenant> allowed=repository.allowedTenants(userId);if(allowed.isEmpty())throw new DomainException(401,"SESSION_EXPIRED","此历史夹具身份已撤权");
        String tenant=allowed.get(0).id();
        validMembership(repository.membership(userId,tenant).orElseThrow(SessionService::notFound));
        repository.idempotent(userId,tenant,route(request),requestKey(request),hash(body),()->userId);
        if(request.getSession(false)!=null)request.getSession(false).invalidate();
        request.getSession(true).setAttribute("mop.tenant_id",tenant);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId,null,List.of()));
        SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        CsrfToken token=csrfRepository.generateToken(request);csrfRepository.saveToken(token,request,response);
        repository.audit(userId,tenant,"fixture.login","session",null,"SUCCEEDED","LOCAL_FIXTURE_IDENTITY",trace(request));
        Membership current=validMembership(repository.membership(userId,tenant).orElseThrow());
        return sessionData(new Access(user,repository.tenant(tenant),current,current.capabilities()),token.getToken());
    }
    public Object session(HttpServletRequest request){return sessionData(access(request),csrf(request));}
    public Object switchTenant(JsonNode body,HttpServletRequest request){
        Access before=access(request);fields(body,Set.of("tenant_id"));String target=text(body,"tenant_id",36);
        validMembership(repository.membership(before.user.id(),target).orElseThrow(SessionService::notFound));
        // Durable replay saves only the authorized target, never a browser's CSRF token or session DTO.
        repository.idempotent(before.user.id(),before.tenant.id(),route(request),requestKey(request),hash(body),()->{
            validMembership(repository.membership(before.user.id(),before.tenant.id()).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效")));
            validMembership(repository.membership(before.user.id(),target).orElseThrow(SessionService::notFound));
            repository.audit(before.user.id(),target,"session.switch_tenant","tenant",target,"SUCCEEDED","AUTHORIZED_MEMBERSHIP",trace(request));
            return map("tenant_id",target);
        });
        Membership member=validMembership(repository.membership(before.user.id(),target).orElseThrow(SessionService::notFound));
        request.getSession().setAttribute("mop.tenant_id",target);
        return sessionData(new Access(before.user,repository.tenant(target),member,member.capabilities()),csrf(request));
    }
    public Object logout(HttpServletRequest request,HttpServletResponse response){
        Access access=access(request);Authentication previous=SecurityContextHolder.getContext().getAuthentication();
        repository.idempotent(access.user.id(),access.tenant.id(),route(request),requestKey(request),hash(mapper.createObjectNode()),()->{
            repository.membership(access.user.id(),access.tenant.id()).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效"));
            repository.audit(access.user.id(),access.tenant.id(),"session.logout","session",null,"SUCCEEDED","LOGOUT",trace(request));return map("logged_out",true);
        });
        csrfRepository.saveToken(null,request,response);request.getSession().invalidate();SecurityContextHolder.clearContext();
        boolean providerLogout=repository.metadata().mode().equals("remote_test")&&previous instanceof OAuth2AuthenticationToken;
        if(providerLogout){
            // A short-lived unauthenticated session hands the verified provider context to the standard RP logout handler.
            // No bearer token is returned by this API or persisted in the repository.
            var pending=request.getSession(true);pending.setMaxInactiveInterval(60);
            pending.setAttribute("mop.logout_auth",previous);pending.setAttribute("mop.logout_expires",Instant.now().plusSeconds(60));
        }else expireCookie(response);
        return map("logged_out",true,"redirect_url",providerLogout?"/auth/logout":null);
    }
    public void providerLogout(HttpServletRequest request,HttpServletResponse response)throws java.io.IOException,jakarta.servlet.ServletException{
        Authentication auth=null;boolean hasPending=false;var pending=request.getSession(false);
        if(pending!=null){
            Object expires=pending.getAttribute("mop.logout_expires"),value=pending.getAttribute("mop.logout_auth");
            if(expires instanceof Instant until&&value instanceof OAuth2AuthenticationToken token){
                hasPending=true;if(until.isAfter(Instant.now()))auth=token;
                pending.invalidate();
            }
        }
        if(hasPending)expireCookie(response);
        var handler=oidcLogout.getIfAvailable();
        if(auth==null||handler==null){response.sendRedirect("/login");return;}
        handler.onLogoutSuccess(request,response,auth);
    }
    private Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw new DomainException(401,"SESSION_EXPIRED","会话不存在或已过期");
        String actor=request.getUserPrincipal().getName(),tenant=(String)request.getSession().getAttribute("mop.tenant_id");
        Membership membership=validMembership(repository.membership(actor,tenant).orElseThrow(()->new DomainException(401,"SESSION_EXPIRED","成员关系已失效，请重新登录")));
        List<String> capabilities=membership.capabilities();
        return new Access(repository.user(actor).orElseThrow(SessionService::notFound),repository.tenant(tenant),membership,capabilities);
    }
    private Map<String,Object> sessionData(Access access,String token){
        validMembership(access.membership);
        FixtureUser projected=new FixtureUser(access.user.id(),access.user.displayName(),access.membership.role());
        return map("user",projected,"tenant",access.tenant,"allowed_tenants",repository.allowedTenants(access.user.id()),"capabilities",access.membership.capabilities(),"permission_version",access.membership.permissionVersion(),"csrf_token",token,"mode",repository.metadata().mode(),"identity_source",repository.metadata().identitySource());
    }
    private static Membership validMembership(Membership membership){
        if(membership==null||!membership.active()||membership.permissionVersion()<1||membership.permissionVersion()>9_007_199_254_740_991L||Capabilities.forRole(membership.role()).isEmpty())throw new DomainException(401,"SESSION_EXPIRED","成员角色未受支持或已失效");
        return membership;
    }
    private String csrf(HttpServletRequest request){CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());if(token==null)token=csrfRepository.loadToken(request);if(token==null)throw new IllegalStateException("CSRF token unavailable");return token.getToken();}
    private static void expireCookie(HttpServletResponse response){var cookie=new jakarta.servlet.http.Cookie("MOP_SESSION","");cookie.setPath("/");cookie.setHttpOnly(true);cookie.setMaxAge(0);cookie.setAttribute("SameSite","Strict");response.addCookie(cookie);}
    private static String requestKey(HttpServletRequest request){String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw validation("Idempotency-Key 为必填，长度1至128且仅包含字母数字及._:-");return key;}
    private static void fields(JsonNode body,Set<String> allowed){if(!body.isObject())throw validation("请求体必须为JSON对象");body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw validation("未知或禁止字段: "+k);});}
    private static String text(JsonNode body,String key,int max){JsonNode value=body.get(key);if(value==null||!value.isTextual())throw validation(key+" 必须为字符串");String result=value.asText().strip();if(result.isEmpty()||result.length()>max||result.codePoints().anyMatch(Character::isISOControl))throw validation(key+" 为空、过长或含控制字符");return result;}
    private String hash(JsonNode value){try{var canonical=mapper.createObjectNode();TreeSet<String> names=new TreeSet<>();value.fieldNames().forEachRemaining(names::add);names.forEach(k->canonical.set(k,value.get(k)));return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void sameOrigin(HttpServletRequest request){String origin=request.getHeader("Origin");if(origin==null)return;try{URI uri=URI.create(origin);int port=uri.getPort()==-1?("https".equals(uri.getScheme())?443:80):uri.getPort();if(!Objects.equals(uri.getScheme(),request.getScheme())||!Objects.equals(uri.getHost(),request.getServerName())||port!=request.getServerPort()||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null)throw new IllegalArgumentException();}catch(Exception e){throw new DomainException(403,"CSRF_INVALID","历史夹具登录要求同源 Origin");}}
    private static DomainException validation(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
    private static DomainException notFound(){return new DomainException(404,"OBJECT_NOT_FOUND","对象不存在或不可见");}
}
