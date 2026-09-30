package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Configuration @Profile({"local_fixture","remote_test"})
public class SecurityConfiguration {
    @Bean SecurityContextRepository contextRepository(){return new HttpSessionSecurityContextRepository();}
    @Bean HttpSessionCsrfTokenRepository csrfRepository(){HttpSessionCsrfTokenRepository repository=new HttpSessionCsrfTokenRepository();repository.setHeaderName("X-CSRF-TOKEN");return repository;}
    @Bean @Profile("local_fixture")
    SecurityFilterChain fixtureSecurity(HttpSecurity http,ObjectMapper mapper,PlatformRepository repository,SecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf) throws Exception {
        common(http,mapper,repository,contexts,csrf);
        http.csrf(c->c.ignoringRequestMatchers(request->request.getMethod().equals("POST")&&ApiResponse.route(request).equals("/api/v1/fixture/session")));
        http.authorizeHttpRequests(auth->auth.requestMatchers("/api/v1/health/live","/api/v1/health/ready","/api/v1/auth/options","/api/v1/fixture/users").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/fixture/session").permitAll().anyRequest().authenticated());
        return http.build();
    }
    @Bean @Profile("remote_test")
    SecurityFilterChain testSecurity(HttpSecurity http,ObjectMapper mapper,PlatformRepository repository,SecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf,TestOidcConfiguration oidc,ClientRegistrationRepository clients,SessionService sessions) throws Exception {
        common(http,mapper,repository,contexts,csrf);
        http.authorizeHttpRequests(auth->auth.requestMatchers("/api/v1/health/live","/api/v1/health/ready","/api/v1/auth/options","/oauth2/**","/login/oauth2/**","/auth/logout").permitAll()
            .requestMatchers("/api/v1/fixture/**").denyAll().anyRequest().authenticated());
        var resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        http.oauth2Login(login->login.authorizationEndpoint(endpoint->endpoint.authorizationRequestResolver(resolver))
            .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
            .userInfoEndpoint(info->info.oidcUserService(oidc.platformUserService()))
            .successHandler((req,res,auth)->{
                try{sessions.establishOidcSession(auth,req,res);res.sendRedirect("/assets");}
                catch(RuntimeException failed){
                    if(req.getSession(false)!=null)req.getSession(false).invalidate();
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    res.sendRedirect("/login?auth_error=SESSION_SETUP_FAILED");
                }
            })
            .failureHandler((req,res,error)->{
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                // A forged callback with unmatched state must not invalidate an existing authorized session.
                // Spring removes its own failed authorization request; no platform session was established here.
                // Provider responses and token details must never be sent to the browser or audit log.
                res.sendRedirect("/login?auth_error=OIDC_LOGIN_FAILED");
            }));
        return http.build();
    }
    private void common(HttpSecurity http,ObjectMapper mapper,PlatformRepository repository,SecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf) throws Exception {
        http.securityContext(c->c.securityContextRepository(contexts).requireExplicitSave(true));
        http.csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
        http.formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable()).requestCache(cache->cache.disable());
        http.exceptionHandling(errors->errors.authenticationEntryPoint((req,res,e)->writeError(mapper,repository,req,res,401,"SESSION_EXPIRED","会话不存在或已过期"))
            .accessDeniedHandler((req,res,e)->writeError(mapper,repository,req,res,403,e instanceof org.springframework.security.web.csrf.CsrfException?"CSRF_INVALID":"ACTION_FORBIDDEN",e instanceof org.springframework.security.web.csrf.CsrfException?"CSRF 令牌缺失或无效":"无动作权限")));
        http.addFilterBefore(new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
                req.setAttribute("mop.trace_id",UUID.randomUUID().toString());res.setHeader("X-Trace-Id",ApiResponse.trace(req));
                res.setHeader("Cache-Control","no-store");chain.doFilter(req,res);
            }
        },CsrfFilter.class);
    }
    static void writeError(ObjectMapper mapper,PlatformRepository repository,HttpServletRequest req,HttpServletResponse res,int status,String code,String message)throws IOException{
        String actor=req.getUserPrincipal()==null?null:req.getUserPrincipal().getName();
        String tenant=req.getSession(false)==null?null:(String)req.getSession(false).getAttribute("mop.tenant_id");
        try{repository.audit(actor,tenant,req.getMethod()+" "+ApiResponse.route(req),"request",null,"DENIED",code,ApiResponse.trace(req));}
        catch(RuntimeException unavailable){status=503;code="UPSTREAM_UNAVAILABLE";message="测试环境审计依赖不可用，未记录此次拒绝事件";}
        res.setStatus(status);res.setContentType("application/json");res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(),ApiResponse.failure(code,message,List.of(),req));
    }
}
