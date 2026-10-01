import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const string = { type: 'string' };
const nullableString = { type: ['string', 'null'] };
const ref = (name) => ({ $ref: `#/components/schemas/${name}` });
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: false });
const envelope = (data) => object({ data, trace_id: string });
const list = (items) => envelope(object({ items: { type: 'array', items }, next_cursor: nullableString, has_more: { type: 'boolean' }, source: { const: 'postgresql' }, page_size_limit: { const: 100 }, page_size_limit_kind: { const: 'test_technical_limit' } }));
const types = ['router', 'switch', 'firewall', 'ap', 'wireless_controller', 'other'];
const errorResponse = (description) => ({ description, content: { 'application/json': { schema: ref('Error') } } });
const errors = Object.fromEntries(Object.entries({ 400: '参数或未知字段错误', 401: '会话失效', 403: '动作权限或CSRF错误', 404: '对象不存在或不可见', 405: '方法不允许', 409: '版本或幂等冲突', 415: '内容类型不支持', 422: '状态禁止或CONFIG_PENDING', 429: '配额限制', 500: '内部错误；按trace_id排查', 503: '依赖未就绪' }).map(([code, label]) => [code, errorResponse(label)]));
const parameter = (name, location, schema, required = true) => ({ name, in: location, required, schema });
const headers = [parameter('Idempotency-Key', 'header', { type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9._:-]{1,128}$' }), parameter('X-CSRF-Token', 'header', string)];
const jsonBody = (schema) => ({ required: true, content: { 'application/json': { schema } } });
const action = (operationId, summary, schema, options = {}) => ({ operationId, summary, ...options, responses: { 200: { description: '共享测试环境事务完成或查询成功；不代表真实监控纳管或生产验收', content: { 'application/json': { schema } } }, ...errors } });
const mutation = (operationId, summary, input, output, options = {}) => action(operationId, summary, output, { parameters: headers, requestBody: jsonBody(input), ...options });
const pathId = parameter('id', 'path', { type: 'string', format: 'uuid' });
const pathKey = parameter('key', 'path', string);
const version = object({ expected_version: { type: 'integer', minimum: 1 } });
const evidence = object({ reference: { type: 'string', pattern: '^document-ref:[A-Za-z0-9._:-]+$', maxLength: 160, description: '仅非敏感资料标识；后缀不得是磁盘路径或包含token/password/secret/credential/private-key等凭据正文标记。不是附件下载地址，不核验文档真实性。' }, title: { type: 'string', minLength: 1, maxLength: 200 }, sha256: { type: 'string', pattern: '^[A-Fa-f0-9]{64}$' } });
const boundedComment = { type: 'string', minLength: 1, maxLength: 500, description: '按Unicode code point计数，拒绝控制字符；不能包含凭据或token。' };
const revision = object({
  id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, configuration_key: string,
  proposed_value: { not: { type: 'null' } }, reason: boundedComment, evidence_refs: { type: 'array', minItems: 1, maxItems: 5, items: evidence },
  workflow_status: { type: 'string', enum: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'] }, version: { type: 'integer', minimum: 1 },
  created_by: { type: 'string', format: 'uuid' }, created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' },
  reviewed_by: { type: ['string', 'null'], format: 'uuid' }, reviewed_at: { type: ['string', 'null'], format: 'date-time' }, review_comment: nullableString,
  decision_status: { type: 'string', enum: ['pending', 'contract_fixed'] }, effective_value: { type: 'null' }, confirmation_status: { const: 'UNVERIFIED' }, source: { const: 'postgresql' },
  history: { type: 'array', items: object({ action: { type: 'string', enum: ['CREATE', 'SUBMIT', 'APPROVE', 'REJECT', 'WITHDRAW'] }, actor_id: { type: 'string', format: 'uuid' }, occurred_at: { type: 'string', format: 'date-time' }, from_status: nullableString, to_status: string, comment: nullableString }) },
});
const blocked = (operationId, summary, pathParameter) => ({ ...mutation(operationId, summary, version, ref('ResourceResult'), { parameters: [pathParameter, ...headers] }), responses: errors });
const tenant = object({ id: { type: 'string', format: 'uuid' }, name: string });
const asset = object({ id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, name: string, type: { type: 'string', enum: types }, management_ip: string, site_id: string, status: { type: 'string', enum: ['unknown', 'ok', 'problem', 'maintenance', 'disabled'] }, lifecycle_status: string, source: { type: 'string', enum: ['test_seed', 'manual'] }, sampled_at: nullableString, unknown_reason: nullableString, version: { type: 'integer', minimum: 1 }, created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' }, availability: string, sync_state: string }, ['id', 'tenant_id', 'name', 'type', 'management_ip', 'site_id', 'status', 'lifecycle_status', 'source', 'sampled_at', 'unknown_reason', 'version', 'created_at', 'updated_at']);
const ticketCreate = JSON.parse(readFileSync(new URL('../contracts/design-core.openapi.json', import.meta.url), 'utf8')).components.schemas.TicketCreate;
const ticketRegistration = object({
  id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, receipt_no: string,
  title: string, description: string, source: { const: 'customer_report' }, category: { type: 'string', enum: ['网络不通', '网速慢', 'WiFi问题', '设备异常', '其他'] },
  impact_scope: { type: 'string', enum: ['company', 'partial_area', 'individual'] }, urgency: { type: 'string', enum: ['normal', 'urgent'] },
  asset_id: { type: ['string', 'null'], format: 'uuid' }, asset_name: nullableString,
  reporter_id: { type: 'string', format: 'uuid' }, contact_name: string, contact_phone: string, contact_binding_status: { const: 'UNVERIFIED' },
  registration_status: { const: 'REGISTERED' }, state: { type: 'null' }, external_id: { type: 'null' }, external_state: { type: 'null' }, sync_state: { const: 'NOT_APPLICABLE' },
  authority_source: { const: 'MOP' }, authority_status: { const: 'RULES_PENDING' }, priority: { type: 'null' }, response_due_at: { type: 'null' }, recovery_due_at: { type: 'null' },
  operation_id: { type: 'string', format: 'uuid' }, version: { const: 1 }, created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' },
  history: { type: 'array', items: object({ action: { const: 'REGISTER' }, actor_id: { type: 'string', format: 'uuid' }, occurred_at: { type: 'string', format: 'date-time' }, version: { const: 1 } }) },
});
const operation = object({
  id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, kind: { const: 'CUSTOMER_REPORT_REGISTRATION' },
  status: { const: 'PENDING' }, step: { const: 'AWAITING_TICKET_CONFIGURATION' }, progress: { type: 'null' }, result_ref: string,
  error: object({ code: { const: 'CONFIG_PENDING' }, message: string, retryable: { const: false }, question_ids: { type: 'array', items: string } }),
  created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' },
});
const contract = {
  openapi: '3.1.0',
  info: { title: 'MOP 共享测试环境 API', version: '0.4.0', description: '本地应用通过SSH复用指定服务器的PostgreSQL与Keycloak。配置内部审核不形成客户确认。工单权威开发方向由用户明确选择MOP；客户报障202只代表ticket/operation/outbox等同事务耐久登记，正式业务状态、优先级与SLA仍待规则落实，不冒充完整建单。fixture身份入口在remote_test不可调用。' },
  servers: [{ url: '/api/v1' }],
  security: [{ SessionCookie: [] }],
  paths: {
    '/health/live': { get: action('liveness', '本地进程存活', ref('ResourceResult'), { security: [] }) },
    '/health/ready': { get: action('readiness', '实际PostgreSQL与Keycloak依赖就绪；不证明生产就绪', ref('ResourceResult'), { security: [] }) },
    '/auth/options': { get: action('authOptions', '读取Keycloak授权登录入口；不返回秘密', envelope(object({ mode: { const: 'remote_test' }, identity_source: { const: 'test_oidc' }, login_url: { const: '/oauth2/authorization/mop' } })), { security: [] }) },
    '/session': { get: action('getSession', '读取可信会话与能力', ref('SessionResult')), delete: action('logout', '注销会话', ref('ResourceResult'), { parameters: headers }) },
    '/session/tenant': { post: mutation('switchTenant', '在服务端授权集合中切换租户', object({ tenant_id: { type: 'string', format: 'uuid' } }), ref('SessionResult')) },
    '/assets': {
      get: action('listAssets', '读取当前租户资产；游标绑定筛选和权限版本', ref('AssetListResult'), { parameters: [parameter('cursor', 'query', string, false), parameter('page_size', 'query', { type: 'integer', enum: [10, 15, 25, 50, 100], default: 15 }, false), ...['type', 'site_id', 'status', 'q'].map((name) => parameter(name, 'query', string, false))] }),
      post: mutation('createAssetDraft', '创建本租户持久资产草稿；不自动纳管', object({ name: { type: 'string', minLength: 1, maxLength: 100 }, type: { type: 'string', enum: types }, management_ip: string, site_id: string }), ref('AssetResult')),
    },
    '/assets/{id}': { get: action('getAsset', '读取本租户资产详情；不存在及无权均404', ref('AssetResult'), { parameters: [pathId] }) },
    '/assets/{id}/onboarding': { post: blocked('requestOnboarding', '接入门禁：Q02/Q03/Q06待确认，拒绝执行', pathId) },
    '/configurations': { get: action('listConfigurations', '仅平台管理员读取设计登记和测试环境持久草稿', ref('ResourceResult'), { parameters: [parameter('decision_status', 'query', { type: 'string', enum: ['pending', 'contract_fixed'] }, false)] }) },
    '/configurations/{key}/draft': { post: mutation('saveConfigurationDraft', '保存独立草稿；不改变value或客户确认状态，动态校验登记类型', object({ proposed_value: { not: { type: 'null' } }, expected_version: { type: 'integer', minimum: 1 } }), ref('ResourceResult'), { parameters: [pathKey, ...headers] }) },
    '/configurations/{key}/publish': { post: blocked('publishConfiguration', '发布门禁；管理员保存不代表客户书面确认', pathKey) },
    '/configurations/{key}/revisions': {
      get: action('listConfigurationRevisions', '本租户配置修订列表；不跨租户聚合', envelope(object({ items: { type: 'array', items: ref('ConfigurationRevision') } })), { parameters: [pathKey] }),
      post: mutation('createConfigurationRevision', '创建持久配置提案和未核验书面资料引用', object({ proposed_value: { not: { type: 'null' } }, reason: boundedComment, evidence_refs: { type: 'array', minItems: 1, maxItems: 5, items: evidence } }), ref('ConfigurationRevisionResult'), { parameters: [pathKey, ...headers] }),
    },
    '/configuration-revisions/{id}': { get: action('getConfigurationRevision', '读取本租户配置修订、状态及历史', ref('ConfigurationRevisionResult'), { parameters: [pathId] }) },
    '/configuration-revisions/{id}/submit': { post: mutation('submitConfigurationRevision', '创建者提交DRAFT进入内部审核', version, ref('ConfigurationRevisionResult'), { parameters: [pathId, ...headers] }) },
    '/configuration-revisions/{id}/review': { post: mutation('reviewConfigurationRevision', '非创建者平台管理员审核；不形成客户确认或生效', object({ expected_version: { type: 'integer', minimum: 1 }, decision: { type: 'string', enum: ['approve', 'reject'] }, comment: boundedComment }), ref('ConfigurationRevisionResult'), { parameters: [pathId, ...headers] }) },
    '/configuration-revisions/{id}/withdraw': { post: mutation('withdrawConfigurationRevision', '创建者撤回DRAFT或IN_REVIEW，保留历史', object({ expected_version: { type: 'integer', minimum: 1 }, reason: boundedComment }), ref('ConfigurationRevisionResult'), { parameters: [pathId, ...headers] }) },
    '/tickets': {
      get: action('listCustomerRegistrations', '查询本租户获准的报障登记；客户仅本人，内部角色仅授权租户', ref('TicketRegistrationListResult'), { parameters: [parameter('cursor', 'query', string, false), parameter('page_size', 'query', { type: 'integer', enum: [10, 15, 25, 50, 100], default: 15 }, false), ...['category', 'urgency', 'q'].map(name => parameter(name, 'query', string, false))] }),
      post: { ...mutation('registerCustomerReport', '耐久登记客户报障；不保证正式业务建单或SLA承诺', ref('TicketCreate'), ref('Accepted')), responses: { 202: { description: '真实PG事务已提交；只返回operation_id/PENDING，需回读本平台受理记录。', content: { 'application/json': { schema: ref('Accepted') } } }, ...errors } },
    },
    '/tickets/{id}': { get: action('getCustomerRegistration', '读取本人或授权租户内部报障登记；跨范围404', ref('TicketRegistrationResult'), { parameters: [pathId] }) },
    '/operations/{id}': { get: action('getOperation', '读取耐久受理operation；PENDING门禁，不冒充远端成功', ref('OperationResult'), { parameters: [pathId] }) },
    '/audit': { get: action('listAudit', '仅平台管理员读取脱敏持久审计', ref('ResourceResult')) },
  },
  components: {
    securitySchemes: { SessionCookie: { type: 'apiKey', in: 'cookie', name: 'MOP_SESSION' } },
    schemas: {
      Error: object({ error: object({ code: string, message: string, details: { type: 'array', items: { type: 'object', additionalProperties: true } } }), trace_id: string }),
      ResourceResult: envelope({ type: 'object', additionalProperties: true }),
      SessionResult: envelope(object({ user: object({ id: string, display_name: string, role: { type: 'string', enum: ['viewer', 'tadmin', 'msp', 'padmin'] } }), tenant, allowed_tenants: { type: 'array', items: tenant }, capabilities: { type: 'array', items: string }, permission_version: { type: 'integer' }, csrf_token: string, mode: { const: 'remote_test' }, identity_source: { const: 'test_oidc' } })),
      Asset: asset,
      AssetResult: envelope(ref('Asset')),
      AssetListResult: list(ref('Asset')),
      ConfigurationRevision: revision,
      ConfigurationRevisionResult: envelope(ref('ConfigurationRevision')),
      TicketCreate: ticketCreate,
      TicketRegistration: ticketRegistration,
      TicketRegistrationResult: envelope(ref('TicketRegistration')),
      TicketRegistrationListResult: list(ref('TicketRegistration')),
      Accepted: envelope(object({ operation_id: { type: 'string', format: 'uuid' }, status: { const: 'PENDING' } })),
      OperationResult: envelope(operation),
    },
  },
};
const target = fileURLToPath(new URL('../contracts/local-api.openapi.json', import.meta.url));
const serialized = JSON.stringify(contract, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (readFileSync(target, 'utf8') !== serialized) throw new Error('local-api.openapi.json 与生成器不一致；先运行 node tools/build-api-contract.mjs');
  console.log('API契约生成一致性检查通过');
} else {
  writeFileSync(target, serialized, 'utf8');
  console.log('生成 contracts/local-api.openapi.json');
}
