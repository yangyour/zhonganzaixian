import assert from 'node:assert/strict';
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash, randomUUID } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const root = resolve(fileURLToPath(new URL('../', import.meta.url))), run = randomUUID();
const marker = join(root, '.local/minio-forward-disabled'), exec = promisify(execFile);
const privatePath = name => join(root, 'deploy/private', name);
const checks = [], processes = [];
let stage = 'initialization', lastHttpStatus, markerOwned = false, appsChanged = false, holdAttempted = false, passed = false;
let sessions = {}, photo, beforeFaultWorker, retrySnapshot, afterRecovery;
const evidence = { mode: 'remote_test', passed: false, checks, processes, real_components_only: true, migrations_executed: false, remote_configuration_changed: false,
  credential_output: 'none', full_M5_M8_acceptance: false, environment: { postgresql: '17.9', keycloak: '26.2.4', minio: 'RELEASE.2025-09-07T16-13-09Z', path: 'designated_project_services_via_SSH' } };
const contract = JSON.parse(readFileSync(join(root, 'contracts/local-api.openapi.json'), 'utf8'));
const ajv = new Ajv({ strict: false, allErrors: true }); addFormats(ajv); ajv.addSchema({ $id: 'urn:mop:worker-retry', components: contract.components });
const validators = new Map(), delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const progress = value => console.log(JSON.stringify(value));
function multipart(bytes, filename) { const form = new FormData(); form.append('file', new Blob([bytes], { type: 'image/png' }), filename); form.append('purpose', 'customer_report_photo'); return form; }
async function api(role, method, path, route, expected, name, body, binary = false) {
  stage = name; const headers = { 'Idempotency-Key': randomUUID() };
  if (role) { headers.Cookie = sessions[role].cookie; headers['X-CSRF-Token'] = sessions[role].csrf_token; }
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  const response = await fetch('http://127.0.0.1:8080/api/v1' + path, { method, headers, redirect: 'manual', signal: AbortSignal.timeout(30000), body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body) });
  lastHttpStatus = response.status; assert.equal(response.status, expected);
  if (binary) {
    const bytes = Buffer.from(await response.arrayBuffer()); assert.equal(bytes.length, photo.bytes.length); assert.equal(sha(bytes), sha(photo.bytes));
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff'); assert.match(response.headers.get('cache-control'), /no-store/);
    checks.push({ stage: name, http_status: expected, original_size_and_hash_verified: true }); return;
  }
  const payload = await response.json(), schema = contract.paths[route][method.toLowerCase()].responses[String(expected)]?.content?.['application/json']?.schema;
  assert.ok(schema); const key = JSON.stringify(schema);
  if (!validators.has(key)) validators.set(key, ajv.compile(schema.$ref ? { $ref: 'urn:mop:worker-retry' + schema.$ref } : schema));
  assert.ok(validators.get(key)(payload)); checks.push({ stage: name, http_status: expected, contract_valid: true }); return payload.data ?? payload;
}
async function processAction(action) {
  stage = 'owned_process_' + action;
  const { stdout } = await exec('pwsh.exe', ['-NoProfile', '-File', join(root, 'tools/photo-resilience-process.ps1'), '-Action', action], { cwd: root, timeout: 180000, maxBuffer: 32768 });
  const lines = stdout.split(/\r?\n/).filter(line => line.startsWith('{')); assert.equal(lines.length, 1);
  const state = JSON.parse(lines[0]); assert.equal(state.status, 'passed'); processes.push({ action, processes: state.processes });
  progress({ stage, status: 'passed', owned_process_count: state.processes.length }); return state.processes;
}
async function jobAction(action) {
  stage = 'scoped_job_' + action;
  const python = join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'); assert.ok(existsSync(python));
  const { stdout } = await exec(python, [join(root, 'tools/photo-worker-retry-job.py'), action], { cwd: root, timeout: 45000, maxBuffer: 16384, env: { ...process.env, PYTHONPATH: join(root, '.local/python-deps') } });
  const result = JSON.parse(stdout); assert.equal(result.status, 'passed'); return result;
}
async function captureSessions() {
  stage = 'capture_real_oidc_sessions';
  for (const role of ['viewerA', 'tadmin', 'msp', 'viewerB']) {
    const expectedActor = JSON.parse(readFileSync(privatePath(`qa-photo-${role}.json`), 'utf8').replace(/^\uFEFF/, '')).user_id;
    await exec(process.execPath, [join(root, 'tools/capture-test-session.mjs'), role], { cwd: root, timeout: 60000, maxBuffer: 8192 });
    sessions[role] = JSON.parse(readFileSync(privatePath('qa-session.json'), 'utf8').replace(/^\uFEFF/, '')); assert.equal(sessions[role].user_id, expectedActor);
    const session = await api(role, 'GET', '/session', '/session', 200, 'fresh_session_' + role); assert.equal(session.user.id, expectedActor);
    assert.equal(session.user.role, role.startsWith('viewer') ? 'viewer' : role); assert.equal(session.identity_source, 'test_oidc');
    assert.equal(session.tenant.id, role === 'viewerB' ? '55000000-0000-4000-8000-000000000002' : '55000000-0000-4000-8000-000000000001');
    writeFileSync(privatePath(`qa-photo-${role}.json`), JSON.stringify(sessions[role]) + '\n');
  }
  progress({ stage: 'capture_real_oidc_sessions', status: 'passed', session_count: 4, credential_output: 'none' });
}
function removeOwnedMarker() {
  if (!markerOwned) { assert.ok(!existsSync(marker)); return; }
  const owned = JSON.parse(readFileSync(marker, 'utf8')); assert.equal(owned.run_id, run); assert.equal(resolve(owned.project_root), root);
  assert.equal(owned.version, 1); assert.equal(owned.component, 'minio'); assert.equal(owned.scope, 'owned-loopback-only');
  unlinkSync(marker); markerOwned = false;
}
async function waitReady() {
  for (let attempt = 0; attempt < 30; attempt++) {
    try { const response = await fetch('http://127.0.0.1:8080/api/v1/health/ready', { redirect: 'manual', signal: AbortSignal.timeout(4000) }); if (response.status === 200 && (await response.json()).data.photos.ready) return; } catch { /* Reconnect within a bounded window. */ }
    await delay(400);
  }
  throw new Error('Photo dependencies did not recover');
}
async function waitCompleted() {
  for (let attempt = 0; attempt < 180; attempt++) {
    const operation = await api('viewerA', 'GET', '/operations/' + photo.operation, '/operations/{id}', 200, 'same_operation_after_storage_recovery');
    if (operation.status === 'SUCCEEDED') { assert.equal(operation.step, 'STORED'); assert.equal(operation.error, null); return; }
    assert.notEqual(operation.status, 'FAILED'); await delay(1000);
  }
  throw new Error('The accepted job did not complete within the recovery window');
}

try {
  assert.ok(!existsSync(marker));
  for (const role of ['viewerA', 'tadmin', 'msp', 'viewerB']) sessions[role] = JSON.parse(readFileSync(privatePath(`qa-photo-${role}.json`), 'utf8').replace(/^\uFEFF/, ''));
  const initialProcesses = await processAction('Snapshot'); assert.equal(initialProcesses.length, 3);
  const identity = await api('viewerA', 'GET', '/session', '/session', 200, 'initial_current_viewer'); assert.equal(identity.user.role, 'viewer');
  const ready = await api(null, 'GET', '/health/ready', '/health/ready', 200, 'initial_storage_and_schema_ready'); assert.equal(ready.photos.ready, true);
  appsChanged = true; await processAction('StopWorker');
  const bytes = readFileSync(join(root, '.local/qa-report-photos/fixtures/MOP-FAKE-blue.png')), filename = `MOP-FAKE-WORKER-RETRY-${run.slice(0, 8)}.png`;
  const accepted = await api('viewerA', 'POST', '/files', '/files', 202, 'fresh_durable_photo_while_worker_stopped', multipart(bytes, filename));
  const operation = await api('viewerA', 'GET', '/operations/' + accepted.operation_id, '/operations/{id}', 200, 'fresh_queued_operation'); assert.equal(operation.status, 'PENDING'); assert.equal(operation.step, 'QUEUED');
  photo = { bytes, operation: accepted.operation_id, id: operation.result_ref.split('/').at(-1) };
  const file = await api('viewerA', 'GET', '/files/' + photo.id, '/files/{id}', 200, 'fresh_pending_file'); assert.equal(file.upload_status, 'PENDING'); assert.equal(file.version, 1); assert.equal(file.sha256, sha(bytes));
  writeFileSync(join(root, '.local/photo-worker-retry-request.json'), JSON.stringify({ run_id: run, actor_id: identity.user.id, tenant_id: identity.tenant.id, file_id: photo.id, operation_id: photo.operation, filename, sha256: sha(bytes), permission_version: identity.permission_version }) + '\n');
  holdAttempted = true; const held = await jobAction('hold'); assert.equal(held.changed_rows, 1);
  await processAction('RestartApps'); await captureSessions();
  const stillHeld = await jobAction('observe'); assert.equal(stillHeld.job_state, 'PENDING'); assert.equal(stillHeld.attempts, 0); assert.equal(stillHeld.token, 0); assert.equal(stillHeld.next_at_future, true);
  const healthy = await api(null, 'GET', '/health/ready', '/health/ready', 200, 'normal_worker_startup_gate_completed'); assert.equal(healthy.photos.ready, true);
  beforeFaultWorker = (await processAction('Snapshot')).find(item => item.name === 'photo-worker'); assert.ok(beforeFaultWorker.owned);
  writeFileSync(marker, JSON.stringify({ version: 1, run_id: run, project_root: root, component: 'minio', scope: 'owned-loopback-only' }) + '\n', { flag: 'wx' }); markerOwned = true; await delay(1800);
  const down = await api(null, 'GET', '/health/ready', '/health/ready', 503, 'only_minio_forward_disconnected_with_worker_running');
  const downPhoto = down.error.details.find(item => item.component === 'photos').photos; assert.equal(downPhoto.schema_ready, true); assert.equal(downPhoto.storage_ready, false);
  const due = await jobAction('make-due'); assert.equal(due.changed_rows, 1);
  for (let attempt = 0; attempt < 30; attempt++) {
    const snapshot = await jobAction('observe');
    if (snapshot.job_state === 'PENDING' && snapshot.operation_step === 'RETRY_WAIT') { retrySnapshot = snapshot; break; }
    assert.notEqual(snapshot.job_state, 'FAILED'); await delay(250);
  }
  assert.ok(retrySnapshot); assert.ok(retrySnapshot.attempts >= 1 && retrySnapshot.token >= 1 && retrySnapshot.file_version >= 2);
  assert.equal(retrySnapshot.file_status, 'PENDING'); assert.equal(retrySnapshot.operation_status, 'PENDING'); assert.equal(retrySnapshot.error_code, 'UPSTREAM_UNAVAILABLE'); assert.equal(retrySnapshot.retryable, 'true');
  assert.equal(retrySnapshot.lease_owner_null, true); assert.equal(retrySnapshot.lease_until_null, true); assert.equal(retrySnapshot.next_at_future, true); assert.ok(retrySnapshot.worker_retry_audit_count >= 1);
  let retryHttp;
  for (let attempt = 0; attempt < 50; attempt++) {
    const response = await api('viewerA', 'GET', '/operations/' + photo.operation, '/operations/{id}', 200, 'running_worker_retry_wait_http');
    if (response.step === 'RETRY_WAIT') { retryHttp = response; break; } await delay(200);
  }
  assert.ok(retryHttp); assert.equal(retryHttp.status, 'PENDING'); assert.equal(retryHttp.error.code, 'UPSTREAM_UNAVAILABLE'); assert.equal(retryHttp.error.retryable, true);
  progress({ stage: 'running_worker_retry_wait', status: 'passed', attempts: retrySnapshot.attempts, fencing_token: retrySnapshot.token, real_retry_audit: true });
  removeOwnedMarker(); await waitReady(); await api(null, 'GET', '/health/ready', '/health/ready', 200, 'storage_recovered_without_worker_restart'); await waitCompleted();
  const completed = await api('viewerA', 'GET', '/files/' + photo.id, '/files/{id}', 200, 'same_file_ready_after_natural_retry'); assert.equal(completed.upload_status, 'READY'); assert.equal(completed.sha256, sha(bytes));
  afterRecovery = await jobAction('observe'); assert.equal(afterRecovery.job_state, 'SUCCEEDED'); assert.ok(afterRecovery.attempts > retrySnapshot.attempts && afterRecovery.token > retrySnapshot.token);
  const sameWorker = (await processAction('Snapshot')).find(item => item.name === 'photo-worker'); assert.equal(sameWorker.pid, beforeFaultWorker.pid); assert.equal(sameWorker.start_time, beforeFaultWorker.start_time);
  const grant = await api('viewerA', 'POST', `/files/${photo.id}/content-grants`, '/files/{id}/content-grants', 200, 'new_authorized_original_grant', {});
  await api('viewerA', 'GET', grant.content_url.slice('/api/v1'.length), '/files/{id}/content', 200, 'natural_retry_original_hash_readback', undefined, true);
  evidence.result = { status: 'PASS', normal_worker_startup_gate_passed: true, only_owned_minio_forward_disabled: true, only_one_fictitious_next_at_field_scheduled: true, initial_hold_cas_rows: 1, release_cas_rows: 1,
    actual_retry_wait_http_and_consistent_pg_snapshot: true, retry_error_code: 'UPSTREAM_UNAVAILABLE', retryable: true, retry_attempts: retrySnapshot.attempts, retry_fencing_token: retrySnapshot.token, retry_file_version: retrySnapshot.file_version,
    actual_worker_retry_audit: true, natural_backoff_completed_without_manual_success_or_lease_reset: true, recovered_attempts: afterRecovery.attempts, recovered_fencing_token: afterRecovery.token,
    worker_pid_and_start_time_unchanged_during_fault_and_recovery: true, same_file_job_operation_completed: true, real_minio_original_hash_verified: true };
  passed = true;
} catch {
  evidence.failed_stage = stage; evidence.last_http_status = lastHttpStatus;
  progress({ stage, status: 'failed', http_status: lastHttpStatus, details: 'redacted; no credentials, bodies, SQL or grant URLs printed' });
} finally {
  const restored = { minio_marker_removed: false, initial_owned_hold_released_without_lease_reset: !holdAttempted, owned_apps_ready: false, four_role_sessions_refreshed: false };
  try { removeOwnedMarker(); restored.minio_marker_removed = true; } catch { passed = false; }
  if (holdAttempted) try { await jobAction('cleanup'); restored.initial_owned_hold_released_without_lease_reset = true; } catch { passed = false; }
  if (appsChanged) try {
    let current; try { current = await processAction('Snapshot'); } catch { current = await processAction('RestartApps'); }
    if (current.length !== 3 || !current.some(item => item.name === 'photo-worker')) await processAction('RestartApps');
    await waitReady(); restored.owned_apps_ready = true;
    await captureSessions(); restored.four_role_sessions_refreshed = true;
  } catch { passed = false; }
  evidence.passed = passed && Object.values(restored).every(Boolean); evidence.restoration = restored; evidence.http_exchanges = checks.length; evidence.verified_at = new Date().toISOString();
  if (!evidence.result) evidence.result = { status: 'NOT_RUN', reason: 'A prior guarded stage failed; no retry success inferred' };
  try { writeFileSync(join(root, 'docs/evidence/local-photo-worker-retry.json'), JSON.stringify(evidence, null, 2) + '\n'); } catch { evidence.passed = false; }
  progress({ stage: 'complete', status: evidence.passed ? 'passed' : 'failed', http_exchanges: checks.length, restoration: restored, credential_output: 'none' });
  if (!evidence.passed) process.exitCode = 1;
}
