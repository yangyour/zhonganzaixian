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
const blocked = (operationId, summary, pathParameter) => ({ ...mutation(operationId, summary, version, ref('ResourceResult'), { parameters: [pathParameter, ...headers] }), responses: errors });
const tenant = object({ id: { type: 'string', format: 'uuid' }, name: string });
const asset = object({ id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, name: string, type: { type: 'string', enum: types }, management_ip: string, site_id: string, status: { type: 'string', enum: ['unknown', 'ok', 'problem', 'maintenance', 'disabled'] }, lifecycle_status: string, source: { type: 'string', enum: ['test_seed', 'manual'] }, sampled_at: nullableString, unknown_reason: nullableString, version: { type: 'integer', minimum: 1 }, created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' }, availability: string, sync_state: string }, ['id', 'tenant_id', 'name', 'type', 'management_ip', 'site_id', 'status', 'lifecycle_status', 'source', 'sampled_at', 'unknown_reason', 'version', 'created_at', 'updated_at']);
const contract = {
  openapi: '3.1.0',
  info: { title: 'MOP 共享测试环境 API', version: '0.2.0', description: '本地应用通过SSH复用指定服务器的PostgreSQL与Keycloak。测试草稿、幂等与审计持久化，身份使用真实OIDC；测试种子不是真实监控数据。客户正式审批发布和监控纳管尚未实现，publish/onboarding保持门禁。fixture身份入口在remote_test不可调用。' },
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
