import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { deflateSync } from 'node:zlib';

const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

/** A small, labelled synthetic PNG. This is test input, never a customer photograph. */
function syntheticPng(marker, blue = 210) {
  const crc32 = bytes => {
    let value = 0xffffffff;
    for (const byte of bytes) {
      value ^= byte;
      for (let bit = 0; bit < 8; bit++) value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
    }
    return (value ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, value) => {
    const name = Buffer.from(type, 'ascii'), length = Buffer.alloc(4), crc = Buffer.alloc(4);
    length.writeUInt32BE(value.length); crc.writeUInt32BE(crc32(Buffer.concat([name, value])));
    return Buffer.concat([length, name, value, crc]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(16, 0); header.writeUInt32BE(16, 4); header[8] = 8; header[9] = 2;
  const rows = Buffer.alloc(16 * (1 + 16 * 3));
  for (let row = 0; row < 16; row++) for (let column = 0; column < 16; column++) {
    const offset = row * 49 + 1 + column * 3;
    rows[offset] = 35; rows[offset + 1] = 125; rows[offset + 2] = blue;
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('tEXt', Buffer.from('Comment\0' + marker + '_SYNTHETIC_NOT_CUSTOMER_PHOTO', 'ascii')),
    chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

function multipart(bytes, name, mediaType, purpose, format) {
  const form = new FormData();
  form.append('file', new Blob([bytes], { type: mediaType }), name);
  form.append('purpose', purpose);
  if (format) form.append('format', format);
  return form;
}

/** Six bounded real-component checks; no device action, notification or formal asset import. */
export async function runIntake(h) {
  const { padmin: admin, msp, tadmin, viewerA: customer, viewerB: foreign, reviewer } = h.clients;
  const marker = h.prefix + '_INTAKE';
  const contactRows = [1, 2].map(index => ({ name: marker + '_CONTACT_' + index, roles: ['TECHNICAL_CONTACT'], mobile: null, email: null, wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false }));
  const importBody = { expected_version: 0, mode: 'CREATE_ONLY', acknowledge_exact_duplicate_rows: false, rows: contactRows };
  const contactKey = randomUUID();
  let contactReceipt, completedJob, photo, source;
  const ownedContacts = new Map();
  const requireUpload = client => { if (typeof client.upload !== 'function') h.block('PREREQUISITE:MULTIPART_CLIENT_NOT_AVAILABLE'); };
  async function poll(read, complete, kind) {
    let last;
    try {
      return await h.waitFor(async () => {
        last = await read();
        h.artifact('intake-progress', { kind, id: last.id, status: last.status ?? last.upload_status ?? last.storage_status, step: last.step, parse_status: last.parse_status, attempts: last.attempts });
        return complete(last) ? last : false;
      }, { timeoutMs: 45000, intervalMs: 750 });
    } catch (error) {
      h.artifact('intake-incomplete', { kind, id: last?.id, status: last?.status ?? last?.upload_status ?? last?.storage_status, step: last?.step, parse_status: last?.parse_status, error_code: last?.error?.code, durable_object_retained: true });
      throw error;
    }
  }

  await h.case('INTAKE-01', '联系人耐久导入受理、原回执和创建者隔离', async () => {
    contactReceipt = await admin.call('/contact-imports', 'POST', importBody, 202, { key: contactKey });
    assert.equal(contactReceipt.status, 'PENDING');
    h.artifact('intake-contact-job', { id: contactReceipt.operation_id, row_count: contactRows.length, synthetic_prefix: marker, enabled: false, notification_requested: false });
    assert.deepEqual(await admin.call('/contact-imports', 'POST', importBody, 202, { key: contactKey }), contactReceipt);
    await admin.call('/contact-imports', 'POST', { ...importBody, rows: [{ ...contactRows[0], name: marker + '_DIFFERENT' }] }, 409, { key: contactKey });
    const current = await admin.call('/contact-imports/' + contactReceipt.operation_id);
    assert.equal(current.tenant_id, admin.session.tenant.id); assert.equal(current.created_by, admin.session.user.id);
    assert.equal(current.row_count, 2); assert.equal(current.worker_liveness, null);
    assert.equal(current.notification_dispatched, false); assert.equal(current.identity_binding_created, false);
    await tadmin.call('/contact-imports/' + current.id, 'GET', undefined, 404);
    await reviewer.call('/contact-imports/' + current.id, 'GET', undefined, 404);
    await customer.call('/contact-imports', 'GET', undefined, 403);
    await admin.call('/contact-imports', 'POST', importBody, 403, { noCsrf: true });
    await admin.call('/contact-imports', 'POST', importBody, 400, { noKey: true });
    await admin.call('/contact-imports', 'POST', { ...importBody, rows: [contactRows[0], contactRows[0]] }, 409);
    const listing = await admin.call('/contact-imports?page_size=15');
    assert.ok(listing.items.some(row => row.id === current.id));
    assert.ok(listing.items.every(row => row.created_by === admin.session.user.id && row.tenant_id === admin.session.tenant.id));
  });

  await h.case('INTAKE-02', '联系人Worker真实完成、逐行读取与原受理回执不改写', async () => {
    if (!contactReceipt?.operation_id) h.block('PREREQUISITE:CONTACT_IMPORT_NOT_ACCEPTED');
    const job = await poll(() => admin.call('/contact-imports/' + contactReceipt.operation_id), row => ['SUCCEEDED', 'FAILED'].includes(row.status), 'CONTACT_IMPORT');
    if (job.status === 'FAILED' && job.error?.code === 'LAST_ENABLED_DECISION_MAKER') h.block('PREREQUISITE:ENABLED_DECISION_MAKER_ABSENT_NO_EXISTING_CONTACT_MODIFIED');
    assert.equal(job.status, 'SUCCEEDED', 'CONTACT_IMPORT_WORKER_TERMINAL_FAILURE');
    completedJob = job;
    assert.equal(job.step, 'APPLIED'); assert.equal(job.completed_rows, 2); assert.equal(job.results.length, 2);
    assert.equal(job.worker_liveness, null); assert.ok(job.attempts >= 1);
    assert.equal(new Set(job.results.map(result => result.contact_id)).size, 2);
    for (const [index, result] of job.results.entries()) {
      // Remember all returned IDs before reading facts, so later assertions cannot lose cleanup targets.
      ownedContacts.set(result.contact_id, { id: result.contact_id, tenantId: admin.session.tenant.id });
      h.artifact('intake-created-contact', { id: result.contact_id, job_id: job.id, row_number: result.row_number, contact_version: result.contact_version });
      assert.equal(result.row_number, index + 1); assert.equal(result.contact_version, 1);
      const contact = await admin.call('/contacts/' + result.contact_id);
      assert.equal(contact.tenant_id, admin.session.tenant.id); assert.equal(contact.created_by, admin.session.user.id);
      assert.equal(contact.name, contactRows[index].name); assert.equal(contact.version, 1); assert.equal(contact.enabled, false);
      assert.equal(contact.notification_dispatched, false); assert.equal(contact.identity_binding_created, false);
      for (const field of ['mobile', 'email', 'wecom_user_id', 'preferred_priorities', 'quiet_window']) assert.equal(contact[field], null);
    }
    assert.deepEqual(await admin.call('/contact-imports', 'POST', importBody, 202, { key: contactKey }), contactReceipt);
    assert.equal(contactReceipt.status, 'PENDING');
    assert.equal((await admin.call('/contact-imports/' + job.id)).status, 'SUCCEEDED');
    h.artifact('intake-contact-completion', { job_id: job.id, real_worker_completion: true, created_contacts: 2, original_receipt_status: contactReceipt.status, current_job_status: job.status, notifications_sent: 0 });
  });

  const png = syntheticPng(marker), imageName = marker + '_PHOTO.png', photoKey = randomUUID();
  const photoForm = (bytes = png) => multipart(bytes, imageName, 'image/png', 'customer_report_photo');
  await h.case('INTAKE-03', '照片原图耐久上传、Worker存储及原请求重放', async () => {
    requireUpload(customer);
    const policy = await customer.call('/files/policy');
    assert.ok(policy.allowed_media_types?.includes('image/png')); assert.equal(policy.scan_status, 'UNSCANNED');
    await customer.upload('/files', photoForm(), 403, { noCsrf: true });
    await customer.upload('/files', photoForm(Buffer.from('SYNTHETIC_NOT_AN_IMAGE')), 400);
    const receipt = await customer.upload('/files', photoForm(), 202, { key: photoKey });
    h.artifact('intake-photo-operation', { operation_id: receipt.operation_id, input_sha256: sha256(png), bytes: png.length, synthetic_prefix: marker });
    const operation = await customer.call('/operations/' + receipt.operation_id);
    assert.equal(operation.kind, 'FILE_UPLOAD'); assert.match(operation.result_ref, /^\/api\/v1\/files\/[0-9a-f-]{36}$/);
    const id = operation.result_ref.split('/').at(-1);
    photo = { id, receipt };
    h.artifact('intake-photo', { id, operation_id: receipt.operation_id, synthetic_prefix: marker, bound_to_customer_report: false, retained_test_artifact: true });
    const file = await poll(() => customer.call('/files/' + id), row => row.upload_status !== 'PENDING', 'PHOTO_STORE');
    assert.equal(file.upload_status, 'READY', 'PHOTO_STORAGE_TERMINAL_FAILURE');
    assert.equal(file.sha256, sha256(png)); assert.equal(file.size_bytes, png.length);
    assert.equal(file.scan_status, 'UNSCANNED'); assert.equal(file.retention_until, null); assert.equal(file.production_approval, 'UNVERIFIED');
    const done = await customer.call('/operations/' + receipt.operation_id);
    assert.equal(done.status, 'SUCCEEDED'); assert.equal(done.step, 'STORED'); assert.equal(done.error, null);
    assert.deepEqual(await customer.upload('/files', photoForm(), 202, { key: photoKey }), receipt);
    await customer.upload('/files', photoForm(syntheticPng(marker, 211)), 409, { key: photoKey });
    photo.file = file;
  });

  await h.case('INTAKE-04', '照片原图授权读取、字节摘要与非所有者隔离', async () => {
    if (!photo?.file) h.block('PREREQUISITE:PHOTO_NOT_READY');
    for (const client of [tadmin, msp, foreign]) {
      await client.call('/files/' + photo.id, 'GET', undefined, 404);
      await client.call('/operations/' + photo.receipt.operation_id, 'GET', undefined, 404);
      await client.call('/files/' + photo.id + '/content-grants', 'POST', {}, 404);
    }
    const grant = await customer.call('/files/' + photo.id + '/content-grants', 'POST', {});
    assert.match(grant.content_url, /^\/api\/v1\/files\/[0-9a-f-]{36}\/content\?grant=[0-9a-f-]{36}$/);
    const downloaded = await customer.download(grant.content_url);
    assert.equal(sha256(downloaded.bytes), sha256(png)); assert.equal(downloaded.bytes.length, png.length);
    assert.equal(downloaded.headers.get('x-content-type-options'), 'nosniff');
    assert.match(downloaded.headers.get('cache-control'), /no-store/);
    assert.equal(downloaded.headers.get('content-type'), 'image/png');
    await tadmin.download(grant.content_url, 404);
    h.artifact('intake-photo-readback', { file_id: photo.id, original_sha256: sha256(png), downloaded_sha256: sha256(downloaded.bytes), original_bytes_preserved: true, other_actor_cannot_reuse_download: true, malware_scan_performed: false });
  });

  const csv = Buffer.from('名称,备注\r\n' + marker + '_ASSET,"虚构来源，不导入资产"\r\n', 'utf8');
  const csvName = marker + '_SOURCE.csv', sourceKey = randomUUID();
  const sourceForm = () => multipart(csv, csvName, 'text/csv', 'asset_import_source', 'CSV');
  await h.case('INTAKE-05', '资产CSV原始来源受理、真实存储与创建者隔离', async () => {
    requireUpload(admin);
    const policy = await admin.call('/assets/import-policy');
    assert.equal(policy.can_import, false); assert.equal(policy.business_status, 'CONFIG_PENDING');
    assert.equal(policy.target_columns.length, 15);
    await admin.upload('/assets/import-sources', sourceForm(), 403, { noCsrf: true });
    const receipt = await admin.upload('/assets/import-sources', sourceForm(), 202, { key: sourceKey });
    source = { id: receipt.source_id, receipt };
    h.artifact('intake-asset-source', { id: source.id, operation_id: receipt.operation_id, source_sha256: sha256(csv), bytes: csv.length, retained_test_artifact: true });
    assert.deepEqual(await admin.upload('/assets/import-sources', sourceForm(), 202, { key: sourceKey }), receipt);
    const current = await poll(() => admin.call('/assets/import-sources/' + source.id), row => row.storage_status !== 'PENDING', 'ASSET_SOURCE_STORE');
    assert.equal(current.storage_status, 'READY', 'ASSET_SOURCE_STORAGE_TERMINAL_FAILURE');
    assert.equal(current.sha256, sha256(csv)); assert.equal(current.size_bytes, csv.length); assert.equal(current.original_name, csvName);
    assert.equal(current.parse_status, 'NOT_REQUESTED'); assert.equal(current.can_import, false); assert.equal(current.quota_reservation, null);
    const operation = await admin.call('/asset-import-operations/' + receipt.operation_id);
    assert.equal(operation.kind, 'STORE_RAW'); assert.equal(operation.status, 'SUCCEEDED'); assert.equal(operation.step, 'STORED');
    await reviewer.call('/assets/import-sources/' + source.id, 'GET', undefined, 404);
    await customer.call('/assets/import-sources/' + source.id, 'GET', undefined, 403);
    source.current = current;
  });

  await h.case('INTAKE-06', 'CSV显式解析、15列映射与原始预览保存', async () => {
    if (!source?.current) h.block('PREREQUISITE:ASSET_SOURCE_NOT_READY');
    const path = '/assets/import-sources/' + source.id;
    const selection = { expected_version: source.current.version, declared_format: 'CSV', encoding: 'UTF-8', delimiter: ',', sheet_index: null, header_row: 1 }, key = randomUUID();
    const receipt = await admin.call(path + '/parses', 'POST', selection, 202, { key });
    h.artifact('intake-asset-parse', { source_id: source.id, operation_id: receipt.operation_id, explicit_encoding: 'UTF-8', explicit_header_row: 1 });
    assert.deepEqual(await admin.call(path + '/parses', 'POST', selection, 202, { key }), receipt);
    const parsed = await poll(() => admin.call(path), row => row.parse_status !== 'PENDING', 'ASSET_SOURCE_PARSE');
    assert.equal(parsed.parse_status, 'PARSED', 'ASSET_SOURCE_PARSE_TERMINAL_FAILURE');
    assert.equal(parsed.storage_status, 'READY'); assert.equal(parsed.sha256, sha256(csv));
    const artifact = parsed.parsed_artifact;
    assert.equal(artifact.state, 'PARSED'); assert.equal(artifact.source_sha256, sha256(csv));
    assert.equal(artifact.columns.length, 2); assert.equal(artifact.rows.length, 1);
    assert.deepEqual(artifact.columns.map(column => column.header), ['名称', '备注']);
    assert.equal(artifact.rows[0].cells[0].lexical, marker + '_ASSET');
    const columns = ['NAME', 'TYPE', 'ADDRESS', 'BRAND', 'MODEL', 'SERIAL_NUMBER', 'SITE', 'LOCATION', 'OWNER', 'BUSINESS', 'PURCHASE_DATE', 'WARRANTY_EXPIRY', 'OVERDUE_SERVICE_DATE', 'COLLECTION_METHOD', 'NOTE'];
    const targetColumns = Object.fromEntries(columns.map(column => [column, column === 'NAME' ? 0 : column === 'NOTE' ? 1 : null]));
    const mappingBody = { expected_version: parsed.version, source_parse_hash: artifact.parse_hash, target_source_columns: targetColumns, ignored_source_columns: [] }, mappingKey = randomUUID();
    await admin.call(path + '/mappings', 'POST', { ...mappingBody, source_parse_hash: '0'.repeat(64) }, 409);
    const mapped = await admin.call(path + '/mappings', 'POST', mappingBody, 200, { key: mappingKey });
    assert.equal(mapped.can_import, false); assert.equal(mapped.business_status, 'CONFIG_PENDING');
    assert.equal(mapped.effective_rule_versions, null); assert.equal(mapped.quota_reservation, null);
    assert.equal(mapped.mapping.precheck_status, 'NOT_RUN'); assert.equal(mapped.mapping.mapped_raw_rows.length, 1);
    assert.deepEqual(mapped.mapping.target_source_columns, targetColumns);
    assert.equal(mapped.mapping.mapped_raw_rows[0].cells.NAME.lexical, marker + '_ASSET');
    assert.equal(mapped.mapping.mapped_raw_rows[0].cells.TYPE, null);
    assert.deepEqual(await admin.call(path + '/mappings', 'POST', mappingBody, 200, { key: mappingKey }), mapped);
    assert.deepEqual((await admin.call(path)).mapping, mapped.mapping);
    await admin.call(path + '/mappings', 'POST', mappingBody, 409);
    const operation = await admin.call('/asset-import-operations/' + receipt.operation_id);
    assert.equal(operation.status, 'SUCCEEDED'); assert.equal(operation.step, 'PARSED'); assert.equal(operation.result_artifact_id, artifact.id);
    h.artifact('intake-asset-mapping', { source_id: source.id, artifact_id: artifact.id, mapping_id: mapped.mapping.id, parse_hash: artifact.parse_hash, preview_hash: mapped.mapping.preview_hash, business_asset_import_performed: false, quota_reserved: false });
  });

  // The import results contain immutable v1 links. Archive only those new disabled contacts.
  if (contactReceipt?.operation_id) {
    await h.case('INTAKE-CLEAN-CONTACTS', '归档本轮导入联系人并保留作业原始结果', async () => {
      const job = await admin.call('/contact-imports/' + contactReceipt.operation_id);
      if (job.status !== 'SUCCEEDED') {
        h.artifact('intake-contact-cleanup-pending', { job_id: job.id, status: job.status, step: job.step, result_count: job.results.length, cleanup_required_if_completed_later: ['PENDING', 'RUNNING'].includes(job.status) });
        if (['PENDING', 'RUNNING'].includes(job.status)) h.block('CONTACT_IMPORT_NOT_FINISHED_CLEANUP_MUST_FOLLOW_THIS_JOB');
        assert.deepEqual(job.results, []);
        return;
      }
      for (const result of job.results) ownedContacts.set(result.contact_id, { id: result.contact_id, tenantId: admin.session.tenant.id });
      for (const record of ownedContacts.values()) {
        const contact = await admin.call('/contacts/' + record.id);
        assert.equal(contact.tenant_id, record.tenantId); assert.ok(contact.name.startsWith(marker));
        assert.equal(contact.enabled, false); assert.equal(contact.roles.includes('DECISION_MAKER'), false);
        if (!contact.archived) await admin.call('/contacts/' + contact.id + '/archive', 'POST', { expected_version: contact.version });
        assert.equal((await admin.call('/contacts/' + contact.id)).archived, true);
        h.artifact('intake-contact-cleanup', { id: contact.id, archived: true, history_retained: true });
      }
      const reread = await admin.call('/contact-imports/' + job.id);
      assert.deepEqual(reread.results, (completedJob ?? job).results);
      assert.ok(reread.results.every(result => result.contact_version === 1));
      assert.deepEqual(await admin.call('/contact-imports', 'POST', importBody, 202, { key: contactKey }), contactReceipt);
    });
  }
}
