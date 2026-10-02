/** Real HTTP only. This file never starts/stops services, runs DDL or accesses a database.
 * node tools/asset-import-http-verification.mjs --basic
 * python tools/generate-asset-import-xlsx-fixture.py --run-id <UUID>
 * node tools/asset-import-http-verification.mjs --xlsx --run-id <UUID> --xlsx-fixture deploy/private/qa-import-fixtures/<UUID>/MOP-FAKE-<UUID>-valid.xlsx
 * Root captures qa-import-{padmin,msp,tadmin,viewerA,viewerB}.json through real OIDC.
 * Two writer identities must share a tenant; the third writer must already be in another tenant.
 * This tool does not switch any session's tenant. All inputs are fictional MOP-FAKE data.
 */
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const root = fileURLToPath(new URL('../', import.meta.url));
const aliases = ['padmin', 'msp', 'tadmin', 'viewerA', 'viewerB'];
const codes = ['NAME', 'TYPE', 'ADDRESS', 'BRAND', 'MODEL', 'SERIAL_NUMBER', 'SITE', 'LOCATION', 'OWNER', 'BUSINESS', 'PURCHASE_DATE', 'WARRANTY_EXPIRY', 'OVERDUE_SERVICE_DATE', 'COLLECTION_METHOD', 'NOTE'];
const labels = ['设备名称', '设备类型', 'IP或域名', '品牌', '型号', '序列号', '站点', '位置', '负责人', '所属业务', '采购日期', '维保到期日', '超期服役日期', '采集方式', '备注'];
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const sha = value => createHash('sha256').update(value).digest('hex');
const hash = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;
const same = (left, right) => JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
class SafeFailure extends Error { constructor(code) { super('redacted'); this.code = code } }
let evidenceFile, privateStateFile, base, runId, ajv, contract, ownerAlias, foreignAlias, crossAlias;
let requestCount = 0, pendingWrite = false;
const sessions = new Map(), checks = [], artifacts = [], privateState = { requests: [] };
const evidence = { scope: 'REAL_HTTP_SOURCE_STORE_PARSE_MAPPING_ONLY', status: 'NOT_RUN', credential_output: 'none', raw_requests_and_responses: 'OMITTED', database_access: false, service_lifecycle_changes: false, tenant_switches: false, business_import: 'NOT_IMPLEMENTED', contract_acceptance: 'NOT_RUN', can_import: false, effective_rule_versions: null, quota_reservation: null };

function check(name, condition, safe = {}) {
  checks.push({ name, status: condition ? 'PASS' : 'FAIL', ...safe });
  if (!condition) throw new SafeFailure(name);
}
function argument(name, fallback) { const index = process.argv.indexOf(name); return index < 0 ? fallback : process.argv[index + 1] }
function under(path, parent) { const difference = relative(parent, path); return difference && !difference.startsWith('..' + sep) && difference !== '..' && !isAbsolute(difference) }
function checkpoint() { if (privateStateFile) writeFileSync(privateStateFile, JSON.stringify({ run_id: runId, synthetic_marker: 'MOP-FAKE', ...privateState }, null, 2) + '\n') }
function preserve(name, alias, route, key, body, file) {
  const state = { name, alias, route, key, outcome: 'UNCERTAIN', ...(body === undefined ? {} : { body }), ...(file ? { file_name: file.name, file_sha256: sha(file.bytes), file_format: file.format } : {}) };
  privateState.requests.push(state); checkpoint(); return state;
}
function phase(name) { console.log(JSON.stringify({ phase: name, completed_checks: checks.length, credential_output: 'none' })) }
function redactCode(code) { return typeof code === 'string' && /^[A-Z0-9_]{1,80}$/.test(code) ? code : 'REDACTED_UNEXPECTED_CODE' }
function pathFor(route) {
  return route.split('?')[0].replace(/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?=\/|$)/g, '/{id}');
}

async function request(name, alias, method, route, options = {}) {
  const credential = sessions.get(alias), expectedStatus = options.status ?? 200;
  if (!credential || !/^\/[a-zA-Z0-9/{}?=._%-]+$/.test(route)) throw new SafeFailure('request_context_invalid');
  const headers = { Accept: 'application/json', Cookie: credential.cookie };
  if (method !== 'GET') {
    if (!options.noCsrf) headers['X-CSRF-Token'] = options.csrf ?? credential.csrf;
    if (!options.noKey) headers['Idempotency-Key'] = options.key ?? randomUUID();
  }
  let body;
  if (options.multipart) body = options.multipart;
  else if (options.raw !== undefined) { headers['Content-Type'] = 'application/json'; body = options.raw }
  else if (options.body !== undefined) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(options.body) }
  let response, envelope;
  try {
    response = await fetch(base + route, { method, headers, body, redirect: 'error', signal: AbortSignal.timeout(20_000) });
    const text = await response.text();
    if (text.length > 32 * 1024 * 1024) throw new SafeFailure('response_resource_limit');
    envelope = JSON.parse(text);
  } catch (cause) {
    checks.push({ name, status: 'FAIL', reason: cause instanceof SafeFailure ? cause.code : 'NETWORK_OR_RESPONSE_FORMAT_ERROR' });
    throw new SafeFailure(name);
  }
  requestCount++;
  const schema = contract.paths[options.path ?? pathFor(route)]?.[method.toLowerCase()]?.responses[String(expectedStatus)]?.content?.['application/json']?.schema
    ?? (expectedStatus >= 400 ? { $ref: '#/components/schemas/Error' } : undefined);
  let valid = false;
  try { valid = Boolean(schema && ajv.compile({ ...schema, components: contract.components })(envelope)) } catch { /* Never publish an AJV error that might include source values. */ }
  const traceValid = typeof envelope?.trace_id === 'string' && /^[A-Za-z0-9._:-]{1,128}$/.test(envelope.trace_id) && response.headers.get('x-trace-id') === envelope.trace_id;
  check(name, response.status === expectedStatus && valid && traceValid, { http_status: response.status, expected_status: expectedStatus, schema_valid: valid, trace_header_matches: traceValid, ...(response.status >= 400 ? { error_code: redactCode(envelope?.error?.code) } : {}) });
  if (options.code) check(name + '_error_code', envelope.error?.code === options.code);
  return envelope.data;
}

function gates(name, source) {
  check(name, source.business_status === 'CONFIG_PENDING' && source.can_import === false && source.effective_rule_versions === null && source.quota_reservation === null
    && source.scan_status === 'UNSCANNED' && source.production_approval === 'UNVERIFIED' && source.retention_until === null && same(source.question_ids, ['Q20', 'Q31']));
}
function sourceBindings(name, source, file, accepted) {
  const identity = sessions.get(ownerAlias).identity;
  check(name, source.id === accepted.source_id && source.tenant_id === identity.tenant.id && source.created_by === identity.user.id && source.purpose === 'asset_import_source'
    && source.original_name === file.name && source.declared_format === file.format && source.size_bytes === file.bytes.length && source.sha256 === sha(file.bytes) && source.store_operation_id === file.storeOperationId);
  gates(name + '_unapproved', source);
}
function artifactBindings(name, source, operation, selection) {
  const artifact = source.parsed_artifact;
  check(name, Boolean(artifact && artifact.id === source.latest_artifact_id && artifact.id === operation.result_artifact_id && artifact.tenant_id === source.tenant_id && artifact.source_id === source.id
    && artifact.operation_id === operation.id && source.parse_operation_id === operation.id && artifact.source_sha256 === source.sha256 && hash(artifact.parse_hash)
    && artifact.schema_version === 'contract-m3-6-standard15-v1' && artifact.resource_policy_version === 'asset-import-technical-v1'
    && Object.entries(selection).every(([key, value]) => key === 'expected_version' || artifact.selection[key] === value)));
  const excluded = new Set(['id', 'tenant_id', 'source_id', 'operation_id', 'parse_hash', 'source_sha256', 'schema_version', 'selection', 'created_at']);
  const rawArtifact = Object.fromEntries(Object.entries(artifact).filter(([key]) => !excluded.has(key)));
  const binding = { tenant_id: source.tenant_id, source_id: source.id, source_sha256: source.sha256, selection: artifact.selection, schema_version: artifact.schema_version, artifact: rawArtifact };
  check(name + '_canonical_parse_hash', artifact.parse_hash === sha(JSON.stringify(canonical(binding))));
}
function multipart(file, { mime = file.format === 'CSV' ? 'text/csv' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', extra, duplicate } = {}) {
  const form = new FormData(); form.append('file', new Blob([file.bytes], { type: mime }), file.name); form.append('purpose', 'asset_import_source'); form.append('format', file.format);
  if (extra) form.append('tenant_id', 'MOP-FAKE-client-tenant');
  if (duplicate) form.append('format', file.format);
  return form;
}
async function readSource(name, file, accepted) {
  const source = await request(name, ownerAlias, 'GET', `/assets/import-sources/${accepted.source_id}`);
  sourceBindings(name + '_metadata_binding', source, file, accepted); return source;
}
async function poll(name, file, accepted, kind, expectedStep, selection) {
  const deadline = Date.now() + 180_000;
  for (let attempt = 0; attempt < 180 && Date.now() < deadline; attempt++) {
    const operation = await request(`${name}_operation_${String(attempt + 1).padStart(3, '0')}`, ownerAlias, 'GET', `/asset-import-operations/${accepted.operation_id}`);
    const actor = sessions.get(ownerAlias).identity;
    check(`${name}_operation_binding_${attempt + 1}`, operation.id === accepted.operation_id && operation.source_id === accepted.source_id && operation.kind === kind && operation.tenant_id === actor.tenant.id && operation.created_by === actor.user.id && operation.result_ref === `/api/v1/assets/import-sources/${accepted.source_id}`);
    const source = await readSource(`${name}_source_${String(attempt + 1).padStart(3, '0')}`, file, accepted);
    if (operation.status === 'FAILED' && expectedStep !== 'PARSE_INVALID') throw new SafeFailure(`${name}_real_worker_failed`);
    if (['SUCCEEDED', 'FAILED'].includes(operation.status)) {
      check(name + '_real_worker_terminal', operation.step === expectedStep && operation.status === (expectedStep === 'PARSE_INVALID' ? 'FAILED' : 'SUCCEEDED'));
      if (kind === 'STORE_RAW') check(name + '_raw_ready_only', source.storage_status === 'READY' && source.parse_status === 'NOT_REQUESTED' && source.parsed_artifact === null && source.mapping === null);
      else if (expectedStep === 'PARSE_INVALID') check(name + '_invalid_has_no_artifact', source.storage_status === 'READY' && source.parse_status === 'FAILED' && source.parsed_artifact === null && source.mapping === null && operation.error?.code === 'IMPORT_SOURCE_INVALID');
      else { artifactBindings(name + '_artifact_binding', source, operation, selection); check(name + '_parse_status_matches', source.parse_status === expectedStep && source.parsed_artifact.state === expectedStep) }
      return source;
    }
    await delay(1000);
  }
  throw new SafeFailure(name + '_real_worker_timeout_unresolved');
}
async function upload(name, file) {
  const key = randomUUID(), retained = preserve(name, ownerAlias, '/assets/import-sources', key, undefined, file); pendingWrite = true;
  const accepted = await request(name + '_accepted', ownerAlias, 'POST', '/assets/import-sources', { status: 202, key, multipart: multipart(file) });
  check(name + '_accepted_ids', uuid(accepted.source_id) && uuid(accepted.operation_id) && accepted.status === 'PENDING');
  retained.outcome = 'ACCEPTED'; retained.acceptance = accepted; checkpoint(); pendingWrite = false; file.storeOperationId = accepted.operation_id;
  const replay = await request(name + '_same_key_boundary_mime_replay', ownerAlias, 'POST', '/assets/import-sources', { status: 202, key, multipart: multipart(file, { mime: 'application/octet-stream' }) });
  check(name + '_same_source_and_operation', same(replay, accepted));
  await request(name + '_different_bytes_same_key_conflict', ownerAlias, 'POST', '/assets/import-sources', { status: 409, code: 'IDEMPOTENCY_CONFLICT', key, multipart: multipart({ ...file, bytes: Buffer.concat([file.bytes, Buffer.from('\nMOP-FAKE-change')]) }) });
  const source = await poll(name + '_store', file, accepted, 'STORE_RAW', 'STORED'); retained.outcome = 'READBACK_CONFIRMED'; checkpoint();
  return { source, accepted };
}
function reverseKeys(value) { if (Array.isArray(value)) return value.map(reverseKeys); if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).reverse().map(key => [key, reverseKeys(value[key])])); return value }
async function parse(name, file, source, selection, expectedStep = 'PARSED') {
  const body = { expected_version: source.version, ...selection }, key = randomUUID(), route = `/assets/import-sources/${source.id}/parses`, retained = preserve(name, ownerAlias, route, key, body); pendingWrite = true;
  const accepted = await request(name + '_accepted', ownerAlias, 'POST', route, { status: 202, key, body });
  check(name + '_same_source', accepted.source_id === source.id); retained.outcome = 'ACCEPTED'; retained.acceptance = accepted; checkpoint(); pendingWrite = false;
  const replay = await request(name + '_canonical_object_replay', ownerAlias, 'POST', route, { status: 202, key, body: reverseKeys(body) });
  check(name + '_same_source_and_operation', same(replay, accepted));
  const result = await poll(name, file, accepted, 'PARSE', expectedStep, body); retained.outcome = 'READBACK_CONFIRMED'; checkpoint();
  return { source: result, accepted, body };
}
function mappingBody(source) { return { expected_version: source.version, source_parse_hash: source.parsed_artifact.parse_hash, target_source_columns: Object.fromEntries(codes.map((code, index) => [code, index])), ignored_source_columns: [15, 16] } }
async function map(name, file, source, accepted) {
  const body = mappingBody(source), route = `/assets/import-sources/${source.id}/mappings`;
  await request(name + '_stale_cas', ownerAlias, 'POST', route, { status: 409, code: 'VERSION_CONFLICT', body: { ...body, expected_version: 1 } });
  await request(name + '_wrong_parse_hash', ownerAlias, 'POST', route, { status: 409, code: 'VERSION_CONFLICT', body: { ...body, source_parse_hash: '0'.repeat(64) } });
  await request(name + '_unknown_field', ownerAlias, 'POST', route, { status: 400, code: 'VALIDATION_ERROR', body: { ...body, tenant_id: 'MOP-FAKE-client' } });
  await request(name + '_note_must_map', ownerAlias, 'POST', route, { status: 400, code: 'VALIDATION_ERROR', body: { ...body, target_source_columns: { ...body.target_source_columns, NOTE: null } } });
  await request(name + '_duplicate_source_assignment', ownerAlias, 'POST', route, { status: 400, code: 'VALIDATION_ERROR', body: { ...body, target_source_columns: { ...body.target_source_columns, TYPE: 0 } } });
  await request(name + '_explicit_all_ignored_required', ownerAlias, 'POST', route, { status: 400, code: 'VALIDATION_ERROR', body: { ...body, ignored_source_columns: [15] } });
  const key = randomUUID(), retained = preserve(name, ownerAlias, route, key, body); pendingWrite = true;
  const returned = await request(name + '_saved_response', ownerAlias, 'POST', route, { key, body });
  retained.outcome = 'ACCEPTED'; checkpoint(); pendingWrite = false;
  const replay = await request(name + '_canonical_object_replay', ownerAlias, 'POST', route, { key, body: reverseKeys(body) });
  check(name + '_same_mapping_replay', replay.id === returned.id && replay.mapping.id === returned.mapping.id && replay.mapping.preview_hash === returned.mapping.preview_hash && replay.version === returned.version);
  const result = await readSource(name + '_durable_source_readback', file, accepted), saved = result.mapping;
  check(name + '_mapping_binding', Boolean(saved && saved.id === result.latest_mapping_id && saved.tenant_id === result.tenant_id && saved.source_id === result.id && saved.artifact_id === result.parsed_artifact.id && saved.source_parse_hash === body.source_parse_hash
    && hash(saved.preview_hash) && saved.schema_version === result.parsed_artifact.schema_version && saved.precheck_status === 'NOT_RUN' && codes.every(code => saved.target_source_columns[code] === body.target_source_columns[code]) && same(saved.ignored_source_columns, [15, 16])));
  const originalRows = result.parsed_artifact.rows;
  const previewBinding = { tenant_id: result.tenant_id, source_id: result.id, source_sha256: result.sha256, source_parse_hash: saved.source_parse_hash, schema_version: saved.schema_version, target_source_columns: saved.target_source_columns, ignored_source_columns: saved.ignored_source_columns, mapped_raw_rows: saved.mapped_raw_rows };
  check(name + '_canonical_preview_hash', saved.preview_hash === sha(JSON.stringify(canonical(previewBinding))));
  check(name + '_raw_mapped_row_count', saved.mapped_raw_rows.length === originalRows.length);
  for (const row of saved.mapped_raw_rows) {
    const raw = originalRows.find(item => item.logical_record_number === row.logical_record_number);
    check(name + '_raw_row_' + row.logical_record_number, Boolean(raw && row.source_row_number === raw.source_row_number && row.physical_start_line === raw.physical_start_line && row.physical_end_line === raw.physical_end_line && Object.keys(row.cells).length === 15
      && codes.every(code => { const cell = raw.cells.find(item => item.column_index === body.target_source_columns[code]); return Boolean(cell && ['column_index', 'kind', 'lexical', 'display', 'style_index', 'format_code'].every(field => row.cells[code]?.[field] === cell[field])) })));
  }
  await request(name + '_changed_body_same_key_conflict', ownerAlias, 'POST', route, { status: 409, code: 'IDEMPOTENCY_CONFLICT', key, body: { ...body, target_source_columns: { ...body.target_source_columns, NAME: 15 }, ignored_source_columns: [0, 16] } });
  gates(name + '_business_rules_and_quota_unconfirmed', result); retained.outcome = 'READBACK_CONFIRMED'; checkpoint();
  artifacts.push({ format: file.format, source_id: result.id, storage_operation_id: file.storeOperationId, parse_operation_id: result.parse_operation_id, source_sha256: result.sha256, parse_hash: result.parsed_artifact.parse_hash, preview_hash: saved.preview_hash, source_version: result.version, date_epoch: result.parsed_artifact.date_epoch, original_rows: originalRows.length, precheck_status: 'NOT_RUN', business_status: 'CONFIG_PENDING', can_import: false });
  return result;
}

async function main() {
  const allowedOptions = new Set(['--basic', '--xlsx', '--base', '--run-id', '--xlsx-fixture']);
  for (let index = 2; index < process.argv.length; index++) { const value = process.argv[index]; if (!allowedOptions.has(value)) throw new SafeFailure('unknown_cli_option'); if (['--base', '--run-id', '--xlsx-fixture'].includes(value)) index++ }
  base = argument('--base', 'http://127.0.0.1:8080/api/v1'); const target = new URL(base);
  if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' || !['8080', '5173'].includes(target.port) || target.pathname !== '/api/v1' || target.search || target.hash || target.username || target.password) throw new SafeFailure('only_normal_loopback_api_allowed');
  runId = argument('--run-id', randomUUID()); if (!uuid(runId)) throw new SafeFailure('run_uuid_invalid');
  const directory = resolve(root, 'deploy/private/qa-import-fixtures', runId); mkdirSync(directory, { recursive: true });
  privateStateFile = resolve(directory, 'http-request-state.json'); if (existsSync(privateStateFile)) throw new SafeFailure('run_id_already_has_requests_use_new_run_id');
  evidenceFile = resolve(root, 'docs/evidence', `local-asset-import-http-${runId}.json`); mkdirSync(dirname(evidenceFile), { recursive: true });
  Object.assign(evidence, { started_at: new Date().toISOString(), run_id: runId, api_port: Number(target.port), api_version: '0.8.0', selected_mode: process.argv.includes('--xlsx') ? 'BASIC_AND_XLSX' : 'BASIC_CSV', private_fixture_reference: 'ignored_deploy_private_qa_import_fixtures', checks });
  contract = JSON.parse(readFileSync(resolve(root, 'contracts/local-api.openapi.json'), 'utf8')); check('contract_version_0_8', contract.info.version === '0.8.0');
  ajv = new Ajv2020({ strict: false, allErrors: true }); addFormats(ajv); ajv.addFormat('binary', true);
  for (const alias of aliases) {
    let credential;
    try { credential = JSON.parse(readFileSync(resolve(root, `deploy/private/qa-import-${alias}.json`), 'utf8').replace(/^\uFEFF/, '')) } catch { throw new SafeFailure(`private_session_${alias}_missing_or_invalid`) }
    check(`private_session_${alias}_shape`, typeof credential.cookie === 'string' && /^MOP_SESSION=[^\s;\r\n]+$/.test(credential.cookie) && typeof credential.csrf_token === 'string' && credential.csrf_token.length > 0 && !/[\r\n]/.test(credential.csrf_token) && uuid(credential.user_id));
    sessions.set(alias, { cookie: credential.cookie, csrf: credential.csrf_token });
    const identity = await request(`real_oidc_session_${alias}`, alias, 'GET', '/session');
    check(`real_oidc_session_${alias}_trusted_identity`, identity.mode === 'remote_test' && identity.identity_source === 'test_oidc' && identity.user.id === credential.user_id && identity.user.role === (alias.startsWith('viewer') ? 'viewer' : alias) && uuid(identity.tenant.id));
    check(`real_oidc_session_${alias}_capability`, identity.capabilities.includes('assets:write') === !alias.startsWith('viewer'));
    sessions.set(alias, { cookie: credential.cookie, csrf: identity.csrf_token, identity });
  }
  const writers = aliases.filter(alias => !alias.startsWith('viewer'));
  ownerAlias = writers.find(alias => writers.some(other => other !== alias && sessions.get(other).identity.tenant.id === sessions.get(alias).identity.tenant.id && sessions.get(other).identity.user.id !== sessions.get(alias).identity.user.id));
  check('prerequisite_two_same_tenant_distinct_writers', Boolean(ownerAlias));
  foreignAlias = writers.find(alias => alias !== ownerAlias && sessions.get(alias).identity.tenant.id === sessions.get(ownerAlias).identity.tenant.id && sessions.get(alias).identity.user.id !== sessions.get(ownerAlias).identity.user.id);
  crossAlias = writers.find(alias => sessions.get(alias).identity.tenant.id !== sessions.get(ownerAlias).identity.tenant.id);
  check('prerequisite_cross_tenant_writer_already_captured', Boolean(crossAlias));
  check('prerequisite_viewer_a_and_b_actual_tenants', sessions.get('viewerA').identity.tenant.id === sessions.get(ownerAlias).identity.tenant.id && sessions.get('viewerB').identity.tenant.id !== sessions.get(ownerAlias).identity.tenant.id);
  evidence.role_context = { owner: ownerAlias, foreign_creator: foreignAlias, cross_tenant_writer: crossAlias, viewer_same_tenant: 'viewerA', viewer_other_tenant: 'viewerB', all_from_real_session: true };
  phase('validated_real_role_context');
  const ready = await request('actual_dependencies_ready', ownerAlias, 'GET', '/health/ready');
  check('actual_dependencies_ready_no_worker_guess', ready.mode === 'remote_test' && ready.durable === true && ready.production_ready === false && ready.asset_imports.enabled && ready.asset_imports.ready && ready.asset_imports.policy_ready && ready.asset_imports.schema_ready && ready.asset_imports.storage_ready && ready.asset_imports.worker_activity === 'NOT_PROBED' && ready.asset_imports.can_import === false);
  const policy = await request('actual_import_policy', ownerAlias, 'GET', '/assets/import-policy');
  check('actual_import_20mib_and_contract_columns', policy.max_source_bytes === 20 * 1024 * 1024 && policy.max_data_rows === 500 && policy.target_columns.length === 15 && codes.every((code, index) => policy.target_columns[index].code === code && policy.target_columns[index].label === labels[index]));
  const photoPolicy = await request('actual_photo_policy_independent', ownerAlias, 'GET', '/files/policy'); check('actual_photo_stays_5000000_bytes', photoPolicy.max_size_bytes === 5_000_000);

  const quote = value => /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
  const note = `MOP-FAKE-${runId}-quoted line1\nline2 "preserved"`;
  const csvRows = [
    [...labels, '设备名称', 'MOP-FAKE忽略列'],
    [`MOP-FAKE-${runId}-A`, 'MOP-FAKE-unapproved-type', '192.0.2.10', 'MOP-FAKE-brand', 'MOP-FAKE-model', 'MOP-FAKE-SN1', 'MOP-FAKE-site', 'MOP-FAKE-location', 'MOP-FAKE-owner', 'MOP-FAKE-business', 'MOP-FAKE-unapproved-date', '', '', 'MOP-FAKE-unapproved-method', note, 'MOP-FAKE-duplicate-ignore', 'MOP-FAKE-extra-ignore'],
    [`MOP-FAKE-${runId}-B`, 'MOP-FAKE-unapproved-type', '192.0.2.11', 'MOP-FAKE-brand', 'MOP-FAKE-model', 'MOP-FAKE-SN2', 'MOP-FAKE-site', 'MOP-FAKE-location', 'MOP-FAKE-owner', 'MOP-FAKE-business', 'MOP-FAKE-unapproved-date', '', '', 'MOP-FAKE-unapproved-method', 'MOP-FAKE-second note', 'MOP-FAKE-duplicate-ignore', 'MOP-FAKE-extra-ignore'],
  ];
  const csv = { name: `MOP-FAKE-${runId}.csv`, format: 'CSV', bytes: Buffer.from('\uFEFF' + csvRows.map(row => row.map(quote).join(',')).join('\r\n') + '\r\n') }; writeFileSync(resolve(directory, csv.name), csv.bytes);
  await request('upload_missing_csrf', ownerAlias, 'POST', '/assets/import-sources', { status: 403, code: 'CSRF_INVALID', noCsrf: true, multipart: multipart(csv) });
  await request('upload_unknown_part', ownerAlias, 'POST', '/assets/import-sources', { status: 400, code: 'VALIDATION_ERROR', multipart: multipart(csv, { extra: true }) });
  await request('upload_duplicate_part', ownerAlias, 'POST', '/assets/import-sources', { status: 400, code: 'VALIDATION_ERROR', multipart: multipart(csv, { duplicate: true }) });
  const stored = await upload('csv_source', csv), parseRoute = `/assets/import-sources/${stored.source.id}/parses`;
  const selection = { declared_format: 'CSV', encoding: 'UTF-8', delimiter: ',', sheet_index: null, header_row: 1 }, parseInput = { expected_version: stored.source.version, ...selection };
  await request('parse_unknown_field', ownerAlias, 'POST', parseRoute, { status: 400, code: 'VALIDATION_ERROR', body: { ...parseInput, tenant_id: 'MOP-FAKE-client' } });
  await request('parse_missing_csrf', ownerAlias, 'POST', parseRoute, { status: 403, code: 'CSRF_INVALID', noCsrf: true, body: parseInput });
  const raw = JSON.stringify(parseInput);
  for (const [name, value] of [
    ['metadata_over_16kib', raw.slice(0, -1) + ',"untrusted":"' + 'x'.repeat(17_000) + '"}'],
    ['metadata_deep_nesting', raw.slice(0, -1) + ',"untrusted":[[[[[[0]]]]]]}'],
    ['metadata_over_256_tokens', raw.slice(0, -1) + ',"untrusted":[' + Array.from({ length: 300 }, () => '0').join(',') + ']}'],
    ['metadata_duplicate_key', raw.slice(0, -1) + ',"expected_version":' + stored.source.version + '}'],
    ['metadata_trailing_json', raw + '{}'],
  ]) await request(name, ownerAlias, 'POST', parseRoute, { status: 400, code: 'VALIDATION_ERROR', raw: value });
  const parsed = await parse('csv_parse', csv, stored.source, selection), artifact = parsed.source.parsed_artifact;
  check('csv_bom_quoted_multiline_original_records', artifact.rows.length === 2 && artifact.rows[0].logical_record_number === 2 && artifact.rows[0].source_row_number === 2 && artifact.rows[0].physical_start_line === 2 && artifact.rows[0].physical_end_line === 3 && artifact.rows[1].logical_record_number === 3 && artifact.rows[1].source_row_number === 3 && artifact.rows[1].physical_start_line === 4 && artifact.rows[1].physical_end_line === 4);
  check('csv_lexical_display_and_no_date_normalization', artifact.date_epoch === 'NOT_APPLICABLE' && artifact.rows[0].cells.find(cell => cell.column_index === 14)?.lexical === note && artifact.rows[0].cells.find(cell => cell.column_index === 14)?.display === note && artifact.rows[0].cells.find(cell => cell.column_index === 10)?.lexical === 'MOP-FAKE-unapproved-date');
  check('csv_all_source_cells_preserve_fictional_input', artifact.rows.every((row, rowIndex) => row.cells.length === 17 && row.cells.every(cell => cell.kind === 'TEXT' && cell.lexical === csvRows[rowIndex + 1][cell.column_index] && cell.display === cell.lexical && cell.style_index === null && cell.format_code === null)));
  check('csv_duplicate_headers_keep_positions', artifact.columns.length === 17 && same(artifact.columns.filter(column => column.header === '设备名称').map(column => column.index), [0, 15]) && artifact.columns[15].target_suggestions.includes('NAME'));
  await request('parse_stale_cas', ownerAlias, 'POST', parseRoute, { status: 409, code: 'VERSION_CONFLICT', body: parseInput });
  const mapped = await map('csv_mapping', csv, parsed.source, parsed.accepted);
  for (const alias of ['viewerA', 'viewerB']) {
    for (const [suffix, method, route, body] of [
      ['policy', 'GET', '/assets/import-policy'], ['source', 'GET', `/assets/import-sources/${mapped.id}`], ['operation', 'GET', `/asset-import-operations/${mapped.parse_operation_id}`],
      ['parse', 'POST', `/assets/import-sources/${mapped.id}/parses`, { ...parseInput, expected_version: mapped.version }], ['mapping', 'POST', `/assets/import-sources/${mapped.id}/mappings`, mappingBody(mapped)],
    ]) await request(`${alias}_forbidden_${suffix}`, alias, method, route, { status: 403, code: 'ACTION_FORBIDDEN', body });
    await request(`${alias}_forbidden_upload`, alias, 'POST', '/assets/import-sources', { status: 403, code: 'ACTION_FORBIDDEN', multipart: multipart(csv) });
  }
  for (const [scope, alias] of [['foreign_creator', foreignAlias], ['cross_tenant', crossAlias]]) {
    for (const [suffix, method, route, body] of [
      ['source', 'GET', `/assets/import-sources/${mapped.id}`], ['operation', 'GET', `/asset-import-operations/${mapped.parse_operation_id}`],
      ['parse', 'POST', `/assets/import-sources/${mapped.id}/parses`, { ...parseInput, expected_version: mapped.version }], ['mapping', 'POST', `/assets/import-sources/${mapped.id}/mappings`, mappingBody(mapped)],
    ]) await request(`${scope}_404_${suffix}`, alias, method, route, { status: 404, code: 'OBJECT_NOT_FOUND', body });
  }
  phase('csv_real_worker_parse_mapping_and_negatives_confirmed');

  if (process.argv.includes('--xlsx')) {
    const argumentPath = argument('--xlsx-fixture'); if (!argumentPath) throw new SafeFailure('xlsx_pre_generated_fixture_required');
    const fixture = resolve(root, argumentPath), expectedName = `MOP-FAKE-${runId}-valid.xlsx`;
    if (basename(fixture) !== expectedName || dirname(fixture) !== directory || !under(realpathSync(fixture), realpathSync(directory))) throw new SafeFailure('xlsx_fixture_must_match_owned_ignored_run');
    const manifest = JSON.parse(readFileSync(resolve(directory, 'fixture-manifest.json'), 'utf8'));
    if (manifest.run_id !== runId || manifest.synthetic_marker !== 'MOP-FAKE' || manifest.generator !== 'python_stdlib_zipfile') throw new SafeFailure('xlsx_manifest_untrusted');
    const bytes = readFileSync(fixture); check('xlsx_original_fixture_digest', sha(bytes) === manifest.files.valid.sha256);
    const xlsx = { name: expectedName, format: 'XLSX', bytes }, uploaded = await upload('xlsx_source', xlsx);
    const inventory = await parse('xlsx_inventory', xlsx, uploaded.source, { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null }, 'SELECTION_REQUIRED');
    check('xlsx_inventory_no_guess', inventory.source.parsed_artifact.rows.length === 0 && inventory.source.parsed_artifact.columns.length === 0 && same(inventory.source.parsed_artifact.sheets, manifest.expected.sheets) && inventory.source.parsed_artifact.date_epoch === 'MAC_1904' && inventory.source.mapping === null);
    const chosen = await parse('xlsx_selected_hidden_sheet', xlsx, inventory.source, { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: 1, header_row: 2 });
    const serial = chosen.source.parsed_artifact.rows[0].cells.find(cell => cell.column_index === 10);
    check('xlsx_serial_date_raw_kind_style_epoch', chosen.source.parsed_artifact.selected_sheet_index === 1 && chosen.source.parsed_artifact.header_row === 2 && chosen.source.parsed_artifact.date_epoch === 'MAC_1904' && serial.kind === 'NUMERIC' && serial.lexical === '1.25' && serial.style_index === 1 && serial.format_code === 'yyyy-mm-dd hh:mm' && serial.display === '1904-01-02 06:00');
    check('xlsx_source_row_and_physical_line_metadata', chosen.source.parsed_artifact.rows.length === 1 && chosen.source.parsed_artifact.rows[0].source_row_number === 3 && chosen.source.parsed_artifact.rows[0].logical_record_number === 1 && chosen.source.parsed_artifact.rows[0].physical_start_line === null && chosen.source.parsed_artifact.rows[0].physical_end_line === null);
    await map('xlsx_mapping', xlsx, chosen.source, chosen.accepted);
    const formulaName = `MOP-FAKE-${runId}-formula.xlsx`, formulaPath = resolve(directory, formulaName);
    check('xlsx_formula_fixture_owned', manifest.files.formula.name === formulaName && under(realpathSync(formulaPath), realpathSync(directory)));
    const formula = { name: formulaName, format: 'XLSX', bytes: readFileSync(formulaPath) }; check('xlsx_formula_fixture_digest', sha(formula.bytes) === manifest.files.formula.sha256);
    const formulaUpload = await upload('xlsx_formula_source', formula);
    await parse('xlsx_formula_cached_value_rejected', formula, formulaUpload.source, { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null }, 'PARSE_INVALID');
    phase('xlsx_inventory_serial_metadata_mapping_and_formula_rejection_confirmed');
  }
  evidence.status = 'PASS';
}

try { await main() }
catch (cause) {
  evidence.status = 'FAIL'; evidence.failure = cause instanceof SafeFailure ? cause.code : 'REDACTED_VERIFICATION_FAILURE';
  evidence.unconfirmed_write_preserved_in_private_state = pendingWrite || privateState.requests.some(request => ['UNCERTAIN', 'ACCEPTED'].includes(request.outcome)); process.exitCode = 1;
} finally {
  Object.assign(evidence, { completed_at: new Date().toISOString(), actual_http_responses: requestCount, check_count: checks.length, passed: checks.filter(check => check.status === 'PASS').length, failed: checks.filter(check => check.status === 'FAIL').length, artifacts });
  if (evidenceFile) { writeFileSync(evidenceFile, JSON.stringify(evidence, null, 2) + '\n'); console.log(JSON.stringify({ status: evidence.status, run_id: runId, actual_http_responses: requestCount, checks: checks.length, passed: evidence.passed, failed: evidence.failed, ...(evidence.failure ? { failure: evidence.failure } : {}), evidence_file: `docs/evidence/local-asset-import-http-${runId}.json`, credential_output: 'none' })) }
  else console.error(JSON.stringify({ status: 'FAIL', failure: evidence.failure || 'REDACTED_SETUP_FAILURE', credential_output: 'none' }));
}
