import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const contract = read('../contracts/local-api.openapi.json');
const baseline = read('../contracts/config-baseline.json');
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addFormat('binary', true);
ajv.addSchema({ $id: 'urn:mop:actual-api', components: contract.components });
const validates = (name, value) => {
  const check = ajv.getSchema(`urn:mop:actual-api#/components/schemas/${name}`);
  assert.ok(check, `missing schema ${name}`);
  return check(value);
};
const id = (digit) => `${digit.repeat(8)}-${digit.repeat(4)}-4${digit.repeat(3)}-8${digit.repeat(3)}-${digit.repeat(12)}`;
const timestamp = '2026-10-01T05:00:00.000Z';
const facts = { purpose: 'customer_report_photo', scan_status: 'UNSCANNED', retention_until: null, production_approval: 'UNVERIFIED', policy_source: 'USER_DEVELOPMENT_DIRECTION' };
const photo = {
  id: id('1'), tenant_id: id('2'), ...facts, original_name: 'MOP-FAKE.png', media_type: 'image/png', size_bytes: 5000000, sha256: 'a'.repeat(64),
  upload_status: 'READY', created_by: id('3'), ticket_id: id('4'), operation_id: id('5'), version: 2, created_at: timestamp, updated_at: timestamp, source: 'postgresql',
};

test('设计基线复制保持原始待确认值，没有补默认值', () => {
  assert.deepEqual(baseline, read('../doc/development/contracts/config-baseline.json'));
  assert.equal(baseline.status, 'design_registry_not_runtime_config');
  for (const setting of baseline.settings) {
    if (setting.decision_status === 'pending') {
      assert.equal(setting.value, null, setting.key);
      assert.ok(setting.question_ids.length, setting.key);
    }
  }
});

test('所有业务写接口明确要求幂等及CSRF，remote_test不存在fixture身份入口', () => {
  assert.equal(contract.paths['/fixture/users'], undefined);
  assert.equal(contract.paths['/fixture/session'], undefined);
  for (const [path, methods] of Object.entries(contract.paths)) {
    for (const [method, operation] of Object.entries(methods)) {
      if (!['post', 'put', 'patch', 'delete'].includes(method)) continue;
      for (const name of ['Idempotency-Key', 'X-CSRF-Token']) {
        assert.ok(operation.parameters.some((p) => p.in === 'header' && p.name === name && p.required), `${method} ${path} ${name}`);
      }
    }
  }
});

test('没有可被误读为真实异步受理或已纳管的契约', () => {
  for (const path of ['/assets/{id}/onboarding', '/configurations/{key}/publish']) {
    const responses = contract.paths[path].post.responses;
    assert.ok(responses['422']);
    assert.equal(responses['200'], undefined);
    assert.equal(responses['202'], undefined);
  }
  const create = contract.paths['/assets'].post.requestBody.content['application/json'].schema;
  assert.equal(create.additionalProperties, false);
  for (const field of ['tenant_id', 'actor_id', 'source', 'secret_ref']) assert.equal(create.properties[field], undefined);
});

test('照片受理仅multipart并返回202，原图使用受权平台入口', () => {
  const upload = contract.paths['/files'].post;
  assert.deepEqual(Object.keys(upload.requestBody.content), ['multipart/form-data']);
  assert.ok(upload.responses['202']);
  assert.equal(upload.responses['200'], undefined);
  const input = contract.components.schemas.ReportPhotoUpload;
  assert.equal(input.additionalProperties, false);
  assert.deepEqual([...input.required].sort(), ['file', 'purpose']);
  assert.equal(input.properties.file['x-mop-max-bytes'], 5000000);
  assert.equal(input.properties.purpose.const, 'customer_report_photo');
  const content = contract.paths['/files/{id}/content'].get;
  assert.deepEqual(Object.keys(content.responses['200'].content).sort(), ['image/jpeg', 'image/png']);
  assert.ok(content.parameters.some(parameter => parameter.in === 'query' && parameter.name === 'grant' && parameter.required));
  assert.equal(content.responses['200'].headers['Cache-Control'].schema.const, 'no-store');
});

test('照片追加请求拒绝缺CAS、重复引用、超量和伪造主体', () => {
  const body = { expected_version: 2, attachment_ids: [id('1')] };
  assert.equal(validates('PhotoAttachmentCreate', body), true);
  for (const invalid of [
    { attachment_ids: body.attachment_ids }, { ...body, expected_version: 0 }, { ...body, expected_version: 1.5 },
    { ...body, attachment_ids: [] }, { ...body, attachment_ids: [id('1'), id('1')] },
    { ...body, attachment_ids: [id('1'), id('2'), id('3'), id('4')] }, { ...body, attachment_ids: ['not-a-uuid'] },
    { ...body, tenant_id: id('2') }, { ...body, actor_id: id('3') },
  ]) assert.equal(validates('PhotoAttachmentCreate', invalid), false, JSON.stringify(invalid));
  assert.equal(validates('ReportPhotoContentGrant', { content_url: `/api/v1/files/${id('1')}/content?grant=${id('6')}`, expires_at: timestamp }), true);
  for (const content_url of [`https://storage.example.invalid/photos/${id('1')}`, `/api/v1/files/${id('1')}/content?grant=invalid`, `/api/v1/files/${id('1')}/content?grant=${id('6')}&tenant_id=${id('2')}`])
    assert.equal(validates('ReportPhotoContentGrant', { content_url, expires_at: timestamp }), false);
});

test('照片策略和元数据保留未扫描、未批准及未知留存并拒绝私有存储字段', () => {
  const policy = { ...facts, allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5000000, max_attachments: 3 };
  assert.equal(validates('ReportPhotoPolicy', policy), true);
  assert.equal(validates('ReportPhotoPolicy', { ...policy, allowed_media_types: null }), true);
  for (const invalid of [{ ...policy, allowed_media_types: [] }, { ...policy, allowed_media_types: ['image/svg+xml'] }, { ...policy, max_size_bytes: 5242880 }, { ...policy, production_approval: 'APPROVED' }])
    assert.equal(validates('ReportPhotoPolicy', invalid), false);
  assert.equal(validates('ReportPhoto', photo), true);
  for (const invalid of [{ ...photo, size_bytes: 5000001 }, { ...photo, scan_status: 'CLEAN' }, { ...photo, retention_until: timestamp }, ...['object_key', 'bucket', 'content', 'secret'].map(field => ({ ...photo, [field]: 'must-not-leak' }))])
    assert.equal(validates('ReportPhoto', invalid), false);
});

test('operation区分报障门禁与真实文件状态，终态不得伪造', () => {
  const base = { id: id('5'), tenant_id: id('2'), kind: 'FILE_UPLOAD', progress: null, result_ref: `/api/v1/files/${id('1')}`, created_at: timestamp, updated_at: timestamp };
  const envelope = data => ({ data, trace_id: 'MOP-FAKE-contract' });
  for (const [step, status, error] of [
    ['QUEUED', 'PENDING', null], ['UPLOADING', 'RUNNING', null], ['STORED', 'SUCCEEDED', null],
    ['RETRY_WAIT', 'PENDING', { code: 'UPSTREAM_UNAVAILABLE', message: 'storage unavailable', retryable: true, question_ids: [] }],
    ['AUTHORIZATION_REVOKED', 'FAILED', { code: 'ACTION_FORBIDDEN', message: 'authorization changed', retryable: false, question_ids: [] }],
    ['STORAGE_CONFLICT', 'FAILED', { code: 'FILE_INTEGRITY_CONFLICT', message: 'original hash mismatch', retryable: false, question_ids: [] }],
  ]) assert.equal(validates('OperationResult', envelope({ ...base, step, status, error })), true, step);
  assert.equal(validates('OperationResult', envelope({ ...base, step: 'QUEUED', status: 'SUCCEEDED', error: null })), false);
  assert.equal(validates('OperationResult', envelope({ ...base, step: 'STORED', status: 'SUCCEEDED', error: null, object_key: 'photos/private' })), false);
  const registration = { ...base, kind: 'CUSTOMER_REPORT_REGISTRATION', status: 'PENDING', step: 'AWAITING_TICKET_CONFIGURATION', result_ref: `/api/v1/tickets/${id('4')}`, error: { code: 'CONFIG_PENDING', message: 'rules pending', retryable: false, question_ids: ['Q07', 'Q08'] } };
  assert.equal(validates('OperationResult', envelope(registration)), true);
  assert.equal(validates('OperationResult', envelope({ ...registration, status: 'SUCCEEDED' })), false);
  assert.equal(contract.components.schemas.TicketRegistration.properties.version.minimum, 1);
  assert.deepEqual(contract.components.schemas.TicketRegistration.properties.history.items.properties.action.enum, ['REGISTER', 'ADD_ATTACHMENTS']);
});
