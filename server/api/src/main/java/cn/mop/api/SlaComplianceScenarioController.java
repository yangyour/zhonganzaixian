package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.sla.SlaComplianceScenario;
import cn.mop.domain.sla.SlaComplianceScenario.*;
import cn.mop.domain.worklog.ManualWorklog;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;
/** Readonly explicit-input arithmetic. It neither loads real tickets nor validates scenario references as real approval. */
@RestController @RequestMapping("/api/v1/sla/compliance-scenarios") @Profile("remote_test")
public final class SlaComplianceScenarioController {
    static final int MAX_BODY_BYTES=262144,MAX_HTTP_TICKETS=200;
    private static final Set<String> ROW_FIELDS=Set.of("scenario_ref","priority","exclusion","exclusion_approval","sla_snapshot","response_due_at","recovery_due_at","response_actual_at","recovery_actual_at");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(1000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;
    public SlaComplianceScenarioController(PlatformRepository platform){this.platform=platform;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object preview(HttpServletRequest request){access(request);return calculate(body(request),request);}
    Object calculate(JsonNode input,HttpServletRequest request){
        Access a=access(request);if(!request.getParameterMap().isEmpty())throw invalid();fields(input,Set.of("population_complete","tickets"));
        if(!input.get("population_complete").isNull()&&!input.get("population_complete").isBoolean()||!input.get("tickets").isArray()||input.get("tickets").size()>MAX_HTTP_TICKETS)throw invalid();
        Boolean complete=input.get("population_complete").isNull()?null:input.get("population_complete").booleanValue();List<Evidence> rows=new ArrayList<>();for(JsonNode row:input.get("tickets")){fields(row,ROW_FIELDS);rows.add(new Evidence(label(row.get("scenario_ref")),enumeration(row.get("priority"),Priority.class),enumeration(row.get("exclusion"),Exclusion.class),reference(row.get("exclusion_approval")),reference(row.get("sla_snapshot")),time(row.get("response_due_at")),time(row.get("recovery_due_at")),time(row.get("response_actual_at")),time(row.get("recovery_actual_at"))));}
        Aggregate result=SlaComplianceScenario.aggregate(rows,complete);
        return success(map("tenant_id",a.tenantId(),"scope",result.scope(),"metric_version",result.metricVersion(),"source","EXPLICIT_INPUT_NOT_PROJECT_DATA","status",result.status().name(),"reason",result.reason()==null?null:result.reason().name(),"population_complete_declared",complete,"approval_validation_status","UNVERIFIED","runtime_snapshot_validation","NOT_CHECKED","effective",false,"effective_achievement",null,"compensation_amount",null,"compensation_status","CONFIG_PENDING","counts",map("input_total",result.items().size(),"known_applicable",result.knownApplicable(),"applicability_unknown",result.applicabilityUnknown(),"met",result.met(),"not_met",result.notMet(),"timing_unresolved",result.timingUnresolved(),"not_applicable",result.notApplicable()),"scenario_achievement",result.scenarioAchievement()==null?null:map("numerator",result.scenarioAchievement().numerator(),"denominator",result.scenarioAchievement().denominator()),"items",result.items().stream().map(SlaComplianceScenarioController::dto).toList(),"scenario_limit",MAX_HTTP_TICKETS),request);
    }
    private static Map<String,Object> dto(Evaluation value){Evidence row=value.evidence();return map("input",map("scenario_ref",row.ticketId(),"priority",row.priority()==null?null:row.priority().name(),"exclusion",row.exclusion()==null?null:row.exclusion().name(),"exclusion_approval",refDto(row.exclusionApproval()),"sla_snapshot",refDto(row.slaSnapshot()),"response_due_at",row.responseDueAt(),"recovery_due_at",row.recoveryDueAt(),"response_actual_at",row.responseActualAt(),"recovery_actual_at",row.recoveryActualAt()),"applicable",value.applicable(),"scenario_outcome",value.outcome().name(),"response_check",value.response().name(),"recovery_check",value.recovery().name(),"exclusion_applied_in_scenario",value.exclusionApplied(),"unapproved_exclusion_ignored",value.unapprovedExclusionIgnored(),"reasons",value.reasons().stream().map(Enum::name).toList());}
    private static Object refDto(Reference value){return value==null?null:map("id",value.id(),"version",value.version());}
    private record Access(String actorId,String tenantId,long permissionVersion){}
    private Access access(HttpServletRequest request){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership member=platform.membership(actor,scope).orElseThrow(SlaComplianceScenarioController::expired);if(!member.active()||member.permissionVersion()<1)throw expired();if(!member.capabilities().contains("sla_scenarios:read"))throw new DomainException(403,"ACTION_FORBIDDEN","SLA显式对照仅向当前授权内部人员开放");return new Access(actor,scope,member.permissionVersion());}
    private static Reference reference(JsonNode value){if(value.isNull())return null;fields(value,Set.of("id","version"));JsonNode version=value.get("version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<1||version.longValue()>9007199254740991L)throw invalid();return new Reference(label(value.get("id")),version.longValue());}
    private static String label(JsonNode value){if(value==null||!value.isTextual())throw invalid();String text=value.textValue();if(!SlaComplianceScenario.validReferenceText(text))throw invalid();return text;}
    private static Instant time(JsonNode value){if(value.isNull())return null;if(!value.isTextual())throw invalid();return ManualWorklog.parseInstant(value.textValue());}
    private static <E extends Enum<E>> E enumeration(JsonNode value,Class<E> type){if(value.isNull())return null;if(!value.isTextual())throw invalid();try{return Enum.valueOf(type,value.textValue());}catch(IllegalArgumentException failure){throw invalid();}}
    private static void fields(JsonNode value,Set<String> keys){if(value==null||!value.isObject()||value.size()!=keys.size())throw invalid();value.fieldNames().forEachRemaining(key->{if(!keys.contains(key))throw invalid();});}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var parser=INPUT.getFactory().createParser(bytes)){int tokens=0;while(parser.nextToken()!=null)if(++tokens>10000)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","SLA对照仅接受最多200条显式场景、完整字段和可空时间；引用不是实际业务对象查询或审批证明");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
