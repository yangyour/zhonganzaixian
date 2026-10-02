package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.audit.AuditReader;
import cn.mop.domain.audit.AuditReader.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Read-only tenant audit queries; no edit, delete, repair, signing or platform-wide namespace endpoint. */
@RestController @RequestMapping("/api/v1/audit") @Profile("remote_test")
public final class AuditReadController {
    private static final long MAX_SEQUENCE=9007199254740991L;
    private final PlatformRepository platform;private final AuditReader audits;
    public AuditReadController(PlatformRepository platform,AuditReader audits){this.platform=platform;this.audits=audits;}
    @GetMapping("/records") Object records(HttpServletRequest request){
        Access access=access(request);Map<String,String> q=query(request,Set.of("page_size","snapshot_sequence","before_sequence","actor_id","action","result","resource_type","resource_id","trace_id","occurred_from","occurred_before"));
        String size=q.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(size))throw invalid();Long snapshot=q.containsKey("snapshot_sequence")?sequence(q.get("snapshot_sequence"),true):null,before=q.containsKey("before_sequence")?sequence(q.get("before_sequence"),false):null;if(before!=null&&(snapshot==null||before>snapshot))throw invalid();
        String actor=q.get("actor_id");if(actor!=null){if(!actor.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();actor=UUID.fromString(actor).toString();}
        Filters filters=new Filters(actor,text(q.get("action"),200),text(q.get("result"),32),text(q.get("resource_type"),64),text(q.get("resource_id"),160),text(q.get("trace_id"),128),time(q.get("occurred_from")),time(q.get("occurred_before")));
        if(filters.occurredFrom()!=null&&filters.occurredBefore()!=null&&!filters.occurredBefore().isAfter(filters.occurredFrom()))throw invalid();
        Page page=audits.list(access,filters,Integer.parseInt(size),snapshot,before);
        return success(map("items",page.items(),"snapshot_sequence",page.snapshotSequence(),"next_before_sequence",page.nextBeforeSequence(),"has_more",page.hasMore(),"source","postgresql","scope","CURRENT_TENANT","page_size_limit",100,"filter_semantics","EXACT_MATCH_HALF_OPEN_TIME","integrity_scope","EACH_RECORD_LOCAL_HASH_ONLY","externally_anchored",false),request);
    }
    @GetMapping("/integrity") Object integrity(HttpServletRequest request){
        Access access=access(request);Map<String,String> q=query(request,Set.of("from_sequence","to_sequence"));long from=q.containsKey("from_sequence")?sequence(q.get("from_sequence"),false):1;Long to=q.containsKey("to_sequence")?sequence(q.get("to_sequence"),false):null;if(to!=null&&(to<from||to-from>=1000))throw invalid();
        Integrity result=audits.verify(access,from,to);
        return success(map("status",result.status(),"valid",result.valid(),"snapshot_sequence",result.snapshotSequence(),"from_sequence",result.fromSequence(),"to_sequence",result.toSequence(),"verified_records",result.verifiedRecords(),"failure_sequence",result.failureSequence(),"failure",result.failure(),"anchor_hash",result.anchorHash(),"final_hash",result.finalHash(),"has_more",result.hasMore(),"next_from_sequence",result.nextFromSequence(),"checked_at",result.checkedAt(),"source","postgresql","scope","CURRENT_TENANT","verification_scope","LOCAL_DATABASE_SNAPSHOT","externally_anchored",false,"max_range_records",1000,"hash_algorithm","SHA256_TOPLEVEL_SORTED_JSON_V1","whole_snapshot_chain_checked",Boolean.TRUE.equals(result.valid())&&Objects.equals(result.fromSequence(),1L)&&Objects.equals(result.toSequence(),result.snapshotSequence())),request);
    }
    private Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object value=request.getSession(false).getAttribute("mop.tenant_id");if(!(value instanceof String tenant))throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,tenant).orElseThrow(AuditReadController::expired);if(!member.active())throw expired();if(!Capabilities.forRole(member.role()).contains("audit:read"))throw new DomainException(403,"ACTION_FORBIDDEN","当前身份无本租户审计查询权限");return new Access(actor,tenant,member.permissionVersion());
    }
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1||values[0].length()>2048)throw invalid();result.put(key,values[0]);});return result;}
    private static long sequence(String value,boolean zero){if(value==null||!value.matches(zero?"(?:0|[1-9][0-9]{0,15})":"[1-9][0-9]{0,15}"))throw invalid();try{long sequence=Long.parseLong(value);if(sequence>MAX_SEQUENCE)throw invalid();return sequence;}catch(NumberFormatException failure){throw invalid();}}
    private static String text(String value,int max){if(value!=null&&(value.isEmpty()||value.codePointCount(0,value.length())>max||value.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||value.codePoints().anyMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.SURROGATE)))throw invalid();return value;}
    private static Instant time(String value){if(value==null)return null;if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?(?:Z|[+-][0-9]{2}:[0-9]{2})"))throw invalid();try{Instant instant=OffsetDateTime.parse(value).toInstant();if(instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||instant.isAfter(Instant.parse("9999-12-31T23:59:59.999Z")))throw invalid();return instant;}catch(DateTimeException failure){throw invalid();}}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","审计筛选、时间或有界序号无效");}
}
