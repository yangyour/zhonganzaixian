package cn.mop.api;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
/** Read-only server projection; authorization and session recheck share the lifecycle boundary. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/tickets/{ticketId}/sla")
public final class TicketSlaController {
    private final TicketLifecycleController lifecycle;
    public TicketSlaController(TicketLifecycleController lifecycle){this.lifecycle=lifecycle;}
    @GetMapping("/policy-binding") Object policy(@PathVariable String ticketId,HttpServletRequest request){return lifecycle.slaPolicy(ticketId,request);}
    @PostMapping(value="/policy-binding",consumes="application/json") Object bind(@PathVariable String ticketId,HttpServletRequest request){return lifecycle.bindSlaPolicy(ticketId,request);}
    @GetMapping Object get(@PathVariable String ticketId,HttpServletRequest request){return lifecycle.sla(ticketId,request);}
    @GetMapping("/reminders") Object reminders(@PathVariable String ticketId,HttpServletRequest request){return lifecycle.reminders(ticketId,request);}
}
