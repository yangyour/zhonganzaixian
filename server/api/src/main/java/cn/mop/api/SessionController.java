package cn.mop.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile({"local_fixture","remote_test"})
public class SessionController {
    private final SessionService sessions;
    public SessionController(SessionService sessions){this.sessions=sessions;}
    @GetMapping("/api/v1/auth/options") Object options(HttpServletRequest request){return success(sessions.authOptions(),request);}
    @GetMapping("/api/v1/session") Object session(HttpServletRequest request){return success(sessions.session(request),request);}
    @PostMapping("/api/v1/session/tenant") Object switchTenant(@RequestBody JsonNode body,HttpServletRequest request){return success(sessions.switchTenant(body,request),request);}
    @DeleteMapping("/api/v1/session") Object logout(HttpServletRequest request,HttpServletResponse response){return success(sessions.logout(request,response),request);}
    @GetMapping("/auth/logout") void providerLogout(HttpServletRequest request,HttpServletResponse response)throws java.io.IOException,jakarta.servlet.ServletException{sessions.providerLogout(request,response);}
}
