package cn.mop.api;

import cn.mop.domain.PlatformRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static cn.mop.api.ApiResponse.*;

/** Historical isolated unit-test identity fixture. Never registered in remote_test. */
@RestController @RequestMapping("/api/v1/fixture") @Profile("local_fixture")
public class FixtureSessionController {
    private final PlatformRepository repository;
    private final SessionService sessions;
    public FixtureSessionController(PlatformRepository repository,SessionService sessions){this.repository=repository;this.sessions=sessions;}
    @GetMapping("/users") Object users(HttpServletRequest request){return success(map("items",repository.users(),"mode","local_fixture","source","fixture"),request);}
    @PostMapping("/session") Object login(@RequestBody JsonNode body,HttpServletRequest request,HttpServletResponse response){return success(sessions.fixtureLogin(body,request,response),request);}
}
