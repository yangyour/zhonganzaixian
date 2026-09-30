"""Generate the reviewed first-batch design contract; no network or application writes."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STR = {"type": "string"}
ID = {"type": "string", "format": "uuid"}
VERSION = {"type": "integer", "minimum": 1}
TIME = {"type": "string", "format": "date-time"}


def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False}


def ref(name):
    return {"$ref": "#/components/schemas/" + name}


def envelope(data):
    return obj({"data": data, "trace_id": STR}, ("data", "trace_id"))


schemas = {
    "Error": obj({"error": obj({"code": STR, "message": STR, "details": {"type": "array", "items": {"type": "object", "additionalProperties": True}}}, ("code", "message", "details")), "trace_id": STR}, ("error", "trace_id")),
    "Accepted": envelope(obj({"operation_id": ID, "status": {"type": "string", "enum": ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]}}, ("operation_id", "status"))),
    "ResourceResult": envelope({"type": "object", "description": "角色投影后的资源DTO；本批为接口设计骨架，完整读模型按03逐接口扩展。", "additionalProperties": True}),
    "ListResult": envelope(obj({"items": {"type": "array", "items": {"type": "object", "additionalProperties": True}}, "next_cursor": {"type": ["string", "null"]}, "has_more": {"type": "boolean"}}, ("items", "next_cursor", "has_more"))),
    "OperationResult": envelope(obj({"id": ID, "kind": STR, "status": {"type": "string", "enum": ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]}, "step": {"type": ["string", "null"]}, "progress": {"type": ["number", "null"], "minimum": 0, "maximum": 1}, "result_ref": {"type": ["string", "null"]}, "error": {"type": ["object", "null"], "properties": {"code": STR, "message": STR, "retryable": {"type": "boolean"}}, "additionalProperties": False}}, ("id", "kind", "status", "step", "error"))),
    "TenantCreate": obj({"name": {"type": "string", "minLength": 1, "maxLength": 128}, "isolation_mode": {"type": "string", "enum": ["shared", "dedicated", "independent"], "description": "shared=共享监控池；dedicated=独立监控实例；independent=控制层与监控层独立部署。模式不代替Q02控制库隔离决策。"}, "asset_quota": {"type": "integer", "minimum": 1}, "idp_type": {"type": "string", "enum": ["local", "oidc"]}}, ("name", "isolation_mode", "asset_quota", "idp_type")),
    "AssetImport": obj({"file_id": ID, "column_mapping": {"type": "object", "minProperties": 1, "additionalProperties": {"type": "string"}}, "dry_run": {"type": "boolean"}, "preview_hash": {"type": "string", "pattern": "^[a-f0-9]{64}$", "description": "dry_run=false时业务层强制提供，必须与文件和映射的已核验预览一致。"}}, ("file_id", "column_mapping", "dry_run")),
    "TicketCreate": obj({"title": {"type": "string", "minLength": 1, "maxLength": 200, "description": "客户报障可省略，由类别与设备生成。"}, "description": {"type": "string", "minLength": 1, "maxLength": 3000}, "source": {"type": "string", "enum": ["customer_report", "manual", "incident"], "description": "客户只能customer_report；incident仅内部授权主体可用。"}, "asset_id": ID, "incident_id": ID, "category": {"type": "string", "maxLength": 64}, "impact_scope": {"type": "string", "enum": ["company", "partial_area", "individual"], "description": "全公司/部分区域/个人"}, "contact_name": {"type": "string", "minLength": 1, "maxLength": 100}, "contact_phone": {"type": "string", "minLength": 1, "maxLength": 64}, "urgency": {"type": "string", "enum": ["normal", "urgent"], "description": "客户很急仅供参考，不直接设P1。"}, "attachment_ids": {"type": "array", "items": ID, "uniqueItems": True}}, ("description", "source")),
    "VersionAction": obj({"expected_version": VERSION}, ("expected_version",)),
    "IncidentAck": obj({"note": {"type": "string", "maxLength": 2000}, "expected_version": VERSION}, ("note", "expected_version")),
    "TicketTransfer": obj({"assignee_id": ID, "reason_code": {"type": "string", "minLength": 1, "description": "来源于已确认转派原因字典，未给出枚举值不自行杜撰。"}, "reason_detail": {"type": "string", "minLength": 1, "maxLength": 2000}, "retain_previous_as_collaborator": {"type": "boolean"}, "expected_version": VERSION}, ("assignee_id", "reason_code", "reason_detail", "retain_previous_as_collaborator", "expected_version")),
    "TicketResolve": obj({"solution": {"type": "string", "minLength": 20, "maxLength": 10000}, "expected_version": VERSION}, ("solution", "expected_version")),
    "TicketConfirm": obj({"decision": {"type": "string", "enum": ["resolved", "unresolved"]}, "reason": {"type": "string", "minLength": 1, "maxLength": 2000, "description": "decision=unresolved时强制填写。"}, "expected_version": VERSION}, ("decision", "expected_version")),
    "TicketReopen": obj({"reason": {"type": "string", "minLength": 1, "maxLength": 2000}, "attachment_ids": {"type": "array", "items": ID, "uniqueItems": True}, "expected_version": VERSION}, ("reason", "expected_version")),
    "WorklogCreate": obj({"started_at": TIME, "ended_at": TIME, "category": {"type": "string", "minLength": 1, "maxLength": 64}, "expected_version": VERSION}, ("started_at", "ended_at", "category", "expected_version")),
}
schemas["AssetImport"]["allOf"] = [{"if": {"properties": {"dry_run": {"const": False}}, "required": ["dry_run"]}, "then": {"required": ["preview_hash"]}}]
schemas["TicketConfirm"]["allOf"] = [{"if": {"properties": {"decision": {"const": "unresolved"}}, "required": ["decision"]}, "then": {"required": ["reason"]}}]
schemas["TicketCreate"]["allOf"] = [{"if": {"properties": {"source": {"const": "incident"}}, "required": ["source"]}, "then": {"required": ["incident_id"]}}]
schemas["TicketCreate"]["allOf"].append({"if": {"properties": {"source": {"const": "customer_report"}}, "required": ["source"]}, "then": {"required": ["category", "impact_scope", "contact_name", "contact_phone", "urgency"], "properties": {"category": {"enum": ["网络不通", "网速慢", "WiFi问题", "设备异常", "其他"]}, "attachment_ids": {"maxItems": 3, "description": "每张≤5MB；图片异步追加不阻塞报障。附件所有权、图片内容和大小由文件服务复验。"}}}})

api = {
    "openapi": "3.1.0",
    "info": {"title": "MOP首批开发接口设计", "version": "0.1.0", "description": "2026-09-30设计契约，仅覆盖首批接口，尚未实现服务。正式规则以合同和客户书面结论为准。对象读DTO本批仍为可扩展设计骨架，不能据此宣称全量接口字段冻结。字符串技术上限为本版拟定值，合同已明确值另行标注。"},
    "servers": [{"url": "/api/v1", "description": "相对地址，不指向任何已部署环境"}],
    "security": [{"sessionCookie": []}],
    "paths": {},
    "components": {"securitySchemes": {"sessionCookie": {"type": "apiKey", "in": "cookie", "name": "mop_session", "description": "本版同源会话设计；实际身份源按Q17。HttpOnly/Secure/SameSite及CSRF由服务端配置。"}}, "schemas": schemas},
}


def response(schema, description):
    return {"description": description, "content": {"application/json": {"schema": ref(schema)}}}


def add(path, method, operation_id, summary, body=None, result="ResourceResult", asynchronous=False, both=False, description=""):
    params = []
    if "{id}" in path:
        params.append({"name": "id", "in": "path", "required": True, "schema": ID, "description": "只查当前已授权租户对象。"})
    if method != "get":
        params.extend([
            {"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string", "minLength": 1, "maxLength": 128}},
            {"name": "X-CSRF-Token", "in": "header", "required": True, "schema": {"type": "string", "minLength": 1}},
        ])
    responses = {}
    if not asynchronous or both:
        responses["200"] = response(result, "同步结果；只代表已完成的本地事务或查询。")
    if asynchronous or both:
        responses["202"] = response("Accepted", "已耐久受理，需查询operation及目标事实，不代表外部动作成功。")
    for code, label in [(400, "参数错误"), (401, "会话失效"), (403, "动作禁止或CSRF错误"), (404, "对象不存在或不可见"), (409, "版本或幂等冲突"), (422, "状态禁止或CONFIG_PENDING"), (429, "配额或速率限制"), (503, "尚未受理且上游不可用")]:
        responses[str(code)] = response("Error", label)
    spec = {"operationId": operation_id, "summary": summary, "description": description or "按服务端租户、动作与对象权限执行；未确认规则不得回退到演示值。", "parameters": params, "responses": responses}
    if body:
        spec["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(body)}}}
    api["paths"].setdefault(path, {})[method] = spec
    return spec


add("/session", "get", "getSession", "读取会话与授权上下文")
add("/tenants", "post", "createTenant", "创建租户及资源准备任务", "TenantCreate", asynchronous=True, description="平台管理员；名称全平台唯一。Q02/Q16/Q17中的实际必需参数未确认时返回CONFIG_PENDING，不分配真实资源。")
for path, oid, title in [("/assets", "listAssets", "查询资产"), ("/incidents", "listIncidents", "查询事件")]:
    op = add(path, "get", oid, title, result="ListResult")
    op["parameters"] += [{"name": "cursor", "in": "query", "schema": STR}, {"name": "page_size", "in": "query", "schema": {"type": "integer", "minimum": 1, "maximum": 500, "default": 15}, "description": "M10-9页面选项10/15/25/50/100；M2-3实际接口上限100或500按已确认配置再次校验，不替代导入行数上限。"}]
    filters = ["type", "site_id", "status"] if path == "/assets" else ["status", "priority", "asset_id", "occurred_from", "occurred_to"]
    for name in filters:
        op["parameters"].append({"name": name, "in": "query", "schema": TIME if name.startswith("occurred_") else STR, "description": "白名单筛选；具体枚举在本模块读模型冻结时补齐。"})
add("/assets/imports", "post", "importAssets", "预览或确认资产导入", "AssetImport", asynchronous=True, description="文件实际最多500行；dry_run=false必须提交有效preview_hash；文件及列映射变化需重新预览。")
add("/incidents/{id}/ack", "post", "ackIncident", "有效人工确认事件", "IncidentAck")
add("/tickets/{id}", "get", "getTicket", "读取有权查看的工单详情")
add("/tickets", "post", "createTicket", "报障或创建工单意图", "TicketCreate", asynchronous=True)
for action, body, title in [("accept", "VersionAction", "接单"), ("transfer", "TicketTransfer", "转派且不重置SLA"), ("resolve", "TicketResolve", "提交解决"), ("confirm", "TicketConfirm", "客户确认或未解决"), ("reopen", "TicketReopen", "客户重开"), ("worklogs", "WorklogCreate", "填写本人工作记录")]:
    add("/tickets/{id}/" + action, "post", "ticket" + action.title(), title, body, both=True, description="动作主体从会话取得。依Q07实际权威源决定同步200或异步202；客户端须兼容两者。客户确认还需报障人/决策人关系，转派不改SLA，重开保留历史。")
add("/operations/{id}", "get", "getOperation", "读取异步操作状态", result="OperationResult")

out = ROOT / "contracts" / "core-api.openapi.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(api, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"output": str(out), "paths": len(api["paths"]), "operations": sum(len(x) for x in api["paths"].values()), "schemas": len(schemas)}, ensure_ascii=False))
