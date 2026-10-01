import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

// Real HTTP/SSO smoke test. Sessions are created by capture-test-session.mjs,
// copied to ignored qa-ticket-<role>.json files; never print cookies or payloads.
const root = new URL('../', import.meta.url);
let stage = 'initialization';
try {
  const contract = JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json', root), 'utf8'));
  const ajv = new Ajv({ strict: false, allErrors: true });
  addFormats(ajv);
  ajv.addSchema({ $id: 'urn:mop:customer-report-smoke', components: contract.components });
  const validators = new Map();
  const sessions = Object.fromEntries(['viewerA', 'tadmin', 'msp', 'viewerB'].map(role => [role,
    JSON.parse(readFileSync(new URL(`deploy/private/qa-ticket-${role}.json`, root), 'utf8').replace(/^\uFEFF/, ''))]));
  const run = randomUUID();
  const prefix = `MOP-FAKE-REPORT-${run.slice(0, 8)}`;
  const checks = [];
  async function request(role, method, path, route, expected, name, body, options = {}) {
    stage = name;
    const headers = { 'Content-Type': 'application/json' };
    if (role) headers.Cookie = sessions[role].cookie;
    if (role && !options.noCsrf) headers['X-CSRF-Token'] = sessions[role].csrf_token;
    if (!options.noKey) headers['Idempotency-Key'] = options.key ?? randomUUID();
    const response = await fetch(`http://127.0.0.1:8080/api/v1${path}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(12000), redirect: 'manual',
    });
    assert.equal(response.status, expected);
    const payload = await response.json();
    const schema = contract.paths[route][method.toLowerCase()].responses[String(expected)]?.content?.['application/json']?.schema;
    assert.ok(schema);
    const schemaKey = JSON.stringify(schema);
    if (!validators.has(schemaKey)) validators.set(schemaKey, ajv.compile(schema.$ref
      ? { $ref: `urn:mop:customer-report-smoke${schema.$ref}` } : schema));
    assert.ok(validators.get(schemaKey)(payload));
    checks.push({ name, status: expected, contract_valid: true });
    return payload.data ?? payload;
  }
  const current = {};
  for (const role of Object.keys(sessions)) {
    current[role] = await request(role, 'GET', '/session', '/session', 200, `session_${role}`);
    assert.equal(current[role].mode, 'remote_test');
    assert.equal(current[role].identity_source, 'test_oidc');
    assert.equal(current[role].capabilities.includes('tickets:report'), ['viewerA', 'viewerB', 'tadmin'].includes(role));
  }
  assert.equal(current.viewerA.tenant.id, current.msp.tenant.id);
  assert.equal(current.viewerA.tenant.id, current.tadmin.tenant.id);
  assert.notEqual(current.viewerA.tenant.id, current.viewerB.tenant.id);
  const foreignAssets = await request('viewerB', 'GET', '/assets?page_size=10', '/assets', 200, 'foreign_tenant_asset_lookup');
  assert.ok(foreignAssets.items.length);
  const asset = await request('msp', 'POST', '/assets', '/assets', 200, 'isolated_fake_asset_creation', {
    name: `${prefix}-ASSET`, type: 'switch', management_ip: `2001:db8:${run.slice(0, 4)}:${run.slice(4, 8)}::1`, site_id: 'site-a-main',
  });
  const command = {
    title: `${prefix}-KNOWN`, source: 'customer_report', description: '虚构联调故障描述\n第二行用于验证多行文本。',
    category: '网络不通', impact_scope: 'partial_area', contact_name: '虚构测试联系人', contact_phone: '+00 000 0000', urgency: 'normal', asset_id: asset.id, attachment_ids: [],
  };
  const key = randomUUID();
  const accepted = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'durable_registration_202', command, { key });
  assert.deepEqual(Object.keys(accepted).sort(), ['operation_id', 'status']);
  const op = await request('viewerA', 'GET', `/operations/${accepted.operation_id}`, '/operations/{id}', 200, 'read_pending_operation');
  assert.equal(op.status, 'PENDING');
  assert.equal(op.error.code, 'CONFIG_PENDING');
  assert.equal(op.progress, null);
  assert.match(op.result_ref, /^\/api\/v1\/tickets\/[0-9a-f-]{36}$/);
  const ticketId = op.result_ref.split('/').at(-1);
  const ticket = await request('viewerA', 'GET', `/tickets/${ticketId}`, '/tickets/{id}', 200, 'read_durable_registration');
  assert.equal(ticket.description, command.description);
  assert.equal(ticket.reporter_id, sessions.viewerA.user_id);
  assert.equal(ticket.authority_source, 'MOP');
  for (const field of ['state', 'priority', 'response_due_at', 'recovery_due_at', 'external_id', 'external_state']) assert.equal(ticket[field], null);
  assert.equal(ticket.contact_binding_status, 'UNVERIFIED');
  assert.equal(ticket.history.length, 1);
  const replay = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'same_key_exact_replay', command, { key });
  assert.deepEqual(replay, accepted);
  const conflict = await request('viewerA', 'POST', '/tickets', '/tickets', 409, 'same_key_changed_body_rejected', { ...command, description: '虚构变更' }, { key });
  assert.equal(conflict.error.code, 'IDEMPOTENCY_CONFLICT');
  const duplicate = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'known_asset_semantic_duplicate', { ...command, contact_name: '另一个虚构联系人', description: '重复报障，不得覆盖旧记录' });
  assert.deepEqual(duplicate, accepted);
  const reread = await request('viewerA', 'GET', `/tickets/${ticketId}`, '/tickets/{id}', 200, 'semantic_duplicate_keeps_original_payload');
  assert.equal(reread.contact_name, command.contact_name);
  assert.equal(reread.description, command.description);
  const otherReporter = await request('tadmin', 'POST', '/tickets', '/tickets', 202, 'separate_customer_registration', command);
  assert.notEqual(otherReporter.operation_id, accepted.operation_id);
  const unknown = { ...command, title: `${prefix}-UNKNOWN` };
  delete unknown.asset_id;
  const unknownFirst = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'unknown_asset_registration', unknown);
  const unknownSecond = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'unknown_asset_not_semantically_merged', unknown);
  assert.notEqual(unknownFirst.operation_id, unknownSecond.operation_id);
  await request('msp', 'GET', `/tickets/${ticketId}`, '/tickets/{id}', 200, 'operator_authorized_tenant_read');
  await request('msp', 'GET', `/operations/${accepted.operation_id}`, '/operations/{id}', 200, 'operator_authorized_operation_read');
  for (const role of ['tadmin', 'viewerB']) {
    await request(role, 'GET', `/tickets/${ticketId}`, '/tickets/{id}', 404, `${role}_hidden_ticket_404`);
    await request(role, 'GET', `/operations/${accepted.operation_id}`, '/operations/{id}', 404, `${role}_hidden_operation_404`);
  }
  await request('viewerA', 'POST', '/tickets', '/tickets', 404, 'foreign_tenant_asset_rejected', { ...command, asset_id: foreignAssets.items[0].id });
  await request('msp', 'POST', '/tickets', '/tickets', 403, 'operator_cannot_impersonate_customer', command);
  await request('viewerA', 'POST', '/tickets', '/tickets', 403, 'missing_csrf_rejected', command, { noCsrf: true });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'missing_idempotency_rejected', command, { noKey: true });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'priority_spoof_rejected', { ...command, priority: 'P1' });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'actor_spoof_rejected', { ...command, actor_id: sessions.msp.user_id });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'tenant_spoof_rejected', { ...command, tenant_id: current.viewerB.tenant.id });
  const photo = await request('viewerA', 'POST', '/tickets', '/tickets', 422, 'unavailable_photo_service_rejected', { ...command, attachment_ids: [randomUUID()] });
  assert.equal(photo.error.code, 'CONFIG_PENDING');
  assert.deepEqual(photo.error.details[0].question_ids, ['Q28']);
  await request('viewerA', 'POST', '/tickets', '/tickets', 403, 'customer_manual_source_rejected', { ...command, source: 'manual' });
  await request('msp', 'POST', '/tickets', '/tickets', 422, 'internal_manual_workflow_pending', { ...command, source: 'manual' });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'invalid_phone_rejected', { ...command, contact_phone: 'abc' });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'invalid_category_rejected', { ...command, category: 'UNKNOWN' });
  await request('viewerA', 'POST', '/tickets', '/tickets', 400, 'overlong_description_rejected', { ...command, description: '测'.repeat(3001) });
  const filter = new URLSearchParams({ q: prefix, category: '网络不通', urgency: 'normal', page_size: '10' });
  const own = await request('viewerA', 'GET', `/tickets?${filter}`, '/tickets', 200, 'customer_list_scope_and_filters');
  assert.equal(own.items.length, 3);
  assert.ok(own.items.every(item => item.reporter_id === sessions.viewerA.user_id));
  const internal = await request('msp', 'GET', `/tickets?${filter}`, '/tickets', 200, 'operator_list_current_tenant');
  assert.equal(internal.items.length, 4);
  const unseen = await request('viewerB', 'GET', `/tickets?${filter}`, '/tickets', 200, 'foreign_tenant_list_has_no_records');
  assert.equal(unseen.items.length, 0);
  await request('viewerA', 'GET', '/tickets?page_size=11', '/tickets', 400, 'page_size_whitelist');
  await request('viewerA', 'GET', '/tickets?category=UNKNOWN', '/tickets', 400, 'category_filter_whitelist');
  await request('viewerA', 'GET', '/tickets?q=a&q=b', '/tickets', 400, 'duplicate_query_rejected');
  await request('viewerA', 'GET', '/tickets?actor_id=spoof', '/tickets', 400, 'query_spoof_rejected');
  const page = await request('msp', 'GET', '/tickets?page_size=10', '/tickets', 200, 'keyset_first_page');
  assert.equal(page.has_more, true);
  const cursor = encodeURIComponent(page.next_cursor);
  const next = await request('msp', 'GET', `/tickets?page_size=10&cursor=${cursor}`, '/tickets', 200, 'keyset_second_page');
  assert.ok(next.items.every(item => !page.items.some(first => first.id === item.id)));
  await request('viewerA', 'GET', `/tickets?page_size=10&cursor=${cursor}`, '/tickets', 400, 'cursor_bound_to_actor');
  await request('msp', 'GET', `/tickets?page_size=10&category=网络不通&cursor=${cursor}`, '/tickets', 400, 'cursor_bound_to_filter');
  const finalRead = await request('viewerA', 'GET', `/tickets/${ticketId}`, '/tickets/{id}', 200, 'rejected_requests_preserve_registration');
  assert.equal(finalRead.history.length, 1);
  const evidence = {
    verified_at: new Date().toISOString(), mode: 'remote_test', authority_direction: 'MOP',
    environment: { postgres: '17.9', keycloak: '26.2.4', access: 'existing_shared_services_via_ssh_tunnel' },
    http_checks: checks.length, all_passed: true, checks, credential_output: 'none',
    remaining: ['formal_ticket_state', 'SLA_configuration', 'contact_binding', 'photo_upload', 'cross_reporter_semantic_merge', 'unknown_asset_semantic_merge'],
  };
  writeFileSync(new URL('docs/evidence/customer-report-http-verification.json', root), JSON.stringify(evidence, null, 2) + '\n');
  writeFileSync(new URL('.local/customer-report-smoke.json', root), JSON.stringify({ ticket_id: ticketId, operation_id: accepted.operation_id, asset_id: asset.id, fake_prefix: prefix }) + '\n');
  console.log(JSON.stringify({ status: 'passed', http_checks: checks.length, contract_valid: true, environment: 'remote_test', credential_output: 'none' }));
} catch {
  console.error(JSON.stringify({ status: 'failed', stage, details: 'redacted; no private values or HTTP bodies printed' }));
  process.exitCode = 1;
}
