import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

// Real OIDC sessions and original virtual images remain in ignored local paths.
const root = new URL('../', import.meta.url);
let stage = 'initialization';
let lastHttpStatus;
try {
  const contract = JSON.parse(readFileSync(new URL('contracts/local-api.openapi.json', root), 'utf8'));
  const ajv = new Ajv({ strict: false, allErrors: true }); addFormats(ajv);
  ajv.addSchema({ $id: 'urn:mop:photo-smoke', components: contract.components });
  const validators = new Map(), checks = [], sessions = {};
  for (const role of ['viewerA', 'tadmin', 'msp', 'viewerB']) sessions[role] = JSON.parse(readFileSync(new URL(`deploy/private/qa-photo-${role}.json`, root), 'utf8').replace(/^\uFEFF/, ''));
  const png = readFileSync(new URL('.local/qa-report-photos/fixtures/MOP-FAKE-blue.png', root));
  const green = readFileSync(new URL('.local/qa-report-photos/fixtures/MOP-FAKE-green.png', root));
  const jpeg = readFileSync(new URL('.local/qa-report-photos/fixtures/MOP-FAKE-blue.jpg', root));
  const hash = value => createHash('sha256').update(value).digest('hex');
  const run = randomUUID(), prefix = `MOP-FAKE-PHOTOS-${run.slice(0, 8)}`;
  function multipart(bytes = png, name = prefix + '.png', media = 'image/png') {
    const form = new FormData(); form.append('file', new Blob([bytes], { type: media }), name); form.append('purpose', 'customer_report_photo'); return form;
  }
  async function request(role, method, path, route, expected, name, body, options = {}) {
    stage = name; const headers = {};
    if (role) headers.Cookie = sessions[role].cookie;
    if (role && !options.noCsrf) headers['X-CSRF-Token'] = sessions[role].csrf_token;
    if (!options.noKey) headers['Idempotency-Key'] = options.key ?? randomUUID();
    if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
    const response = await fetch('http://127.0.0.1:8080/api/v1' + path, { method, headers,
      body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), redirect: 'manual', signal: AbortSignal.timeout(25000) });
    lastHttpStatus=response.status;assert.equal(response.status, expected);
    if (options.binary) {
      const original = Buffer.from(await response.arrayBuffer()); assert.equal(hash(original), options.sha256);
      assert.equal(response.headers.get('x-content-type-options'), 'nosniff'); assert.match(response.headers.get('cache-control'), /no-store/);
      assert.equal(response.headers.get('content-type'), options.media);
      checks.push({ name, status: expected, original_hash_verified: true }); return original;
    }
    const payload = await response.json();
    const schema = contract.paths[route][method.toLowerCase()].responses[String(expected)]?.content?.['application/json']?.schema; assert.ok(schema);
    const key = JSON.stringify(schema);
    if (!validators.has(key)) validators.set(key, ajv.compile(schema.$ref ? { $ref: 'urn:mop:photo-smoke' + schema.$ref } : schema));
    assert.ok(validators.get(key)(payload)); checks.push({ name, status: expected, contract_valid: true }); return payload.data ?? payload;
  }
  const current = {};
  for (const role of Object.keys(sessions)) current[role] = await request(role, 'GET', '/session', '/session', 200, 'real_session_' + role);
  const readiness = await request('viewerA', 'GET', '/health/ready', '/health/ready', 200, 'real_photo_dependencies_ready');
  assert.equal(readiness.photos.ready, true); assert.equal(readiness.photos.schema_ready, true); assert.equal(readiness.photos.storage_ready, true);
  const policy = await request('viewerA', 'GET', '/files/policy', '/files/policy', 200, 'real_policy_with_pending_production_and_scan');
  assert.deepEqual(policy.allowed_media_types, ['image/jpeg', 'image/png']); assert.equal(policy.max_size_bytes, 5000000); assert.equal(policy.scan_status, 'UNSCANNED');
  await request(null, 'GET', '/files/policy', '/files/policy', 401, 'anonymous_read_denied');
  await request(null, 'POST', '/files', '/files', 403, 'anonymous_upload_without_csrf_denied', multipart());
  await request('msp', 'POST', '/files', '/files', 403, 'operator_cannot_customer_upload', multipart());
  await request('viewerA', 'POST', '/files', '/files', 403, 'csrf_required', multipart(), { noCsrf: true });
  await request('viewerA', 'POST', '/files', '/files', 400, 'idempotency_required', multipart(), { noKey: true });
  await request('viewerA', 'POST', '/files', '/files', 400, 'non_image_content_denied', multipart(Buffer.from('MOP-FAKE-NON-IMAGE')));
  await request('viewerA', 'POST', '/files', '/files', 400, 'declared_mime_mismatch_denied', multipart(png, prefix + '.jpg', 'image/jpeg'));
  await request('viewerA', 'POST', '/files', '/files', 400, 'truncated_image_denied', multipart(png.subarray(0, png.length - 8)));
  await request('viewerA', 'POST', '/files', '/files', 400, 'over_five_mb_denied', multipart(Buffer.alloc(5000001)));
  const forged = multipart(); forged.append('tenant_id', current.viewerB.tenant.id);
  await request('viewerA', 'POST', '/files', '/files', 400, 'multipart_actor_scope_spoof_denied', forged);
  async function upload(bytes, suffix, media = 'image/png', role = 'viewerA') {
    const key = randomUUID(), filename = `${prefix}-${suffix}.${media === 'image/jpeg' ? 'jpg' : 'png'}`;
    const accepted = await request(role, 'POST', '/files', '/files', 202, 'durable_upload_' + suffix, multipart(bytes, filename, media), { key });
    const fileOperation = await request(role, 'GET', '/operations/' + accepted.operation_id, '/operations/{id}', 200, 'read_upload_operation_' + suffix);
    assert.equal(fileOperation.kind, 'FILE_UPLOAD'); assert.match(fileOperation.result_ref, /^\/api\/v1\/files\/[0-9a-f-]{36}$/);
    const id = fileOperation.result_ref.split('/').at(-1);
    let file;
    for (let attempt = 0; attempt < 50; attempt++) {
      file = await request(role, 'GET', '/files/' + id, '/files/{id}', 200, 'worker_file_readback_' + suffix);
      if (file.upload_status !== 'PENDING') break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    assert.equal(file.upload_status, 'READY'); assert.equal(file.sha256, hash(bytes)); assert.equal(file.size_bytes, bytes.length);
    assert.equal(file.scan_status, 'UNSCANNED'); assert.equal(file.retention_until, null); assert.equal(file.production_approval, 'UNVERIFIED');
    const completed = await request(role, 'GET', '/operations/' + accepted.operation_id, '/operations/{id}', 200, 'worker_terminal_operation_' + suffix);
    assert.equal(completed.status, 'SUCCEEDED'); assert.equal(completed.step, 'STORED'); assert.equal(completed.error, null);
    return { id, key, filename, accepted, file, bytes, role, media };
  }
  const first = await upload(png, 'first');
  const replay = await request('viewerA', 'POST', '/files', '/files', 202, 'same_original_request_replay', multipart(png, first.filename), { key: first.key });
  assert.deepEqual(replay, first.accepted);
  await request('viewerA', 'POST', '/files', '/files', 409, 'same_upload_key_changed_original_rejected', multipart(green, first.filename), { key: first.key });
  for (const role of ['tadmin', 'viewerB', 'msp']) {
    await request(role, 'GET', '/files/' + first.id, '/files/{id}', 404, role + '_unbound_metadata_hidden');
    await request(role, 'GET', '/operations/' + first.accepted.operation_id, '/operations/{id}', 404, role + '_unbound_operation_hidden');
    await request(role, 'POST', `/files/${first.id}/content-grants`, '/files/{id}/content-grants', 404, role + '_unbound_grant_hidden', {});
  }
  async function readOriginal(role, photo, name) {
    const grant = await request(role, 'POST', `/files/${photo.id}/content-grants`, '/files/{id}/content-grants', 200, 'grant_' + name, {});
    assert.match(grant.content_url, /^\/api\/v1\/files\/[0-9a-f-]{36}\/content\?grant=[0-9a-f-]{36}$/);
    assert.ok(Date.parse(grant.expires_at) > Date.now() && Date.parse(grant.expires_at) <= Date.now() + 901000);
    await request(role, 'GET', grant.content_url.slice('/api/v1'.length), '/files/{id}/content', 200, 'original_' + name, undefined, { binary: true, sha256: hash(photo.bytes), media: photo.media });
    return grant;
  }
  const ownerGrant = await readOriginal('viewerA', first, 'owner_before_binding');
  await request('tadmin', 'GET', ownerGrant.content_url.slice('/api/v1'.length), '/files/{id}/content', 404, 'copied_grant_cannot_expand_identity');
  const reportBody = { title: prefix + '-REPORT', source: 'customer_report', description: '虚构真实照片链路验收\n保留原图和待确认正式规则。', category: 'WiFi问题', impact_scope: 'individual', contact_name: '虚构照片联系人', contact_phone: '+00 000 0000', urgency: 'normal', attachment_ids: [first.id] };
  const reportAccepted = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'register_with_ready_original', reportBody);
  const reportOperation = await request('viewerA', 'GET', '/operations/' + reportAccepted.operation_id, '/operations/{id}', 200, 'registration_remains_rules_pending');
  const ticketId = reportOperation.result_ref.split('/').at(-1);
  let ticket = await request('viewerA', 'GET', '/tickets/' + ticketId, '/tickets/{id}', 200, 'registration_photo_snapshot');
  assert.equal(ticket.attachments.length, 1); assert.equal(ticket.version, 1); assert.equal(ticket.state, null); assert.equal(ticket.priority, null); assert.equal(ticket.response_due_at, null);
  await request('msp', 'GET', '/files/' + first.id, '/files/{id}', 200, 'operator_bound_metadata_allowed');
  await request('msp', 'GET', '/operations/' + first.accepted.operation_id, '/operations/{id}', 200, 'operator_bound_operation_allowed');
  await readOriginal('msp', first, 'operator_after_binding');
  for (const role of ['tadmin', 'viewerB']) await request(role, 'GET', '/files/' + first.id, '/files/{id}', 404, role + '_bound_metadata_hidden');
  const second = await upload(green, 'second'), third = await upload(jpeg, 'third', 'image/jpeg');
  const appendBody = { expected_version: ticket.version, attachment_ids: [second.id, third.id] }, appendKey = randomUUID();
  ticket = await request('viewerA', 'POST', `/tickets/${ticketId}/attachments`, '/tickets/{id}/attachments', 200, 'append_two_ready_originals_cas', appendBody, { key: appendKey });
  assert.equal(ticket.attachments.length, 3); assert.equal(ticket.version, 2); assert.equal(ticket.history.at(-1).action, 'ADD_ATTACHMENTS');
  const appendedReplay = await request('viewerA', 'POST', `/tickets/${ticketId}/attachments`, '/tickets/{id}/attachments', 200, 'append_same_key_does_not_repeat_history', appendBody, { key: appendKey });
  assert.deepEqual(appendedReplay, ticket);
  await request('viewerA', 'POST', `/tickets/${ticketId}/attachments`, '/tickets/{id}/attachments', 409, 'stale_append_version_rejected', appendBody);
  const fourth = await upload(png, 'fourth');
  await request('viewerA', 'POST', `/tickets/${ticketId}/attachments`, '/tickets/{id}/attachments', 422, 'fourth_photo_capacity_rejected', { expected_version: 2, attachment_ids: [fourth.id] });
  await request('tadmin', 'POST', `/tickets/${ticketId}/attachments`, '/tickets/{id}/attachments', 404, 'same_tenant_other_reporter_append_rejected', { expected_version: 2, attachment_ids: [fourth.id] });
  await request('msp', 'POST', `/tickets/${ticketId}/attachments`, '/tickets/{id}/attachments', 403, 'internal_append_denied', { expected_version: 2, attachment_ids: [fourth.id] });
  const ownSecondReport = await request('viewerA', 'POST', '/tickets', '/tickets', 202, 'empty_registration_for_binding_conflict', { ...reportBody, title: prefix + '-SECOND', attachment_ids: [] });
  const secondOp = await request('viewerA', 'GET', '/operations/' + ownSecondReport.operation_id, '/operations/{id}', 200, 'second_registration_readback');
  await request('viewerA', 'POST', secondOp.result_ref.slice('/api/v1'.length) + '/attachments', '/tickets/{id}/attachments', 409, 'already_bound_photo_cannot_move', { expected_version: 1, attachment_ids: [first.id] });
  await readOriginal('viewerA', third, 'jpeg_after_append');
  const reread = await request('viewerA', 'GET', '/tickets/' + ticketId, '/tickets/{id}', 200, 'rejected_append_preserves_registration_history');
  assert.equal(reread.version, 2); assert.equal(reread.history.length, 2); assert.equal(reread.attachments.length, 3);
  const evidence = { verified_at: new Date().toISOString(), mode: 'remote_test', http_exchanges: checks.length, all_passed: true,
    environment: { postgresql: '17.9', keycloak: '26.2.4', minio: 'RELEASE.2025-09-07T16-13-09Z', path: 'designated_project_services_via_SSH' },
    original_worker_storage_and_authorized_readback: true, checks, full_M5_M8_acceptance: false, remaining: ['formal_ticket_rules_SLA_notification', 'malware_scanning', 'retention_policy', 'physical_mobile_and_WeCom_acceptance'], credential_output: 'none' };
  writeFileSync(new URL('docs/evidence/local-report-photo-http.json', root), JSON.stringify(evidence, null, 2) + '\n');
  writeFileSync(new URL('.local/report-photo-http-state.json', root), JSON.stringify({ ticket_id: ticketId, second_ticket_id: secondOp.result_ref.split('/').at(-1), first_file_id: first.id, operation_id: first.accepted.operation_id, original_sha256: hash(png), fake_prefix: prefix }) + '\n');
  console.log(JSON.stringify({ status: 'passed', http_exchanges: checks.length, schema_validated: true, actual_worker_storage: true, credential_output: 'none' }));
} catch {
  console.error(JSON.stringify({ status: 'failed', stage, http_status:lastHttpStatus, details: 'redacted; no credentials, bodies or grant URLs printed' })); process.exitCode = 1;
}
