package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.sla.ServiceTierRepository;
import cn.mop.domain.ticket.*;
import cn.mop.domain.ticket.TicketWorkflowRepository.*;
import cn.mop.domain.ticket.WorkflowLifecycle.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

@RestController @Profile("remote_test") @RequestMapping("/api/v1")
public final class TicketWorkflowController {
    private static final long MAX=9007199254740990L;
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(12000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    private final TicketWorkflowRepository workflow;
    private final TicketWorkflowRepository.SlaProvider policy;
    private final ServiceTierRepository serviceTiers;
    private final ObjectMapper mapper;
    private final byte[] cursorSecret=new byte[32];
    public TicketWorkflowController(PlatformRepository platform,TicketWorkflowRepository workflow,TicketRuntimePolicy policy,ObjectMapper mapper){this(platform,workflow,policy,mapper,null);}
    @Autowired public TicketWorkflowController(PlatformRepository platform,TicketWorkflowRepository workflow,TicketRuntimePolicy policy,ObjectMapper mapper,@Autowired(required=false) ServiceTierRepository serviceTiers){this.platform=platform;this.workflow=workflow;this.policy=policy;this.serviceTiers=serviceTiers;this.mapper=mapper;new SecureRandom().nextBytes(cursorSecret);}
    @GetMapping("/tickets/{id}/workflow") Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request,"tickets:read");query(request,Set.of());return success(workflow.read(a,uuid(id)),request);}
    @GetMapping("/tickets/{id}/workflow/sla") Object sla(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,"tickets:read");query(request,Set.of());String ticket=uuid(id);
        CustomerConfirmationWindow.Tier currentTier=serviceTiers==null?null:serviceTiers.current(new ServiceTierRepository.Access(a.tenantId(),a.actorId(),a.permissionVersion(),a.traceId())).map(ServiceTierRepository.Snapshot::tier).orElse(null);
        return success(workflow.readSla(a,ticket,currentTier),request);
    }
    @GetMapping("/ticket-workflows/assignees") Object assignees(HttpServletRequest request){Access a=access(request,"ticket_notes:write_internal");query(request,Set.of());return success(map("items",workflow.assignees(a),"source","postgresql"),request);}
    @GetMapping("/tickets/{id}/events") Object events(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,"tickets:read");var q=query(request,Set.of("before_version","limit"));int size=limit(q);long before=q.containsKey("before_version")?positive(q.get("before_version")):MAX;
        var all=workflow.events(a,uuid(id),before,size+1);boolean more=all.size()>size;var items=all.subList(0,Math.min(all.size(),size));
        return success(map("items",items,"has_more",more,"next_before_version",more?items.get(items.size()-1).get("version"):null,"source","postgresql"),request);
    }
    @GetMapping("/ticket-workflows") Object list(HttpServletRequest request){
        Access a=access(request,"tickets:read");var q=query(request,Set.of("state","priority","assignee","cursor","limit"));int size=limit(q);
        if(q.containsKey("state"))try{State.valueOf(q.get("state"));}catch(Exception e){throw invalid();}
        if(q.containsKey("priority")&&!Set.of("P1","P2","P3").contains(q.get("priority")))throw invalid();
        if(q.containsKey("assignee")){access(request,"ticket_notes:write_internal");uuid(q.get("assignee"));}
        var filters=new TreeMap<>(q);filters.remove("cursor");filters.remove("limit");String filter=hash(mapper.valueToTree(filters));JsonNode after=q.containsKey("cursor")?cursor(q.get("cursor"),a,filter):null;
        var all=workflow.list(a,new Query(q.get("state"),q.get("priority"),q.get("assignee"),after==null?null:Instant.parse(after.path("created_at").asText()),after==null?null:after.path("id").asText(),size+1));
        boolean more=all.size()>size;var items=all.subList(0,Math.min(size,all.size()));String next=null;
        if(more){var tail=items.get(items.size()-1);String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(encode(map("actor",a.actorId(),"tenant",a.tenantId(),"permission_version",a.permissionVersion(),"filter",filter,"created_at",tail.get("registration_created_at"),"id",tail.get("ticket_id"))));next=payload+"."+signature(payload);}
        return success(map("items",items,"has_more",more,"next_cursor",next,"source","postgresql"),request);
    }
    @PostMapping(value="/tickets/{id}/activate",consumes=MediaType.APPLICATION_JSON_VALUE) Object activate(@PathVariable String id,HttpServletRequest request){
        Access a=access(request,"ticket_notes:write_internal");query(request,Set.of());String ticket=uuid(id);JsonNode body=body(request);fields(body,Set.of("expected_version","priority","service_tier","reason"));
        long expected=version(body,true);String priority=text(body,"priority",2),reason=text(body,"reason",3000);if(!Set.of("P1","P2","P3").contains(priority))throw invalid();
        JsonNode rawTier=body.get("service_tier");
        CustomerConfirmationWindow.Tier explicitTier=null;
        if(rawTier!=null&&!rawTier.isNull()){try{explicitTier=CustomerConfirmationWindow.Tier.valueOf(text(body,"service_tier",10));}catch(Exception e){throw invalid();}}
        final CustomerConfirmationWindow.Tier chosenTier=explicitTier;
        Object result=command(a,ticket,body,request,false,()->{
            CustomerConfirmationWindow.Tier tier=chosenTier!=null?chosenTier:(serviceTiers==null?null:serviceTiers.current(new ServiceTierRepository.Access(a.tenantId(),a.actorId(),a.permissionVersion(),a.traceId())).map(ServiceTierRepository.Snapshot::tier).orElse(null));
            if(tier==null)throw new DomainException(422,"SERVICE_TIER_UNCONFIGURED","当前租户尚未登记服务档位，请先配置或显式指定服务档位");
            return workflow.activate(a,ticket,expected,priority,tier,reason,policy);
        });
        return success(result,request);
    }
    @PostMapping(value="/tickets/{id}/{action:accept|reassign|start|resolve|close|reopen|cancel}",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object action(@PathVariable String id,@PathVariable String action,HttpServletRequest request){
        Action operation=Action.valueOf(action.toUpperCase(Locale.ROOT));boolean customer=Set.of(Action.CLOSE,Action.REOPEN).contains(operation);
        Access a=access(request,customer?"tickets:report":"ticket_notes:write_internal");query(request,Set.of());String ticket=uuid(id);JsonNode body=body(request);
        Set<String> allowed=switch(operation){case ACCEPT,START->Set.of("expected_version");case REASSIGN->Set.of("expected_version","assignee_id","note","reason_code","retain_previous_as_collaborator");default->Set.of("expected_version","note");};fields(body,allowed);
        String note=allowed.contains("note")?text(body,"note",3000):null;
        if(operation==Action.RESOLVE)TicketCustomerActionRules.requireSolution(note);
        String assignee=null,reason=null;boolean retain=false;
        if(operation==Action.REASSIGN){assignee=uuid(text(body,"assignee_id",36));reason=text(body,"reason_code",64);if(!reason.matches("[A-Z][A-Z0-9_]{0,63}"))throw invalid();if(!body.path("retain_previous_as_collaborator").isBoolean())throw invalid();retain=body.path("retain_previous_as_collaborator").booleanValue();}
        Command change=new Command(operation,version(body,false),assignee,note,reason,retain);
        Object result=command(a,ticket,body,request,customer,()->workflow.transition(a,ticket,change));return success(result,request);
    }
    private Object command(Access a,String ticket,JsonNode body,HttpServletRequest request,boolean customer,java.util.function.Supplier<Object> work){
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        return platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+request.getRequestURI(),key,hash(body),a.permissionVersion(),()->workflow.authorizeCommand(a,ticket,customer),work);
    }
    private Access access(HttpServletRequest r,String capability){
        if(r.getUserPrincipal()==null||r.getSession(false)==null)throw expired();Object tenant=r.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String t)||t.isBlank())throw expired();String actor=r.getUserPrincipal().getName();
        Membership member=platform.membership(actor,t).orElseThrow(TicketWorkflowController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains(capability))throw new DomainException(403,"ACTION_FORBIDDEN","当前成员无此工单操作权限");return new Access(t,actor,member.permissionVersion(),trace(r));
    }
    private static JsonNode body(HttpServletRequest r){try{byte[] data=r.getInputStream().readNBytes(32769);if(data.length>32768)throw new DomainException(413,"PAYLOAD_TOO_LARGE","工单请求超过32KiB技术上限");JsonNode b=INPUT.readTree(data);if(b==null||!b.isObject())throw invalid();return b;}catch(DomainException e){throw e;}catch(Exception e){throw invalid();}}
    private static void fields(JsonNode b,Set<String> keys){if(b.size()!=keys.size())throw invalid();b.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw invalid();});}
    private static String text(JsonNode b,String key,int max){JsonNode v=b.get(key);if(v==null||!v.isTextual())throw invalid();String s=v.textValue();TicketCustomerActionRules.requireUnresolvedReason(s);if(s.codePointCount(0,s.length())>max)throw invalid();return s;}
    private static long version(JsonNode b,boolean zero){JsonNode v=b.get("expected_version");if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<(zero?0:1)||v.longValue()>MAX)throw invalid();return v.longValue();}
    private static Map<String,String> query(HttpServletRequest r,Set<String> keys){var result=new LinkedHashMap<String,String>();r.getParameterMap().forEach((k,v)->{if(!keys.contains(k)||v.length!=1||v[0].length()>2048||v[0].isBlank())throw invalid();result.put(k,v[0]);});return result;}
    private static int limit(Map<String,String> q){long n=q.containsKey("limit")?positive(q.get("limit")):25;if(n>100)throw invalid();return (int)n;}
    private static long positive(String raw){try{if(!raw.matches("[1-9][0-9]{0,15}"))throw invalid();long n=Long.parseLong(raw);if(n>MAX)throw invalid();return n;}catch(NumberFormatException e){throw invalid();}}
    private static String uuid(String s){try{if(s==null||!UUID.fromString(s).toString().equals(s))throw invalid();return s;}catch(IllegalArgumentException e){throw invalid();}}
    private byte[] encode(Object v){try{return mapper.writeValueAsBytes(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private String hash(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(canonical(value))));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode canonical(JsonNode v){if(v.isObject()){var out=mapper.createObjectNode();var names=new TreeSet<String>();v.fieldNames().forEachRemaining(names::add);names.forEach(k->out.set(k,canonical(v.get(k))));return out;}return v;}
    private String signature(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(cursorSecret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private JsonNode cursor(String raw,Access a,String filter){try{String[] p=raw.split("\\.",-1);if(p.length!=2||!MessageDigest.isEqual(p[1].getBytes(StandardCharsets.UTF_8),signature(p[0]).getBytes(StandardCharsets.UTF_8)))throw invalid();JsonNode j=mapper.readTree(Base64.getUrlDecoder().decode(p[0]));if(!a.actorId().equals(j.path("actor").asText())||!a.tenantId().equals(j.path("tenant").asText())||a.permissionVersion()!=j.path("permission_version").longValue()||!filter.equals(j.path("filter").asText()))throw invalid();uuid(j.path("id").asText());TicketSlaSnapshot.time(Instant.parse(j.path("created_at").asText()));return j;}catch(Exception e){throw new DomainException(400,"CURSOR_INVALID","游标无效或不属于当前身份、租户与筛选");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","工单请求缺少字段、含非法字段或格式无效");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话已失效");}
}
