package cn.mop.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every request rechecks the project authentication revision; resets never rely on cookie lifetime. */
public final class ProjectSessionValidationFilter extends OncePerRequestFilter {
    private static final String RETURN_CHECK=ProjectSessionValidationFilter.class.getName()+".returnCheck";
    private static final Set<String> PASSWORD_CHANGE_ROUTES=Set.of("/api/v1/session","/api/v1/account","/api/v1/account/password","/api/v1/auth/options","/api/v1/auth/csrf");
    @FunctionalInterface private interface ReturnCheck { void verify(); }
    private final ProjectIdentityService identities;
    public ProjectSessionValidationFilter(ProjectIdentityService identities){this.identities=identities;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        request.removeAttribute(RETURN_CHECK);
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null&&authentication.isAuthenticated()&&!(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)&&!(authentication.getPrincipal() instanceof ProjectSessionIdentity)){
            if(request.getSession(false)!=null)request.getSession(false).invalidate();SecurityContextHolder.clearContext();
        }
        if(authentication!=null&&authentication.isAuthenticated()&&authentication.getPrincipal() instanceof ProjectSessionIdentity principal){
            try{
                var current=identities.repository().sessionState(principal.getName());
                if(current.isEmpty()||current.get().securityVersion()!=principal.securityVersion()){
                    if(request.getSession(false)!=null)request.getSession(false).invalidate();SecurityContextHolder.clearContext();
                }else if(current.get().passwordChangeRequired()&&!PASSWORD_CHANGE_ROUTES.contains(request.getRequestURI())){
                    error(response,403,"PASSWORD_CHANGE_REQUIRED","请先修改当前账号密码");return;
                }else{
                    var session=request.getSession(false);
                    String sessionId=session==null?null:session.getId();
                    String route=request.getRequestURI();
                    // Request-local only. Keeping the callback through async completion avoids a missing guard on async reads.
                    request.setAttribute(RETURN_CHECK,(ReturnCheck)()->{
                        var now=SecurityContextHolder.getContext().getAuthentication();
                        var activeSession=request.getSession(false);
                        if(now!=authentication||!now.isAuthenticated()||activeSession==null||sessionId==null||!sessionId.equals(activeSession.getId()))throw expired();
                        java.util.Optional<cn.mop.persistence.ProjectIdentityRepository.SessionState> latest;
                        try{latest=identities.repository().sessionState(principal.getName());}
                        catch(RuntimeException unavailable){throw identityUnavailable();}
                        if(latest==null)throw identityUnavailable();
                        if(latest.isEmpty()||latest.get().securityVersion()!=principal.securityVersion())throw expired();
                        if(latest.get().passwordChangeRequired()&&!PASSWORD_CHANGE_ROUTES.contains(route))
                            throw new cn.mop.domain.DomainException(403,"PASSWORD_CHANGE_REQUIRED","请先修改当前账号密码");
                    });
                }
            }catch(RuntimeException failure){
                error(response,503,"IDENTITY_UNAVAILABLE","账号依赖暂不可用");return;
            }
        }
        chain.doFilter(request,response);
    }
    /** Shared read boundaries invoke this immediately before returning authorized data, not after bytes are sent. */
    static void recheckBeforeReturn(HttpServletRequest request){
        Object check=request.getAttribute(RETURN_CHECK);
        if(check instanceof ReturnCheck current){current.verify();return;}
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null&&authentication.getPrincipal() instanceof ProjectSessionIdentity)throw identityUnavailable();
        // Fixture/no-project-authentication test contexts have no account revision authority to invent.
    }
    private static cn.mop.domain.DomainException expired(){return new cn.mop.domain.DomainException(401,"SESSION_EXPIRED","读取期间账号或会话身份已变化，请重新读取");}
    private static cn.mop.domain.DomainException identityUnavailable(){return new cn.mop.domain.DomainException(503,"IDENTITY_UNAVAILABLE","返回前账号身份无法核对，未返回旧授权数据");}
    private static void error(HttpServletResponse response,int status,String code,String message)throws IOException{
        String trace=java.util.UUID.randomUUID().toString();response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setHeader("X-Trace-Id",trace);response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"error\":{\"code\":\""+code+"\",\"message\":\""+message+"\",\"details\":[]},\"trace_id\":\""+trace+"\"}");
    }
}
