package cn.mop.api;

import cn.mop.adapters.cmdb.*;
import cn.mop.domain.*;
import cn.mop.domain.assetimport.source.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.mop.api.ApiResponse.*;

/** Owner-authorized current durable artifact only; no client-supplied cells or preflight result is trusted. */
@RestController @Profile("remote_test") @RequestMapping("/api/v1/assets/import-sources")
public final class CmdbSourcePreflightController {
 private final PlatformRepository platform;private final AssetImportSourceRepository sources;private final ObjectMapper mapper;private final CmdbSourcePreflight checker;
 public CmdbSourcePreflightController(PlatformRepository platform,AssetImportSourceRepository sources,ObjectMapper mapper){this.platform=platform;this.sources=sources;this.mapper=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);checker=new CmdbSourcePreflight(CustomerCmdbDictionary.load(mapper));}
 @GetMapping("/{id}/cmdb-preflight") Object get(@PathVariable String id,HttpServletRequest request,HttpServletResponse response){
  response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
  if(request.getUserPrincipal()==null||request.getSession(false)==null)throw expired();
  String actor=request.getUserPrincipal().getName();Object scope=request.getSession(false).getAttribute("mop.tenant_id");if(!(scope instanceof String tenant))throw expired();
  Membership member=platform.membership(actor,tenant).orElseThrow(CmdbSourcePreflightController::expired);
  if(!member.active()||member.permissionVersion()<1||!actor.equals(member.userId())||!tenant.equals(member.tenantId()))throw expired();
  if(!member.capabilities().contains("assets:write"))throw new DomainException(403,"ACTION_FORBIDDEN","当前成员没有导入来源检查权限");
  if(request.getQueryString()!=null||!request.getParameterMap().isEmpty())throw new DomainException(400,"VALIDATION_ERROR","预检不接受客户端规则、租户或单元格覆盖");
  if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new DomainException(404,"NOT_FOUND","来源不存在或不可访问");
  var raw=sources.source(new AssetImportSourceRepository.Access(actor,tenant,member.permissionVersion(),trace(request)),id);
  JsonNode source=mapper.valueToTree(raw);
  if(!id.equals(source.path("id").asText())||!tenant.equals(source.path("tenant_id").asText())||!actor.equals(source.path("created_by").asText())||!source.path("version").isIntegralNumber()||!source.path("version").canConvertToLong()||source.path("version").asLong()<1||source.path("version").asLong()>9007199254740991L)throw unavailable();
  if(!"PARSED".equals(source.path("parse_status").asText()))throw new DomainException(409,"IMPORT_PARSE_REQUIRED","请先完成当前来源的工作表选择与解析");
  JsonNode stored=source.path("parsed_artifact");
  if(!stored.isObject()||!AssetImportSchema.VERSION.equals(stored.path("schema_version").asText())||!uuid(source.path("latest_artifact_id").asText())||!uuid(source.path("parse_operation_id").asText())||!tenant.equals(stored.path("tenant_id").asText())||!id.equals(stored.path("source_id").asText())||!source.path("latest_artifact_id").asText().equals(stored.path("id").asText())||!source.path("parse_operation_id").asText().equals(stored.path("operation_id").asText())||!source.path("sha256").asText().matches("[0-9a-f]{64}")||!source.path("sha256").asText().equals(stored.path("source_sha256").asText())||!stored.path("parse_hash").asText().matches("[0-9a-f]{64}"))throw unavailable();
  var artifact=stored.deepCopy();for(String key:List.of("id","tenant_id","source_id","operation_id","parse_hash","source_sha256","schema_version","selection","created_at"))((com.fasterxml.jackson.databind.node.ObjectNode)artifact).remove(key);
  ParsedArtifact parsed;try{parsed=mapper.treeToValue(artifact,ParsedArtifact.class);}catch(Exception invalid){throw unavailable();}
  CmdbSourcePreflight.Result result;try{result=checker.check(parsed);}catch(IllegalArgumentException mismatch){throw new DomainException(422,"CMDB_SOURCE_SCHEMA_MISMATCH","来源需使用客户 33 列模板、明确表头并完成解析；不会自动套用旧 15 列映射");}
  var findings=new ArrayList<Map<String,Object>>();for(var f:result.findings())findings.add(map("record",f.record(),"source_row",f.sourceRow(),"column",f.column(),"level",f.level(),"code",f.code(),"message",f.message()));
  return success(map("tenant_id",tenant,"source_id",id,"source_version",source.path("version").asLong(),"parse_hash",stored.path("parse_hash").asText(),"catalog_version",CustomerCmdbDictionary.VERSION,"scope","SOURCE_DICTIONARY_ONLY","status",result.status(),"records",result.records(),"error_rows",result.errorRows(),"warning_rows",result.warningRows(),"findings",findings,"database_checks","NOT_RUN","can_import",false,"asset_mutations",false,"production_approved",false),request);
 }
 private static boolean uuid(String value){return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
 private static DomainException expired(){return new DomainException(401,"SESSION_EXPIRED","当前会话或成员关系已失效");}
 private static DomainException unavailable(){return new DomainException(503,"CMDB_SOURCE_UNAVAILABLE","来源或解析身份不一致，请重新读取；未执行导入");}
}
