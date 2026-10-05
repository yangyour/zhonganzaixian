package cn.mop.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every request rechecks the project authentication revision; resets never rely on cookie lifetime. */
public final class ProjectSessionValidationFilter extends OncePerRequestFilter {
    private final ProjectIdentityService identities;
    public ProjectSessionValidationFilter(ProjectIdentityService identities){this.identities=identities;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null&&authentication.isAuthenticated()&&!(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)&&!(authentication.getPrincipal() instanceof ProjectSessionIdentity)){
            if(request.getSession(false)!=null)request.getSession(false).invalidate();SecurityContextHolder.clearContext();
        }
        if(authentication!=null&&authentication.isAuthenticated()&&authentication.getPrincipal() instanceof ProjectSessionIdentity principal){
            try{
                var current=identities.repository().sessionState(principal.getName());
                if(current.isEmpty()||current.get().securityVersion()!=principal.securityVersion()){
                    if(request.getSession(false)!=null)request.getSession(false).invalidate();SecurityContextHolder.clearContext();
                }else if(current.get().passwordChangeRequired()&&!Set.of("/api/v1/session","/api/v1/account","/api/v1/account/password","/api/v1/auth/options","/api/v1/auth/csrf").contains(request.getRequestURI())){
                    error(response,403,"PASSWORD_CHANGE_REQUIRED","请先修改当前账号密码");return;
                }
            }catch(RuntimeException failure){
                error(response,503,"IDENTITY_UNAVAILABLE","账号依赖暂不可用");return;
            }
        }
        chain.doFilter(request,response);
    }
    private static void error(HttpServletResponse response,int status,String code,String message)throws IOException{
        String trace=java.util.UUID.randomUUID().toString();response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setHeader("X-Trace-Id",trace);response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\",\"details\":[]},\"trace_id\":\""+trace+"\"}");
    }
}
