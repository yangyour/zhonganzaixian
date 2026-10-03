package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.capacity.*;
import cn.mop.domain.capacity.CapacityScenarioRepository.*;
import cn.mop.domain.worklog.ManualWorklog;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Explicit immutable proposals. A saved calculation is not an approved model or a measured baseline. */
@RestController @RequestMapping("/api/v1/capacity/scenarios") @Profile("remote_test")
public final class CapacityScenarioController {
    static final int MAX_BODY_BYTES=32768;
    private static final Set<String> FIELDS=Set.of("expected_version","label","note","calculation","benchmark_reference");
    private static final ObjectMapper INPUT=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(8000).maxNumberLength(16).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlatformRepository platform;private final CapacityScenarioRepository scenarios;private final ObjectMapper mapper;
    public CapacityScenarioController(PlatformRepository platform,CapacityScenarioRepository scenarios,ObjectMapper mapper){this.platform=platform;this.scenarios=scenarios;this.mapper=mapper;}
    @GetMapping Object list(HttpServletRequest request){
        Access a=access(request,false);Map<String,String> q=query(request,Set.of("label_query","page_size","before_recorded_at","before_id"));String sizeText=q.getOrDefault("page_size","15");if(!Set.of("10","15","25","50","100").contains(sizeText))throw invalid();int size=Integer.parseInt(sizeText);
        if(q.containsKey("before_recorded_at")!=q.containsKey("before_id"))throw invalid();Instant before=q.containsKey("before_recorded_at")?ManualWorklog.parseInstant(q.get("before_recorded_at")):null;String id=q.containsKey("before_id")?uuid(q.get("before_id")):null;
        List<Stored> all=scenarios.list(a,q.getOrDefault("label_query",""),size+1,before,id);boolean more=all.size()>size;var items=all.subList(0,Math.min(size,all.size()));var tail=more?items.get(items.size()-1):null;
        return success(map("items",items.stream().map(CapacityScenarioController::dto).toList(),"has_more",more,"next_before_recorded_at",tail==null?null:tail.recordedAt().toString(),"next_before_id",tail==null?null:tail.id(),"source","postgresql","scope","CURRENT_TENANT_CAPACITY_DRAFTS","page_size_limit",100,"pagination_consistency","CURRENT_REQUEST_SNAPSHOT_ONLY"),request);
    }
    @GetMapping("/{id}") Object get(@PathVariable String id,HttpServletRequest request){Access a=access(request,false);query(request,Set.of());return success(dto(scenarios.get(a,uuid(id))),request);}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE) Object create(HttpServletRequest request){access(request,true);return write(body(request),request);}
    Object write(JsonNode input,HttpServletRequest request){
        Access a=access(request,true);query(request,Set.of());fields(input,FIELDS);JsonNode v=input.get("expected_version");if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()!=0)throw invalid();
        CapacityScenarioDraft draft;try{CapacityScenarioDraft.BenchmarkReference reference=null;JsonNode b=input.get("benchmark_reference");if(!b.isNull()){fields(b,Set.of("reference","title","sha256"));reference=new CapacityScenarioDraft.BenchmarkReference(text(b.get("reference")),text(b.get("title")),text(b.get("sha256")));}draft=new CapacityScenarioDraft(text(input.get("label")),text(input.get("note")),CapacityCalculation.fromWire(INPUT.convertValue(input.get("calculation"),Object.class)),reference);}catch(IllegalArgumentException failure){throw invalid();}
        String key=request.getHeader("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid();
        Object result=platform.idempotentAuthorized(a.actorId(),a.tenantId(),request.getMethod()+" "+route(request),key,hash(input),a.permissionVersion(),()->scenarios.authorizeCreate(a),()->dto(scenarios.create(a,0,draft)));return success(result,request);
    }
    static Map<String,Object> dto(Stored row){
        var d=row.draft();var snapshot=row.snapshot();var capacity=snapshot.capacity();var buffer=snapshot.proxyBuffer();var ref=d.benchmarkReference();var values=d.calculation().proxyBuffer();
        return map("id",row.id(),"tenant_id",row.tenantId(),"created_by",row.actorId(),"recorded_at",row.recordedAt().toString(),"version",row.version(),"label",d.label(),"note",d.note(),"calculation",d.calculation().wire(),"calculation_sha256",row.calculationSha256(),"snapshot_sha256",row.snapshotSha256(),"benchmark_reference",ref==null?null:map("reference",ref.reference(),"title",ref.title(),"sha256",ref.sha256()),"benchmark_verification",d.benchmarkVerification(),"snapshot",map("capacity_formula_version",snapshot.capacityFormulaVersion(),"buffer_formula_version",snapshot.bufferFormulaVersion(),"status",capacity.status().name(),"model_version",capacity.modelVersion(),"missing_inputs",capacity.missingInputs(),"outputs",outputs(capacity.outputs()),"proxy_buffer",map("status",buffer.status().name(),"missing_inputs",buffer.missingInputs(),"estimated_bytes",decimal(buffer.estimatedBytes()),"margin_factor_below_one",values==null||values.marginFactor()==null?null:values.marginFactor().compareTo(BigDecimal.ONE)<0,"measurement_verified",false,"failover_verified",false)),"decimal_encoding","BASE10_STRINGS","decimal_precision","DECIMAL128","lifecycle_status","DRAFT","model_approval_status","UNVERIFIED","measurement_verification","UNVERIFIED","production_capacity_verified",false,"approved_pool_nvps_limit",null,"measured_capacity",null,"poc_status","NOT_RUN","effective",false,"immutable",true,"source","postgresql","record_source","EXPLICIT_CAPACITY_DRAFT");
    }
    private static Object outputs(CapacityEstimate.Outputs v){return v==null?null:map("nvps",decimal(v.nvps()),"daily_bytes",decimal(v.dailyBytes()),"history_bytes",decimal(v.historyBytes()),"trend_bytes",decimal(v.trendBytes()),"single_node_bytes",decimal(v.singleNodeBytes()),"two_node_bytes",decimal(v.twoNodeBytes()),"peak_nvps",decimal(v.peakNvps()),"total_items",v.totalItems().toString(),"pools",v.pools().toString());}
    private static String decimal(BigDecimal value){return value==null?null:value.stripTrailingZeros().toPlainString();}
    private Access access(HttpServletRequest request,boolean write){if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();Object tenant=request.getSession(false).getAttribute("mop.tenant_id");if(!(tenant instanceof String scope)||scope.isBlank())throw expired();String actor=request.getUserPrincipal().getName();Membership m=platform.membership(actor,scope).orElseThrow(CapacityScenarioController::expired);if(!m.active()||m.permissionVersion()<1)throw expired();if(!Capabilities.forRole(m.role()).contains(write?"capacity_scenarios:write":"capacity_scenarios:read"))throw new DomainException(403,"ACTION_FORBIDDEN","容量方案仅向当前租户获准平台管理员开放");return new Access(actor,scope,m.permissionVersion(),trace(request));}
    static JsonNode body(HttpServletRequest request){try{if(request.getContentLengthLong()>MAX_BODY_BYTES)throw invalid();byte[] bytes=request.getInputStream().readNBytes(MAX_BODY_BYTES+1);if(bytes.length>MAX_BODY_BYTES)throw invalid();try(var p=INPUT.getFactory().createParser(bytes)){int tokens=0;while(p.nextToken()!=null)if(++tokens>100)throw invalid();}return INPUT.readTree(bytes);}catch(Exception failure){throw invalid();}}
    private static void fields(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.size()!=fields.size())throw invalid();node.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw invalid();});}
    private static Map<String,String> query(HttpServletRequest request,Set<String> allowed){Map<String,String> result=new HashMap<>();request.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v.length!=1||v[0]==null||v[0].codePointCount(0,v[0].length())>(k.equals("label_query")?100:64))throw invalid();result.put(k,v[0]);});return result;}
    private static String uuid(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw invalid();return UUID.fromString(value).toString();}
    private static String text(JsonNode value){if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static JsonNode canonical(JsonNode node){if(!node.isObject())return node;var result=INPUT.createObjectNode();var names=new TreeSet<String>();node.fieldNames().forEachRemaining(names::add);names.forEach(k->result.set(k,canonical(node.get(k))));return result;}
    private String hash(JsonNode input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(input))));}catch(Exception failure){throw new IllegalStateException("Capacity request fingerprint unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","容量方案仅接受明确标签、说明、完整计算输入、可空未验证基准引用及创建版本0；不接受计算结果或生效声明");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
}
