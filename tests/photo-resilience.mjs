import assert from 'node:assert/strict';
import { existsSync, readFileSync, writeFileSync, unlinkSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import { chromium } from 'playwright';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

// All secrets are read from ignored private files, never child arguments or public output.
const root = resolve(fileURLToPath(new URL('../', import.meta.url)));
const privatePath = name => join(root, 'deploy/private', name);
const marker = join(root, '.local/minio-forward-disabled');
const run = randomUUID();
const prefix = `MOP-FAKE-RESILIENCE-${run.slice(0, 8)}`;
const exec = promisify(execFile), checks = [], processes = [], scenarios = {};
let stage = 'initialize', failedStage, lastHttpStatus, membershipAttempted = false, appsChanged = false, markerOwned = false;
let sessions = {}, maxPhoto, pendingPhoto, baseline, oldGrant;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const safe = value => console.log(JSON.stringify(value));
const evidence = { verified_at: new Date().toISOString(), mode: 'remote_test', passed: false, scenarios, checks, processes,
  environment: { postgresql: '17.9', keycloak: '26.2.4', minio: 'RELEASE.2025-09-07T16-13-09Z', path: 'designated_project_services_via_SSH' },
  real_components_only: true, remote_service_configuration_changed: false, migrations_executed: false, credential_output: 'none', full_M5_M8_acceptance: false };
const contract = JSON.parse(readFileSync(join(root, 'contracts/local-api.openapi.json'), 'utf8'));
const ajv = new Ajv({ strict: false, allErrors: true }); addFormats(ajv);
ajv.addSchema({ $id: 'urn:mop:photo-resilience', components: contract.components });
const validators = new Map();

function multipart(bytes, name, media) {
  const form = new FormData(); form.append('file', new Blob([bytes], { type: media }), name); form.append('purpose', 'customer_report_photo'); return form;
}
async function request(role, method, path, route, expected, name, body, options = {}) {
  stage = name; const headers = { 'Idempotency-Key': options.key ?? randomUUID() };
  if (role) { headers.Cookie = sessions[role].cookie; headers['X-CSRF-Token'] = sessions[role].csrf_token; }
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  const response = await fetch('http://127.0.0.1:8080/api/v1' + path, { method, headers,
    body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), redirect: 'manual', signal: AbortSignal.timeout(40000) });
  lastHttpStatus = response.status; assert.equal(response.status, expected);
  if (options.binary) {
    const bytes = Buffer.from(await response.arrayBuffer()); assert.equal(bytes.length, options.size); assert.equal(sha(bytes), options.sha256);
    assert.match(response.headers.get('cache-control'), /no-store/); assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    checks.push({ stage: name, http_status: expected, original_bytes_and_hash_verified: true }); return bytes;
  }
  const payload = await response.json();
  const schema = contract.paths[route][method.toLowerCase()].responses[String(expected)]?.content?.['application/json']?.schema;
  assert.ok(schema); const key = JSON.stringify(schema);
  if (!validators.has(key)) validators.set(key, ajv.compile(schema.$ref ? { $ref: 'urn:mop:photo-resilience' + schema.$ref } : schema));
  assert.ok(validators.get(key)(payload)); checks.push({ stage: name, http_status: expected, contract_valid: true });
  return payload.data ?? payload;
}
async function processAction(action) {
  stage = 'owned_process_' + action;
  const { stdout } = await exec('pwsh.exe', ['-NoProfile', '-File', join(root, 'tools/photo-resilience-process.ps1'), '-Action', action], { cwd: root, timeout: 180000, maxBuffer: 32768 });
  const lines = stdout.split(/\r?\n/).filter(line => line.startsWith('{')); assert.equal(lines.length, 1);
  const state = JSON.parse(lines[0]); assert.equal(state.status, 'passed');
  processes.push({ action, processes: state.processes }); safe({ stage, status: 'passed', owned_process_count: state.processes.length }); return state.processes;
}
async function membershipAction(action) {
  stage = 'scoped_membership_' + action;
  const python = join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe');
  assert.ok(existsSync(python));
  const { stdout } = await exec(python, [join(root, 'tools/photo-resilience-membership.py'), action], { cwd: root, timeout: 45000, maxBuffer: 16384, env: { ...process.env, PYTHONPATH: join(root, '.local/python-deps') } });
  const result = JSON.parse(stdout); assert.equal(result.status, 'passed'); return result;
}
async function captureSessions(roles = ['viewerA', 'tadmin', 'msp', 'viewerB']) {
  stage = 'capture_real_oidc_sessions';
  const runtime = JSON.parse(readFileSync(privatePath('runtime.json'), 'utf8').replace(/^\uFEFF/, ''));
  const executablePath = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe'].find(existsSync);
  const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  try {
    for (const role of roles) {
      const user = runtime.test_users.find(candidate => candidate.role === role); assert.ok(user);
      const expectedPlatformUser = JSON.parse(readFileSync(privatePath(`qa-photo-${role}.json`), 'utf8').replace(/^\uFEFF/, '')).user_id;
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      try {
        const page = await context.newPage(); page.setDefaultTimeout(15000);
        await page.goto('http://127.0.0.1:5173/login', { waitUntil: 'networkidle' });
        await page.getByRole('button', { name: '使用 Keycloak 登录', exact: true }).click(); await page.locator('#username').waitFor();
        const provider = new URL(page.url()); assert.equal(provider.origin, new URL(runtime.environment.MOP_OIDC_ISSUER).origin);
        assert.equal(provider.pathname, '/realms/mop-test/protocol/openid-connect/auth'); assert.equal(provider.searchParams.get('code_challenge_method'), 'S256');
        assert.ok(provider.searchParams.get('state')); assert.ok(provider.searchParams.get('nonce'));
        await page.locator('#username').fill(user.username); await page.locator('#password').fill(user.password); await page.locator('#kc-login').click();
        await page.getByRole('heading', { name: '资产管理', exact: true }).waitFor();
        const response = await context.request.get('http://127.0.0.1:5173/api/v1/session'); assert.equal(response.status(), 200);
        const data = (await response.json()).data; assert.equal(data.mode, 'remote_test'); assert.equal(data.identity_source, 'test_oidc'); assert.equal(data.user.id, expectedPlatformUser);
        assert.equal(data.user.role, role.startsWith('viewer') ? 'viewer' : role);
        assert.equal(data.tenant.id, role === 'viewerB' ? '55000000-0000-4000-8000-000000000002' : '55000000-0000-4000-8000-000000000001');
        const cookie = (await context.cookies('http://127.0.0.1:5173')).find(candidate => candidate.name === 'MOP_SESSION'); assert.ok(cookie?.httpOnly);
        sessions[role] = { cookie: `MOP_SESSION=${cookie.value}`, user_id: data.user.id, csrf_token: data.csrf_token, mode: data.mode, created_at: new Date().toISOString() };
        writeFileSync(privatePath(`qa-photo-${role}.json`), JSON.stringify(sessions[role]) + '\n');
      } finally { await context.close(); }
    }
  } finally { await browser.close(); }
  safe({ stage, status: 'passed', session_count: roles.length, credential_output: 'none' });
}
function paddedOriginal(original, size) {
  assert.equal(original.readUInt16BE(0), 0xffd8); assert.equal(original.readUInt16BE(original.length - 2), 0xffd9);
  let remaining = size - original.length; const segments = [];
  while (remaining) {
    let count = Math.min(65537, remaining); if (remaining > count && remaining - count < 4) count -= 4 - (remaining - count);
    assert.ok(count >= 4); const segment = Buffer.alloc(count); segment.writeUInt16BE(0xfffe, 0); segment.writeUInt16BE(count - 2, 2);
    segments.push(segment); remaining -= count;
  }
  const result = Buffer.concat([original.subarray(0, 2), ...segments, original.subarray(2)]); assert.equal(result.length, size); return result;
}
async function upload(bytes, suffix, media = 'image/png') {
  const key = randomUUID(), filename = `${prefix}-${suffix}.${media === 'image/jpeg' ? 'jpg' : 'png'}`;
  const accepted = await request('viewerA', 'POST', '/files', '/files', 202, 'accept_' + suffix, multipart(bytes, filename, media), { key });
  const operation = await request('viewerA', 'GET', '/operations/' + accepted.operation_id, '/operations/{id}', 200, 'operation_' + suffix);
  assert.equal(operation.kind, 'FILE_UPLOAD'); assert.match(operation.result_ref, /^\/api\/v1\/files\/[0-9a-f-]{36}$/);
  return { bytes, key, filename, media, accepted, id: operation.result_ref.split('/').at(-1) };
}
async function readyPhoto(photo, suffix) {
  let file;
  for (let attempt = 0; attempt < 80; attempt++) {
    file = await request('viewerA', 'GET', '/files/' + photo.id, '/files/{id}', 200, 'poll_' + suffix);
    if (file.upload_status !== 'PENDING') break; await sleep(400);
  }
  assert.equal(file.upload_status, 'READY'); assert.equal(file.size_bytes, photo.bytes.length); assert.equal(file.sha256, sha(photo.bytes));
  const operation = await request('viewerA', 'GET', '/operations/' + photo.accepted.operation_id, '/operations/{id}', 200, 'completed_' + suffix);
  assert.equal(operation.status, 'SUCCEEDED'); assert.equal(operation.step, 'STORED'); assert.equal(operation.error, null);
  return file;
}
async function original(photo, suffix) {
  const grant = await request('viewerA', 'POST', `/files/${photo.id}/content-grants`, '/files/{id}/content-grants', 200, 'grant_' + suffix, {});
  await request('viewerA', 'GET', grant.content_url.slice('/api/v1'.length), '/files/{id}/content', 200, 'original_' + suffix, undefined, { binary: true, size: photo.bytes.length, sha256: sha(photo.bytes) });
  return grant;
}
async function ticketDetail(name) { return request('viewerA', 'GET', '/tickets/' + baseline.id, '/tickets/{id}', 200, name); }
async function waitReady() {
  for (let attempt = 0; attempt < 20; attempt++) {
    try {
      const response = await fetch('http://127.0.0.1:8080/api/v1/health/ready', { redirect: 'manual', signal: AbortSignal.timeout(5000) });
      if (response.status === 200 && (await response.json()).data.photos.ready === true) return;
    } catch { /* Dependencies have bounded reconnect time. */ }
    await sleep(400);
  }
  throw new Error('Dependency recovery was not observed');
}
function removeOwnedMarker() {
  if (!markerOwned) { assert.ok(!existsSync(marker)); return; }
  const owned = JSON.parse(readFileSync(marker, 'utf8'));
  assert.equal(owned.run_id, run); assert.equal(owned.version, 1); assert.equal(owned.component, 'minio'); assert.equal(owned.scope, 'owned-loopback-only'); assert.equal(resolve(owned.project_root), root);
  unlinkSync(marker); markerOwned = false;
}

try {
  stage = 'jar_freshness_guard';
  const newest = Math.max(...['server/api/src/main/java/cn/mop/api/PhotoConfiguration.java', 'server/api/src/main/java/cn/mop/api/PlatformController.java', 'server/persistence/src/main/java/cn/mop/persistence/PostgresPlatformRepository.java'].map(path => statSync(join(root, path)).mtimeMs));
  assert.ok(statSync(join(root, 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar')).mtimeMs >= newest);
  assert.ok(!existsSync(marker));
  appsChanged = true; const initialProcesses = await processAction('Snapshot');
  await processAction('RestartTunnelApps'); await captureSessions();
  const health = await request(null, 'GET', '/health/ready', '/health/ready', 200, 'initial_real_dependencies'); assert.equal(health.photos.ready, true);
  const jpeg = readFileSync(join(root, '.local/qa-report-photos/fixtures/MOP-FAKE-blue.jpg'));
  const png = readFileSync(join(root, '.local/qa-report-photos/fixtures/MOP-FAKE-blue.png'));
  const exact = paddedOriginal(jpeg, 5000000), over = paddedOriginal(jpeg, 5000001);
  writeFileSync(join(root, '.local/qa-report-photos/fixtures/MOP-FAKE-exact-5000000.jpg'), exact);
  maxPhoto = await upload(exact, 'exact-five-million', 'image/jpeg'); await readyPhoto(maxPhoto, 'exact-five-million'); await original(maxPhoto, 'exact-five-million');
  await request('viewerA', 'POST', '/files', '/files', 400, 'valid_jpeg_one_byte_over_rejected', multipart(over, prefix + '-over.jpg', 'image/jpeg'));
  scenarios.jpeg_size_boundary = { status: 'PASS', accepted_original_bytes: 5000000, rejected_original_bytes: 5000001, preserved_original_codestream_with_legal_COM_segments: true, original_hash_readback_verified: true };
  safe({ stage: 'jpeg_size_boundary', status: 'passed', accepted_bytes: 5000000, rejected_bytes: 5000001 });

  const report = { source: 'customer_report', description: prefix + ' 虚构持久登记恢复验收', category: '其他', impact_scope: 'individual', contact_name: '虚构恢复联系人', contact_phone: '0000', urgency: 'normal' };
  const acceptedReport = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'registration_without_ready_photo', report);
  const reportOperation = await request('viewerA', 'GET', '/operations/' + acceptedReport.operation_id, '/operations/{id}', 200, 'registration_operation_before_restart');
  baseline = { id: reportOperation.result_ref.split('/').at(-1), operation: acceptedReport.operation_id };
  const beforeRestart = await processAction('Snapshot'); await processAction('StopWorker');
  pendingPhoto = await upload(png, 'pending-owned-worker-stopped');
  let pending = await request('viewerA', 'GET', '/files/' + pendingPhoto.id, '/files/{id}', 200, 'durable_pending_before_restart'); assert.equal(pending.upload_status, 'PENDING');
  const queued = await request('viewerA', 'GET', '/operations/' + pendingPhoto.accepted.operation_id, '/operations/{id}', 200, 'queued_before_restart'); assert.equal(queued.status, 'PENDING'); assert.equal(queued.step, 'QUEUED');
  await request('viewerA', 'POST', `/tickets/${baseline.id}/attachments`, '/tickets/{id}/attachments', 422, 'pending_photo_does_not_cancel_registration', { expected_version: 1, attachment_ids: [pendingPhoto.id] });
  let registered = await ticketDetail('registration_survives_pending_photo'); assert.equal(registered.version, 1); assert.equal(registered.attachments.length, 0);
  const afterRestart = await processAction('RestartApps'); await captureSessions();
  for (const name of ['api', 'photo-worker']) {
    const before = beforeRestart.find(item => item.name === name), after = afterRestart.find(item => item.name === name);
    assert.ok(before.owned && after.owned); assert.notEqual(before.pid, after.pid); assert.notEqual(before.start_time, after.start_time);
  }
  await readyPhoto(pendingPhoto, 'same-durable-task-after-restart'); await original(pendingPhoto, 'same-durable-task-after-restart');
  const replay = await request('viewerA', 'POST', '/files', '/files', 202, 'same_durable_request_after_restart', multipart(png, pendingPhoto.filename, 'image/png'), { key: pendingPhoto.key }); assert.deepEqual(replay, pendingPhoto.accepted);
  registered = await ticketDetail('same_registration_after_api_restart'); assert.equal(registered.operation_id, baseline.operation); assert.equal(registered.version, 1);
  await request('viewerA', 'POST', `/tickets/${baseline.id}/attachments`, '/tickets/{id}/attachments', 200, 'recovered_original_attaches_to_preserved_registration', { expected_version: 1, attachment_ids: [pendingPhoto.id] });
  scenarios.durable_restart = { status: 'PASS', owned_worker_stopped: true, file_pending_and_operation_queued_observed: true, registration_preserved: true, owned_api_and_worker_pid_and_start_time_changed: true, same_pg_operation_succeeded: true, original_hash_readback_verified: true, same_key_replay_returned_same_receipt: true };
  safe({ stage: 'durable_restart', status: 'passed', same_pg_task: true, registration_preserved: true });

  await processAction('StopWorker'); const disconnectedPending = await upload(png, 'queued-before-minio-disconnect');
  const countsBefore = await membershipAction('counts');
  writeFileSync(marker, JSON.stringify({ version: 1, run_id: run, component: 'minio', scope: 'owned-loopback-only', project_root: root }) + '\n', { flag: 'wx' }); markerOwned = true; await sleep(1800);
  const unavailable = await request(null, 'GET', '/health/ready', '/health/ready', 503, 'only_minio_forward_disconnected');
  const photos = unavailable.error.details.find(item => item.component === 'photos').photos; assert.equal(photos.schema_ready, true); assert.equal(photos.storage_ready, false); assert.ok(photos.reasons.includes('PHOTO_STORAGE_UNAVAILABLE'));
  await request('viewerA', 'GET', '/session', '/session', 200, 'postgres_identity_path_survives_minio_disconnect');
  const rejected = await request('viewerA', 'POST', '/files', '/files', 503, 'minio_disconnect_upload_has_no_receipt', multipart(png, prefix + '-unavailable.png', 'image/png')); assert.equal(rejected.error.code, 'UPSTREAM_UNAVAILABLE'); assert.ok(!rejected.data);
  const countsAfter = await membershipAction('counts'); for (const field of ['files', 'jobs', 'file_operations']) assert.equal(countsAfter[field], countsBefore[field]);
  registered = await ticketDetail('registration_preserved_during_minio_disconnect'); assert.equal(registered.version, 2); assert.equal(registered.attachments.length, 1);
  removeOwnedMarker(); await waitReady(); await request(null, 'GET', '/health/ready', '/health/ready', 200, 'minio_forward_recovered_before_worker');
  await processAction('RestartApps'); await captureSessions(); await readyPhoto(disconnectedPending, 'queued-job-after-minio-recovery'); await original(disconnectedPending, 'queued-job-after-minio-recovery');
  scenarios.minio_disconnect = { status: 'PASS', only_project_minio_forward_disabled: true, existing_minio_channels_closed: true, health_http_status: 503, schema_ready_during_disconnect: true, storage_ready_during_disconnect: false, postgres_and_oidc_routes_preserved: true, failed_upload_http_status: 503, no_file_job_or_operation_receipt_created: true, existing_registration_preserved: true, recovery_http_status: 200, accepted_durable_job_completed_after_recovery: true, original_hash_readback_verified: true, running_worker_RETRY_WAIT: 'NOT_RUN: worker was safely stopped before the disconnect; startup would reject disconnected storage' };
  safe({ stage: 'minio_disconnect', status: 'passed', no_fake_receipt: true, queued_job_recovered: true });

  oldGrant = await original(maxPhoto, 'before-membership-revocation');
  const initial = await membershipAction('initial'); membershipAttempted = true; const revoked = await membershipAction('revoke'); assert.equal(revoked.permission_version, initial.permission_version + 1);
  await request('viewerA', 'GET', '/files/' + maxPhoto.id, '/files/{id}', 401, 'revoked_old_cookie_metadata_denied');
  await request('viewerA', 'GET', oldGrant.content_url.slice('/api/v1'.length), '/files/{id}/content', 401, 'revoked_old_grant_bytes_denied');
  await request('viewerA', 'POST', '/files', '/files', 401, 'revoked_old_cookie_write_replay_denied', multipart(maxPhoto.bytes, maxPhoto.filename, maxPhoto.media), { key: maxPhoto.key });
  await request('viewerA', 'GET', '/operations/' + maxPhoto.accepted.operation_id, '/operations/{id}', 401, 'revoked_old_cookie_operation_denied');
  const restored = await membershipAction('restore'); assert.equal(restored.permission_version, initial.permission_version + 2); await captureSessions(['viewerA']);
  const restoredSession = await request('viewerA', 'GET', '/session', '/session', 200, 'restored_fresh_oidc_session'); assert.equal(restoredSession.permission_version, restored.permission_version); assert.equal(restoredSession.user.role, 'viewer');
  await request('viewerA', 'GET', oldGrant.content_url.slice('/api/v1'.length), '/files/{id}/content', 404, 'permission_version_keeps_old_grant_invalid_after_restore');
  await request('viewerA', 'GET', '/files/' + maxPhoto.id, '/files/{id}', 200, 'fresh_session_metadata_after_cas_restore');
  await original(maxPhoto, 'fresh-grant-after-cas-restore');
  scenarios.revocation = { status: 'PASS', only_project_test_membership_changed: true, role_restored: true, cas_restore_used: true, initial_permission_version: initial.permission_version, revoked_permission_version: revoked.permission_version, restored_permission_version: restored.permission_version, old_cookie_metadata_denied: true, old_grant_bytes_denied: true, old_cookie_write_replay_denied: true, old_grant_stays_invalid_after_restore: true, fresh_oidc_session_recaptured: true, fresh_authorized_readback_verified: true };
  evidence.passed = true;
} catch {
  failedStage = stage; evidence.failed_stage = failedStage; evidence.last_http_status = lastHttpStatus;
  safe({ stage: failedStage, status: 'failed', http_status: lastHttpStatus, details: 'redacted; no credentials, bodies, SQL or grant URLs printed' });
} finally {
  const restored = { minio_marker_removed: false, membership_restored: !membershipAttempted, owned_apps_ready: false, sessions_refreshed: false };
  try { removeOwnedMarker(); restored.minio_marker_removed = true; } catch { evidence.passed = false; }
  if (membershipAttempted) try { const result = await membershipAction('restore'); restored.membership_restored = result.status === 'passed'; } catch { evidence.passed = false; }
  try {
    if (appsChanged) {
      let current;
      try { current = await processAction('Snapshot'); } catch { current = await processAction('RestartApps'); }
      if (current.length !== 3 || !current.some(item => item.name === 'photo-worker')) await processAction('RestartApps');
      await waitReady(); restored.owned_apps_ready = true; await captureSessions(); restored.sessions_refreshed = true;
    }
  } catch { evidence.passed = false; }
  evidence.restoration = restored;
  if (!Object.values(restored).every(Boolean)) evidence.passed = false;
  evidence.http_exchanges = checks.length;
  for (const scenario of ['jpeg_size_boundary', 'durable_restart', 'minio_disconnect', 'revocation']) if (!scenarios[scenario]) scenarios[scenario] = { status: 'NOT_RUN', reason: 'A prior guarded stage failed; no success inferred' };
  evidence.verified_at = new Date().toISOString();
  try { writeFileSync(join(root, 'docs/evidence/local-photo-resilience.json'), JSON.stringify(evidence, null, 2) + '\n'); } catch { evidence.passed = false; }
  safe({ stage: 'complete', status: evidence.passed ? 'passed' : 'failed', http_exchanges: checks.length, restoration: restored, credential_output: 'none' });
  if (!evidence.passed) process.exitCode = 1;
}
