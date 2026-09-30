import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const base = process.argv[2] ?? 'http://127.0.0.1:8080/api/v1';
assert.equal(new URL(base).hostname, '127.0.0.1', '联调脚本仅允许本机SSH转发应用');
const contract = JSON.parse(readFileSync(new URL('../contracts/local-api.openapi.json', import.meta.url), 'utf8'));
const credentialFile = new URL('../deploy/private/qa-session.json', import.meta.url);
const evidenceFile = new URL('../.local/persistence-smoke.json', import.meta.url);
const processFile = new URL('../.local/processes.json', import.meta.url);
const processState = JSON.parse(readFileSync(processFile, 'utf8').replace(/^\uFEFF/, ''));
assert.equal(processState.mode, 'remote_test');
assert.equal(String(processState.api_port), new URL(base).port, '进程记录必须对应被测API端口');
const apiProcess = processState.processes.find(process => process.name === 'api');
assert.ok(apiProcess);
const apiIdentity = { id: apiProcess.id, start_time: apiProcess.start_time };
const authenticated = JSON.parse(readFileSync(credentialFile, 'utf8'));
assert.equal(authenticated.mode, 'remote_test', '必须先通过真实Keycloak登录获取测试会话');
const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
let checked = 0;
let csrf = authenticated.csrf_token;
const key = () => crypto.randomUUID();

async function request(method, route, { body, status = 200, operationPath = route.split('?')[0], idem = key(), token = csrf, cookie = authenticated.cookie } = {}) {
  const headers = { Cookie: cookie, 'Idempotency-Key': idem, Accept: 'application/json' };
  if (token) headers['X-CSRF-Token'] = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(base + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'manual' });
  const data = await response.json();
  assert.equal(response.status, status, `${method} ${operationPath}: ${data.error?.code ?? 'unexpected_status'}`);
  const schema = contract.paths[operationPath]?.[method.toLowerCase()]?.responses[String(status)]?.content?.['application/json']?.schema ?? (status >= 400 ? { $ref: '#/components/schemas/Error' } : undefined);
  assert.ok(schema, `契约缺少${method} ${operationPath} ${status}`);
  const validate = ajv.compile({ ...schema, components: contract.components });
  assert.ok(validate(data), `${method} ${operationPath}: ${JSON.stringify(validate.errors)}`);
  assert.ok(data.trace_id);
  assert.equal(response.headers.get('x-trace-id'), data.trace_id);
  checked++;
  return data;
}

const health = await request('GET', '/health/ready');
assert.equal(health.data.mode, 'remote_test');
assert.equal(health.data.durable, true);
assert.equal(health.data.production_ready, false);
let session = (await request('GET', '/session')).data;
csrf = session.csrf_token;
assert.equal(session.identity_source, 'test_oidc');

if (process.argv.includes('--verify-persistence')) {
  const previous = JSON.parse(readFileSync(evidenceFile, 'utf8'));
  assert.notDeepEqual(apiIdentity, previous.api_process_identity, '必须先重启API，不能把同一进程回读称为重启验证');
  assert.ok(previous.api_process_identity, '旧证据缺少进程身份；先重新执行一次正常API联调');
  if (session.tenant.id !== previous.tenant_id) {
    session = (await request('POST', '/session/tenant', { body: { tenant_id: previous.tenant_id } })).data;
    csrf = session.csrf_token;
  }
  const detail = await request('GET', '/assets/' + previous.asset_id, { operationPath: '/assets/{id}' });
  assert.equal(detail.data.name, previous.command.name);
  assert.equal(detail.data.created_at, previous.created_at);
  const replay = await request('POST', '/assets', { body: previous.command, idem: previous.idempotency_key });
  assert.equal(replay.data.id, previous.asset_id);
  const configs = await request('GET', '/configurations');
  const saved = configs.data.items.find(item => item.key === previous.configuration_key);
  assert.equal(saved.draft_value, previous.draft_value);
  assert.equal(saved.version, previous.configuration_version);
  assert.equal(saved.value, null);
  console.log(JSON.stringify({ status: 'passed', mode: 'remote_test', response_checks: checked, persisted_asset: true, persisted_idempotency: true, persisted_config_draft: true, api_restart_verified: true }, null, 2));
  process.exit(0);
}

await request('GET', '/session', { status: 401, cookie: '' });
const options = await request('GET', '/auth/options');
assert.equal(options.data.login_url, '/oauth2/authorization/mop');
await request('GET', '/fixture/users', { status: 403 });
await request('POST', '/fixture/session', { body: { user_id: session.user.id }, status: 403 });
assert.equal(session.user.role, 'padmin', '此契约套件需要平台管理员测试身份');
const tenantA = session.allowed_tenants[0].id;
const tenantB = session.allowed_tenants[1].id;
if (session.tenant.id !== tenantA) {
  session = (await request('POST', '/session/tenant', { body: { tenant_id: tenantA } })).data;
  csrf = session.csrf_token;
}
const page = await request('GET', '/assets?page_size=10', { operationPath: '/assets' });
assert.equal(page.data.items.length, 10);
assert.ok(page.data.has_more);
const assetA = page.data.items[0];
assert.equal(assetA.status, 'unknown');
assert.equal(assetA.sampled_at, null);
assert.equal(assetA.source, 'test_seed');
await request('GET', '/assets/' + assetA.id, { operationPath: '/assets/{id}' });
const second = await request('GET', '/assets?page_size=10&cursor=' + encodeURIComponent(page.data.next_cursor), { operationPath: '/assets' });
assert.ok(second.data.items.every(asset => !page.data.items.some(prior => prior.id === asset.id)));
await request('GET', '/assets?tenant_id=' + tenantB, { status: 400, operationPath: '/assets' });
const empty = await request('GET', '/assets?q=no-such-test-asset', { operationPath: '/assets' });
assert.equal(empty.data.items.length, 0);
const command = { name: 'RemoteSmoke-' + key(), type: 'switch', management_ip: '203.0.113.8', site_id: 'site-a-main' };
await request('POST', '/assets', { body: command, token: 'wrong-csrf', status: 403 });
const assetKey = key();
const created = await request('POST', '/assets', { body: command, idem: assetKey });
assert.equal(created.data.source, 'manual');
assert.equal(created.data.lifecycle_status, 'DRAFT');
const replayed = await request('POST', '/assets', { body: command, idem: assetKey });
assert.equal(replayed.data.id, created.data.id);
await request('POST', '/assets', { body: { ...command, name: command.name + '-changed' }, idem: assetKey, status: 409 });
await request('POST', '/assets', { body: { ...command, name: command.name + '-forged', tenant_id: tenantB }, status: 400 });
const concurrentKey = key();
const concurrentBody = { ...command, name: command.name + '-concurrent' };
const concurrent = await Promise.all([request('POST', '/assets', { body: concurrentBody, idem: concurrentKey }), request('POST', '/assets', { body: concurrentBody, idem: concurrentKey })]);
assert.equal(concurrent[0].data.id, concurrent[1].data.id);
await request('POST', '/assets/' + created.data.id + '/onboarding', { body: { expected_version: created.data.version }, status: 422, operationPath: '/assets/{id}/onboarding' });
session = (await request('POST', '/session/tenant', { body: { tenant_id: tenantB } })).data;
csrf = session.csrf_token;
await request('GET', '/assets/' + assetA.id, { status: 404, operationPath: '/assets/{id}' });
await request('GET', '/assets?page_size=10&cursor=' + encodeURIComponent(page.data.next_cursor), { status: 400, operationPath: '/assets' });
const bAssets = await request('GET', '/assets');
assert.ok(bAssets.data.items.every(asset => asset.tenant_id === tenantB));
const configs = await request('GET', '/configurations');
const pending = configs.data.items.find(item => item.decision_status === 'pending' && item.type === 'integer');
assert.ok(pending);
assert.equal(pending.value, null);
const draft = await request('POST', '/configurations/' + encodeURIComponent(pending.key) + '/draft', { body: { proposed_value: 60, expected_version: pending.version }, operationPath: '/configurations/{key}/draft' });
assert.equal(draft.data.value, null);
assert.equal(draft.data.effective_value, null);
assert.equal(draft.data.decision_status, 'pending');
const publish = await request('POST', '/configurations/' + encodeURIComponent(pending.key) + '/publish', { body: { expected_version: draft.data.version }, status: 422, operationPath: '/configurations/{key}/publish' });
assert.equal(publish.error.code, 'CONFIG_PENDING');
const audit = await request('GET', '/audit');
assert.equal(audit.data.durable, true);
assert.ok(audit.data.items.some(item => item.result === 'DENIED'));
assert.ok(audit.data.items.every(item => item.entry_hash && Number.isInteger(item.sequence)));
session = (await request('POST', '/session/tenant', { body: { tenant_id: tenantA } })).data;
csrf = session.csrf_token;
mkdirSync(new URL('../.local/', import.meta.url), { recursive: true });
writeFileSync(evidenceFile, JSON.stringify({ api_process_identity: apiIdentity, asset_id: created.data.id, created_at: created.data.created_at, tenant_id: tenantA, command, idempotency_key: assetKey, configuration_key: pending.key, draft_value: 60, configuration_version: draft.data.version }, null, 2));
console.log(JSON.stringify({ status: 'passed', mode: 'remote_test', http_responses_validated_against_openapi: checked, identity: 'real_keycloak_oidc_session', durable: true, scope: ['csrf', 'fixture_entry_rejected', 'asset_cursor', 'durable_idempotency', 'concurrent_idempotency', 'tenant_isolation', 'config_pending', 'durable_audit'] }, null, 2));
