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
      const readonlyPreview = method === 'post' && ['/working-calendars/{id}/preview', '/sla/compliance-scenarios', '/capacity/estimates', '/operations/explicit-comparisons', '/contacts/import-preview', '/monitoring/threshold-diagnostics'].includes(path);
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
  const data = { inspection_source_collections: { schema_access_ready: true, verification_status: 'SELF_REPORTED_UNVERIFIED', generation_status: 'CONFIG_PENDING', formal_report_generatable: false, report_published: false }, inspection_worksheets: { schema_access_ready: true, verification_status: 'SELF_REPORTED_UNVERIFIED', evidence_binding_status: 'NOT_IMPLEMENTED', publication_status: 'NOT_IMPLEMENTED', report_published: false, active_scan_performed: false }, contact_imports: { schema_access_ready: true, worker_liveness: null, notification_dispatched: false, identity_binding_created: false }, inspection_templates: { schema_access_ready: true, approval_status: 'UNVERIFIED', publication_status: 'NOT_IMPLEMENTED', report_published: false, active_scan_performed: false }, customer_contacts: { schema_access_ready: true, notification_dispatched: false, channel_verification: 'NOT_VERIFIED', identity_binding_created: false }, status: 'READY_REMOTE_TEST', ready: true, mode: 'remote_test', source: 'postgresql', durable: true, production_ready: false, capacity_scenarios: { schema_access_ready: true, effective: false, measurement_verified: false, poc_executed: false }, working_calendars: { schema_access_ready: true, effective: false, customer_confirmation: 'UNVERIFIED' }, manual_incidents: { schema_access_ready: true, source_state: 'MANUAL', registration_status: 'REGISTERED_ONLY', monitoring_recovery_applicable: false, ticket_automatically_created: false }, manual_recovery: { schema_access_ready: true, source: 'HUMAN_CONFIRMATION', monitoring_recovery_applicable: false, ticket_state_changed: false, sla_changed: false }, photos, asset_reads: { unicode_search_ready: true, pagination: 'BOUNDED_POSTGRESQL_KEYSET' }, ticket_comments: { schema_access_ready: true, visibility: 'REPORTER_AND_INTERNAL', internal_identity_exposed: false, itsm_applied: false }, internal_notes: { schema_access_ready: true, visibility: 'INTERNAL', ticket_state_changed: false, sla_changed: false }, asset_manual_metadata: { schema_access_ready: true, scope: 'INTERNAL_MANUAL_DRAFT', monitoring_applied: false, cmdb_applied: false }, worklogs: { ready: true, schema_access_ready: true, financial_status: 'CONFIG_PENDING' }, asset_dependencies: { ready: true, schema_access_ready: true, configuration_status: 'CONFIG_PENDING', monitoring_applied: false, effective_rules: null }, asset_imports: { enabled: false, ready: false, policy_ready: false, schema_ready: false, storage_ready: false, worker_activity: 'NOT_PROBED', business_status: 'CONFIG_PENDING', can_import: false, reasons: ['ASSET_IMPORT_NOT_CONFIGURED'] } };
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
  assert.equal(contract.info.version, '0.38.0');
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
test('代码能力目录必须四角色五资源维度，不是签字或生效授权矩阵', () => {
  const schema = contract.components.schemas.CapabilityCatalog;
  assert.deepEqual(schema.properties.roles.prefixItems.map(v => v.properties.id.const), ['viewer', 'tadmin', 'msp', 'padmin']);
  assert.deepEqual(schema.properties.dimensions.prefixItems.map(v => v.properties.label.const), ['资产与指标', '告警与工单', '模板与自动化', '平台管理', '成员管理']);
  for (const key of ['effective_customer_matrix', 'customer_signature', 'supplier_signature', 'resource_access_decision']) assert.equal(schema.properties[key].type, 'null');
  assert.equal(schema.properties.configuration_driven_authorization.const, false); assert.equal(schema.properties.production_authority_verified.const, false); assert.equal(schema.properties.resource_authorization_required.const, true); assert.equal(schema.properties.subject_bindings_included.const, false);
  const route = contract.paths['/authorization/capability-catalog']; assert.deepEqual(Object.keys(route), ['get']); assert.equal(route.get['x-mop-read-only'], true); assert.deepEqual(route.get['x-mop-roles'], ['padmin']);
});
test('能力声明行仅含声明向量，拒绝新增角色、授权结论和未知维度', () => {
  const entry = { id: 'search:read', label: '分组搜索', dimension_ids: ['ASSETS_METRICS', 'ALERTS_TICKETS'], declared_by_role: { viewer: true, tadmin: true, msp: true, padmin: true } }; assert.equal(validates('CapabilityCatalogEntry', entry), true);
  for (const patch of [{ permitted: true }, { dimension_ids: ['MENU'] }, { dimension_ids: ['ASSETS_METRICS', 'ASSETS_METRICS'] }, { declared_by_role: { ...entry.declared_by_role, super_admin: true } }, { declared_by_role: { ...entry.declared_by_role, viewer: null } }]) assert.equal(validates('CapabilityCatalogEntry', { ...entry, ...patch }), false);
});
test('维保专用写入要求全部显式字段且不扩展旧基础请求与DTO', () => {
  const input = { expected_version: 1, warranty_start: null, warranty_expiry: null, warranty_supplier: null, overdue_service_date: null, note: '明确资料备注' }; assert.equal(validates('ManualWarrantyUpdate', input), true);
  for (const key of Object.keys(input)) { const missing = { ...input }; delete missing[key]; assert.equal(validates('ManualWarrantyUpdate', missing), false); }
  for (const patch of [{ expected_version: 0 }, { warranty_start: '0000-01-01' }, { warranty_expiry: '2026-02-30' }, { warranty_supplier: '' }, { warranty_supplier: '😀'.repeat(201) }, { tenant_id: id('2') }, { warranty_coverage_verified: true }]) assert.equal(validates('ManualWarrantyUpdate', { ...input, ...patch }), false);
  assert.equal(validates('ManualWarrantyUpdate', { ...input, warranty_supplier: '😀'.repeat(200) }), true);
  assert.equal(contract.components.schemas.ManualAssetCurrent.properties.warranty_details, undefined); assert.equal(contract.components.schemas.ManualAssetUpdate.properties.warranty_start, undefined);
  const route = contract.paths['/assets/{id}/manual-metadata/warranty']; assert.deepEqual(Object.keys(route), ['get', 'patch']); assert.equal(route.get['x-mop-read-only'], true); assert.match(route.patch['x-mop-replay-authorization'], /before original receipt/); assert.equal(route.patch['x-mop-reject-duplicate-json-keys'], true);
});
test('补充资料未登记与显式null不同，供应商和覆盖不能冒充核验', () => {
  assert.equal(validates('ManualWarrantyDetails', { warranty_start: null, warranty_supplier: null }), true); assert.equal(validates('ManualWarrantyDetails', null), false); assert.equal(validates('ManualWarrantyDetails', { warranty_start: null }), false);
  const current = contract.components.schemas.ManualWarrantyCurrent; assert.equal(current.properties.warranty_coverage_verified.const, false); assert.equal(current.properties.warranty_supplier_verification.const, 'UNVERIFIED'); assert.equal(current.properties.warranty_details.oneOf[1].type, 'null');
  const snapshot = contract.components.schemas.ManualWarrantySnapshot; assert.equal(snapshot.allOf[1].then.properties.warranty_details.type, 'null'); assert.equal(contract.components.schemas.ManualWarrantyRevision.properties.before.$ref, '#/components/schemas/ManualWarrantySnapshot');
});

test('容量输入用十进制字符串保精度，未知必须明确null且不补原型参数', () => {
  const unknown = { inputs: null, model: null, proxy_buffer: null }; assert.equal(validates('CapacityEstimateInput', unknown), true);
  const v = { inputs: { hosts: '9007199254740993', items_per_host: '100', interval_seconds: '10', history_days: 2, bytes_per_value: '80', trend_ratio: '0.5', trend_days: 3, headroom_ratio: '0.25' }, model: { version: 'SYNTHETIC-ONLY', bytes_per_trend_row: '128', pool_nvps_limit: '60' }, proxy_buffer: null }; assert.equal(validates('CapacityEstimateInput', v), true);
  for (const value of [1, '1e3', '01', '-1', '1'.repeat(41)]) assert.equal(validates('CapacityEstimateInput', { ...v, inputs: { ...v.inputs, hosts: value } }), false);
  const missing = structuredClone(v); delete missing.model.pool_nvps_limit; assert.equal(validates('CapacityEstimateInput', missing), false); assert.equal(validates('CapacityEstimateInput', { ...v, tenant_id: id('2') }), false);
  const route = contract.paths['/capacity/estimates'].post; assert.equal(route['x-mop-read-only'], true); assert.deepEqual(route['x-mop-roles'], ['padmin']); assert.deepEqual(route.parameters.map(p => p.name), ['X-CSRF-Token']);
});
test('容量与缓冲结果不能声称已保存场景、已测容量、批准池限或压测通过', () => {
  const buffer = { status: 'UNRESOLVED', formula_version: 'contract-m4-15-buffer-v1', missing_inputs: ['proxy_buffer'], estimated_bytes: null, margin_factor_below_one: null, measurement_verified: false, failover_verified: false }; assert.equal(validates('ProxyBufferEstimate', buffer), true); for (const patch of [{ estimated_bytes: '0' }, { measurement_verified: true }, { failover_verified: true }, { missing_inputs: [] }]) assert.equal(validates('ProxyBufferEstimate', { ...buffer, ...patch }), false);
  const v = { tenant_id: id('1'), scope: 'EXPLICIT_CAPACITY_ESTIMATE', source: 'EXPLICIT_INPUT_NOT_MEASURED_DATA', formula_version: 'contract-m10-7-capacity-v1', input: { inputs: null, model: null, proxy_buffer: null }, status: 'UNRESOLVED', model_version: null, missing_inputs: ['inputs', 'model'], outputs: null, decimal_precision: 'DECIMAL128', decimal_encoding: 'BASE10_STRINGS', estimate_only: true, scenario_saved: false, model_approval_status: 'UNVERIFIED', measurement_verification: 'UNVERIFIED', approved_pool_nvps_limit: null, measured_capacity: null, production_capacity_verified: false, poc_status: 'NOT_RUN', proxy_buffer: buffer, units: { nvps: 'values_per_second', storage: 'bytes', counts: 'integers' } }; assert.equal(validates('CapacityEstimate', v), true);
  for (const patch of [{ scenario_saved: true }, { measured_capacity: '12500' }, { poc_status: 'PASSED' }, { estimate_only: false }, { model_approval_status: 'APPROVED' }, { approved_pool_nvps_limit: '5000' }, { missing_inputs: [] }]) assert.equal(validates('CapacityEstimate', { ...v, ...patch }), false);
});

test('容量草稿创建严格版本0和显式五字段，原收据受当前权限保护', () => {
 const value={expected_version:0,label:'合成方案',note:'合成说明',calculation:{inputs:null,model:null,proxy_buffer:null},benchmark_reference:null}; assert.equal(validates('CapacityScenarioCreate',value),true);
 for(const key of Object.keys(value)){const copy={...value};delete copy[key];assert.equal(validates('CapacityScenarioCreate',copy),false);}
 for(const patch of [{expected_version:1},{effective:true},{label:''},{calculation:{inputs:null,model:null}}])assert.equal(validates('CapacityScenarioCreate',{...value,...patch}),false);
 const route=contract.paths['/capacity/scenarios'];assert.deepEqual(Object.keys(route),['get','post']);assert.equal(route.post['x-mop-capability'],'capacity_scenarios:write');assert.match(route.post['x-mop-replay-authorization'],/before cached receipt/);assert.deepEqual(route.post.parameters.map(p=>p.name),['Idempotency-Key','X-CSRF-Token']);assert.equal(route.get['x-mop-read-only'],true);
});
test('已保存容量草稿仍禁止生效和实测结论，引用与状态必须一致', () => {
 const snapshot={capacity_formula_version:'contract-m10-7-capacity-v1',buffer_formula_version:'contract-m4-15-buffer-v1',status:'UNRESOLVED',model_version:null,missing_inputs:['inputs','model'],outputs:null,proxy_buffer:{status:'UNRESOLVED',missing_inputs:['proxy_buffer'],estimated_bytes:null,margin_factor_below_one:null,measurement_verified:false,failover_verified:false}};
 const value={id:id('1'),tenant_id:id('2'),created_by:id('3'),recorded_at:timestamp,version:1,label:'方案',note:'说明',calculation:{inputs:null,model:null,proxy_buffer:null},calculation_sha256:'a'.repeat(64),snapshot_sha256:'b'.repeat(64),benchmark_reference:null,benchmark_verification:'NOT_PROVIDED',snapshot,decimal_encoding:'BASE10_STRINGS',decimal_precision:'DECIMAL128',lifecycle_status:'DRAFT',model_approval_status:'UNVERIFIED',measurement_verification:'UNVERIFIED',production_capacity_verified:false,approved_pool_nvps_limit:null,measured_capacity:null,poc_status:'NOT_RUN',effective:false,immutable:true,source:'postgresql',record_source:'EXPLICIT_CAPACITY_DRAFT'};
 assert.equal(validates('CapacityScenario',value),true);for(const patch of [{effective:true},{production_capacity_verified:true},{poc_status:'PASSED'},{model_approval_status:'APPROVED'},{version:2},{benchmark_verification:'REFERENCE_ONLY_UNVERIFIED'}])assert.equal(validates('CapacityScenario',{...value,...patch}),false);
 const ref={reference:'document-ref:synthetic-01',title:'未核验',sha256:'a'.repeat(64)};assert.equal(validates('CapacityScenario',{...value,benchmark_reference:ref,benchmark_verification:'REFERENCE_ONLY_UNVERIFIED'}),true);
});

test('显式经营对照输入完整且大数使用规范字符串',()=>{
 const v={elapsed_millis:['0',null,'9007199254740993'],staffing:{active_tenants:'30',on_call_fte:'1.5',second_line_fte:'1'}};assert.equal(validates('ExplicitOperationalInput',v),true);assert.equal(validates('ExplicitOperationalInput',{elapsed_millis:null,staffing:null}),true);
 for(const bad of [1,'01','1e2','-1','1.5','1'.repeat(41)])assert.equal(validates('ExplicitOperationalInput',{...v,elapsed_millis:[bad]}),false);
 assert.equal(validates('ExplicitOperationalInput',{...v,elapsed_millis:Array(10001).fill('0')}),false);assert.equal(validates('ExplicitOperationalInput',{staffing:null}),false);assert.equal(validates('ExplicitOperationalInput',{...v,tenant_id:id('2')}),false);
 const route=contract.paths['/operations/explicit-comparisons'].post;assert.equal(route['x-mop-read-only'],true);assert.deepEqual(route.parameters.map(p=>p.name),['X-CSRF-Token']);assert.deepEqual(route['x-mop-roles'],['padmin']);
});
test('显式中位数空值与0分母不能伪装为0或正式指标',()=>{
 const median={status:'UNDEFINED',reason:'EMPTY_SAMPLE',formula_version:'explicit-elapsed-median-v1',unit:'milliseconds',sample_count:0,unknown_count:0,median_millis:null,duration_basis_verification:'UNVERIFIED_Q27',population_verification:'UNVERIFIED',effective_processing_duration:null};assert.equal(validates('ExplicitOperationalMedian',median),true);
 for(const patch of [{median_millis:'0'},{status:'CALCULATED'},{effective_processing_duration:'0'},{sample_count:null}])assert.equal(validates('ExplicitOperationalMedian',{...median,...patch}),false);
 const staffing={status:'UNDEFINED',reason:'ZERO_FTE',formula_version:'explicit-tenants-per-fte-v1',explicit_total_fte:'0',tenants_per_fte:null,missing_inputs:[],decimal_precision:'DECIMAL128',population_verification:'UNVERIFIED_Q30',staffing_basis_verification:'UNVERIFIED_Q23',effective_tenants_per_fte:null,target_met:null,billing_applicable:false};assert.equal(validates('ExplicitOperationalStaffing',staffing),true);
 for(const patch of [{tenants_per_fte:'0'},{target_met:false},{billing_applicable:true},{explicit_total_fte:'1'},{missing_inputs:['input']}])assert.equal(validates('ExplicitOperationalStaffing',{...staffing,...patch}),false);
});

test('联系人输入完整，角色和级别为不重复集合且未知与空集分开',()=>{
 const v={expected_version:0,name:'合成联系人',roles:['REPORTER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:null,quiet_window:null,enabled:true};assert.equal(validates('CustomerContactCreate',v),true);assert.equal(validates('CustomerContactCreate',{...v,preferred_priorities:[]}),true);assert.equal(validates('CustomerContactCreate',{...v,mobile:'+8613800000000',email:'Example+tag@example.test'}),true);
 for(const roles of [[],null,['REPORTER','REPORTER'],['OTHER'],[null]])assert.equal(validates('CustomerContactCreate',{...v,roles}),false);
 for(const patch of [{expected_version:1},{mobile:'13800000000'},{email:'broken'},{enabled:null},{preferred_priorities:['P1','P1']},{tenant_id:id('2')}])assert.equal(validates('CustomerContactCreate',{...v,...patch}),false);
 const missing=structuredClone(v);delete missing.quiet_window;assert.equal(validates('CustomerContactCreate',missing),false);
});
test('联系人静音组未知、明确关闭和跨午夜窗口不混同',()=>{
 const v={expected_version:0,name:'合成联系人',roles:['DECISION_MAKER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:[],quiet_window:null,enabled:true};
 for(const quiet_window of [null,{enabled:null,start:null,end:null},{enabled:false,start:null,end:null},{enabled:true,start:'22:30',end:'07:15'}])assert.equal(validates('CustomerContactCreate',{...v,quiet_window}),true);
 for(const quiet_window of [{enabled:true,start:null,end:'07:00'},{enabled:false,start:'12:00',end:null},{enabled:true,start:'24:00',end:'07:00'},{enabled:true,start:'7:00',end:'08:00'}])assert.equal(validates('CustomerContactCreate',{...v,quiet_window}),false);
});
test('归档联系人必须停用，不能伪造渠道送达与身份授权',()=>{
 const v={id:id('1'),tenant_id:id('2'),created_by:id('3'),version:2,created_at:'2026-10-03T12:00:00Z',updated_at:'2026-10-03T13:00:00Z',recorded_by:id('3'),recorded_at:'2026-10-03T13:00:00Z',action:'ARCHIVE',archived:true,name:'合成联系人',roles:['REPORTER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:null,quiet_window:null,enabled:false,quiet_timezone:'Asia/Shanghai',contact_verification:'SELF_REPORTED_UNVERIFIED',channel_verification:'NOT_VERIFIED',notification_routing_status:'CONFIG_PENDING',delivery_eligible:false,identity_binding_created:false,notification_dispatched:false,source:'postgresql',record_source:'SELF_REPORTED_CONTACT'};assert.equal(validates('CustomerContact',v),true);
 for(const patch of [{enabled:true},{delivery_eligible:null},{channel_verification:'VERIFIED'},{notification_dispatched:true},{identity_binding_created:true},{action:'CREATE'},{version:1}])assert.equal(validates('CustomerContact',{...v,...patch}),false);
});
test('联系人API只有可恢复归档，无永久删除，版本与重放门禁明确',()=>{
 assert.deepEqual(Object.keys(contract.paths['/contacts/{id}']),['get','patch']);assert.deepEqual(contract.paths['/contacts'].post['x-mop-roles'],['padmin','tadmin']);assert.match(contract.paths['/contacts/{id}/archive'].post['x-mop-replay-authorization'],/before cached receipt/);assert.equal(validates('CustomerContactArchive',{expected_version:1}),true);assert.equal(validates('CustomerContactArchive',{expected_version:1,enabled:false}),false);assert.equal(validates('CustomerContactArchive',{expected_version:9007199254740991}),false);
 assert.equal(validates('CustomerContactPage',{tenant_id:id('1'),items:[],has_more:false,next_before_created_at:null,next_before_id:null,view:'CURRENT',source:'postgresql',scope:'CURRENT_TENANT',page_size_limit:100}),true);
});


test('联系人格式预览接收原始行诊断，根范围和技术边界严格且不是写动作',()=>{
 assert.equal(validates('ContactImportPreviewInput',{rows:[null,{},true,[]]}),true);
 for(const value of [{rows:[]},{rows:Array(101).fill(null)},{rows:[{}],tenant_id:id('2')},{}])assert.equal(validates('ContactImportPreviewInput',value),false);
 const route=contract.paths['/contacts/import-preview'].post;assert.equal(route['x-mop-read-only'],true);assert.equal(route['x-mop-capability'],'contacts:write');assert.deepEqual(route['x-mop-roles'],['padmin','tadmin']);assert.deepEqual(route.parameters.map(p=>p.name),['X-CSRF-Token']);
});
test('联系人预览坏行不能伪造通过或回显资料，预览不等于已保存联系人',()=>{
 const row={row_number:1,status:'INVALID',code:'VALIDATION_ERROR',contact:null,identical_to_row:null};assert.equal(validates('ContactImportPreviewRow',row),true);
 for(const patch of [{status:'FORMAT_VALID'},{code:null},{identical_to_row:1},{row_number:0}])assert.equal(validates('ContactImportPreviewRow',{...row,...patch}),false);
 const facts={name:'synthetic',roles:['REPORTER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:null,quiet_window:null,enabled:true,quiet_timezone:'Asia/Shanghai',contact_verification:'SELF_REPORTED_UNVERIFIED',channel_verification:'NOT_VERIFIED',notification_routing_status:'CONFIG_PENDING',delivery_eligible:null,identity_binding_created:false,notification_dispatched:false,source:'EXPLICIT_INPUT',record_source:'EXPLICIT_NEW_CONTACT_INPUT'};
 assert.equal(validates('ContactImportPreviewFacts',facts),true);for(const patch of [{source:'postgresql'},{id:id('1')},{version:1},{delivery_eligible:true}])assert.equal(validates('ContactImportPreviewFacts',{...facts,...patch}),false);
});
test('联系人预览不能声称导入许可、存储或事务检查已完成',()=>{
 const value={tenant_id:id('2'),scope:'NEW_CONTACT_FORMAT_PREVIEW_ONLY',source:'EXPLICIT_INPUT',rows:[{row_number:1,status:'INVALID',code:'VALIDATION_ERROR',contact:null,identical_to_row:null}],valid_rows:0,invalid_rows:1,identical_rows:0,row_limit:100,row_limit_kind:'TECHNICAL_REQUEST_BOUND',can_import:false,tenant_authorization:'CURRENT_MEMBERSHIP_VERIFIED',existing_contact_check:'NOT_RUN',last_decision_maker_check:'NOT_RUN',notification_routing_status:'CONFIG_PENDING',writes_performed:false,notification_dispatched:false,preview_saved:false,job_created:false};assert.equal(validates('ContactImportPreview',value),true);
 for(const patch of [{can_import:true},{preview_saved:true},{job_created:true},{writes_performed:true},{existing_contact_check:'PASS'},{last_decision_maker_check:'PASS'},{notification_dispatched:true}])assert.equal(validates('ContactImportPreview',{...value,...patch}),false);
});

test('联系人耐久导入202与当前状态分开，全部写保护且无隐式去重', () => {
  const post=contract.paths['/contact-imports'].post;
  assert.ok(post.responses[202]);assert.equal(post.responses[200],undefined);
  assert.deepEqual(post.parameters.map(p=>p.name),['Idempotency-Key','X-CSRF-Token']);
  assert.deepEqual(post['x-mop-roles'],['padmin','tadmin']);assert.equal(post['x-mop-capability'],'contacts:write');
  const row={name:'SYNTHETIC',roles:['REPORTER'],mobile:null,email:null,wecom_user_id:null,preferred_priorities:null,quiet_window:null,enabled:false};
  const body={expected_version:0,mode:'CREATE_ONLY',acknowledge_exact_duplicate_rows:false,rows:[row]};
  assert.ok(validates('ContactImportInput',body));assert.equal(validates('ContactImportInput',{...body,mode:'UPSERT'}),false);assert.equal(validates('ContactImportInput',{...body,tenant_id:id('2')}),false);
  assert.equal(validates('ContactImportInput',{...body,rows:[{...row,enabled:null}]}),false);assert.equal(validates('ContactImportInput',{...body,rows:Array(101).fill(row)}),false);
});
const contactJob={id:id('1'),tenant_id:id('2'),created_by:id('3'),accepted_permission_version:1,input_sha256:'a'.repeat(64),mode:'CREATE_ONLY',row_count:1,status:'PENDING',step:'QUEUED',attempts:0,version:1,completed_rows:null,error:null,next_attempt_at:timestamp,created_at:timestamp,updated_at:timestamp,worker_liveness:null,notification_dispatched:false,identity_binding_created:false,contact_verification:'SELF_REPORTED_UNVERIFIED',source:'postgresql',scope:'CURRENT_CREATOR_TENANT'};
test('导入作业不暴露载荷租约，不把未知Worker和部分结果冒充完成',()=>{
  assert.ok(validates('ContactImportSummary',contactJob));for(const extra of [{worker_liveness:true},{lease_owner:'worker'},{payload_bytes:'private'},{notification_dispatched:true},{status:'SUCCEEDED'}])assert.equal(validates('ContactImportSummary',{...contactJob,...extra}),false);
  assert.ok(validates('ContactImportDetail',{...contactJob,results:[]}));assert.equal(validates('ContactImportDetail',{...contactJob,results:[{row_number:1,contact_id:id('4'),contact_version:1}]}),false);
  const done={...contactJob,status:'SUCCEEDED',step:'APPLIED',attempts:1,version:3,completed_rows:1,next_attempt_at:null,results:[{row_number:1,contact_id:id('4'),contact_version:1}]};assert.ok(validates('ContactImportDetail',done));assert.equal(validates('ContactImportDetail',{...done,results:[]}),false);assert.equal(validates('ContactImportDetail',{...done,results:[{...done.results[0],contact_version:2}]}),false);
});
test('创建者租户分页只返回元数据且游标必须成对',()=>{
  const route=contract.paths['/contact-imports'].get;assert.deepEqual(route['x-mop-paired-query-keys'],['before_created_at','before_id']);assert.equal(route['x-mop-read-only'],true);
  const page={tenant_id:id('2'),created_by:id('3'),items:[contactJob],has_more:false,next_before_created_at:null,next_before_id:null,source:'postgresql',scope:'CURRENT_CREATOR_TENANT',page_size_limit:100};assert.ok(validates('ContactImportPage',page));assert.equal(validates('ContactImportPage',{...page,has_more:true}),false);assert.equal(validates('ContactImportPage',{...page,items:[{...contactJob,results:[]}]}),false);
});

const thresholdInputSample={direction:'HIGH_IS_BAD',warning:{comparison:'GT',threshold:'70',unit:'EXPLICIT_UNIT'},critical:{comparison:'GT',threshold:'85',unit:'EXPLICIT_UNIT'},recovery:{comparison:'LT',threshold:'65',unit:'EXPLICIT_UNIT'},trigger_hold_seconds:'300',recovery_hold_seconds:'60'};
const thresholdResultSample={tenant_id:id('1'),source:'EXPLICIT_INPUT',scope:'EXPLICIT_PREDICATE_DIAGNOSTIC_ONLY',analysis_version:'threshold-half-lines-v1',inputs:thresholdInputSample,status:'CONSISTENT',direction_consistent:true,critical_strict_subset_of_warning:true,recovery_overlaps_warning:false,recovery_overlaps_critical:false,durations_nonnegative:true,issues:[],publishable:false,effective_rule:null,customer_confirmation:'UNVERIFIED',physical_range_check:'NOT_RUN',units_converted:false,monitoring_applied:false,time_series_evaluated:false,rule_saved:false,writes_performed:false,tenant_authorization:'CURRENT_MEMBERSHIP_VERIFIED'};
test('阈值候选诊断仅配置读取权限与CSRF，不产生幂等写或发布',()=>{
 const route=contract.paths['/monitoring/threshold-diagnostics'].post;assert.equal(route['x-mop-read-only'],true);assert.deepEqual(route['x-mop-roles'],['padmin']);assert.equal(route['x-mop-capability'],'configurations:read');assert.deepEqual(route.parameters.map(p=>p.name),['X-CSRF-Token']);assert.ok(validates('ThresholdCandidateInput',thresholdInputSample));assert.ok(validates('ThresholdDiagnostic',thresholdResultSample));for(const flag of ['publishable','monitoring_applied','rule_saved','writes_performed','time_series_evaluated'])assert.equal(validates('ThresholdDiagnostic',{...thresholdResultSample,[flag]:true}),false);
});
test('阈值诊断十进制字符串保精度，未知不转换为默认值',()=>{
 for(const value of [null,'0','-0.5','0.99999999999999999'])assert.ok(validates('ThresholdCandidateInput',{...thresholdInputSample,trigger_hold_seconds:value}));for(const value of [0,0.5,'-0','-0.000','01','1e3','NaN'])assert.equal(validates('ThresholdCandidateInput',{...thresholdInputSample,trigger_hold_seconds:value}),false);assert.equal(validates('ThresholdCandidateInput',{...thresholdInputSample,tenant_id:id('2')}),false);
 const input=Object.fromEntries(Object.keys(thresholdInputSample).map(k=>[k,null]));const unknown={...thresholdResultSample,inputs:input,status:'UNRESOLVED',direction_consistent:null,critical_strict_subset_of_warning:null,recovery_overlaps_warning:null,recovery_overlaps_critical:null,durations_nonnegative:null,issues:[{code:'DIRECTION_MISSING',kind:'UNRESOLVED'}]};assert.ok(validates('ThresholdDiagnostic',unknown));assert.equal(validates('ThresholdDiagnostic',{...unknown,status:'CONSISTENT'}),false);
});
test('阈值冲突须有冲突原因，数学通过不能替代物理范围与客户批准',()=>{
 const conflict={...thresholdResultSample,status:'CONFLICT',direction_consistent:false,issues:[{code:'RECOVERY_DIRECTION_CONFLICT',kind:'CONFLICT'}]};assert.ok(validates('ThresholdDiagnostic',conflict));assert.equal(validates('ThresholdDiagnostic',{...conflict,issues:[]}),false);assert.equal(validates('ThresholdDiagnostic',{...conflict,issues:[{code:'DIRECTION_MISSING',kind:'UNRESOLVED'}]}),false);assert.equal(validates('ThresholdDiagnostic',{...thresholdResultSample,physical_range_check:'VERIFIED'}),false);assert.equal(validates('ThresholdDiagnostic',{...thresholdResultSample,effective_rule:{}}),false);
});

const tenantInventory = { id: id('1'), name: '合成登记租户', created_at: timestamp, registered_asset_count: '0', registered_site_count: '2', active_member_count: '3', ...Object.fromEntries(['isolation_mode','resource_pool','lifecycle_status','asset_quota','monitored_item_count','item_quota','nvps','nvps_quota','data_retention_days','itsm_integration','topology_enabled'].map(k=>[k,null])) };
test('tenant inventory keeps exact bounded counts and never invents pending monitoring/quota fields',()=>{assert.ok(validates('TenantInventory',tenantInventory));for(const value of ['0','9223372036854775807','9007199254740993'])assert.ok(validates('TenantInventory',{...tenantInventory,registered_asset_count:value}));for(const value of [0,-1,'-1','01','1.0','9223372036854775808','9999999999999999999'])assert.equal(validates('TenantInventory',{...tenantInventory,registered_asset_count:value}),false);for(const key of ['lifecycle_status','asset_quota','monitored_item_count','topology_enabled'])assert.equal(validates('TenantInventory',{...tenantInventory,[key]:0}),false)});
test('tenant inventory page requires exact scope, cursor and no creation activation',()=>{const page={context_tenant_id:id('1'),items:[tenantInventory],has_more:false,next_after_id:null,source:'postgresql',scope:'ACTIVE_PADMIN_MEMBERSHIPS_ONLY',page_size_limit:100,pagination_consistency:'CURRENT_REQUEST_SNAPSHOT_ONLY',count_basis:'RECORDED_ROWS_NOT_MONITORED_OR_LICENSED_USAGE',unknown_fields_policy:'EXPLICIT_NULL_NO_INFERENCE',new_tenant_creation_enabled:false};assert.ok(validates('TenantInventoryPage',page));assert.ok(validates('TenantInventoryPage',{...page,has_more:true,next_after_id:id('1')}));for(const patch of [{has_more:true},{next_after_id:id('1')},{scope:'ALL_TENANTS'},{new_tenant_creation_enabled:true},{unexpected:true}])assert.equal(validates('TenantInventoryPage',{...page,...patch}),false)});
test('tenant inventory is GET-only with current and per-target admin authorization',()=>{const route=contract.paths['/tenants/inventory'];assert.deepEqual(Object.keys(route),['get']);assert.deepEqual(route.get['x-mop-roles'],['padmin']);for(const key of ['x-mop-reject-unknown-query-keys','x-mop-reject-duplicate-query-keys','x-mop-permission-version-recheck','x-mop-per-target-padmin-membership-required','x-mop-readonly'])assert.equal(route.get[key],true);assert.deepEqual(route.get.parameters.map(p=>p.name),['page_size','after_id']);assert.equal(route.get['x-mop-query-max-bytes'],512)});


test('ticket defensive presentation documents null gates and retains server authorization',()=>{
  const check=contract.components.schemas.TicketRegistration['x-mop-browser-read-validation'];
  assert.equal(check.tenant,'CAPTURED_CURRENT_TENANT');assert.equal(check.customer_scope,'REPORTER_ONLY');
  assert.equal(check.formal_workflow,'EXPLICIT_NULL_NO_INFERENCE');assert.equal(check.timestamps,'STRICT_UTC_NANOSECOND_PRECISION');
  assert.equal(check.history,'CONTIGUOUS_REGISTRATION_VERSIONS');assert.equal(check.attachments,'UNIQUE_READY_SAME_TENANT_REPORTER_AND_TICKET');
  assert.equal(check.failure_display,'CLEAR_REJECTED_SNAPSHOT');assert.equal(check.authorization,'SERVER_RECHECK_REQUIRED');
  assert.equal(contract.components.schemas.TicketRegistration.additionalProperties,false);
  for(const key of ['state','external_id','external_state','priority','response_due_at','recovery_due_at'])assert.equal(contract.components.schemas.TicketRegistration.properties[key].type,'null');
});

const inspectionItem={key:'firmware',section:'安全',label:'固件',instruction:null};
const inspectionFacts={device_type:'new_device_type',name:'SYNTHETIC_TEMPLATE',item_count:1,content_sha256:'a'.repeat(64),approval_status:'UNVERIFIED',publication_status:'NOT_IMPLEMENTED',report_published:false,active_scan_performed:false,source:'postgresql',record_source:'EXPLICIT_TEMPLATE_DEFINITION'};
const inspectionRow={id:id('1'),tenant_id:id('2'),created_by:id('3'),version:1,created_at:timestamp,updated_at:timestamp,recorded_by:id('3'),recorded_at:timestamp,action:'CREATE',archived:false,...inspectionFacts};
test('inspection template input is explicit, ordered and never an approval request',()=>{
  const body={expected_version:0,device_type:'new_device_type',name:'合成模板',items:[inspectionItem]};assert.ok(validates('InspectionTemplateCreate',body));
  for(const patch of [{approval_status:'APPROVED'},{tenant_id:id('4')},{items:[]},{items:[{...inspectionItem,instruction:42}]},{device_type:'ANY DEVICE'},{expected_version:1}])assert.equal(validates('InspectionTemplateCreate',{...body,...patch}),false);
  assert.ok(validates('InspectionTemplateUpdate',{...body,expected_version:1}));assert.equal(validates('InspectionTemplateUpdate',{...body,expected_version:9007199254740990}),false);
  assert.ok(validates('InspectionTemplateArchive',{expected_version:1}));assert.equal(validates('InspectionTemplateArchive',{expected_version:1,name:'forged'}),false);
});
test('inspection list summaries exclude item content and retain explicit unknown publication',()=>{
  assert.ok(validates('InspectionTemplateSummary',inspectionRow));assert.equal(validates('InspectionTemplateSummary',{...inspectionRow,items:[inspectionItem]}),false);
  assert.ok(validates('InspectionTemplate',{...inspectionRow,items:[inspectionItem]}));
  for(const patch of [{approval_status:'APPROVED'},{publication_status:'PUBLISHED'},{report_published:true},{active_scan_performed:true},{source:'fixture'},{archived:true}])assert.equal(validates('InspectionTemplate',{...inspectionRow,items:[inspectionItem],...patch}),false);
  assert.ok(validates('InspectionTemplate',{...inspectionRow,items:[inspectionItem],action:'ARCHIVE',archived:true}));
});
test('inspection page and history continuation fields cannot claim missing next boundaries',()=>{
  const page={tenant_id:id('2'),items:[inspectionRow],has_more:false,next_before_created_at:null,next_before_id:null,view:'CURRENT',source:'postgresql',scope:'CURRENT_TENANT_PADMIN',page_size_limit:100};assert.ok(validates('InspectionTemplatePage',page));assert.equal(validates('InspectionTemplatePage',{...page,has_more:true}),false);assert.ok(validates('InspectionTemplatePage',{...page,has_more:true,next_before_created_at:timestamp,next_before_id:id('1')}));
  const history={tenant_id:id('2'),template_id:id('1'),items:[{version:1,previous_version:null,recorded_by:id('3'),recorded_at:timestamp,action:'CREATE',archived:false,...inspectionFacts,items:[inspectionItem]}],has_more:false,next_before_version:null,source:'postgresql',scope:'CURRENT_TENANT_PADMIN',page_size_limit:100};assert.ok(validates('InspectionTemplateHistory',history));assert.equal(validates('InspectionTemplateHistory',{...history,has_more:true}),false);assert.equal(validates('InspectionTemplateHistory',{...history,next_before_version:1}),false);
});
test('inspection mutation boundary retains CSRF, idempotency, duplicate-key rejection and bounded input',()=>{
  const route=contract.paths['/inspection-templates'].post;assert.deepEqual(route['x-mop-roles'],['padmin']);assert.equal(route['x-mop-before-replay-authorization'],true);assert.equal(route['x-mop-body-max-bytes'],1048576);assert.equal(route['x-mop-json-max-tokens'],2000);assert.equal(route['x-mop-reject-duplicate-json-keys'],true);assert.ok(route.parameters.some(p=>p.name==='Idempotency-Key'&&p.required));assert.ok(route.parameters.some(p=>p.name.toLowerCase()==='x-csrf-token'&&p.required));assert.equal(contract.paths['/inspection-templates/{id}/publish'],undefined);
});
test('inspection readiness reports structure only and never template approval or report delivery',()=>{
  const schema=contract.components.schemas.ReadinessResult.properties.data;assert.ok(schema.required.includes('inspection_templates'));const facts=schema.properties.inspection_templates.properties;assert.equal(facts.approval_status.const,'UNVERIFIED');assert.equal(facts.publication_status.const,'NOT_IMPLEMENTED');assert.equal(facts.report_published.const,false);assert.equal(facts.active_scan_performed.const,false);
});


test('readiness includes actual contact-import structure without inventing worker liveness',()=>{const schema=contract.components.schemas.ReadinessResult.properties.data;assert.ok(schema.required.includes('contact_imports'));const fields=schema.properties.contact_imports.properties;assert.equal(fields.schema_access_ready.const,true);assert.equal(fields.worker_liveness.type,'null');assert.equal(fields.notification_dispatched.const,false);assert.equal(fields.identity_binding_created.const,false)});

const worksheetCounts={total:1,answered:0,normal:0,attention:0,requires_action:0,unanswered:1,all_results_entered:false};
const worksheetFacts={asset_id:id('5'),asset_name:'合成资产',template_id:id('4'),template_version:1,template_content_sha256:'a'.repeat(64),template_device_type:'custom_device',template_name:'合成模板',template_item_count:1,content_sha256:'b'.repeat(64),reported_performed_at:null,time_source:'NOT_PROVIDED',summary:worksheetCounts,verification_status:'SELF_REPORTED_UNVERIFIED',template_approval_status:'UNVERIFIED',template_applicability_status:'UNVERIFIED',publication_status:'NOT_IMPLEMENTED',report_published:false,active_scan_performed:false,evidence_binding_status:'NOT_IMPLEMENTED',source:'postgresql',record_source:'MANUAL_INSPECTION_DRAFT'};
const worksheetRow={id:id('1'),tenant_id:id('2'),created_by:id('3'),version:1,created_at:timestamp,updated_at:timestamp,recorded_by:id('3'),recorded_at:timestamp,action:'CREATE',archived:false,...worksheetFacts};
test('worksheet creation has explicit source version, unknown time and no report counts or evidence inputs',()=>{
  const body={expected_version:0,asset_id:id('5'),template_id:id('4'),template_version:1,observations:[],reported_performed_at:null};assert.ok(validates('InspectionWorksheetCreate',body));
  for(const patch of [{expected_version:1},{asset_name:'forged'},{tenant_id:id('8')},{template_version:0},{summary:worksheetCounts},{verification_status:'VERIFIED'},{observations:[{key:'a',result:'NORMAL',note:null,evidence_ids:[]}]}])assert.equal(validates('InspectionWorksheetCreate',{...body,...patch}),false);
  assert.ok(validates('InspectionWorksheetUpdate',{expected_version:1,observations:[],reported_performed_at:null}));assert.equal(validates('InspectionWorksheetUpdate',{expected_version:1,observations:[],reported_performed_at:null,asset_id:id('5')}),false);
  assert.ok(validates('InspectionWorksheetArchive',{expected_version:1}));
});
test('worksheet results are explicit three values and notes preserve null or empty',()=>{
  for(const result of ['NORMAL','ATTENTION','REQUIRES_ACTION'])for(const note of [null,'','😀\n备注'])assert.ok(validates('InspectionWorksheetObservation',{key:'firmware',result,note}));
  for(const patch of [{result:'AUTO_NORMAL'},{note:42},{key:'UPPER'},{note:'😀'.repeat(3001)}])assert.equal(validates('InspectionWorksheetObservation',{key:'firmware',result:'NORMAL',note:null,...patch}),false);
});
test('worksheet summary omits large content and detail includes frozen items and only entered answers',()=>{
  assert.ok(validates('InspectionWorksheetSummary',worksheetRow));assert.equal(validates('InspectionWorksheetSummary',{...worksheetRow,observations:[]}),false);
  const row={...worksheetRow,template_items:[inspectionItem],observations:[]};assert.ok(validates('InspectionWorksheet',row));
  for(const patch of [{verification_status:'VERIFIED'},{template_approval_status:'APPROVED'},{template_applicability_status:'VERIFIED'},{report_published:true},{active_scan_performed:true},{evidence_binding_status:'VERIFIED'},{source:'fixture'},{archived:true}])assert.equal(validates('InspectionWorksheet',{...row,...patch}),false);
});
test('worksheet unknown time is not the recording time and completed counts cannot conceal unanswered items',()=>{
  assert.equal(validates('InspectionWorksheetSummary',{...worksheetRow,reported_performed_at:timestamp}),false);assert.ok(validates('InspectionWorksheetSummary',{...worksheetRow,reported_performed_at:timestamp,time_source:'SELF_REPORTED_TIME'}));assert.equal(validates('InspectionWorksheetSummary',{...worksheetRow,time_source:'SELF_REPORTED_TIME'}),false);
  assert.equal(validates('InspectionWorksheetCounts',{...worksheetCounts,all_results_entered:true}),false);assert.ok(validates('InspectionWorksheetCounts',{total:1,answered:1,normal:1,attention:0,requires_action:0,unanswered:0,all_results_entered:true}));
});
test('worksheet pagination and immutable history keep explicit cursor boundaries',()=>{
  const page={tenant_id:id('2'),items:[worksheetRow],has_more:false,next_before_created_at:null,next_before_id:null,view:'CURRENT',source:'postgresql',scope:'CURRENT_TENANT_PADMIN',page_size_limit:20};assert.ok(validates('InspectionWorksheetPage',page));assert.equal(validates('InspectionWorksheetPage',{...page,has_more:true}),false);assert.equal(validates('InspectionWorksheetPage',{...page,page_size_limit:100}),false);
  const history={tenant_id:id('2'),worksheet_id:id('1'),items:[{version:1,previous_version:null,recorded_by:id('3'),recorded_at:timestamp,action:'CREATE',archived:false,...worksheetFacts,template_items:[inspectionItem],observations:[]}],has_more:false,next_before_version:null,source:'postgresql',scope:'CURRENT_TENANT_PADMIN',page_size_limit:20};assert.ok(validates('InspectionWorksheetHistory',history));assert.equal(validates('InspectionWorksheetHistory',{...history,has_more:true}),false);
});
test('worksheet routes declare actual authorization replay guards limits and no publication endpoint',()=>{
  const base=contract.paths['/inspection-worksheets'];assert.deepEqual(base.post['x-mop-roles'],['padmin']);assert.equal(base.post['x-mop-before-replay-authorization'],true);assert.equal(base.post['x-mop-body-max-bytes'],2097152);assert.equal(base.post['x-mop-reject-duplicate-json-keys'],true);assert.deepEqual(base.get.parameters.find(p=>p.name==='page_size').schema.enum,[10,15,20]);
  for(const suffix of ['/publish','/verify','/scan'])assert.equal(contract.paths['/inspection-worksheets/{id}'+suffix],undefined);
  const ready=contract.components.schemas.ReadinessResult.properties.data;assert.ok(ready.required.includes('inspection_worksheets'));assert.equal(ready.properties.inspection_worksheets.properties.verification_status.const,'SELF_REPORTED_UNVERIFIED');assert.equal(ready.properties.inspection_worksheets.properties.report_published.const,false);
});

const worksheetSource={worksheet_id:id('1'),tenant_id:id('2'),version:1,created_by:id('3'),created_at:timestamp,recorded_by:id('3'),recorded_at:timestamp,action:'CREATE',archived:false,source_type:'IMMUTABLE_INSPECTION_WORKSHEET_REVISION',source_format:'MOP_INSPECTION_SOURCE_SNAPSHOT_V1',source_snapshot_sha256:'c'.repeat(64),scope:'CURRENT_TENANT_PADMIN',evidence_access_granted:false,...worksheetFacts,template_items:[inspectionItem],observations:[]};
test('明确历史来源包含完整模板与不可变记录事实，不投影可变头时间',()=>{assert.ok(validates('InspectionWorksheetSource',worksheetSource));for(const patch of [{updated_at:timestamp},{source_type:'CURRENT_HEAD'},{source_format:'v2'},{source_snapshot_sha256:'broken'},{evidence_access_granted:true},{report_published:true},{verification_status:'VERIFIED'}])assert.equal(validates('InspectionWorksheetSource',{...worksheetSource,...patch}),false);});
test('来源修订生命周期和未知自述时间保持原事实',()=>{assert.equal(validates('InspectionWorksheetSource',{...worksheetSource,version:2}),false);assert.ok(validates('InspectionWorksheetSource',{...worksheetSource,version:2,action:'ARCHIVE',archived:true}));assert.equal(validates('InspectionWorksheetSource',{...worksheetSource,action:'ARCHIVE',archived:false}),false);assert.equal(validates('InspectionWorksheetSource',{...worksheetSource,reported_performed_at:timestamp}),false);});
test('历史下钻是显式版本只读事务，不提供发布或隐式latest路径',()=>{const p=contract.paths['/inspection-worksheets/{id}/revisions/{version}'];assert.deepEqual(Object.keys(p),['get']);assert.equal(p.get['x-mop-explicit-version-only'],true);assert.equal(p.get['x-mop-success-writes-audit-or-receipts'],false);assert.equal(p.get['x-mop-read-consistency'],'READ_ONLY_REPEATABLE_READ');assert.deepEqual(p.get['x-mop-roles'],['padmin']);assert.equal(p.get.parameters.find(x=>x.name==='version').required,true);assert.equal(contract.paths['/inspection-worksheets/{id}/revisions/latest'],undefined);});

test('来源拒绝读取仍保留安全审计，成功来源读取不写业务审计或原请求收据',()=>{const op=contract.paths['/inspection-worksheets/{id}/revisions/{version}'].get;assert.equal(op['x-mop-success-writes-audit-or-receipts'],false);assert.equal(op['x-mop-denied-read-security-audit'],'EXISTING_DENIED_REQUEST_AUDIT');});

const sourceCollectionMeta={id:id('7'),tenant_id:id('2'),created_by:id('3'),recorded_at:timestamp,version:1,label:'合成来源',source_count:1,document_format:'MOP_INSPECTION_SOURCE_DOCUMENT_V1',document_sha256:'a'.repeat(64),source_manifest_format:'MOP_INSPECTION_SOURCE_MANIFEST_V1',source_manifest_sha256:'b'.repeat(64),projection:'METADATA_ONLY',record_source:'EXPLICIT_IMMUTABLE_SOURCE_COLLECTION',verification_status:'SELF_REPORTED_UNVERIFIED',scope:'CURRENT_TENANT_PADMIN',source:'postgresql',evidence_access_granted:false,period_time_zone:null,quarter_selection_rule:null,archived_source_policy:null,metric_version:null,report_template_version:null,tenant_asset_population:null,generation_status:'CONFIG_PENDING',formal_report_generatable:false,report_published:false};
const sourceCollectionRef={worksheet_id:id('1'),version:1,source_snapshot_sha256:'c'.repeat(64),content_sha256:'b'.repeat(64)};
const sourceCollectionFull={...sourceCollectionMeta,projection:'FULL_SOURCES',document_integrity:'VERIFIED_SERVER_SIDE',sources:[worksheetSource],diagnostics:{distinct_selected_assets:1,unknown_inspection_time:[sourceCollectionRef],incompletely_entered:[sourceCollectionRef],archived_selected_revisions:[],assets_with_multiple_sources:[]}};
test('来源集合创建只接受明确修订与预期摘要，不允许业务口径或发布覆盖',()=>{const input={expected_version:0,label:'人工选择',sources:[{worksheet_id:id('1'),version:1,expected_source_sha256:'a'.repeat(64)}]};assert.ok(validates('InspectionSourceCollectionCreate',input));assert.ok(validates('InspectionSourceCollectionCreate',{...input,sources:[]}));for(const patch of [{expected_version:1},{tenant_id:id('2')},{quarter_selection_rule:'latest'},{report_published:true},{label:'😀'.repeat(201)},{sources:[{...input.sources[0],expected_source_sha256:'A'.repeat(64)}]},{sources:[{...input.sources[0],version:0}]},{sources:[input.sources[0],input.sources[0]]}])assert.equal(validates('InspectionSourceCollectionCreate',{...input,...patch}),false);assert.deepEqual(contract.components.schemas.InspectionSourceCollectionCreate.properties.sources['x-mop-unique-by'],['worksheet_id','version']);});
test('集合摘要不宣称读取正文，详情包含完整冻结来源和缺值诊断',()=>{assert.ok(validates('InspectionSourceCollectionSummary',sourceCollectionMeta));assert.equal(validates('InspectionSourceCollectionSummary',{...sourceCollectionMeta,document_integrity:'VERIFIED_SERVER_SIDE'}),false);assert.equal(validates('InspectionSourceCollectionSummary',{...sourceCollectionMeta,sources:[]}),false);assert.ok(validates('InspectionSourceCollectionDetail',sourceCollectionFull));for(const patch of [{sources:[]},{projection:'METADATA_ONLY'},{document_integrity:'NOT_READ'},{report_published:true},{formal_report_generatable:true},{tenant_asset_population:0},{period_time_zone:'UTC'},{generation_status:'READY'}])assert.equal(validates('InspectionSourceCollectionDetail',{...sourceCollectionFull,...patch}),false);});
test('空来源集合只能描述空选择，不能变成零资产库存或已生成报告',()=>{const empty={...sourceCollectionFull,source_count:0,sources:[],diagnostics:{distinct_selected_assets:0,unknown_inspection_time:[],incompletely_entered:[],archived_selected_revisions:[],assets_with_multiple_sources:[]}};assert.ok(validates('InspectionSourceCollectionDetail',empty));assert.equal(validates('InspectionSourceCollectionDetail',{...empty,sources:[worksheetSource]}),false);assert.equal(validates('InspectionSourceCollectionDetail',{...empty,tenant_asset_population:0}),false);assert.equal(validates('InspectionSourceCollectionDetail',{...empty,diagnostics:{...empty.diagnostics,distinct_selected_assets:1}}),false);});
test('来源集合分页元数据有成对游标和20条保护',()=>{const page={data:{tenant_id:id('2'),items:[sourceCollectionMeta],has_more:false,next_before_recorded_at:null,next_before_id:null,page_size_limit:20,scope:'CURRENT_TENANT_PADMIN',source:'postgresql'},trace_id:'test'};assert.ok(validates('InspectionSourceCollectionPageResult',page));assert.equal(validates('InspectionSourceCollectionPageResult',{...page,data:{...page.data,has_more:true}}),false);assert.ok(validates('InspectionSourceCollectionPageResult',{...page,data:{...page.data,has_more:true,next_before_recorded_at:timestamp,next_before_id:id('7')}}));assert.equal(validates('InspectionSourceCollectionPageResult',{...page,data:{...page.data,items:Array(21).fill(sourceCollectionMeta)}}),false);});
test('来源集合路径声明当前鉴权原收据、CSRF与限额且没有更新发布端点',()=>{const base=contract.paths['/inspection-source-collections'];assert.deepEqual(Object.keys(base).sort(),['get','post']);assert.deepEqual(base.post['x-mop-roles'],['padmin']);assert.equal(base.post['x-mop-before-replay-authorization'],true);assert.equal(base.post['x-mop-body-max-bytes'],262144);assert.equal(base.post['x-mop-json-max-tokens'],12000);assert.ok(base.post.parameters.some(p=>p.name==='X-CSRF-Token'&&p.required));assert.ok(base.post.parameters.some(p=>p.name==='Idempotency-Key'&&p.required));assert.deepEqual(Object.keys(contract.paths['/inspection-source-collections/{id}']),['get']);assert.equal(base.get['x-mop-success-writes-audit-or-receipts'],false);assert.equal(base.get['x-mop-denied-read-security-audit'],'EXISTING_DENIED_REQUEST_AUDIT');for(const suffix of ['/publish','/update','/delete'])assert.equal(contract.paths['/inspection-source-collections/{id}'+suffix],undefined);});
test('集合结构就绪不等于已核验来源或可以生成正式报告',()=>{const ready=contract.components.schemas.ReadinessResult.properties.data;assert.ok(ready.required.includes('inspection_source_collections'));const fields=ready.properties.inspection_source_collections.properties;assert.equal(fields.schema_access_ready.const,true);assert.equal(fields.verification_status.const,'SELF_REPORTED_UNVERIFIED');assert.equal(fields.generation_status.const,'CONFIG_PENDING');assert.equal(fields.formal_report_generatable.const,false);assert.equal(fields.report_published.const,false);});
test('集合未选报告周期与已固定平台业务时区是不同事实',()=>{const fixed=baseline.settings.find(x=>x.key==='business.timezone');assert.equal(fixed.value,'Asia/Shanghai');assert.equal(fixed.decision_status,'contract_fixed');for(const name of ['InspectionSourceCollectionSummary','InspectionSourceCollectionDetail']){const schema=contract.components.schemas[name];assert.equal(schema['x-mop-business-timezone'],fixed.value);assert.equal(schema.properties.period_time_zone.type,'null');assert.ok(schema.properties.period_time_zone.description.includes('No report period is selected'));}assert.ok(validates('InspectionSourceCollectionSummary',sourceCollectionMeta));assert.equal(validates('InspectionSourceCollectionSummary',{...sourceCollectionMeta,period_time_zone:'Asia/Shanghai'}),false);});
