package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.core.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Durable original source, explicit parser selection and preview mapping; no asset import execution. */
@RestController @RequestMapping("/api/v1") @Profile("remote_test")
public final class AssetImportController {
    private final PlatformRepository platform;
    private final AssetImportSourceRepository sources;
    private final AssetImportObjectStore store;
    private final AssetImportPolicy policy;
    private final ObjectMapper mapper;
    private static final int MAX_METADATA_BYTES=16*1024,MAX_METADATA_TOKENS=256;
    private static final ObjectMapper METADATA=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(128).maxNumberLength(19).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public AssetImportController(PlatformRepository platform,AssetImportSourceRepository sources,AssetImportObjectStore store,AssetImportPolicy policy,ObjectMapper mapper){this.platform=platform;this.sources=sources;this.store=store;this.policy=policy;this.mapper=mapper;}

    @GetMapping("/assets/import-policy") Object policy(HttpServletRequest request){access(request);noQuery(request);return success(policy.dto(),request);}

    @PostMapping(value="/assets/import-sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(HttpServletRequest request){
        var access=access(request);noQuery(request);String key=requestKey(request);
        Map<String,Part> parts=parts(request);Part file=parts.get("file");
        if(file.getSubmittedFileName()==null||parts.get("purpose").getSubmittedFileName()!=null||parts.get("format").getSubmittedFileName()!=null)throw invalid("multipart 的 file 必须是原文件，purpose 和 format 必须是文本字段");
        byte[] purpose=bytes(parts.get("purpose"),64),formatBytes=bytes(parts.get("format"),8);
        if(!Arrays.equals(purpose,AssetImportPolicy.PURPOSE.getBytes(StandardCharsets.UTF_8)))throw invalid("purpose 必须为 asset_import_source");
        ImportFormat format=format(new String(formatBytes,StandardCharsets.UTF_8));
        policy.validate(AssetImportPolicy.PURPOSE,file.getSubmittedFileName(),format,file.getContentType(),file.getSize());
        byte[] original=bytes(file,Math.toIntExact(ImportLimits.MAX_SOURCE_BYTES));
        if(original.length!=file.getSize())throw invalid("原文件声明大小与读取结果不一致");
        String digest=hash(mapper.valueToTree(map("purpose",AssetImportPolicy.PURPOSE,"original_name",file.getSubmittedFileName(),"declared_format",format.name(),"size_bytes",original.length,"sha256",sha(original))));
        Object result=platform.idempotent(access.actorId(),access.tenantId(),route(request),key,digest,access.permissionVersion(),()->{
            if(!policy.configured())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","导入源格式能力未配置，未受理新原文件");
            if(!sources.ready()||!store.ready())throw new DomainException(503,"UPSTREAM_UNAVAILABLE","指定导入源数据库或存储未就绪，未受理新原文件");
            return sources.acceptSource(access,file.getSubmittedFileName(),format,original);
        });
        return ResponseEntity.status(202).body(success(result,request));
    }

    @GetMapping("/assets/import-sources") Object list(HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        var access=access(request);
        for(var entry:request.getParameterMap().entrySet())if(!Set.of("limit","cursor").contains(entry.getKey())||entry.getValue().length!=1)throw invalid("来源列表仅接受单个 limit 和 cursor，不接受租户或创建者覆盖");
        String raw=request.getParameter("limit"),cursor=request.getParameter("cursor");
        if(raw!=null&&!raw.matches("[1-9][0-9]?"))throw invalid("limit 必须为 1 至 50 的整数");
        int limit=raw==null?20:Integer.parseInt(raw);if(limit>50)throw invalid("limit 必须为 1 至 50 的整数");
        if(cursor!=null&&!cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("cursor 必须为来源列表返回的记录编号");
        return success(sources.listSources(access,limit,cursor),request);
    }

    @GetMapping("/assets/import-sources/{id}") Object source(@PathVariable String id,HttpServletRequest request){
        var access=access(request);noQuery(request);Map<String,Object> value=sources.source(access,id);
        platform.audit(access.actorId(),access.tenantId(),"asset.import.source.read","asset_import_source",id,"SUCCEEDED","CREATOR_ONLY_RAW_PREVIEW_CONFIG_PENDING",trace(request));
        return success(value,request);
    }
    @GetMapping("/asset-import-operations/{id}") Object operation(@PathVariable String id,HttpServletRequest request){var access=access(request);noQuery(request);return success(sources.operation(access,id),request);}

    @PostMapping(value="/assets/import-sources/{id}/parses",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> parseRequest(@PathVariable String id,HttpServletRequest request){access(request);return parse(id,metadata(request),request);}
    ResponseEntity<?> parse(String id,JsonNode body,HttpServletRequest request){
        var access=access(request);noQuery(request);String key=requestKey(request);
        fields(body,Set.of("expected_version","declared_format","encoding","delimiter","sheet_index","header_row"));
        long version=version(body);ParseSelection selection;
        try{selection=new ParseSelection(format(text(body,"declared_format",8)),nullableText(body,"encoding",16),nullableText(body,"delimiter",1),nullableInt(body,"sheet_index"),nullableInt(body,"header_row"));}
        catch(IllegalArgumentException malformed){throw invalid("解析选择不符合 CSV/XLSX 约定，请显式选择编码、分隔符、工作表和表头行");}
        Object result=platform.idempotent(access.actorId(),access.tenantId(),route(request),key,hash(body),access.permissionVersion(),()->sources.requestParse(access,id,version,selection));
        return ResponseEntity.status(202).body(success(result,request));
    }

    @PostMapping(value="/assets/import-sources/{id}/mappings",consumes=MediaType.APPLICATION_JSON_VALUE)
    Object mappingRequest(@PathVariable String id,HttpServletRequest request){access(request);return mapping(id,metadata(request),request);}
    Object mapping(String id,JsonNode body,HttpServletRequest request){
        var access=access(request);noQuery(request);String key=requestKey(request);
        fields(body,Set.of("expected_version","source_parse_hash","target_source_columns","ignored_source_columns"));
        JsonNode targets=body.get("target_source_columns"),ignored=body.get("ignored_source_columns");
        Set<String> codes=new HashSet<>();for(Column column:Column.values())codes.add(column.name());fields(targets,codes);
        if(!ignored.isArray()||ignored.size()>ImportLimits.MAX_COLUMNS)throw invalid("忽略列必须是明确且不重复的列位置数组");
        EnumMap<Column,Integer> positions=new EnumMap<>(Column.class);for(Column column:Column.values())positions.put(column,nullableInt(targets,column.name()));
        Set<Integer> ignoredPositions=new HashSet<>();for(JsonNode value:ignored){if(!value.isIntegralNumber()||!value.canConvertToInt()||!ignoredPositions.add(value.intValue()))throw invalid("忽略列必须是明确且不重复的整数位置");}
        MappingRequest mapping;try{mapping=new MappingRequest(version(body),text(body,"source_parse_hash",64),positions,ignoredPositions);}
        catch(IllegalArgumentException malformed){throw invalid("映射必须包含全部15个目标字段，备注列必选，每个来源列须明确映射或忽略");}
        return success(platform.idempotent(access.actorId(),access.tenantId(),route(request),key,hash(body),access.permissionVersion(),()->sources.saveMapping(access,id,mapping)),request);
    }

    private AssetImportSourceRepository.Access access(HttpServletRequest request){
        if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
        Object scope=request.getSession(false).getAttribute("mop.tenant_id");if(!(scope instanceof String tenant))throw expired();String actor=request.getUserPrincipal().getName();
        Membership member=platform.membership(actor,tenant).orElseThrow(AssetImportController::expired);if(!member.active())throw expired();
        if(!member.capabilities().contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","当前角色无资产导入来源暂存权限");
        return new AssetImportSourceRepository.Access(actor,tenant,member.permissionVersion(),trace(request));
    }
    private static void fields(JsonNode body,Set<String> expected){if(body==null||!body.isObject())throw invalid("请求必须为约定 JSON 对象");Set<String> actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw invalid("请求含缺失或未知字段，不能提交客户端租户或身份");}
    private static long version(JsonNode body){JsonNode value=body.get("expected_version");if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1)throw invalid("expected_version 必须为正整数");return value.longValue();}
    private static Integer nullableInt(JsonNode body,String name){JsonNode value=body.get(name);if(value==null)throw invalid("缺少明确选择字段");if(value.isNull())return null;if(!value.isIntegralNumber()||!value.canConvertToInt())throw invalid("列、工作表和表头位置必须为整数或null");return value.intValue();}
    private static String nullableText(JsonNode body,String name,int maximum){JsonNode value=body.get(name);if(value==null)throw invalid("缺少明确选择字段");return value.isNull()?null:text(body,name,maximum);}
    private static String text(JsonNode body,String name,int maximum){JsonNode value=body.get(name);if(value==null||!value.isTextual()||value.textValue().isEmpty()||value.textValue().length()>maximum)throw invalid("字段必须是约定长度的文本");return value.textValue();}
    private static ImportFormat format(String value){try{return ImportFormat.valueOf(value);}catch(IllegalArgumentException|NullPointerException malformed){throw invalid("format 只允许 CSV 或 XLSX");}}
    private static Map<String,Part> parts(HttpServletRequest request){try{Map<String,Part> result=new LinkedHashMap<>();for(Part part:request.getParts())if(!Set.of("file","purpose","format").contains(part.getName())||result.putIfAbsent(part.getName(),part)!=null)throw invalid("multipart 含未知或重复字段");if(!result.keySet().equals(Set.of("file","purpose","format")))throw invalid("multipart 必须只含 file、purpose、format");return result;}catch(DomainException safe){throw safe;}catch(Exception malformed){throw invalid("multipart 格式无效或超过原文件上限");}}
    private static byte[] bytes(Part part,int maximum){if(part.getSize()>maximum)throw invalid("导入字段超过上限，原文件最大20971520 bytes");try(var input=part.getInputStream()){byte[] value=input.readNBytes(maximum+1);if(value.length>maximum)throw invalid("导入字段超过上限，原文件最大20971520 bytes");return value;}catch(DomainException safe){throw safe;}catch(Exception malformed){throw invalid("原文件内容无法读取");}}
    private String hash(JsonNode value){try{return sha(mapper.writeValueAsBytes(canonical(value)));}catch(Exception malformed){throw new IllegalStateException("Cannot calculate import request digest");}}
    private JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode result=mapper.createObjectNode();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)result.set(name,canonical(value.get(name)));return result;}if(value.isArray()){ArrayNode result=mapper.createArrayNode();value.forEach(item->result.add(canonical(item)));return result;}return value;}
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception unavailable){throw new IllegalStateException("SHA-256 unavailable");}}
    private static String requestKey(HttpServletRequest request){String value=request.getHeader("Idempotency-Key");if(value==null||!value.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid("Idempotency-Key 必填，长度1至128且只含字母数字及._:-");return value;}
    private static void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw invalid("此接口不接受查询参数");}
    private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","会话或当前成员关系已失效");}
    private static DomainException invalid(String message){return new DomainException(400,"VALIDATION_ERROR",message);}
    /** Bound bytes and streaming tokens before constructing any tree, including rejected unknown fields. */
    private static JsonNode metadata(HttpServletRequest request){
        if(request.getContentLengthLong()>MAX_METADATA_BYTES)throw invalid("解析和映射参数最多16384 bytes");
        try{
            byte[] bytes=request.getInputStream().readNBytes(MAX_METADATA_BYTES+1);if(bytes.length>MAX_METADATA_BYTES)throw invalid("解析和映射参数最多16384 bytes");
            try(JsonParser parser=METADATA.createParser(bytes)){int count=0;for(JsonToken token;(token=parser.nextToken())!=null;){if(++count>MAX_METADATA_TOKENS||token==JsonToken.FIELD_NAME&&parser.currentName().length()>64)throw invalid("解析和映射参数超过技术节点上限");if(token==JsonToken.VALUE_STRING)parser.getTextLength();}}
            return METADATA.readTree(bytes);
        }catch(DomainException safe){throw safe;}catch(Exception malformed){throw invalid("解析和映射参数必须是有界、无重复字段且无尾随内容的合法JSON");}
    }
}
