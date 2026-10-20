package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.TicketLifecycleRepository;
import cn.mop.domain.ticket.TicketLifecycleRepository.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** No opening rules or identity fields can be supplied through action bodies. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/tickets/{ticketId}/lifecycle")
public final class TicketLifecycleController {
    private static final int MAX_BODY_BYTES=65536;
    private static final long MAX_VERSION=9007199254740990L;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(32000).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String,TicketLifecycle.Action> ACTIONS=Map.of(
            "accept",TicketLifecycle.Action.ACCEPT,"transfer",TicketLifecycle.Action.TRANSFER,
            "wait-for-vendor",TicketLifecycle.Action.WAIT_FOR_VENDOR,"resume",TicketLifecycle.Action.RESUME,
            "submit-resolution",TicketLifecycle.Action.SUBMIT_RESOLUTION,"confirm",TicketLifecycle.Action.CONFIRM,
            "not-resolved",TicketLifecycle.Action.NOT_RESOLVED,"reopen",TicketLifecycle.Action.REOPEN);
    private final PlatformRepository platform;
    private final TicketLifecycleRepository repository;
    private final ObjectMapper mapper;
    public TicketLifecycleController(PlatformRepository platform,TicketLifecycleRepository repository,ObjectMapper mapper){
        this.platform=platform;this.repository=repository;this.mapper=mapper;
    }
    @GetMapping Object get(@PathVariable String ticketId,HttpServletRequest request){
        Membership member=member(request,"tickets:read");Access a=access(member,request);query(request,Set.of());
        Optional<Entry> value=repository.get(a,uuid(ticketId));recheck(member,request,"tickets:read");
        return success(result(value.orElse(null),Capabilities.internal(member.role())),request);
    }
    @GetMapping("/solution") Object solution(@PathVariable String ticketId,HttpServletRequest request){
        Membership member=member(request,"tickets:read");Access a=access(member,request);
        String hash=query(request,Set.of("expected_hash")).get("expected_hash");
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid();
        var frozen=repository.solution(a,uuid(ticketId),hash).orElseThrow(()->new DomainException(404,"OBJECT_NOT_FOUND","解决方案不存在"));
        recheck(member,request,"tickets:read");
        return success(map("tenant_id",frozen.tenantId().toString(),"ticket_id",frozen.ticketId().toString(),
                "lifecycle_version",frozen.lifecycleVersion(),"resolved_at",frozen.resolvedAt(),
                "ticket_summary",frozen.ticketSummary(),"solution",frozen.solution(),"snapshot_hash",frozen.sha256(),
                "engineer_label","工程师","source","postgresql","production_approved",false),request);
    }
    @GetMapping("/history") Object history(@PathVariable String ticketId,HttpServletRequest request){
        Membership member=member(request,"tickets:read");Access a=access(member,request);
        Map<String,String> params=query(request,Set.of("page_size","before_version"));
        String raw=params.getOrDefault("page_size","15");
        if(!Set.of("10","15","25","50","100").contains(raw))throw invalid();
        int size=Integer.parseInt(raw);long before=MAX_VERSION+1;
        if(params.containsKey("before_version")){
            String value=params.get("before_version");if(!value.matches("[1-9][0-9]{0,15}"))throw invalid();
            try{before=Long.parseLong(value);}catch(NumberFormatException e){throw invalid();}
            if(before>MAX_VERSION+1)throw invalid();
        }
        List<Entry> all=repository.history(a,uuid(ticketId),before,size+1);
        recheck(member,request,"tickets:read");
        boolean more=all.size()>size;List<Entry> items=all.subList(0,Math.min(size,all.size()));
        return success(map("items",items.stream().map(e->dto(e,Capabilities.internal(member.role()))).toList(),
                "has_more",more,"next_before_version",more?items.get(items.size()-1).snapshot().version():null,
                "source","postgresql","production_approved",false),request);
    }
    @PostMapping(value="/actions/{action}",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object request(@PathVariable String ticketId,@PathVariable String action,HttpServletRequest request){
        member(request,capability(action));return write(ticketId,action,body(request),request);
    }
    Object write(String ticketId,String name,JsonNode input,HttpServletRequest request){
        String capability=capability(name);Membership member=member(request,capability);
        Access a=access(member,request);query(request,Set.of());String ticket=uuid(ticketId);
        Action action=parse(name,input);
        String key=request.getHeader("Idempotency-Key");
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        boolean customer=capability.equals("tickets:customer_decide");
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),
                key,hash(input),a.permissionVersion(),()->repository.authorizeCurrentAccess(a,ticket,customer),
                ()->result(repository.act(a,ticket,action),Capabilities.internal(member.role())));
        return success(result,request);
    }
    static Action parse(String name,JsonNode input){
        TicketLifecycle.Action action=ACTIONS.get(name);if(action==null||input==null||!input.isObject())throw invalid();
        Set<String> fields=switch(action){
            case ACCEPT,RESUME -> Set.of("expected_version");
            case TRANSFER -> Set.of("expected_version","reason","assignee_id","keep_prior_assignee");
            case CONFIRM -> Set.of("expected_version","expected_solution_hash");
            case NOT_RESOLVED -> Set.of("expected_version","reason","expected_solution_hash");
            default -> Set.of("expected_version","reason");
        };
        if(input.size()!=fields.size()||!fields.stream().allMatch(input::has))throw invalid();
        JsonNode version=input.get("expected_version");
        if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1||version.longValue()>MAX_VERSION)throw invalid();
        String reason=null,hash=null,target=null;boolean keep=false;
        if(fields.contains("reason")){
            JsonNode v=input.get("reason");if(!v.isTextual()||v.textValue().codePointCount(0,v.textValue().length())>16000)throw invalid();
            reason=v.textValue();TicketCustomerActionRules.requireUnresolvedReason(reason);
        }
        if(fields.contains("expected_solution_hash")){
            JsonNode v=input.get("expected_solution_hash");if(!v.isTextual()||!v.textValue().matches("[0-9a-f]{64}"))throw invalid();
            hash=v.textValue();
        }
        if(action==TicketLifecycle.Action.TRANSFER){
            if(!input.get("assignee_id").isTextual()||!input.get("keep_prior_assignee").isBoolean())throw invalid();
            target=uuid(input.get("assignee_id").textValue());keep=input.get("keep_prior_assignee").booleanValue();
        }
        return new Action(action,version.longValue(),reason,hash,target,keep);
    }
    static Map<String,Object> result(Entry entry,boolean internal){
        return map("lifecycle",entry==null?null:dto(entry,internal),"status",entry==null?"NOT_STARTED":"TEST_LIFECYCLE",
                "source","postgresql","production_approved",false);
    }
    static Map<String,Object> dto(Entry entry,boolean internal){
        var s=entry.snapshot();
        return map("tenant_id",s.tenantId(),"ticket_id",entry.ticketId(),"version",s.version(),"state",s.state().name(),
                "reopen_count",s.reopenCount(),"occurred_at",s.lastTransitionAt(),"resolved_at",s.resolvedAt(),
                "closed_at",s.closedAt(),"response_due_at",s.responseDueAt(),"recovery_due_at",s.recoveryDueAt(),
                "solution_snapshot_hash",s.solutionSnapshotHash(),"close_mode",s.closeMode()==null?null:s.closeMode().name().toLowerCase(Locale.ROOT),
                "assignee_label",entry.assigneeId()==null?null:"工程师","assigned_engineer_id",internal?entry.assigneeId():null,
                "production_approved",false,"notification_status","NOT_INTEGRATED","sla_clock_status","NOT_INTEGRATED");
    }
    private Membership member(HttpServletRequest request,String capability){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        Object selected=request.getSession(false).getAttribute("mop.tenant_id");
        if(!(selected instanceof String tenant)||tenant.isBlank())throw expired();
        String actor=request.getUserPrincipal().getName();
        Membership member=platform.membership(actor,tenant).orElseThrow(TicketLifecycleController::expired);
        if(!member.active()||member.permissionVersion()<1||!actor.equals(member.userId())||!tenant.equals(member.tenantId()))throw expired();
        if(!member.capabilities().contains(capability))throw new DomainException(403,"ACTION_FORBIDDEN","当前账号未获准执行此工单动作");
        return member;
    }
    private void recheck(Membership before,HttpServletRequest request,String capability){
        if(!before.equals(member(request,capability)))throw expired();
    }
    private static Access access(Membership member,HttpServletRequest request){
        return new Access(member.userId(),member.tenantId(),member.permissionVersion(),trace(request));
    }
    private static String capability(String action){
        if(!ACTIONS.containsKey(action))throw invalid();
        return Set.of("confirm","not-resolved","reopen").contains(action)?"tickets:customer_decide":"tickets:lifecycle_write";
    }
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){
        var result=new HashMap<String,String>();request.getParameterMap().forEach((key,values)->{
            if(!allowed.contains(key)||values.length!=1||values[0].length()>64)throw invalid();result.put(key,values[0]);
        });return result;
    }
    static JsonNode body(HttpServletRequest request){
        try{
            if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();
            byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();
            try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>24)throw invalid();}
            return INPUT.readTree(bytes);
        }catch(Exception e){throw invalid();}
    }
    private String hash(JsonNode input){
        try{
            var canonical=mapper.createObjectNode();var keys=new TreeSet<String>();input.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key->canonical.set(key,input.get(key)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
        }catch(Exception e){throw new IllegalStateException("Lifecycle request digest unavailable");}
    }
    private static String uuid(String s){
        if(s==null||!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();
        return UUID.fromString(s).toString();
    }
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","工单动作、字段或分页参数无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或权限已变化");}
}
