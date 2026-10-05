// Exercise the deployed Worker processes against real PG and MinIO. No fixture services.
import assert from 'node:assert/strict'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
const root = new URL('../', import.meta.url)
const cfg = JSON.parse(readFileSync(new URL('deploy/private/server-test-runtime.json', root), 'utf8'))
const base = cfg.public_origin + '/api/v1'
const file = new URL('.local/server-acceptance/private/deployed-worker-control.json', root)
const output = new URL('docs/evidence/server-deployed-workers-20261005.json', root)
assert.equal(existsSync(file), false, 'Keep the prior run and original commands')
const state = { run: randomUUID(), requests: [], pending: [], receipts: [], objects: {}, complete: false }
const evidence = { environment: 'designated-server-acceptance', api_version: '0.44.0', http_requests: 0, checks: [], completed: false, formal_acceptance: 'NOT_RUN' }
let stage = 'initialization'
const save = () => writeFileSync(file, JSON.stringify(state, null, 2) + '\n')
save()
const auth = Object.fromEntries(['padmin', 'viewerA', 'viewerB'].map(role => [role, JSON.parse(readFileSync(new URL('.local/server-acceptance/private/sessions/qa-' + role + '.json', root), 'utf8'))]))
const sha = bytes => createHash('sha256').update(bytes).digest('hex')
async function request(role, method, path, body, expected = 200, key = randomUUID()) {
  const headers = { Cookie: auth[role].cookie, 'X-CSRF-Token': auth[role].csrf_token, 'Idempotency-Key': key }
  if (body !== undefined && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const command = method === 'GET' ? null : { id: randomUUID(), role, method, path, key, body: body instanceof FormData ? 'known synthetic multipart; original source recorded in objects' : body }
  if (command) { state.requests.push(command); state.pending.push(command.id); save() }
  const response = await fetch(base + path, { method, headers, body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), redirect: 'error', signal: AbortSignal.timeout(30000) })
  evidence.http_requests++
  const value = await response.json()
  if (command) { state.receipts.push({ command_id: command.id, status: response.status, data: value.data ?? null, error: value.error?.code ?? null }); state.pending = state.pending.filter(id => id !== command.id); save() }
  assert.equal(response.status, expected, stage + ' HTTP status')
  return value.data ?? value
}
async function poll(role, path, finished) {
  const deadline = Date.now() + 90000
  while (true) {
    const value = await request(role, 'GET', path)
    if (finished(value)) return value
    assert.notEqual(value.status, 'FAILED', stage + ' terminal failure')
    assert.notEqual(value.storage_status, 'FAILED', stage + ' storage failure')
    assert.ok(Date.now() < deadline, stage + ' deadline')
    await new Promise(resolve => setTimeout(resolve, 250))
  }
}
try {
  for (const role of Object.keys(auth)) {
    const session = await request(role, 'GET', '/session'); auth[role].csrf_token = session.csrf_token
    assert.equal(session.user.id, auth[role].user_id)
  }
  stage = 'remote_photo_worker_store_original'
  const bytes = readFileSync(new URL('.local/qa-report-photos/fixtures/MOP-FAKE-blue.png', root))
  const photoName = 'MOP-SYNTHETIC-SERVER-' + state.run + '.png'
  state.objects.photo_input = { fixture: '.local/qa-report-photos/fixtures/MOP-FAKE-blue.png', name: photoName, sha256: sha(bytes) }; save()
  const photoForm = () => { const form = new FormData(); form.append('file', new Blob([bytes], { type: 'image/png' }), photoName); form.append('purpose', 'customer_report_photo'); return form }
  const photoKey = randomUUID(), acceptedPhoto = await request('viewerA', 'POST', '/files', photoForm(), 202, photoKey)
  state.objects.photo_receipt = acceptedPhoto; save()
  const photoOperation = await poll('viewerA', '/operations/' + acceptedPhoto.operation_id, value => value.status === 'SUCCEEDED')
  const photo = await request('viewerA', 'GET', photoOperation.result_ref.replace('/api/v1', ''))
  assert.equal(photo.upload_status, 'READY'); assert.equal(photo.sha256, sha(bytes)); assert.equal(photo.scan_status, 'UNSCANNED')
  assert.deepEqual(await request('viewerA', 'POST', '/files', photoForm(), 202, photoKey), acceptedPhoto)
  const grant = await request('viewerA', 'POST', '/files/' + photo.id + '/content-grants', {})
  const original = await fetch(cfg.public_origin + grant.content_url, { headers: { Cookie: auth.viewerA.cookie }, redirect: 'error' }); evidence.http_requests++
  assert.equal(original.status, 200); assert.equal(sha(Buffer.from(await original.arrayBuffer())), sha(bytes))
  await request('viewerB', 'GET', '/files/' + photo.id, undefined, 404)
  state.objects.photo_id = photo.id; save(); evidence.checks.push('remote photo Worker -> MinIO -> authorized original bytes/hash; original 202 replay')

  stage = 'remote_import_worker_raw_and_parser'
  const csv = Buffer.from('name,management_ip\r\n验收虚构来源,192.0.2.211\r\n')
  const csvName = 'MOP-SYNTHETIC-SERVER-' + state.run + '.csv'
  state.objects.csv_input = { content: csv.toString('utf8'), name: csvName, sha256: sha(csv) }; save()
  const sourceForm = new FormData(); sourceForm.append('file', new Blob([csv], { type: 'text/csv' }), csvName); sourceForm.append('purpose', 'asset_import_source'); sourceForm.append('format', 'CSV')
  const sourceReceipt = await request('padmin', 'POST', '/assets/import-sources', sourceForm, 202)
  state.objects.source_receipt = sourceReceipt; save()
  const stored = await poll('padmin', '/asset-import-operations/' + sourceReceipt.operation_id, value => value.status === 'SUCCEEDED')
  const sourcePath = stored.result_ref.replace('/api/v1', ''), source = await request('padmin', 'GET', sourcePath)
  assert.equal(source.storage_status, 'READY'); assert.equal(source.sha256, sha(csv))
  const parseReceipt = await request('padmin', 'POST', sourcePath + '/parses', { expected_version: source.version, declared_format: 'CSV', encoding: 'UTF-8', delimiter: ',', sheet_index: null, header_row: 1 }, 202)
  state.objects.parse_receipt = parseReceipt; state.objects.source_id = source.id; save()
  const parsed = await poll('padmin', '/asset-import-operations/' + parseReceipt.operation_id, value => value.status === 'SUCCEEDED')
  assert.equal(parsed.step, 'PARSED')
  const parsedSource = await request('padmin', 'GET', sourcePath)
  assert.equal(parsedSource.parse_status, 'PARSED'); assert.equal(parsedSource.can_import, false); assert.equal(parsedSource.business_status, 'CONFIG_PENDING')
  evidence.checks.push('remote asset-source Worker -> MinIO -> real standalone CSV parser; business import stays gated')

  stage = 'remote_contact_worker_one_row'
  const row = { name: '服务器验收虚构联系人 ' + state.run, roles: ['REPORTER'], mobile: null, email: null, wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false }
  const input = { expected_version: 0, mode: 'CREATE_ONLY', acknowledge_exact_duplicate_rows: false, rows: [row] }, contactKey = randomUUID()
  const acceptedContact = await request('padmin', 'POST', '/contact-imports', input, 202, contactKey)
  state.objects.contact_receipt = acceptedContact; save()
  const completed = await poll('padmin', '/contact-imports/' + acceptedContact.operation_id, value => value.status === 'SUCCEEDED')
  assert.equal(completed.completed_rows, 1); assert.equal(completed.results.length, 1)
  const contactId = completed.results[0].contact_id
  const contact = await request('padmin', 'GET', '/contacts/' + contactId)
  assert.equal(contact.name, row.name); assert.equal(contact.enabled, false); assert.equal(contact.notification_dispatched, false)
  await request('padmin', 'POST', '/contacts/' + contactId + '/archive', { expected_version: 1 })
  assert.deepEqual(await request('padmin', 'POST', '/contact-imports', input, 202, contactKey), acceptedContact)
  state.objects.contact_id = contactId; save(); evidence.checks.push('remote dedicated contact Worker creates one real row; archived with original 202 retained')
  assert.equal(state.pending.length, 0); state.complete = true; evidence.completed = true; save()
} catch (error) { evidence.failure = { stage, type: error?.name ?? 'Error' }; writeFileSync(new URL('.local/server-acceptance/private/deployed-worker-failure.log', root), String(error?.stack ?? error)); process.exitCode = 1 }
finally { evidence.verified_at = new Date().toISOString(); evidence.pending_original_requests = state.pending.length; writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n'); console.log(JSON.stringify({ completed: evidence.completed, stage, http_requests: evidence.http_requests, checks: evidence.checks.length, pending: state.pending.length })) }
