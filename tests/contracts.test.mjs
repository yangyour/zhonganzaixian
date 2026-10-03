import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
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

const diagnostic = {
  key: 'MOP-FAKE.rule', status: 'CONFIG_PENDING', effective_value: null,
  context: { tenant_id: id('2'), environment: 'remote_test' }, observed_at: timestamp,
  registry: { origin: 'DESIGN_REGISTRY', version: 1, type_label: 'integer', unit_label: null, scope_label: '原始作用域标签', source_ref: 'MOP-FAKE-source', decision_status_label: 'pending', classification_label: null, question_ids: ['Q09'] },
  recent_proposals: { items: [{ id: id('3'), version: 3, workflow_status: 'APPROVED', origin: 'INTERNAL_REVISION', confirmation_status: 'UNVERIFIED', evidence_input_status: 'REFERENCE_ONLY', evidence_reference_count: 1 }], truncated: false },
  runtime_definition: { availability: 'NOT_IMPLEMENTED', schema_version: null, allowed_layers: null, precedence: null, resolved_scope: null },
  publication: { availability: 'NOT_IMPLEMENTED', record_ref: null, revision_id: null, revision_version: null, effective_at: null, authorization_verified: null, production_approval_verified: null },
  customer_verification: { availability: 'NOT_IMPLEMENTED', required: null, decision_ref: null, authority_verified: null, authenticity_verified: null },
  reasons: [{ code: 'DESIGN_REGISTRY_NOT_RUNTIME_CONFIG', source_kind: 'DESIGN_REGISTRY', source_id: 'MOP-FAKE.rule', source_version: 1 }],
  question_ids: ['Q09'],
};

test('配置诊断只接受单key读取契约，拒绝生效、发布和客户核验的伪事实', () => {
  const route = contract.paths['/settings/effective'];
  assert.deepEqual(Object.keys(route), ['get']);
  assert.deepEqual(route.get['x-mop-allowed-query-keys'], ['key']);
  assert.equal(route.get['x-mop-reject-duplicate-query-keys'], true);
  assert.equal(route.get.parameters[0].required, true);
  assert.equal(route.get.parameters[0].schema.maxLength, 128);
  assert.ok(validates('ConfigurationDiagnostic', diagnostic));
  for (const change of [
    item => { item.effective_value = 0; },
    item => { item.effective_value = false; },
    item => { item.status = 'RESOLVED'; },
    item => { item.publication.availability = 'AVAILABLE'; },
    item => { item.publication.effective_at = timestamp; },
    item => { item.customer_verification.required = false; },
    item => { item.customer_verification.authority_verified = true; },
    item => { item.runtime_definition.precedence = []; },
    item => { item.recent_proposals.items[0].confirmation_status = 'VERIFIED'; },
  ]) {
    const candidate = structuredClone(diagnostic); change(candidate);
    assert.equal(validates('ConfigurationDiagnostic', candidate), false);
  }
});

test('配置诊断保留未知Q与证据数量，不接受原值、草稿、资料正文或超量近期提案', () => {
  const unknown = structuredClone(diagnostic);
  unknown.question_ids = null; unknown.registry.question_ids = null;
  unknown.recent_proposals.items[0].evidence_reference_count = null;
  assert.ok(validates('ConfigurationDiagnostic', unknown));
  for (const change of [
    item => { item.registry.value = 'MOP-FAKE-private-value'; },
    item => { item.registry.draft_value = 'MOP-FAKE-private-draft'; },
    item => { item.recent_proposals.items[0].proposed_value = 'MOP-FAKE-private-proposal'; },
    item => { item.recent_proposals.items[0].evidence_refs = []; },
    item => { item.recent_proposals.items[0].review_comment = 'MOP-FAKE-private-comment'; },
    item => { item.recent_proposals.truncated = true; },
    item => { item.recent_proposals.items = Array.from({ length: 6 }, () => structuredClone(item.recent_proposals.items[0])); },
    item => { item.question_ids = ['未提供的客户结论']; },
  ]) {
    const candidate = structuredClone(diagnostic); change(candidate);
    assert.equal(validates('ConfigurationDiagnostic', candidate), false);
  }
});

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
      const readonlyPreview = method === 'post' && ['/working-calendars/{id}/preview', '/sla/compliance-scenarios'].includes(path);
      if (readonlyPreview) assert.equal(operation['x-mop-read-only'], true);
      for (const name of readonlyPreview ? ['X-CSRF-Token'] : ['Idempotency-Key', 'X-CSRF-Token']) {
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

test('就绪成功不能把已启用但缺schema或存储的照片能力写成READY', () => {
  const photos = { enabled: true, ready: true, policy_ready: true, schema_ready: true, storage_ready: true, policy_source: 'USER_DEVELOPMENT_DIRECTION', reasons: [] };
  const data = { status: 'READY_REMOTE_TEST', ready: true, mode: 'remote_test', source: 'postgresql', durable: true, production_ready: false, working_calendars: { schema_access_ready: true, effective: false, customer_confirmation: 'UNVERIFIED' }, manual_incidents: { schema_access_ready: true, source_state: 'MANUAL', registration_status: 'REGISTERED_ONLY', monitoring_recovery_applicable: false, ticket_automatically_created: false }, manual_recovery: { schema_access_ready: true, source: 'HUMAN_CONFIRMATION', monitoring_recovery_applicable: false, ticket_state_changed: false, sla_changed: false }, photos, asset_reads: { unicode_search_ready: true, pagination: 'BOUNDED_POSTGRESQL_KEYSET' }, ticket_comments: { schema_access_ready: true, visibility: 'REPORTER_AND_INTERNAL', internal_identity_exposed: false, itsm_applied: false }, internal_notes: { schema_access_ready: true, visibility: 'INTERNAL', ticket_state_changed: false, sla_changed: false }, asset_manual_metadata: { schema_access_ready: true, scope: 'INTERNAL_MANUAL_DRAFT', monitoring_applied: false, cmdb_applied: false }, worklogs: { ready: true, schema_access_ready: true, financial_status: 'CONFIG_PENDING' }, asset_dependencies: { ready: true, schema_access_ready: true, configuration_status: 'CONFIG_PENDING', monitoring_applied: false, effective_rules: null }, asset_imports: { enabled: false, ready: false, policy_ready: false, schema_ready: false, storage_ready: false, worker_activity: 'NOT_PROBED', business_status: 'CONFIG_PENDING', can_import: false, reasons: ['ASSET_IMPORT_NOT_CONFIGURED'] } };
  assert.equal(validates('ReadinessResult', { data, trace_id: 'MOP-FAKE-readiness' }), true);
  for (const key of ['policy_ready', 'schema_ready', 'storage_ready']) assert.equal(validates('ReadinessResult', { data: { ...data, photos: { ...photos, [key]: false } }, trace_id: 'MOP-FAKE-readiness' }), false);
  assert.equal(validates('ReadinessResult', { data: { ...data, production_ready: true }, trace_id: 'MOP-FAKE-readiness' }), false);
});

const importColumns = ['NAME', 'TYPE', 'ADDRESS', 'BRAND', 'MODEL', 'SERIAL_NUMBER', 'SITE', 'LOCATION', 'OWNER', 'BUSINESS', 'PURCHASE_DATE', 'WARRANTY_EXPIRY', 'OVERDUE_SERVICE_DATE', 'COLLECTION_METHOD', 'NOTE'];
const importLabels = ['设备名称', '设备类型', 'IP或域名', '品牌', '型号', '序列号', '站点', '位置', '负责人', '所属业务', '采购日期', '维保到期日', '超期服役日期', '采集方式', '备注'];
const importFacts = { business_status: 'CONFIG_PENDING', can_import: false, question_ids: ['Q20', 'Q31'], scan_status: 'UNSCANNED', production_approval: 'UNVERIFIED', retention_until: null };
const importSelection = { declared_format: 'CSV', encoding: 'GB18030', delimiter: '\t', sheet_index: null, header_row: 1 };
const importArtifact = {
  id: id('6'), tenant_id: id('2'), source_id: id('1'), operation_id: id('5'), parse_hash: 'b'.repeat(64), source_sha256: 'a'.repeat(64), schema_version: 'contract-m3-6-standard15-v1', selection: importSelection, created_at: timestamp,
  state: 'PARSED', detected_format: 'CSV', sheets: [{ index: 0, name: 'CSV', hidden: false }], selected_sheet_index: 0, header_row: 1, date_epoch: 'NOT_APPLICABLE',
  columns: importColumns.map((code, index) => ({ index, header: importLabels[index], target_suggestions: [code] })),
  rows: [{ logical_record_number: 2, source_row_number: 2, physical_start_line: 2, physical_end_line: 2, cells: importColumns.map((_code, index) => ({ column_index: index, kind: 'TEXT', lexical: index === 14 ? 'MOP-FAKE原备注' : 'MOP-FAKE原值', display: index === 14 ? 'MOP-FAKE原备注' : 'MOP-FAKE原值', style_index: null, format_code: null })) }],
  parser_version: 'mop-source-parser-v1', resource_policy_version: 'asset-import-technical-v1',
};
const importMapping = {
  id: id('7'), tenant_id: id('2'), source_id: id('1'), artifact_id: id('6'), version: 1, source_parse_hash: 'b'.repeat(64), preview_hash: 'c'.repeat(64), schema_version: 'contract-m3-6-standard15-v1',
  target_source_columns: Object.fromEntries(importColumns.map((code, index) => [code, index])), ignored_source_columns: [],
  mapped_raw_rows: importArtifact.rows.map(row => ({ ...row, cells: Object.fromEntries(importColumns.map((code, index) => [code, row.cells[index]])) })), precheck_status: 'NOT_RUN', created_at: timestamp,
};
const importSource = {
  id: id('1'), tenant_id: id('2'), created_by: id('3'), purpose: 'asset_import_source', original_name: 'MOP-FAKE.csv', declared_format: 'CSV', size_bytes: 20971520, sha256: 'a'.repeat(64), storage_status: 'READY', parse_status: 'PARSED',
  store_operation_id: id('4'), parse_operation_id: id('5'), latest_artifact_id: id('6'), latest_mapping_id: id('7'), version: 5, created_at: timestamp, updated_at: timestamp, parsed_artifact: importArtifact, mapping: importMapping,
  ...importFacts, effective_rule_versions: null, quota_reservation: null,
};

test('V0.17保留来源接口creator-only、CSRF/幂等与上传解析202边界', () => {
  assert.equal(contract.info.version, '0.24.0');
  const routes = { '/assets/import-policy': 'get', '/assets/import-sources': 'post', '/assets/import-sources/{id}': 'get', '/assets/import-sources/{id}/parses': 'post', '/assets/import-sources/{id}/mappings': 'post', '/asset-import-operations/{id}': 'get' };
  for (const [path, method] of Object.entries(routes)) {
    const operation = contract.paths[path][method];
    assert.equal(operation['x-mop-capability'], 'assets:write'); assert.deepEqual(operation['x-mop-roles'], ['padmin', 'msp', 'tadmin']);
    assert.equal(operation['x-mop-creator-only'], true); assert.equal(operation['x-mop-permission-version-recheck'], true); assert.equal(operation['x-mop-reject-unknown-query-keys'], true);
    assert.ok(operation.responses['404']); assert.ok(operation.responses['503']);
    if (path.endsWith('import-sources') || path.endsWith('/parses')) { assert.ok(operation.responses['202']); assert.equal(operation.responses['200'], undefined) }
  }
  assert.equal(contract.paths['/assets/import-sources/{id}/imports'], undefined);
  assert.equal(contract.paths['/assets/import-sources/{id}/mappings'].post.responses['200'].content['application/json'].schema.$ref, '#/components/schemas/AssetImportSourceResult');
  const accepted = { data: { source_id: id('1'), operation_id: id('4'), status: 'PENDING' }, trace_id: 'MOP-FAKE-import' };
  assert.equal(validates('AssetImportAccepted', accepted), true);
  assert.equal(validates('AssetImportAccepted', { ...accepted, data: { operation_id: id('4'), status: 'PENDING' } }), false);
  assert.equal(validates('Accepted', accepted), false);
});

test('来源multipart严格三parts、CSV/XLSX与20MiB，照片维持独立5MB', () => {
  const upload = contract.paths['/assets/import-sources'].post.requestBody;
  assert.deepEqual(Object.keys(upload.content), ['multipart/form-data']);
  const schema = contract.components.schemas.AssetImportSourceUpload;
  assert.equal(schema.additionalProperties, false); assert.deepEqual([...schema.required].sort(), ['file', 'format', 'purpose']); assert.equal(schema.properties.file['x-mop-max-bytes'], 20971520);
  assert.equal(contract.components.schemas.ReportPhotoUpload.properties.file['x-mop-max-bytes'], 5000000);
  assert.equal(validates('AssetImportSourceUpload', { file: 'binary-placeholder', purpose: 'asset_import_source', format: 'XLSX' }), true);
  for (const invalid of [{ file: 'binary-placeholder', purpose: 'customer_report_photo', format: 'CSV' }, { file: 'binary-placeholder', purpose: 'asset_import_source', format: 'XLS' }, { file: 'binary-placeholder', purpose: 'asset_import_source', format: 'CSV', tenant_id: id('2') }]) assert.equal(validates('AssetImportSourceUpload', invalid), false);
});

test('解析flat参数拒绝默认编码、literal TAB、零表头、客户端主体与缺CAS', () => {
  const request = { expected_version: 2, ...importSelection };
  assert.equal(validates('AssetImportParseRequest', request), true);
  for (const invalid of [
    { ...request, encoding: null }, { ...request, encoding: 'GBK' }, { ...request, delimiter: '\\t' }, { ...request, sheet_index: 0 }, { ...request, header_row: 0 }, { ...request, header_row: null },
    { ...request, expected_version: 0 }, { ...request, expected_version: 1.5 }, { ...request, tenant_id: id('2') }, { ...request, selection: importSelection },
    Object.fromEntries(Object.entries(request).filter(([key]) => key !== 'expected_version')),
  ]) assert.equal(validates('AssetImportParseRequest', invalid), false);
  const inventory = { expected_version: 2, declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null };
  assert.equal(validates('AssetImportParseRequest', inventory), true);
  assert.equal(validates('AssetImportParseRequest', { ...inventory, sheet_index: 1, header_row: 2 }), true);
  for (const invalid of [{ ...inventory, sheet_index: 32 }, { ...inventory, header_row: 20001 }, { ...inventory, encoding: 'UTF-8' }, { ...inventory, delimiter: ',' }]) assert.equal(validates('AssetImportParseRequest', invalid), false);
});

test('映射恰好15个目标、NOTE必须index，显式null与ignore有独立语义', () => {
  const request = { expected_version: 4, source_parse_hash: 'b'.repeat(64), target_source_columns: importMapping.target_source_columns, ignored_source_columns: [] };
  assert.equal(validates('AssetImportMappingRequest', request), true);
  assert.equal(validates('AssetImportMappingRequest', { ...request, target_source_columns: { ...request.target_source_columns, NAME: null }, ignored_source_columns: [0] }), true);
  for (const change of [
    value => { delete value.target_source_columns.NAME }, value => { value.target_source_columns.EXTRA = null }, value => { value.target_source_columns.NOTE = null }, value => { value.target_source_columns.NAME = 'UNDECIDED' },
    value => { value.target_source_columns.NAME = -1 }, value => { value.target_source_columns.NAME = 64 }, value => { value.ignored_source_columns = [1, 1] }, value => { value.source_parse_hash = 'unknown' }, value => { delete value.expected_version }, value => { value.actor_id = id('3') },
  ]) { const candidate = structuredClone(request); change(candidate); assert.equal(validates('AssetImportMappingRequest', candidate), false) }
  const semantic = contract.components.schemas.AssetImportMappingRequest['x-mop-semantic-checks'].join(' ');
  assert.match(semantic, /不得重复分配/); assert.match(semantic, /不得与映射集合交叉/); assert.match(semantic, /所有当前源列必须显式映射或忽略/);
});

test('raw artifact保留lexical/display/style/date_epoch，目录状态不冒充PARSED', () => {
  assert.equal(validates('AssetImportParsedArtifact', { ...importArtifact, rows: [] }), false);
  assert.equal(validates('AssetImportParsedArtifact', importArtifact), true);
  const inventory = { ...importArtifact, detected_format: 'XLSX', state: 'SELECTION_REQUIRED', selection: { declared_format: 'XLSX', encoding: null, delimiter: null, sheet_index: null, header_row: null }, sheets: [{ index: 0, name: 'Cover', hidden: false }, { index: 1, name: 'MOP-FAKE hidden', hidden: true }], selected_sheet_index: null, header_row: null, date_epoch: 'MAC_1904', columns: [], rows: [] };
  assert.equal(validates('AssetImportParsedArtifact', inventory), true);
  for (const invalid of [{ ...inventory, rows: importArtifact.rows }, { ...inventory, columns: importArtifact.columns }, { ...inventory, date_epoch: 'NOT_APPLICABLE' }, { ...importArtifact, date_epoch: 'MAC_1904' }, { ...importArtifact, selected_sheet_index: null }, { ...importArtifact, rows: Array.from({ length: 501 }, () => importArtifact.rows[0]) }]) assert.equal(validates('AssetImportParsedArtifact', invalid), false);
  assert.equal(validates('AssetImportSourceCell', { column_index: 0, kind: 'NUMERIC', lexical: '46000.25', display: '2025/12/09 06:00', style_index: 2, format_code: 'yyyy/mm/dd hh:mm' }), true);
  assert.equal(validates('AssetImportSourceCell', { ...importArtifact.rows[0].cells[0], kind: 'FORMULA', cached_value: 'fake' }), false);
});

test('来源和映射拒绝实际导入、配额、生效规则以及私有存储伪事实', () => {
  assert.equal(validates('AssetImportSource', importSource), true); assert.equal(validates('AssetImportMapping', importMapping), true);
  for (const change of [
    value => { value.can_import = true }, value => { value.business_status = 'IMPORTED' }, value => { value.effective_rule_versions = [] }, value => { value.quota_reservation = { count: 1 } },
    value => { value.scan_status = 'CLEAN' }, value => { value.production_approval = 'APPROVED' }, value => { value.retention_until = timestamp }, value => { value.size_bytes = 20971521 },
    value => { value.mapping.precheck_status = 'PASS' }, value => { value.mapping.can_import = true }, value => { value.object_key = 'private/source' }, value => { value.bucket = 'private-bucket' }, value => { value.imported_count = 1 },
  ]) { const candidate = structuredClone(importSource); change(candidate); assert.equal(validates('AssetImportSource', candidate), false) }
  const pending = { ...importSource, storage_status: 'PENDING', parse_status: 'NOT_REQUESTED', parse_operation_id: null, latest_artifact_id: null, latest_mapping_id: null, parsed_artifact: null, mapping: null };
  assert.equal(validates('AssetImportSource', pending), true);
  for (const invalid of [{ ...importSource, latest_artifact_id: null }, { ...importSource, latest_mapping_id: null }, { ...importSource, parse_status: 'PENDING' }, { ...pending, parse_status: 'PARSED' }]) assert.equal(validates('AssetImportSource', invalid), false);
});

test('STORE_RAW/PARSE操作终态与error一致，独立于照片operation', () => {
  const base = { id: id('4'), tenant_id: id('2'), source_id: id('1'), created_by: id('3'), kind: 'STORE_RAW', result_ref: `/api/v1/assets/import-sources/${id('1')}`, result_artifact_id: null, created_at: timestamp, updated_at: timestamp };
  for (const [kind, step, status, result_artifact_id, error] of [
    ['STORE_RAW', 'QUEUED', 'PENDING', null, null], ['STORE_RAW', 'STORING', 'RUNNING', null, null], ['STORE_RAW', 'STORED', 'SUCCEEDED', null, null], ['PARSE', 'PARSING', 'RUNNING', null, null], ['PARSE', 'PARSED', 'SUCCEEDED', id('6'), null], ['PARSE', 'SELECTION_REQUIRED', 'SUCCEEDED', id('6'), null],
    ['PARSE', 'RETRY_WAIT', 'PENDING', null, { code: 'UPSTREAM_UNAVAILABLE', message: 'MOP-FAKE unavailable', retryable: true, question_ids: [] }], ['PARSE', 'PARSE_INVALID', 'FAILED', null, { code: 'IMPORT_SOURCE_INVALID', message: 'MOP-FAKE invalid input', retryable: false, question_ids: [] }],
  ]) assert.equal(validates('AssetImportOperation', { ...base, kind, step, status, result_artifact_id, error }), true);
  for (const invalid of [{ ...base, status: 'SUCCEEDED', step: 'QUEUED', error: null }, { ...base, status: 'SUCCEEDED', step: 'PARSED', error: null }, { ...base, status: 'RUNNING', step: 'PARSING', error: null }, { ...base, status: 'SUCCEEDED', step: 'STORED', error: null, object_key: 'private' }, { ...base, status: 'SUCCEEDED', step: 'STORED', error: null, result_ref: `/api/v1/files/${id('1')}` }]) assert.equal(validates('AssetImportOperation', invalid), false);
});

test('技术政策与就绪不冒充业务资格或Worker活动，实际限制不可与照片混同', () => {
  const policy = { ...importFacts, purpose: 'asset_import_source', allowed_formats: ['CSV', 'XLSX'], format_source: 'CONTRACT_M3_6', max_source_bytes: 20971520, max_data_rows: 500, max_source_columns: 64, max_sheets: 32, max_header_row: 20000, max_cell_chars: 4096, resource_policy_version: 'asset-import-technical-v1', resource_limit_kind: 'TECHNICAL_CONTAINMENT', schema_version: 'contract-m3-6-standard15-v1', target_columns: importColumns.map((code, index) => ({ code, label: importLabels[index], contract_always_required: code === 'NOTE' })), csv_encodings: ['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030'], csv_delimiters: [',', ';', '\t'], sheet_index_base: 0, column_index_base: 0, row_number_base: 1 };
  assert.equal(validates('AssetImportPolicy', policy), true);
  for (const invalid of [{ ...policy, max_source_bytes: 5000000 }, { ...policy, max_data_rows: 501 }, { ...policy, can_import: true }, { ...policy, csv_delimiters: [',', ';', '\\t'] }, { ...policy, target_columns: [...policy.target_columns.slice(0, 14), policy.target_columns[0]] }]) assert.equal(validates('AssetImportPolicy', invalid), false);
  const ready = { enabled: true, ready: true, policy_ready: true, schema_ready: true, storage_ready: true, worker_activity: 'NOT_PROBED', business_status: 'CONFIG_PENDING', can_import: false, reasons: [] };
  assert.equal(validates('AssetImportReadiness', ready), true);
  for (const key of ['policy_ready', 'schema_ready', 'storage_ready']) assert.equal(validates('AssetImportReadiness', { ...ready, [key]: false }), false);
  assert.equal(validates('AssetImportReadiness', { ...ready, worker_activity: 'RUNNING' }), false);
  assert.equal(validates('AssetImportReadiness', { ...ready, can_import: true }), false);
});

test('依赖草稿请求明确图CAS、空业务元数据与写动作保护', () => {
  const route = contract.paths['/assets/{id}/dependencies'];
  assert.deepEqual(Object.keys(route), ['get', 'post']);
  assert.equal(route.post['x-mop-capability'], 'assets:write');
  assert.equal(route.post['x-mop-permission-version-recheck'], true);
  const edge = { parent_id: id('1'), child_id: id('2'), proposed_type: null, proposed_suppression_scope: null, proposed_enabled: null };
  const request = { expected_version: 0, additions: [edge] };
  assert.ok(validates('AssetDependencyAppend', request));
  for (const change of [x => { x.expected_version = -1; }, x => { x.expected_version = 1.5; }, x => { x.tenant_id = id('3'); }, x => { x.additions = []; }, x => { x.additions = Array.from({ length: 101 }, () => edge); }, x => { delete x.additions[0].proposed_type; }, x => { x.additions[0].enabled = true; }, x => { x.additions[0].proposed_enabled = 'true'; }]) {
    const invalid = structuredClone(request); change(invalid); assert.equal(validates('AssetDependencyAppend', invalid), false);
  }
});

test('依赖回读只代表完整持久草稿，拒绝有效规则与已发布伪事实', () => {
  const draft = { asset_id: id('1'), tenant_id: id('2'), version: 0, version_kind: 'tenant_dependency_graph', updated_at: null, complete: true, proposed_edges: [], lifecycle_status: 'DRAFT', decision_status: 'pending', configuration_status: 'CONFIG_PENDING', effective_rules: null, monitoring_applied: false, question_ids: ['Q20'], source: 'postgresql', technical_limits: { max_additions: 100, max_graph_edges: 10000, max_body_bytes: 131072 } };
  assert.ok(validates('AssetDependencyDraft', draft));
  for (const change of [x => { x.effective_rules = []; }, x => { x.monitoring_applied = true; }, x => { x.lifecycle_status = 'PUBLISHED'; }, x => { x.decision_status = 'approved'; }, x => { x.complete = false; }, x => { x.technical_limits.max_graph_edges = 0; }, x => { x.configuration_status = 'READY'; }]) {
    const invalid = structuredClone(draft); change(invalid); assert.equal(validates('AssetDependencyDraft', invalid), false);
  }
});


test('标准15列模板为授权的固定二进制GET，不产生导入写请求', () => {
  const spec = read('../contracts/asset-import-template.json');
  const bytes = readFileSync(new URL('../server/api/src/main/resources/templates/asset-import-standard15.xlsx', import.meta.url));
  assert.equal(bytes.length, spec.size_bytes);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), spec.sha256);
  assert.equal(spec.data_rows, 0); assert.equal(spec.columns, 15); assert.equal(spec.business_status, 'CONFIG_PENDING');
  const route = contract.paths['/assets/import-template']; assert.deepEqual(Object.keys(route), ['get']);
  assert.equal(route.get['x-mop-capability'], 'assets:write');
  assert.deepEqual(route.get['x-mop-roles'], ['padmin', 'msp', 'tadmin']);
  assert.equal(route.get.responses[200].content[spec.media_type].schema.format, 'binary');
  assert.equal(route.get.responses[200].headers['X-MOP-Template-SHA256'].schema.const, spec.sha256);
  assert.equal(route.get.responses[200].headers['X-MOP-Business-Status'].schema.const, 'CONFIG_PENDING');
  assert.ok(route.get.responses[401].content['application/json']); assert.ok(route.get.responses[503].content['application/json']);
});


test('会话权限来自当前启用成员角色，未知角色没有资产读取兜底', () => {
  assert.equal(contract.components.schemas.SessionResult['x-mop-role-source'], 'current_active_tenant_membership');
  assert.equal(contract.components.schemas.SessionResult['x-mop-unsupported-role'], 'deny_all_and_do_not_establish_or_project_platform_session');
});


test('本人工时保留明确事实与未知成本，写入不接受actor/tenant/default', () => {
  const fields = { kind: 'REMOTE', started_at: timestamp, ended_at: timestamp, note: null };
  assert.ok(validates('WorklogCreate', { expected_ticket_version: 1, ...fields }));
  assert.ok(validates('WorklogUpdate', { expected_version: 1, ...fields }));
  for (const key of ['owner_id', 'tenant_id', 'default_minutes', 'labor_cost']) assert.equal(validates('WorklogCreate', { expected_ticket_version: 1, ...fields, [key]: 0 }), false);
  assert.equal(validates('WorklogCreate', { expected_ticket_version: 9007199254740991, ...fields }), false);
  const entry = { id: id('1'), tenant_id: id('2'), ticket_id: id('3'), owner_id: id('4'), version: 1, created_at: timestamp, updated_at: timestamp, ...fields, elapsed_millis: 0, record_source: 'MANUAL_INTERVAL', source: 'postgresql', verification_status: 'SELF_REPORTED', financial_status: 'CONFIG_PENDING', default_minutes: null, labor_cost: null, transport_cost: null, gross_margin: null };
  assert.ok(validates('Worklog', entry));
  for (const key of ['default_minutes', 'labor_cost', 'transport_cost', 'gross_margin']) assert.equal(validates('Worklog', { ...entry, [key]: 0 }), false);
  assert.equal(validates('Worklog', { ...entry, verification_status: 'VERIFIED' }), false);
  assert.equal(contract.paths['/tickets/{ticketId}/worklogs'].get['x-mop-capability'], 'worklogs:read_own');
  assert.equal(contract.paths['/tickets/{ticketId}/worklogs/{id}'].patch['x-mop-capability'], 'worklogs:write_own');
  assert.equal(contract.paths['/tickets/{ticketId}/worklogs/{id}'].delete, undefined);
});


test('审计查询仅GET、有界当前租户，区间校验不冒充外部可信锚', () => {
  for (const path of ['/audit/records', '/audit/integrity']) { assert.deepEqual(Object.keys(contract.paths[path]), ['get']); assert.equal(contract.paths[path].get['x-mop-capability'], 'audit:read'); }
  const empty = { status: 'EMPTY', valid: null, snapshot_sequence: 0, from_sequence: null, to_sequence: null, verified_records: 0, failure_sequence: null, failure: 'NONE', anchor_hash: null, final_hash: null, has_more: false, next_from_sequence: null, checked_at: timestamp, source: 'postgresql', scope: 'CURRENT_TENANT', verification_scope: 'LOCAL_DATABASE_SNAPSHOT', externally_anchored: false, max_range_records: 1000, hash_algorithm: 'SHA256_TOPLEVEL_SORTED_JSON_V1', whole_snapshot_chain_checked: false };
  assert.ok(validates('AuditIntegrity', empty)); assert.equal(validates('AuditIntegrity', { ...empty, valid: true }), false);
  const partial = { ...empty, status: 'VALID', valid: true, snapshot_sequence: 2000, from_sequence: 1, to_sequence: 1000, verified_records: 1000, anchor_hash: '0'.repeat(64), final_hash: 'a'.repeat(64), has_more: true, next_from_sequence: 1001 };
  assert.ok(validates('AuditIntegrity', partial)); assert.equal(validates('AuditIntegrity', { ...partial, externally_anchored: true }), false); assert.equal(validates('AuditIntegrity', { ...partial, whole_snapshot_chain_checked: true }), false);
  const invalid = { ...partial, status: 'INVALID', valid: false, failure_sequence: 5, failure: 'HASH_MISMATCH', verified_records: 4, next_from_sequence: null };
  assert.ok(validates('AuditIntegrity', invalid)); assert.equal(validates('AuditIntegrity', { ...invalid, valid: true }), false); assert.equal(validates('AuditIntegrity', { ...invalid, failure: 'NONE' }), false);
  const page = { items: [], snapshot_sequence: 0, next_before_sequence: null, has_more: false, source: 'postgresql', scope: 'CURRENT_TENANT', page_size_limit: 100, filter_semantics: 'EXACT_MATCH_HALF_OPEN_TIME', integrity_scope: 'EACH_RECORD_LOCAL_HASH_ONLY', externally_anchored: false };
  assert.ok(validates('AuditRecordPage', page)); assert.equal(validates('AuditRecordPage', { ...page, has_more: true }), false); assert.equal(validates('AuditRecordPage', { ...page, scope: 'PLATFORM' }), false);
});


test('手工草稿资料严格全字段、显式null、版本历史与未知监控边界', () => {
  const metadata = { name: '虚构设备', type: 'router', site_id: 'site-a-main', brand: null, model: null, serial_number: null, location: null, owner_id: null, business_description: null, purchase_date: null, warranty_expiry: null, overdue_service_date: null, collection_method_note: null, note: '维护备注' };
  const input = { expected_version: 1, ...metadata }; assert.ok(validates('ManualAssetUpdate', input));
  for (const extra of ['management_ip', 'tenant_id', 'source', 'status', 'actor_id', 'monitoring_applied']) assert.equal(validates('ManualAssetUpdate', { ...input, [extra]: 'forged' }), false);
  const missing = { ...input }; delete missing.owner_id; assert.equal(validates('ManualAssetUpdate', missing), false);
  for (const date of ['0000-01-01', '2026-02-30', '2026-10-02T00:00:00Z', 123]) assert.equal(validates('ManualAssetUpdate', { ...input, purchase_date: date }), false);
  const asset = { id: id('1'), tenant_id: id('2'), name: metadata.name, type: metadata.type, management_ip: '192.0.2.1', site_id: metadata.site_id, status: 'unknown', lifecycle_status: 'DRAFT', source: 'manual', sampled_at: null, unknown_reason: 'NOT_ONBOARDED', version: 1, created_at: timestamp, updated_at: timestamp, availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING' };
  const absent = { asset, metadata: null, metadata_version: null, recorded_by: null, recorded_at: null, can_edit: true, edit_blockers: [], scope: 'INTERNAL_MANUAL_DRAFT', verification_status: null, source: 'postgresql', monitoring_applied: false, cmdb_applied: false, management_ip_change_status: 'CONFIG_PENDING' };
  assert.ok(validates('ManualAssetCurrent', absent)); assert.equal(validates('ManualAssetCurrent', { ...absent, verification_status: 'SELF_REPORTED' }), false); assert.equal(validates('ManualAssetCurrent', { ...absent, can_edit: false }), false);
  const saved = { ...absent, asset: { ...asset, version: 2 }, metadata, metadata_version: 2, recorded_by: id('3'), recorded_at: timestamp, verification_status: 'SELF_REPORTED' }; assert.ok(validates('ManualAssetCurrent', saved)); assert.equal(validates('ManualAssetCurrent', { ...saved, monitoring_applied: true }), false);
  const revision = { asset_version: 2, previous_metadata_version: null, actor_id: id('3'), recorded_at: timestamp, before: { asset, metadata: null, metadata_version: null }, metadata, management_ip: asset.management_ip, verification_status: 'SELF_REPORTED', monitoring_applied: false, cmdb_applied: false }; assert.ok(validates('ManualAssetRevision', revision));
  const history = { items: [revision], has_more: false, next_before_version: null, source: 'postgresql', scope: 'INTERNAL_MANUAL_DRAFT', page_size_limit: 100 }; assert.ok(validates('ManualAssetHistory', history)); assert.equal(validates('ManualAssetHistory', { ...history, has_more: true }), false);
  const route = contract.paths['/assets/{id}/manual-metadata']; assert.deepEqual(route.patch['x-mop-roles'], ['msp', 'padmin']); assert.equal(route.patch['x-mop-reject-duplicate-json-keys'], true); assert.equal(route.delete, undefined);
});


test('资料引用选择只公开同租户有界标签并保留未知负责人', () => {
  const value = { data: { sites: [{ id: 'site-a', label: '虚构站点' }], owners: [{ id: id('1'), label: '虚构负责人' }], sites_truncated: false, owners_truncated: false, query: '', limit_per_kind: 100, scope: 'CURRENT_TENANT_ACTIVE_REFERENCES', source: 'postgresql', reference_validation: 'RECHECKED_ON_WRITE', default_owner_id: null }, trace_id: 'synthetic' };
  assert.ok(validates('ManualAssetReferencesResult', value)); assert.equal(validates('ManualAssetReferencesResult', { ...value, data: { ...value.data, default_owner_id: id('1') } }), false); assert.equal(validates('ManualAssetReferencesResult', { ...value, data: { ...value.data, owners: Array(101).fill(value.data.owners[0]) } }), false);
  assert.deepEqual(Object.keys(contract.paths['/assets/{id}/manual-metadata/references']), ['get']);
});


test('资料查询使用显式条件和有界配对cursor，不冒充冻结导出快照', () => {
  const filters = { name: '虚构', brand: '%_', purchase_date: '2024-02-29', unknown_fields: 'owner_id,warranty_expiry' }; assert.ok(validates('ManualAssetQueryFilters', filters));
  for (const change of [{ tenant_id: id('2') }, { brand: null }, { warranty_status: 'expired' }, { purchase_date: '2026-02-30' }, { unknown_fields: 'name' }]) assert.equal(validates('ManualAssetQueryFilters', { ...filters, ...change }), false);
  const page = { items: [], has_more: false, next_before_created_at: null, next_before_id: null, filters, source: 'postgresql', scope: 'CURRENT_TENANT_INTERNAL_MANUAL_SOURCE', page_size_limit: 100, pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY', unknown_semantics: 'EXPLICIT_NULL_NO_INFERENCE' }; assert.ok(validates('ManualAssetQueryPage', page)); assert.equal(validates('ManualAssetQueryPage', { ...page, has_more: true }), false); assert.equal(validates('ManualAssetQueryPage', { ...page, pagination_consistency: 'FROZEN_EXPORT' }), false);
  const route = contract.paths['/assets/manual-metadata']; assert.deepEqual(Object.keys(route), ['get']); assert.equal(route.get['x-mop-max-encoded-query-bytes'], 4096); assert.deepEqual(route.get['x-mop-roles'], ['msp', 'padmin']);
});


test('内部备注严格身份/可见性/不可变和不改工单SLA的真实契约', () => {
  const input = { expected_ticket_version: 1, text: '内部文字' }; assert.ok(validates('InternalTicketNoteCreate', input));
  for (const change of [{ visibility: 'CUSTOMER' }, { actor_id: id('3') }, { tenant_id: id('2') }, { attachment_ids: [] }, { expected_ticket_version: 0 }, { text: null }]) assert.equal(validates('InternalTicketNoteCreate', { ...input, ...change }), false);
  const note = { id: id('1'), tenant_id: id('2'), ticket_id: id('3'), actor_id: id('4'), observed_ticket_version: 1, text: input.text, text_sha256: createHash('sha256').update(input.text).digest('hex'), created_at: timestamp, visibility: 'INTERNAL', record_source: 'MANUAL_INTERNAL_NOTE', verification_status: 'SELF_REPORTED', source: 'postgresql', immutable: true, ticket_state_changed: false, sla_changed: false }; assert.ok(validates('InternalTicketNote', note));
  for (const change of [{ visibility: 'CUSTOMER' }, { immutable: false }, { ticket_state_changed: true }, { sla_changed: true }, { verification_status: 'VERIFIED' }]) assert.equal(validates('InternalTicketNote', { ...note, ...change }), false);
  const page = { items: [note], has_more: false, next_before_created_at: null, next_before_id: null, source: 'postgresql', scope: 'CURRENT_TENANT_INTERNAL', page_size_limit: 100 }; assert.ok(validates('InternalTicketNotePage', page)); assert.equal(validates('InternalTicketNotePage', { ...page, has_more: true }), false);
  assert.equal(contract.paths['/tickets/{ticketId}/internal-notes'].post['x-mop-capability'], 'ticket_notes:write_internal'); assert.deepEqual(Object.keys(contract.paths['/tickets/{ticketId}/internal-notes/{id}']), ['get']);
});


test('主台账查询保留签名游标且数据库有界读取与Unicode依赖明确', () => {
  const route = contract.paths['/assets'].get; assert.match(route['x-mop-repository-pagination'], /page_size\+1 LIMIT/); assert.match(route['x-mop-read-authorization'], /permission-version/); assert.match(route['x-mop-unicode-search'], /no normalization/); assert.match(contract.paths['/assets/{id}'].get['x-mop-read-authorization'], /fresh active/);
  const data = contract.components.schemas.ReadinessResult.properties.data; assert.ok(data.required.includes('asset_reads')); assert.equal(data.properties.asset_reads.properties.unicode_search_ready.const, true);
});

test('ticket query contract bounds Unicode input without expanding reporter visibility', () => {
  const operation = contract.paths['/tickets'].get;
  const parameters = Object.fromEntries(operation.parameters.map(item => [item.name, item.schema]));
  assert.equal(parameters.q.maxLength, 100);
  assert.deepEqual(parameters.urgency.enum, ['normal', 'urgent']);
  assert.deepEqual(parameters.category.enum, ['网络不通', '网速慢', 'WiFi问题', '设备异常', '其他']);
  assert.match(operation['x-mop-filter-semantics'], /reporter-only/);
  assert.match(operation['x-mop-filter-semantics'], /no normalization/);
});


test('customer comment DTO rejects private identity, incorrect attribution and provider claims', () => {
  const input = { expected_ticket_version: 1, text: '明确客户可见原文' }; assert.ok(validates('TicketCommentCreate', input));
  for (const extra of [{ actor_id: id('4') }, { visibility: 'INTERNAL' }, { itsm_sync_requested: true }, { attachment_ids: [] }, { author_kind: 'ENGINEER' }]) assert.equal(validates('TicketCommentCreate', { ...input, ...extra }), false);
  const comment = { id: id('1'), tenant_id: id('2'), ticket_id: id('3'), observed_ticket_version: 1, author_kind: 'ENGINEER', author_label: '工程师', text: input.text, text_sha256: createHash('sha256').update(input.text).digest('hex'), created_at: timestamp, visibility: 'REPORTER_AND_INTERNAL', record_source: 'MANUAL_TICKET_COMMENT', verification_status: 'SELF_REPORTED', source: 'postgresql', immutable: true, ticket_state_changed: false, sla_changed: false, itsm_applied: false, itsm_sync_status: 'NOT_REQUESTED', itsm_sync_available: false };
  assert.ok(validates('TicketComment', comment)); assert.ok(validates('TicketComment', { ...comment, author_kind: 'REPORTER', author_label: '报障人' }));
  for (const extra of [{ actor_id: id('4') }, { actor_name: 'private name' }, { author_label: '报障人' }, { visibility: 'INTERNAL' }, { itsm_applied: true }, { itsm_sync_available: true }, { ticket_state_changed: true }, { sla_changed: true }]) assert.equal(validates('TicketComment', { ...comment, ...extra }), false);
  const page = { items: [comment], has_more: false, next_before_created_at: null, next_before_id: null, source: 'postgresql', scope: 'REPORTER_OR_CURRENT_TENANT_INTERNAL', page_size_limit: 100 }; assert.ok(validates('TicketCommentPage', page)); assert.equal(validates('TicketCommentPage', { ...page, has_more: true }), false);
});

test('customer comment routes bind scoped replay and actual readiness without new mutation routes', () => {
  const path = contract.paths['/tickets/{ticketId}/comments']; assert.deepEqual(Object.keys(path), ['get', 'post']); assert.deepEqual(path.get['x-mop-roles'], ['viewer', 'tadmin', 'msp', 'padmin']); assert.match(path.post['x-mop-replay-authorization'], /before cached receipt/); assert.equal(path.post['x-mop-max-body-bytes'], 32768); assert.deepEqual(Object.keys(contract.paths['/tickets/{ticketId}/comments/{id}']), ['get']);
  const ready = contract.components.schemas.ReadinessResult.properties.data; assert.ok(ready.required.includes('ticket_comments')); assert.equal(ready.properties.ticket_comments.properties.internal_identity_exposed.const, false); assert.equal(ready.properties.ticket_comments.properties.itsm_applied.const, false);
});


test('日历草稿与试算始终未生效，未知不能补默认', () => {
  const input = { name: '合成日历', coverage_start: '2026-10-01T00:00:00Z', coverage_end: '2026-10-03T00:00:00Z', working_windows: [], coverage_complete: false, note: null };
  assert.equal(validates('WorkingCalendarCreate', input), true);
  for (const field of ['effective', 'tenant_id', 'timezone', 'customer_confirmation']) assert.equal(validates('WorkingCalendarCreate', { ...input, [field]: true }), false);
  assert.equal(validates('WorkingCalendarCreate', { ...input, coverage_complete: null }), false);
  assert.equal(validates('WorkingCalendarCreate', { ...input, working_windows: null }), false);
  assert.equal(validates('WorkingCalendarPreviewInput', { expected_version: 1, start: null, required_seconds: null }), true);
  assert.equal(validates('WorkingCalendarPreviewInput', { expected_version: 1, start: null, required_seconds: 0 }), true);
  assert.equal(validates('WorkingCalendarPreviewInput', { expected_version: 1, start: null, required_seconds: -1 }), false);
  assert.equal(contract.components.schemas.WorkingCalendar.properties.effective.const, false);
  assert.equal(contract.paths['/working-calendars/{id}/preview'].post['x-mop-read-only'], true);
  assert.equal(contract.paths['/working-calendars/{id}/preview'].post.parameters.some(p => p.name === 'Idempotency-Key'), false);
});
test('日历计算结果与未知原因的契约不允许混淆或冒充正式截止', () => {
  const value = { calendar_id: '55000000-0000-4000-8000-000000000001', tenant_id: '55000000-0000-4000-8000-000000000002', calendar_version: 1, status: 'UNRESOLVED', reason: 'DURATION_MISSING', scenario_deadline: null, effective_deadline: null, scope: 'UNPUBLISHED_DRAFT_SCENARIO', effective: false, customer_confirmation: 'UNVERIFIED', source: 'postgresql', timezone: 'Asia/Shanghai', start: null, required_seconds: null, remaining_duration: null, consumed: [] };
  const result = data => ({ data, trace_id: 'MOP-synthetic' });
  assert.equal(validates('WorkingCalendarPreviewResult', result(value)), true);
  assert.equal(validates('WorkingCalendarPreviewResult', result({ ...value, scenario_deadline: '2026-10-01T00:00:00Z' })), false);
  assert.equal(validates('WorkingCalendarPreviewResult', result({ ...value, effective: true })), false);
  assert.equal(validates('WorkingCalendarPreviewResult', result({ ...value, effective_deadline: '2026-10-01T00:00:00Z' })), false);
  const calculated = { ...value, status: 'CALCULATED', reason: null, scenario_deadline: '2026-10-01T00:00:00Z', remaining_duration: 'PT0S', required_seconds: 0, start: '2026-10-01T00:00:00Z' };
  assert.equal(validates('WorkingCalendarPreviewResult', result(calculated)), true);
});

test('旧直接建议草稿明确全局设计登记范围，不冒充租户运行配置', () => { const operation = contract.paths['/configurations/{key}/draft'].post; assert.equal(operation['x-mop-scope'], 'GLOBAL_DESIGN_REGISTRY_DRAFT_NOT_TENANT_RUNTIME'); assert.match(operation.description, /revisions/); assert.match(operation.description, /effective_value/); });


test('手工事件仅登记明确原始事实，禁止自动恢复和正式状态覆盖', () => {
  const id = '55000000-0000-4000-8000-000000000001';
  const input = { title: '  手工故障😀  ', severity: 'warning', asset_id: id, expected_asset_version: 1 };
  assert.equal(validates('ManualIncidentCreate', input), true);
  for (const patch of [{ severity: 'none' }, { severity: null }, { expected_asset_version: 0 }, { expected_asset_version: 9007199254740992 }, { title: '😀'.repeat(201) }, { tenant_id: id }, { monitor_state: 'NORMAL' }, { recovered_at: '2026-10-03T00:00:00Z' }, { ticket_priority: 'P1' }]) assert.equal(validates('ManualIncidentCreate', { ...input, ...patch }), false);
  const value = { id, tenant_id: id, asset_id: id, asset_name_at_registration: '合成资产', observed_asset_version: 1, title: input.title, severity: 'warning', severity_label: '警告', registered_by: id, registered_at: '2026-10-03T00:00:00Z', source_state: 'MANUAL', registration_status: 'REGISTERED_ONLY', verification_status: 'SELF_REPORTED', monitor_state: null, disposition_state: null, ticket_priority: null, observed_at: null, recovered_at: null, manual_recovery_status: 'NOT_CONFIRMED', monitoring_recovery_applicable: false, ticket_automatically_created: false, source: 'postgresql', record_source: 'MANUAL_INCIDENT_REGISTRATION', immutable: true };
  assert.equal(validates('ManualIncident', value), true);
  for (const patch of [{ severity_label: '灾难' }, { source_state: 'MONITOR' }, { monitor_state: 'NORMAL' }, { disposition_state: 'RESOLVED' }, { ticket_priority: 'P1' }, { monitoring_recovery_applicable: true }, { manual_recovery_status: 'CONFIRMED' }, { ticket_automatically_created: true }]) assert.equal(validates('ManualIncident', { ...value, ...patch }), false);
});
test('手工事件接口限定内部当前租户、原键重放资格和只读详情', () => {
  const routes = contract.paths['/manual-incidents']; assert.deepEqual(Object.keys(routes), ['get', 'post']); assert.deepEqual(routes.get['x-mop-roles'], ['msp', 'padmin']); assert.match(routes.post['x-mop-replay-authorization'], /before cached receipt/); assert.equal(routes.post['x-mop-max-body-bytes'], 8192); assert.deepEqual(Object.keys(contract.paths['/manual-incidents/{id}']), ['get']);
  const page = { items: [], has_more: false, next_before_registered_at: null, next_before_id: null, source: 'postgresql', scope: 'CURRENT_TENANT_INTERNAL_MANUAL_REGISTRATIONS', page_size_limit: 100, pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY' }; assert.equal(validates('ManualIncidentPage', page), true); assert.equal(validates('ManualIncidentPage', { ...page, has_more: true }), false);
  const ready = contract.components.schemas.ReadinessResult.properties.data; assert.ok(ready.required.includes('manual_incidents')); assert.equal(ready.properties.manual_incidents.properties.ticket_automatically_created.const, false);
});


test('人工恢复独立当前投影区分没有确认与已有人类证据，未知时间不补齐', () => {
  const current = { incident_id: id('1'), tenant_id: id('2'), version: 0, manual_recovery_status: 'NOT_CONFIRMED', confirmation: null, source: 'postgresql', scope: 'CURRENT_TENANT_INTERNAL_HUMAN_CONFIRMATION' };
  assert.equal(validates('ManualRecoveryCurrent', current), true);
  for (const patch of [{ version: 1 }, { manual_recovery_status: 'HUMAN_CONFIRMED' }, { version: 2 }]) assert.equal(validates('ManualRecoveryCurrent', { ...current, ...patch }), false);
  const confirmation = { id: id('3'), tenant_id: id('2'), incident_id: id('1'), confirmed_by: id('4'), note: '  人工说明😀\n原文 ', note_sha256: 'a'.repeat(64), reported_recovered_at: null, recorded_at: timestamp, manual_recovery_status: 'HUMAN_CONFIRMED', verification_status: 'SELF_REPORTED', record_source: 'MANUAL_RECOVERY_CONFIRMATION', monitor_state: null, disposition_state: null, monitoring_recovery_applicable: false, ticket_state_changed: false, sla_changed: false, immutable: true };
  assert.equal(validates('ManualRecoveryCurrent', { ...current, version: 1, manual_recovery_status: 'HUMAN_CONFIRMED', confirmation }), true);
  for (const patch of [{ monitor_state: 'NORMAL' }, { disposition_state: 'RESOLVED' }, { verification_status: 'VERIFIED' }, { monitoring_recovery_applicable: true }, { ticket_state_changed: true }, { sla_changed: true }]) assert.equal(validates('ManualRecoveryConfirmation', { ...confirmation, ...patch }), false);
});
test('人工恢复必须显式说明、可空时间和版本，不接受身份或状态覆盖', () => {
  const input = { expected_version: 0, note: '人工确认', reported_recovered_at: null }; assert.equal(validates('ManualRecoveryInput', input), true);
  for (const patch of [{ expected_version: -1 }, { expected_version: 2 }, { expected_version: null }, { note: '' }, { note: '😀'.repeat(2001) }, { recorded_at: timestamp }, { confirmed_by: id('1') }, { monitor_state: 'NORMAL' }]) assert.equal(validates('ManualRecoveryInput', { ...input, ...patch }), false);
  const missing = { ...input }; delete missing.reported_recovered_at; assert.equal(validates('ManualRecoveryInput', missing), false);
  const route = contract.paths['/manual-incidents/{incidentId}/recovery']; assert.deepEqual(Object.keys(route), ['get', 'post']); assert.deepEqual(route.get['x-mop-roles'], ['msp', 'padmin']); assert.match(route.post['x-mop-replay-authorization'], /before cached receipt/); assert.equal(route.post['x-mop-max-body-bytes'], 32768);
  assert.ok(contract.components.schemas.ReadinessResult.properties.data.required.includes('manual_recovery'));
});
test('SLA显式场景契约只读、未知值明确且拒绝业务权限覆盖', () => {
  const row = { scenario_ref: '😀'.repeat(160), priority: null, exclusion: null, exclusion_approval: null, sla_snapshot: null, response_due_at: null, recovery_due_at: null, response_actual_at: null, recovery_actual_at: null };
  const input = { population_complete: null, tickets: [row] };
  assert.equal(validates('SlaComplianceScenarioInput', input), true);
  for (const patch of [{ tenant_id: id('1') }, { effective: true }, { population_complete: 'true' }, { tickets: Array(201).fill(row) }]) assert.equal(validates('SlaComplianceScenarioInput', { ...input, ...patch }), false);
  for (const patch of [{ priority: 'p1' }, { scenario_ref: '😀'.repeat(161) }, { exclusion_approval: { id: 'x', version: 0 } }, { response_due_at: timestamp, forged: true }]) assert.equal(validates('SlaComplianceScenarioInput', { ...input, tickets: [{ ...row, ...patch }] }), false);
  const route = contract.paths['/sla/compliance-scenarios'].post;
  assert.equal(route['x-mop-read-only'], true); assert.deepEqual(route['x-mop-roles'], ['msp', 'padmin']); assert.equal(route.parameters.some(p => p.name === 'Idempotency-Key'), false); assert.equal(route['x-mop-max-body-bytes'], 262144);
});
test('SLA对照输出不能声称正式达标率、已验真审批或赔偿金额', () => {
  const data = { tenant_id: id('1'), scope: 'EXPLICIT_SLA_COMPARISON_SCENARIO', metric_version: 'contract-m5-19-comparison-v1', source: 'EXPLICIT_INPUT_NOT_PROJECT_DATA', status: 'NO_APPLICABLE', reason: 'NO_APPLICABLE_TICKETS', population_complete_declared: true, approval_validation_status: 'UNVERIFIED', runtime_snapshot_validation: 'NOT_CHECKED', effective: false, effective_achievement: null, compensation_amount: null, compensation_status: 'CONFIG_PENDING', counts: { input_total: 0, known_applicable: 0, applicability_unknown: 0, met: 0, not_met: 0, timing_unresolved: 0, not_applicable: 0 }, scenario_achievement: null, items: [], scenario_limit: 200 };
  const valid = value => validates('SlaComplianceScenarioResult', { data: value, trace_id: 'scenario-test' }); assert.equal(valid(data), true);
  for (const patch of [{ effective: true }, { effective_achievement: 1 }, { compensation_amount: 0 }, { approval_validation_status: 'VERIFIED' }, { source: 'postgresql' }, { status: 'CALCULATED' }, { scenario_achievement: { numerator: 0, denominator: 0 } }]) assert.equal(valid({ ...data, ...patch }), false);
});
test('128项合同追踪保持唯一且逐项证据链接指向实际仓库文件', () => {
  const url = new URL('../doc/development/07_合同逐项开发追踪.md', import.meta.url);
  const rows = readFileSync(url, 'utf8').split('\n').filter(line => /^\|M\d+-\d+\|/.test(line));
  assert.equal(rows.length, 128); assert.equal(new Set(rows.map(row => row.split('|')[1])).size, 128);
  for (const row of rows) {
    const evidence = row.split('|').at(-2);
    if (evidence.trim()) assert.match(evidence, /非DONE|NOT_RUN/, row.split('|')[1] + ' must retain current incomplete acceptance boundary');
    for (const match of evidence.matchAll(/\]\(([^)]+)\)/g)) assert.ok(readFileSync(new URL(match[1], url)).length > 0, match[1]);
  }
});
test('记录日期为空时派生值必须null，到期当天和已过期分开', () => {
  const unknown = { recorded_date: null, days_until: null, position: 'UNKNOWN', within_7_days: null, within_30_days: null, within_90_days: null, in_reference_quarter: null };
  assert.equal(validates('RecordedDateResult', unknown), true);
  for (const patch of [{ days_until: 0 }, { within_90_days: false }, { position: 'PAST_DATE' }, { recorded_date: '2026-10-03' }]) assert.equal(validates('RecordedDateResult', { ...unknown, ...patch }), false);
  const today = { recorded_date: '2026-10-03', days_until: 0, position: 'DUE_TODAY', within_7_days: true, within_30_days: true, within_90_days: true, in_reference_quarter: true }; assert.equal(validates('RecordedDateResult', today), true); for (const patch of [{ position: 'PAST_DATE' }, { days_until: 1 }, { within_7_days: false }, { in_reference_quarter: false }]) assert.equal(validates('RecordedDateResult', { ...today, ...patch }), false);
});
test('真实记录日期对照不冒充维保覆盖、隐患生成或通知送达', () => {
  const unknown = { recorded_date: null, days_until: null, position: 'UNKNOWN', within_7_days: null, within_30_days: null, within_90_days: null, in_reference_quarter: null };
  const data = { asset_id: id('1'), tenant_id: id('2'), asset_version: 1, metadata_version: null, observed_at: timestamp, as_of_date: '2026-10-01', timezone: 'Asia/Shanghai', policy_version: 'contract-m3-10-date-v1', scope: 'RECORDED_DATE_ASSESSMENT_ONLY', source: 'postgresql', recorded_dates_source: 'MANUAL_ASSET_METADATA', verification_status: null, coverage_verified: false, warranty_expiry: unknown, overdue_service_date: unknown, warranty_start: null, warranty_provider: null, hazard_lookup_status: 'NOT_IMPLEMENTED', notification_lookup_status: 'NOT_IMPLEMENTED', actions_this_request: { hazard_creation: 'NOT_REQUESTED', notification: 'NOT_REQUESTED', asset_updated: false } };
  const valid = value => validates('AssetDateAssessmentResult', { data: value, trace_id: 'date-test' }); assert.equal(valid(data), true);
  for (const patch of [{ coverage_verified: true }, { warranty_provider: 'guessed' }, { verification_status: 'SELF_REPORTED' }, { notification_lookup_status: 'DELIVERED' }, { timezone: 'UTC' }, { actions_this_request: { ...data.actions_this_request, asset_updated: true } }]) assert.equal(valid({ ...data, ...patch }), false);
  const route = contract.paths['/assets/{id}/manual-metadata/date-assessment']; assert.deepEqual(Object.keys(route), ['get']); assert.equal(route.get['x-mop-read-only'], true); assert.deepEqual(route.get['x-mop-roles'], ['msp', 'padmin']);
});
test('日期列表只读分页绑定三字段游标和当前服务端参考日', () => {
  const page = { items: [], has_more: false, next_before_created_at: null, next_before_id: null, next_expected_reference_date: null, date_field: 'warranty_expiry', window: 'WITHIN_30_DAYS', filters: {}, reference_date: '2026-10-03', observed_at: timestamp, timezone: 'Asia/Shanghai', source: 'postgresql', scope: 'CURRENT_TENANT_INTERNAL_MANUAL_DATE_FILTER', page_size_limit: 100, pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY', metadata_change_semantics: 'NOT_FROZEN_ACROSS_PAGES' };
  assert.equal(validates('AssetDateQueryPage', page), true);
  for (const patch of [{ has_more: true }, { next_expected_reference_date: '2026-10-03' }, { window: 'COVERED' }, { date_field: 'purchase_date' }, { filters: { tenant_id: id('1') } }, { pagination_consistency: 'FROZEN' }, { metadata_change_semantics: 'FROZEN' }]) assert.equal(validates('AssetDateQueryPage', { ...page, ...patch }), false);
  const route = contract.paths['/assets/date-assessments'].get; assert.equal(route['x-mop-read-only'], true); assert.deepEqual(route['x-mop-paired-query-keys'], ['before_created_at', 'before_id', 'expected_reference_date']);
  for (const name of ['date_field', 'window']) assert.equal(route.parameters.find(p => p.name === name).required, true);
  assert.ok(!route.parameters.some(p => p.name === 'as_of_date' || p.name === 'tenant_id'));
});
test('全局搜索固定5/4/3/3分组且未接入来源不是空集或零计数', () => {
  const supported = (kind, limit, scope, fields) => ({ kind, status: 'AVAILABLE', limit, returned: 0, has_more: false, items: [], source_scope: scope, matched_fields: fields, reason: null });
  const unsupported = (kind, limit) => ({ kind, status: 'NOT_IMPLEMENTED', limit, returned: null, has_more: null, items: null, source_scope: null, matched_fields: null, reason: 'SOURCE_NOT_IMPLEMENTED' });
  const value = { query: 'Ä%_', tenant_id: id('1'), observed_at: timestamp, scope: 'CURRENT_TENANT_AUTHORIZED_GROUPED_SEARCH', coverage: 'PARTIAL', coverage_reason: 'EVENT_AND_AUTOMATION_SOURCES_NOT_IMPLEMENTED', query_semantics: 'LITERAL_UNICODE_CASE_INSENSITIVE', snapshot_consistency: 'ONE_READONLY_TRANSACTION', source: 'postgresql_supported_groups', notification_bell_status: 'NOT_IMPLEMENTED', groups: [supported('assets', 5, 'CURRENT_TENANT', ['name']), unsupported('events', 4), supported('tickets', 3, 'OWN_REPORT_REGISTRATIONS', ['title', 'receipt_no']), unsupported('automation_scripts', 3)] };
  assert.equal(validates('GroupedSearch', value), true);
  for (const patch of [{ coverage: 'COMPLETE' }, { notification_bell_status: 'READY' }, { groups: value.groups.slice(0, 3) }, { query: '' }]) assert.equal(validates('GroupedSearch', { ...value, ...patch }), false);
  for (const patch of [{ items: [] }, { returned: 0 }, { has_more: false }]) { const copy = structuredClone(value); Object.assign(copy.groups[1], patch); assert.equal(validates('GroupedSearch', copy), false); }
  const fakeMore = structuredClone(value); fakeMore.groups[0].has_more = true; assert.equal(validates('GroupedSearch', fakeMore), false);
});
test('分组搜索最小DTO不允许IP、联系人、正文或内部身份字段', () => {
  const asset = { id: id('1'), tenant_id: id('2'), name: '明确名称', type: 'router', record_source: 'manual', version: 1, resource_kind: 'ASSET_RECORD' };
  const ticket = { id: id('3'), tenant_id: id('2'), title: '受理登记', receipt_no: 'test-receipt', record_source: 'customer_report', registration_status: 'REGISTERED', version: 1, resource_kind: 'CUSTOMER_REPORT_REGISTRATION' };
  assert.equal(validates('SearchAssetHit', asset), true); assert.equal(validates('SearchTicketHit', ticket), true);
  for (const key of ['management_ip', 'contact_name', 'contact_phone', 'description', 'reporter_id', 'history', 'attachments']) { assert.equal(validates('SearchAssetHit', { ...asset, [key]: 'private' }), false); assert.equal(validates('SearchTicketHit', { ...ticket, [key]: 'private' }), false); }
  assert.equal(validates('SearchTicketHit', { ...ticket, registration_status: 'CLOSED' }), false);
  const route = contract.paths['/search'].get; assert.deepEqual(route.parameters.map(p => p.name), ['q']); assert.equal(route.parameters[0].required, true); assert.equal(route['x-mop-read-only'], true);
});
