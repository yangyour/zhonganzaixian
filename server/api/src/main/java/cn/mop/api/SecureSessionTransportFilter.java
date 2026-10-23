package cn.mop.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reject plaintext before authentication, CSRF token creation or application session processing. */
public final class SecureSessionTransportFilter extends OncePerRequestFilter {
    private static final Set<String> HEALTH_PATHS = Set.of("/api/v1/health/live", "/api/v1/health/ready");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
            chain.doFilter(request, response);
            return;
        }
        // Credential-free local health probes remain usable by an explicit local supervisor.
        if (Set.of("GET", "HEAD").contains(request.getMethod())
            && HEALTH_PATHS.contains(request.getRequestURI())
            && LOOPBACK.contains(request.getRemoteAddr())
            && request.getHeader("Cookie") == null
            && request.getHeader("Authorization") == null
            && request.getSession(false) == null) {
            chain.doFilter(request, response);
            return;
        }
        String trace = UUID.randomUUID().toString();
        response.setStatus(400);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Trace-Id", trace);
        response.getWriter().write("{\"error\":{\"code\":\"HTTPS_REQUIRED\","
            + "\"message\":\"安全会话要求可信HTTPS连接\",\"details\":[]},\"trace_id\":\"" + trace + "\"}");
    }
}
