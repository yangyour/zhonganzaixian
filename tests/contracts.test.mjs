import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const contract = read('../contracts/local-api.openapi.json');
const baseline = read('../contracts/config-baseline.json');
test('the published contract remains reproducible from source generators', () => {
  assert.doesNotThrow(() => execFileSync(process.execPath,
    [fileURLToPath(new URL('../tools/build-api-contract.mjs', import.meta.url)), '--check'],
    { stdio: ['ignore', 'pipe', 'pipe'] }));
});
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

test('容量运行参数只暴露固定七项及版本来源，未知和未验证不冒充实测',()=>{
  const op=contract.paths['/capacity/runtime-inputs'].get;assert.equal(op['x-mop-capability'],'capacity:estimate');assert.equal(op['x-mop-read-only'],true);
  const keys=['monitor.sample_interval_sec','retention.history_days','retention.trend_days','capacity.bytes_per_value','capacity.trend_ratio','capacity.headroom','capacity.proxy_outage_sec'];
  const data={tenant_id:id('1'),actor_id:id('2'),permission_version:1,checked_at:'2026-10-15T00:00:00Z',items:keys.map(configuration_key=>({configuration_key,status:'UNCONFIGURED',value:null,input_value:null,unit:'test',definition_hash:'a'.repeat(64),publication_id:null,publication_version:null,expires_at:null})),scope:'CURRENT_TENANT_CAPACITY_INPUTS',source:'postgresql',customer_confirmation_status:'UNVERIFIED',production_approved:false,measurement_verified:false};
  assert.ok(validates('CapacityRuntimeInputs',{data,trace_id:'synthetic'}));
  for(const patch of [{measurement_verified:true},{items:data.items.slice(1)},{items:[{...data.items[0],published_by:id('3')},...data.items.slice(1)]},{source:'fixture'}])assert.equal(validates('CapacityRuntimeInputs',{data:{...data,...patch},trace_id:'synthetic'}),false);
});

test('监控查询的越权、参数和依赖失败声明统一错误信封，不能只有描述', () => {
  for (const path of ['/network-monitoring', '/network-monitoring/{assetId}/history', '/network-monitoring/{assetId}/resources', '/network-monitoring/{assetId}/resources/history']) {
    for (const status of ['400','401','403','404','422','503']) {
      assert.equal(contract.paths[path].get.responses[status].content?.['application/json']?.schema?.$ref, '#/components/schemas/Error', path + ':' + status);
    }
  }
});
test('导出发布必须显式确认文件摘要和当前版本，原准备受理仍独立',()=>{
  const input={expected_version:2,file_sha256:'a'.repeat(64),action:'PUBLISH_PREPARED_FILE'};
  assert.ok(validates('AssetExportPublishInput',input));
  for(const patch of [{expected_version:0},{action:'PUBLISH_ALL'},{file_sha256:'bad'},{tenant_id:id('1')}])assert.equal(validates('AssetExportPublishInput',{...input,...patch}),false);
  assert.equal(contract.paths['/asset-export-jobs/{id}/publication'].post['x-mop-current-membership-before-replay'],true);
  assert.ok(contract.paths['/asset-export-jobs/{id}/content-grants'].post.parameters.some(x=>x.name==='X-CSRF-Token'));
});
test('导出发布读回不把未准备、未发布或关闭配置伪装成可下载',()=>{
  const p={job_id:id('1'),tenant_id:id('2'),created_by:id('3'),permission_version:1,version:2,status:'NOT_PUBLISHED',prepared:true,file_sha256:'a'.repeat(64),size_bytes:20,row_count:0,stored_at:null,observed_at:'2026-10-14T00:00:00Z',publication_enabled:true,download_grant_available:false,notification_sent:false,source:'postgresql'};
  assert.ok(validates('AssetExportPublication',p));
  for(const patch of [{prepared:false},{status:'PUBLISHED'},{download_grant_available:true},{notification_sent:true}])assert.equal(validates('AssetExportPublication',{...p,...patch}),false);
  assert.ok(validates('AssetExportPublication',{...p,status:'PUBLISHED',stored_at:p.observed_at,download_grant_available:true}));
  assert.equal(validates('AssetExportPublication',{...p,status:'PUBLISHED',stored_at:p.observed_at,publication_enabled:false,download_grant_available:true}),false);
});
test('导出链接仅允许平台路径，下载使用二次授权及附件类型',()=>{
  const data={job_id:id('1'),content_url:`/api/v1/asset-export-jobs/${id('1')}/content?grant=1791936900000.${'a'.repeat(43)}`,expires_at:'2026-10-14T00:15:00Z',size_bytes:20,file_sha256:'a'.repeat(64)};
  assert.ok(validates('AssetExportDownloadGrantResult',{data,trace_id:'test'}));
  assert.equal(validates('AssetExportDownloadGrantResult',{data:{...data,content_url:'https://example.test/object'},trace_id:'test'}),false);
  const op=contract.paths['/asset-export-jobs/{id}/content'].get;assert.equal(op['x-mop-store-before-and-after-authorization'],true);assert.ok(op.responses[200].content['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet']);
});
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
      const readonlyPreview = method === 'post' && ['/working-calendars/{id}/preview', '/sla/compliance-scenarios', '/capacity/estimates', '/operations/explicit-comparisons', '/contacts/import-preview', '/monitoring/threshold-diagnostics', '/monitoring/observation-window-diagnostics', '/runtime-configurations/{key}/validate', '/monitoring-onboarding/registered-targets/{id}/test', '/monitoring-onboarding/plans'].includes(path);
      if (readonlyPreview) assert.equal(operation['x-mop-read-only'], true);
      for (const name of readonlyPreview || ['/auth/local/login','/auth/wecom/start'].includes(path) ? ['X-CSRF-Token'] : ['Idempotency-Key', 'X-CSRF-Token']) {
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
  assert.deepEqual(contract.components.schemas.TicketRegistration.properties.history.items.properties.action.enum, ['REGISTER', 'MONITORING_REGISTER', 'LEGACY_MONITORING_REGISTER', 'ADD_ATTACHMENTS']);
});

test('就绪成功不能把已启用但缺schema或存储的照片能力写成READY', () => {
  const photos = { enabled: true, ready: true, policy_ready: true, schema_ready: true, storage_ready: true, policy_source: 'USER_DEVELOPMENT_DIRECTION', reasons: [] };
  const data = { own_notification_preferences:{schema_access_ready:true,configuration_status:"CONFIG_PENDING",effective:false,receiver_binding_status:"NOT_VERIFIED",notification_dispatched:false}, asset_exports:{schema_access_ready:true,mode:"PREPARE_ONLY_NO_PUBLICATION",worker_liveness:null,publication_status:"NOT_IMPLEMENTED",download_allowed:false}, change_notice_drafts: {schema_access_ready:true,approval_status:null,automatic_p3_decision:null,approval_workflow_status:"CONFIG_PENDING",matching_policy_status:"CONFIG_PENDING",effective:false,device_execution_allowed:false,alert_suppression_allowed:false}, backup_plan_drafts: {schema_access_ready:true,effective:false,device_execution_allowed:false,credential_verification:"NOT_VERIFIED",scheduler_running:null}, inspection_source_collections: { schema_access_ready: true, verification_status: 'SELF_REPORTED_UNVERIFIED', generation_status: 'CONFIG_PENDING', formal_report_generatable: false, report_published: false }, inspection_worksheets: { schema_access_ready: true, verification_status: 'SELF_REPORTED_UNVERIFIED', evidence_binding_status: 'NOT_IMPLEMENTED', publication_status: 'NOT_IMPLEMENTED', report_published: false, active_scan_performed: false }, contact_imports: { schema_access_ready: true, worker_liveness: null, notification_dispatched: false, identity_binding_created: false }, inspection_templates: { schema_access_ready: true, approval_status: 'UNVERIFIED', publication_status: 'NOT_IMPLEMENTED', report_published: false, active_scan_performed: false }, customer_contacts: { schema_access_ready: true, notification_dispatched: false, channel_verification: 'NOT_VERIFIED', identity_binding_created: false }, status: 'READY_REMOTE_TEST', ready: true, mode: 'remote_test', source: 'postgresql', durable: true, production_ready: false, capacity_scenarios: { schema_access_ready: true, effective: false, measurement_verified: false, poc_executed: false }, working_calendars: { schema_access_ready: true, effective: false, customer_confirmation: 'UNVERIFIED' }, manual_incidents: { schema_access_ready: true, source_state: 'MANUAL', registration_status: 'REGISTERED_ONLY', monitoring_recovery_applicable: false, ticket_automatically_created: false }, manual_recovery: { schema_access_ready: true, source: 'HUMAN_CONFIRMATION', monitoring_recovery_applicable: false, ticket_state_changed: false, sla_changed: false }, photos, asset_reads: { unicode_search_ready: true, pagination: 'BOUNDED_POSTGRESQL_KEYSET' }, ticket_comments: { schema_access_ready: true, visibility: 'REPORTER_AND_INTERNAL', internal_identity_exposed: false, itsm_applied: false }, internal_notes: { schema_access_ready: true, visibility: 'INTERNAL', ticket_state_changed: false, sla_changed: false }, asset_manual_metadata: { schema_access_ready: true, scope: 'INTERNAL_MANUAL_DRAFT', monitoring_applied: false, cmdb_applied: false }, worklogs: { ready: true, schema_access_ready: true, financial_status: 'CONFIG_PENDING' }, asset_dependencies: { ready: true, schema_access_ready: true, configuration_status: 'CONFIG_PENDING', monitoring_applied: false, effective_rules: null }, asset_imports: { enabled: false, ready: false, policy_ready: false, schema_ready: false, storage_ready: false, worker_activity: 'NOT_PROBED', business_status: 'CONFIG_PENDING', can_import: false, reasons: ['ASSET_IMPORT_NOT_CONFIGURED'] } };
  data.runtime_configurations = { schema_access_ready: true, scope: 'CURRENT_TENANT', customer_confirmation_status: 'UNVERIFIED' };
  data.inspection_quarterly_reports = { schema_access_ready:true, configuration_source:'REQUEST_DRAFT_NOT_APPROVED', generation_status:'DRAFT', report_published:false, report_publishable:false, evidence_access_granted:false };
  data.change_notice_approvals = { schema_access_ready: true, record_source: 'REVISION_REVIEW_ONLY', effective: false, device_execution_allowed: false, alert_suppression_allowed: false };
  assert.equal(validates('ReadinessResult', { data, trace_id: 'MOP-FAKE-readiness' }), true);
  for (const key of ['policy_ready', 'schema_ready', 'storage_ready']) assert.equal(validates('ReadinessResult', { data: { ...data, photos: { ...photos, [key]: false } }, trace_id: 'MOP-FAKE-readiness' }), false);
  assert.equal(validates('ReadinessResult', { data: { ...data, production_ready: true }, trace_id: 'MOP-FAKE-readiness' }), false);
  assert.equal(validates('ReadinessResult', { data: { ...data, change_notice_approvals: { ...data.change_notice_approvals, effective: true } }, trace_id: 'MOP-FAKE-readiness' }), false);
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
  assert.equal(contract.info.version, '0.85.0');
  assert.equal(contract.info.version, '0.85.0');
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
  const value = { query: 'Ä%_', tenant_id: id('1'), observed_at: timestamp, scope: 'CURRENT_TENANT_AUTHORIZED_GROUPED_SEARCH', coverage: 'PARTIAL', coverage_reason: 'AUTOMATION_SOURCE_NOT_IMPLEMENTED_AND_EVENT_NOT_AUTHORIZED', query_semantics: 'LITERAL_UNICODE_CASE_INSENSITIVE', snapshot_consistency: 'ONE_READONLY_TRANSACTION', source: 'postgresql_supported_groups', notification_bell_status: 'NOT_IMPLEMENTED', groups: [supported('assets', 5, 'CURRENT_TENANT', ['name']), { ...unsupported('events', 4), status: 'NOT_AUTHORIZED', reason: 'INTERNAL_EVENT_READ_REQUIRED' }, supported('tickets', 3, 'OWN_REPORT_REGISTRATIONS', ['title', 'receipt_no']), unsupported('automation_scripts', 3)] };
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
  assert.equal(schema.properties.configuration_driven_authorization.type, 'boolean'); assert.equal(schema.properties.production_authority_verified.const, false); assert.equal(schema.properties.resource_authorization_required.const, true); assert.equal(schema.properties.subject_bindings_included.const, false);
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

const backupDraftInput={expected_version:0,name:'MOP-SYNTHETIC backup proposal',target_asset_ids:[id('1'),id('2')],daily_local_time:'02:00',credential_ref:null,requested_enabled:true,note:null};
const backupDraftFacts={name:backupDraftInput.name,target_count:2,daily_local_time:'02:00',timezone:'Asia/Shanghai',credential_ref:null,credential_verification:'NOT_SUPPLIED',requested_enabled:true,enabled:false,effective:false,lifecycle_status:'DRAFT',approval_status:'UNVERIFIED',device_execution_allowed:false,next_execution_at:null,proposed_retry_count:2,proposed_retry_interval_minutes:10,retry_policy_source:'CONTRACT_CONFIGURABLE_DEFAULT_NOT_EFFECTIVE',source:'postgresql',record_source:'UNPUBLISHED_BACKUP_PLAN_DRAFT'};
const backupDraftMeta={id:id('3'),tenant_id:id('4'),version:1,archived:false,recorded_by:id('5'),created_at:timestamp,updated_at:timestamp,recorded_at:timestamp,...backupDraftFacts};
test('备份计划草稿输入明确且不接受生效或秘密字段',()=>{assert.ok(validates('BackupPlanDraftCreate',backupDraftInput));for(const patch of [{expected_version:1},{enabled:true},{tenant_id:id('6')},{password:'synthetic'},{requested_enabled:null},{target_asset_ids:[]},{target_asset_ids:[id('1'),id('1')]},{daily_local_time:'2:00'},{daily_local_time:'24:00'},{credential_ref:'raw-key'}])assert.equal(validates('BackupPlanDraftCreate',{...backupDraftInput,...patch}),false);for(const key of Object.keys(backupDraftInput)){const n={...backupDraftInput};delete n[key];assert.equal(validates('BackupPlanDraftCreate',n),false);}assert.ok(validates('BackupPlanDraftUpdate',{...backupDraftInput,expected_version:1}));assert.equal(validates('BackupPlanDraftUpdate',backupDraftInput),false);});
test('计划归档与恢复只接受版本且保持原提案',()=>{assert.ok(validates('BackupPlanDraftLifecycle',{expected_version:1}));for(const n of [{expected_version:0},{expected_version:9007199254740990},{expected_version:1,name:'changed'},{expected_version:1,requested_enabled:false}])assert.equal(validates('BackupPlanDraftLifecycle',n),false);for(const action of ['archive','restore'])assert.equal(contract.paths[`/backup-plan-drafts/{id}/${action}`].post.requestBody.content['application/json'].schema.$ref,'#/components/schemas/BackupPlanDraftLifecycle');});
test('备份计划摘要省略明细并始终未启用，凭据引用不代表可用',()=>{assert.ok(validates('BackupPlanDraftSummary',backupDraftMeta));const detail={...backupDraftMeta,target_asset_ids:backupDraftInput.target_asset_ids,note:null};assert.ok(validates('BackupPlanDraftDetail',detail));assert.equal(validates('BackupPlanDraftSummary',detail),false);for(const patch of [{enabled:true},{effective:true},{device_execution_allowed:true},{next_execution_at:timestamp},{approval_status:'APPROVED'},{credential_verification:'VERIFIED'},{timezone:'UTC'},{proposed_retry_count:3}])assert.equal(validates('BackupPlanDraftDetail',{...detail,...patch}),false);assert.ok(validates('BackupPlanDraftDetail',{...detail,credential_ref:id('6'),credential_verification:'NOT_VERIFIED_BY_THIS_MODEL'}));assert.equal(validates('BackupPlanDraftDetail',{...detail,credential_ref:id('6')}),false);});
test('计划历史保留版本动作及归档事实，不误报自动恢复',()=>{const revision={plan_id:id('3'),tenant_id:id('4'),version:1,previous_version:null,action:'CREATE',archived:false,recorded_by:id('5'),recorded_at:timestamp,...backupDraftFacts,target_asset_ids:backupDraftInput.target_asset_ids,note:null};assert.ok(validates('BackupPlanDraftRevision',revision));assert.equal(validates('BackupPlanDraftRevision',{...revision,version:2}),false);assert.ok(validates('BackupPlanDraftRevision',{...revision,version:2,previous_version:1,action:'ARCHIVE',archived:true}));assert.equal(validates('BackupPlanDraftRevision',{...revision,version:2,previous_version:1,action:'ARCHIVE',archived:false}),false);assert.ok(validates('BackupPlanDraftRevision',{...revision,version:3,previous_version:2,action:'RESTORE',archived:false}));});
test('备份计划分页上限20并要求成对明确游标',()=>{const p={data:{tenant_id:id('4'),items:[backupDraftMeta],has_more:false,next_before_created_at:null,next_before_id:null,scope:'CURRENT_TENANT_PADMIN',source:'postgresql',page_size_limit:20},trace_id:'synthetic'};assert.ok(validates('BackupPlanDraftPageResult',p));assert.equal(validates('BackupPlanDraftPageResult',{...p,data:{...p.data,has_more:true}}),false);assert.ok(validates('BackupPlanDraftPageResult',{...p,data:{...p.data,has_more:true,next_before_created_at:timestamp,next_before_id:id('3')}}));assert.equal(validates('BackupPlanDraftPageResult',{...p,data:{...p.data,items:Array(21).fill(backupDraftMeta)}}),false);});
test('计划草稿路径保持当前鉴权、CSRF、原键与执行隔离',()=>{const base=contract.paths['/backup-plan-drafts'];assert.deepEqual(base.post['x-mop-roles'],['padmin']);assert.equal(base.post['x-mop-before-replay-authorization'],true);assert.equal(base.post['x-mop-body-max-bytes'],262144);assert.equal(base.post['x-mop-json-max-tokens'],4000);assert.ok(base.post.parameters.some(p=>p.name==='X-CSRF-Token'&&p.required));assert.ok(base.post.parameters.some(p=>p.name==='Idempotency-Key'&&p.required));assert.deepEqual(base.post['x-mop-no-side-effects'],['DEVICE_EXECUTION','CREDENTIAL_CONFIGURATION','SCHEDULING','OBJECT_STORAGE']);assert.equal(base.get['x-mop-read-consistency'],'READ_ONLY_REPEATABLE_READ');for(const suffix of ['/execute','/enable','/publish'])assert.equal(contract.paths['/backup-plan-drafts/{id}'+suffix],undefined);const ready=contract.components.schemas.ReadinessResult.properties.data;assert.ok(ready.required.includes('backup_plan_drafts'));assert.equal(ready.properties.backup_plan_drafts.properties.device_execution_allowed.const,false);assert.equal(ready.properties.backup_plan_drafts.properties.scheduler_running.type,'null');});

const noticeInput={expected_version:0,change_summary:'合成报备\n原文',asset_ids:[id('1'),id('2')],planned_start_at:timestamp,planned_end_at:null,ticket_id:null};
const noticeMeta={id:id('3'),tenant_id:id('4'),version:1,archived:false,recorded_by:id('5'),created_at:timestamp,updated_at:timestamp,recorded_at:timestamp,applicant_id:id('5'),ticket_id:null,asset_count:2,planned_start_at:timestamp,planned_end_at:null,content_sha256:'a'.repeat(64),timezone:'Asia/Shanghai',effective:false,lifecycle_status:'DRAFT',approval_status:null,approval_workflow_status:'CONFIG_PENDING',matching_policy_status:'CONFIG_PENDING',automatic_p3_decision:null,approval_evidence_verified:false,device_execution_allowed:false,alert_suppression_allowed:false,source:'postgresql',record_source:'UNREVIEWED_CHANGE_NOTICE_DRAFT'};
test('报备草稿输入拒绝申请人和批准覆盖，并要求显式可空字段',()=>{assert.ok(validates('ChangeNoticeDraftCreate',noticeInput));for(const patch of [{expected_version:1},{applicant_id:id('6')},{approval_status:'APPROVED'},{automatic_p3_decision:false},{tenant_id:id('6')},{asset_ids:[]},{asset_ids:[id('1'),id('1')]},{planned_start_at:'02:00'},{ticket_id:'raw-key'}])assert.equal(validates('ChangeNoticeDraftCreate',{...noticeInput,...patch}),false);for(const key of Object.keys(noticeInput)){const n={...noticeInput};delete n[key];assert.equal(validates('ChangeNoticeDraftCreate',n),false);}assert.ok(validates('ChangeNoticeDraftUpdate',{...noticeInput,expected_version:1}));assert.equal(validates('ChangeNoticeDraftUpdate',noticeInput),false);});
test('报备元数据不含原文和设备，批准匹配与P3保持未知',()=>{assert.ok(validates('ChangeNoticeDraftSummary',noticeMeta));const detail={...noticeMeta,asset_ids:noticeInput.asset_ids,change_summary:noticeInput.change_summary};assert.ok(validates('ChangeNoticeDraftDetail',detail));assert.equal(validates('ChangeNoticeDraftSummary',detail),false);for(const patch of [{effective:true},{device_execution_allowed:true},{approval_status:'PENDING'},{automatic_p3_decision:false},{matching_policy_status:'READY'},{approval_workflow_status:'READY'},{approval_evidence_verified:true},{alert_suppression_allowed:true},{timezone:'UTC'},{content_sha256:'wrong'}])assert.equal(validates('ChangeNoticeDraftDetail',{...detail,...patch}),false);});
test('报备历史区分创建归档恢复并保留原申请人',()=>{const {id:notice_id,created_at,updated_at,...facts}=noticeMeta;const r={...facts,notice_id,previous_version:null,action:'CREATE',asset_ids:noticeInput.asset_ids,change_summary:noticeInput.change_summary};assert.ok(validates('ChangeNoticeDraftRevision',r));assert.equal(validates('ChangeNoticeDraftRevision',{...r,action:'RESTORE'}),false);assert.ok(validates('ChangeNoticeDraftRevision',{...r,version:2,previous_version:1,action:'ARCHIVE',archived:true}));assert.equal(validates('ChangeNoticeDraftRevision',{...r,version:2,previous_version:1,action:'ARCHIVE'}),false);assert.ok(validates('ChangeNoticeDraftLifecycle',{expected_version:1}));assert.equal(validates('ChangeNoticeDraftLifecycle',{expected_version:1,change_summary:'alter'}),false);});
test('报备草稿分页游标成对且最多20项',()=>{const p={data:{tenant_id:id('4'),items:[noticeMeta],has_more:false,next_before_created_at:null,next_before_id:null,scope:'CURRENT_TENANT_PADMIN',source:'postgresql',page_size_limit:20},trace_id:'synthetic'};assert.ok(validates('ChangeNoticeDraftPageResult',p));assert.equal(validates('ChangeNoticeDraftPageResult',{...p,data:{...p.data,has_more:true}}),false);assert.ok(validates('ChangeNoticeDraftPageResult',{...p,data:{...p.data,has_more:true,next_before_created_at:timestamp,next_before_id:id('3')}}));assert.equal(validates('ChangeNoticeDraftPageResult',{...p,data:{...p.data,items:Array(21).fill(noticeMeta)}}),false);});
test('报备草稿同步保护入口与正式报备审批路径分离',()=>{for(const suffix of ['', '/{id}', '/{id}/history', '/{id}/archive', '/{id}/restore'])assert.ok(contract.paths['/change-notice-drafts'+suffix]);const post=contract.paths['/change-notice-drafts'].post;assert.ok(post.responses['200']);assert.equal(post.responses['202'],undefined);assert.equal(post['x-mop-replay'],'CURRENT_AUTHORIZATION_THEN_ORIGINAL_RECEIPT');assert.equal(post['x-mop-applicant'],'CREATE_FROM_CURRENT_ACTOR_UPDATE_PRESERVES_ORIGINAL');assert.equal(post['x-mop-body-max-bytes'],262144);assert.ok(post['x-mop-no-side-effects'].includes('ALERT_SUPPRESSION'));assert.equal(contract.paths['/change-notices'],undefined);});
test('报备结构就绪只表示schema访问，不能代表审批或事件链健康',()=>{const s=contract.components.schemas.ReadinessResult.properties.data.properties.change_notice_drafts;assert.equal(s.properties.schema_access_ready.const,true);for(const key of ['approval_status','automatic_p3_decision'])assert.equal(s.properties[key].type,'null');for(const key of ['effective','device_execution_allowed','alert_suppression_allowed'])assert.equal(s.properties[key].const,false);assert.equal(s.properties.matching_policy_status.const,'CONFIG_PENDING');});

const ownFilters={ticket_id:null,kind:null,started_from:null,started_before:null};
test('本人跨工单查询只读且不授予团队或聚合权限',()=>{const route=contract.paths['/my/worklogs'];assert.deepEqual(Object.keys(route),['get']);assert.deepEqual(route.get['x-mop-roles'],['msp']);assert.equal(route.get['x-mop-capability'],'worklogs:read_own');assert.equal(route.get['x-mop-readonly'],true);assert.equal(route.get['x-mop-success-writes-audit-or-receipts'],false);assert.equal(route.get['x-mop-max-encoded-query-bytes'],2048);assert.equal(route.get.parameters.some(p=>['owner_id','tenant_id','team','aggregate','month'].includes(p.name)),false);});
test('本人查询条件保留空值并要求成对起始范围',()=>{assert.ok(validates('OwnWorklogQueryFilters',ownFilters));assert.ok(validates('OwnWorklogQueryFilters',{...ownFilters,ticket_id:id('1'),kind:'COMMUTE',started_from:timestamp,started_before:'2026-11-01T00:00:00Z'}));for(const patch of [{started_from:timestamp},{started_before:timestamp},{kind:'ALL'},{ticket_id:'bad'},{owner_id:id('1')}])assert.equal(validates('OwnWorklogQueryFilters',{...ownFilters,...patch}),false);});
test('本人查询结果不能把空页解释为零工时或已冻结统计',()=>{const data={tenant_id:id('1'),owner_id:id('2'),items:[],has_more:false,next_before_created_at:null,next_before_id:null,filters:ownFilters,source:'postgresql',scope:'OWN_CURRENT_TENANT',page_size_limit:20,projection:'CURRENT_MANUAL_ENTRIES',time_selection:'STARTED_AT_HALF_OPEN_IF_SUPPLIED',interval_projection:'FULL_RECORDED_INTERVAL_NOT_CLIPPED',snapshot_frozen:false,aggregates:null,financial_status:'CONFIG_PENDING'};assert.ok(validates('OwnWorklogBrowseResult',{data,trace_id:'synthetic'}));for(const patch of [{aggregates:{elapsed_millis:0}},{snapshot_frozen:true},{scope:'TEAM'},{financial_status:'READY'},{interval_projection:'CLIPPED'},{has_more:true},{next_before_id:id('3')}])assert.equal(validates('OwnWorklogBrowseResult',{data:{...data,...patch},trace_id:'synthetic'}),false);});

test('报备请求时间词法与API一致，拒绝多余零小数位和末尾换行',()=>{for(const schema of ['ChangeNoticeDraftCreate','ChangeNoticeDraftUpdate']){const body={...noticeInput,expected_version:schema.endsWith('Create')?0:1};for(const field of ['planned_start_at','planned_end_at'])for(const value of ['2026-10-06T00:00:00.0000Z','2026-10-06T00:00:00.123000Z','2026-10-06T00:00:00Z\n','2026-10-06T00:00:00Z\r\n'])assert.equal(validates(schema,{...body,[field]:value}),false);assert.ok(validates(schema,{...body,planned_start_at:'2026-10-04T08:00:00.123+08:00',planned_end_at:'2026-10-05T00:00:00Z'}));}});


test('导出准备受理必须显式模式与四个可空筛选，不授予发布',()=>{const value={expected_version:0,mode:'PREPARE_ONLY_NO_PUBLICATION',filters:{type:null,status:null,site_id:null,query:null}};assert.ok(validates('AssetExportAcceptance',value));for(const patch of [{mode:'PUBLISH'},{expected_version:1},{download_allowed:true},{tenant_id:id('1')},{filters:{type:null}}])assert.equal(validates('AssetExportAcceptance',{...value,...patch}),false);const op=contract.paths['/asset-export-jobs'].post;assert.ok(op.responses[202]);assert.equal(op.responses[200],undefined);assert.deepEqual(op['x-mop-roles'],['padmin','msp']);assert.equal(op['x-mop-current-membership-before-replay'],true);});
test('导出原收据保持排队且没有下载地址',()=>{const data={job_id:id('1'),status:'QUEUED',version:0,mode:'PREPARE_ONLY_NO_PUBLICATION',publication_status:'NOT_IMPLEMENTED',download_allowed:false};assert.ok(validates('AssetExportReceipt',{data,trace_id:'test'}));for(const patch of [{status:'SUCCEEDED'},{download_allowed:true},{download_url:'https://example.test'}])assert.equal(validates('AssetExportReceipt',{data:{...data,...patch},trace_id:'test'}),false);});
test('导出进度描述区分明确零行，readiness不冒充发布或worker在线',()=>{assert.ok(validates('AssetExportPreparedDescriptor',{selection_sha256:'a'.repeat(64),file_sha256:'b'.repeat(64),size_bytes:4,row_count:0,generated_at:'2026-10-04T00:00:00Z'}));const f=contract.components.schemas.ReadinessResult.properties.data.properties.asset_exports.properties;assert.equal(f.worker_liveness.type,'null');assert.equal(f.download_allowed.const,false);assert.equal(f.publication_status.const,'NOT_IMPLEMENTED');assert.equal(contract.components.schemas.AssetExportJob.properties.object_verification.const,'NOT_CHECKED_BY_PROGRESS_READER');});

const ownPreferenceInput={expected_version:0,intent:'SAVE_REQUEST_DRAFT_ONLY',requested_level:null,requested_channels:null,requested_quiet_window:null};
test('本人偏好请求契约保留null与明确空集，只允许显式草稿保存',()=>{assert.ok(validates('OwnNotificationPreferenceCreate',ownPreferenceInput));assert.ok(validates('OwnNotificationPreferenceCreate',{...ownPreferenceInput,requested_channels:[]}));for(const patch of [{intent:'ENABLE'},{expected_version:1},{owner_id:id('1')},{effective:true},{requested_level:'P1'},{requested_channels:['SMS','SMS']},{requested_channels:['UNKNOWN']},{requested_channels:[null]}])assert.equal(validates('OwnNotificationPreferenceCreate',{...ownPreferenceInput,...patch}),false);for(const field of Object.keys(ownPreferenceInput)){const n={...ownPreferenceInput};delete n[field];assert.equal(validates('OwnNotificationPreferenceCreate',n),false)}});
test('本人静音窗口契约保留未知对象与跨午夜，拒绝秒和隐藏时段',()=>{for(const q of [{enabled:null,start:null,end:null},{enabled:false,start:null,end:null},{enabled:true,start:'22:30',end:'07:00'}])assert.ok(validates('OwnNotificationPreferenceCreate',{...ownPreferenceInput,requested_quiet_window:q}));for(const q of [{enabled:false,start:'22:00',end:'07:00'},{enabled:true,start:'22:00:00',end:'07:00'},{enabled:true,start:null,end:'07:00'},{enabled:null,start:null,end:null,timezone:'UTC'}])assert.equal(validates('OwnNotificationPreferenceCreate',{...ownPreferenceInput,requested_quiet_window:q}),false)});
test('本人偏好HTTP路径声明当前成员、本人范围、CSRF与原收据，不提供管理员覆盖或发送',()=>{const root=contract.paths['/my/notification-preferences'];assert.deepEqual(Object.keys(root),['get','post']);assert.equal(root.get['x-mop-object-scope'],'CURRENT_OWNER_TENANT');assert.equal(root.get['x-mop-administrator-other-owner-override'],false);assert.equal(root.post['x-mop-current-membership-before-replay'],true);assert.equal(root.post['x-mop-immutable-original-receipt'],true);assert.equal(root.post['x-mop-max-raw-body-bytes'],8192);assert.equal(root.post['x-mop-max-json-tokens'],64);assert.equal(root.post['x-mop-effective-or-dispatched'],false);assert.deepEqual(root.post['x-mop-capabilities'],['notification_preferences:write_own']);assert.ok(contract.paths['/my/notification-preferences/{id}/history'].get);});
test('本人偏好结构就绪不声称接收人绑定、有效规则或派发',()=>{const s=contract.components.schemas.ReadinessResult.properties.data.properties.own_notification_preferences;assert.equal(s.properties.schema_access_ready.const,true);assert.equal(s.properties.configuration_status.const,'CONFIG_PENDING');assert.equal(s.properties.receiver_binding_status.const,'NOT_VERIFIED');assert.equal(s.properties.effective.const,false);assert.equal(s.properties.notification_dispatched.const,false);});

const dashboardPeriodSample={from:'2026-10-04T01:00:00.123Z',to:'2026-10-05T01:00:00.123Z'};
const dashboardSchema=contract.components.schemas.DashboardAvailabilitySnapshot;
const dashboardTupleSample=schema=>Object.fromEntries(Object.entries(schema.properties).map(([key,value])=>[key,Object.hasOwn(value,'const')?value.const:value.type==='null'?null:key==='period'?structuredClone(dashboardPeriodSample):key==='detail_counts'?Object.fromEntries(Object.keys(value.properties).map(k=>[k,null])):undefined]));
const dashboardSample=()=>({tenant_id:id('2'),actor_id:id('1'),permission_version:7,generated_at:'2026-10-05T01:00:00.123Z',window:'HOURS_24',timezone:'Asia/Shanghai',trend_period:structuredClone(dashboardPeriodSample),last_24_hours:structuredClone(dashboardPeriodSample),month_start:'2026-09-30T16:00:00Z',month_window_empty:false,layout_version:'MOP_FIXED_DASHBOARD_V1',layout_customization_allowed:false,contract_refresh_seconds:30,kpis:dashboardSchema.properties.kpis.prefixItems.map(dashboardTupleSample),panels:dashboardSchema.properties.panels.prefixItems.map(dashboardTupleSample),metric_samples_loaded:false,clock_accuracy_verified:false,source:'POSTGRESQL_AUTHORIZATION_AND_CLOCK_ONLY',scope:'CURRENT_AUTHORIZED_TENANT',formal_metric_status:'NOT_IMPLEMENTED',writes_performed:false});
test('固定首页只提供当前租户只读GET、三个window预设且不提供布局保存',()=>{const route=contract.paths['/dashboard'];assert.deepEqual(Object.keys(route),['get']);assert.deepEqual(route.get['x-mop-allowed-query-keys'],['window']);assert.equal(route.get['x-mop-reject-duplicate-query-keys'],true);assert.equal(route.get['x-mop-success-writes-audit-or-receipts'],false);assert.deepEqual(route.get.parameters[0].schema.enum,['HOURS_24','DAYS_7','DAYS_30']);assert.equal(route.get.parameters[0].schema.default,'HOURS_24');});
test('固定首页完整null可用性响应和明确UTC毫秒允许序列化',()=>{assert.ok(validates('DashboardAvailabilitySnapshot',dashboardSample()));assert.ok(validates('DashboardAvailabilityResult',{data:dashboardSample(),trace_id:'unit'}));});
test('首页不能把缺失监控伪造成0正常、空事件列表或已核验时钟',()=>{for(const mutate of [s=>s.kpis[0].value=0,s=>s.kpis[0].detail_counts.total=0,s=>s.kpis[1].state='HEALTHY',s=>s.panels[0].items=[],s=>s.metric_samples_loaded=true,s=>s.clock_accuracy_verified=true,s=>s.writes_performed=true]){const sample=dashboardSample();mutate(sample);assert.equal(validates('DashboardAvailabilitySnapshot',sample),false);}});
test('首页固定顺序数量与面板上限不允许增删或互换',()=>{for(const mutate of [s=>s.kpis.reverse(),s=>s.panels.reverse(),s=>s.kpis.pop(),s=>s.panels.push(s.panels[0]),s=>s.panels[2].contract_item_limit=10,s=>s.layout_customization_allowed=true]){const sample=dashboardSample();mutate(sample);assert.equal(validates('DashboardAvailabilitySnapshot',sample),false);}});
test('首页严格拒绝额外字段、身份缺失、非UTC与微秒时刻',()=>{for(const mutate of [s=>s.extra=true,s=>delete s.actor_id,s=>s.permission_version=0,s=>s.generated_at='2026-10-05T09:00:00+08:00',s=>s.generated_at='2026-10-05T01:00:00.123456Z',s=>s.kpis[0].detail_counts.fake=null]){const sample=dashboardSample();mutate(sample);assert.equal(validates('DashboardAvailabilitySnapshot',sample),false);}});

test('会话能力来自当前成员明确集合，空集合不补基础角色权限',()=>{
  const schema=contract.components.schemas.SessionResult;
  assert.equal(schema['x-mop-capability-source'],'current_membership_effective_capabilities_no_category_fallback');
  assert.equal(contract.paths['/tenants/inventory'].get['x-mop-capability'],'tenants:read_inventory');
  assert.equal(schema['x-mop-empty-capabilities'],'active_supported_member_can_have_no_business_grants');
  const data={user:{id:id('1'),display_name:'合成用户',role:'padmin'},tenant:{id:id('2'),name:'合成租户'},allowed_tenants:[{id:id('2'),name:'合成租户'}],capabilities:[],permission_version:1,csrf_token:'unit-not-live',mode:'remote_test',identity_source:'test_oidc'};
  assert.ok(validates('SessionResult',{data,trace_id:'unit'}));
});
test('API控制器源码不得从业务类别重新扩张有效权限集合',()=>{
  const root=new URL('../server/api/src/main/java/cn/mop/api/',import.meta.url);
  for(const file of readdirSync(root).filter(name=>name.endsWith('Controller.java'))){
    const source=readFileSync(new URL(file,root),'utf8');
    assert.equal(source.includes('Capabilities.forRole('),false,file);
  }
});

const inboxMessageSample=()=>({job_id:id('1'),tenant_id:id('2'),recipient_id:id('3'),permission_version:1,published_version:4,file_sha256:'a'.repeat(64),size_bytes:100,row_count:1,issued_at:'2026-10-14T06:00:00Z',expires_at:'2026-10-14T06:15:00Z',version:0,read_at:null,content_url:`/api/v1/asset-export-jobs/${id('1')}/content?grant=1791958500000.${'a'.repeat(43)}`,link_status:'AVAILABLE',source:'postgresql'});
test('导出消息区分未读、已读与链接到期，不把过期消息删除',()=>{const message=inboxMessageSample();assert.ok(validates('ExportInboxMessage',message));assert.equal(validates('ExportInboxMessage',{...message,version:1}),false);assert.ok(validates('ExportInboxMessage',{...message,version:1,read_at:message.issued_at}));assert.equal(validates('ExportInboxMessage',{...message,link_status:'EXPIRED'}),false);assert.ok(validates('ExportInboxMessage',{...message,link_status:'EXPIRED',content_url:null}));});
test('站内消息只读列表与显式已读接口保持当前授权和CSRF幂等',()=>{const read=contract.paths['/my-export-messages/{id}/read'].post;assert.equal(read['x-mop-current-membership-before-replay'],true);assert.ok(read.parameters.some(p=>p.name==='X-CSRF-Token'));assert.deepEqual(Object.keys(contract.paths['/my-export-messages']),['get']);assert.deepEqual(contract.paths['/my-export-messages'].get['x-mop-capabilities'],['assets:read']);assert.ok(validates('ExportInboxReadInput',{expected_version:0}));assert.equal(validates('ExportInboxReadInput',{expected_version:0,read_at:null}),false);assert.equal(validates('ExportInboxReadInput',{expected_version:2}),false);});
test('消息游标末页必须明确结束且签名链接只能指向本站下载接口',()=>{const result={data:{items:[inboxMessageSample()],has_more:false,next_before_issued_at:null,next_before_id:null,unread_count:1,observed_at:'2026-10-14T06:00:01Z'},trace_id:'synthetic'};assert.ok(validates('ExportInboxPageResult',result));assert.equal(validates('ExportInboxPageResult',{...result,data:{...result.data,next_before_id:id('1')}}),false);assert.equal(validates('ExportInboxMessage',{...inboxMessageSample(),content_url:'https://example.invalid/download'}),false);});


test('工单生命周期动作只接收版本及动作输入，不接收身份和开单规则', () => {
  const root='/tickets/{ticketId}/lifecycle';
  for(const name of ['accept','transfer','wait-for-vendor','resume','submit-resolution','confirm','not-resolved','reopen']) {
    const op=contract.paths[root+'/actions/'+name].post;
    assert.ok(op.parameters.some(p=>p.name==='Idempotency-Key'));
    assert.ok(op.parameters.some(p=>p.name==='X-CSRF-Token'));
    const schema=op.requestBody.content['application/json'].schema;
    const validate=ajv.compile(schema);
    assert.equal(validate({expected_version:1,tenant_id:id('1')}),false);
    assert.equal(schema.additionalProperties,false);
    assert.equal(schema.properties.expected_version.maximum,9007199254740990);
  }
  assert.equal(contract.paths[root+'/actions/auto-close'],undefined);
  assert.equal(contract.paths[root+'/start'],undefined);
});
test('工单生命周期读回不声称通知、SLA或生产审批已完成',()=>{
  const s=contract.components.schemas.TicketLifecycleSnapshot;
  assert.equal(s.properties.production_approved.const,false);
  assert.equal(s.properties.notification_status.const,'NOT_INTEGRATED');
  assert.equal(s.properties.sla_clock_status.const,'NOT_INTEGRATED');
  assert.equal(s.properties.opening_snapshot,undefined);
});
test('开单必须明确优先级，客户端不能覆盖租户档位、SLA时间或规则',()=>{
  const op=contract.paths['/tickets/{ticketId}/lifecycle'].post, validate=ajv.compile(op.requestBody.content['application/json'].schema)
  assert.equal(validate({expected_version:0,priority:'P1'}),true)
  for(const patch of [{expected_version:1},{priority:'P4'},{service_tier:'ADVANCED'},{response_due_at:'2026-10-20T00:00:00Z'}])assert.equal(validate({expected_version:0,priority:'P1',...patch}),false)
  assert.deepEqual(op['x-mop-capabilities'],['tickets:lifecycle_write']);assert.ok(op.responses['422'])
})

test('SLA服务端投影包含未知口径而不声称通知送达',()=>{const path=contract.paths['/tickets/{ticketId}/sla'];assert.equal(path.get['x-mop-read-only'],true);assert.equal(path.post,undefined);const s=contract.components.schemas.TicketSlaProjection;assert.equal(s.properties.reminders_delivered.const,false);assert.equal(s.properties.production_approved.const,false);assert.ok(contract.components.schemas.TicketSlaClock.properties.status.enum.includes('CONFIG_PENDING'));});

test('补充SLA口径只能绑定明确发布版本，不接收策略或身份覆盖',()=>{const op=contract.paths['/tickets/{ticketId}/sla/policy-binding'].post;assert.ok(op.parameters.some(x=>x.name==='X-CSRF-Token'));const schema=op.requestBody.content['application/json'].schema;assert.equal(schema.additionalProperties,false);assert.deepEqual(Object.keys(schema.properties),['expected_lifecycle_version','release_id','release_version']);assert.equal(contract.components.schemas.TicketSlaPolicyBindingResult.properties.data.properties.production_approved.const,false);});

test('SLA提醒记录不等于路由或送达，读取无业务写入',()=>{const p=contract.paths['/tickets/{ticketId}/sla/reminders'];assert.equal(p.get['x-mop-read-only'],true);assert.equal(p.post,undefined);const s=contract.components.schemas.TicketSlaReminder;assert.equal(s.properties.notification_status.const,'NOT_ROUTED');assert.equal(s.properties.delivered.const,false);assert.deepEqual(s.properties.progress.enum,[0.5,0.8,1]);});
test('服务档位快照、只追加变更台账与工单SLA冻结时钟符合M2-10/M5-8/M5-18/M5-20契约', () => {
  assert.equal(contract.info.version, '0.85.0');
  const tierGet = contract.paths['/service-tiers'].get;
  const tierPost = contract.paths['/service-tiers/changes'].post;
  const slaGet = contract.paths['/tickets/{id}/workflow/sla'].get;
  assert.deepEqual(tierGet['x-mop-roles'], ['msp', 'padmin']);
  assert.equal(tierGet['x-mop-capability'], 'sla_scenarios:read');
  assert.deepEqual(tierPost['x-mop-roles'], ['padmin']);
  assert.equal(tierPost['x-mop-capability'], 'configurations:write');
  assert.equal(slaGet['x-mop-capability'], 'tickets:read');

  const snap = {
    tenant_id: id('1'), version: 2, tier: 'ADVANCED', availability_target: 99.99,
    contract_device_count: 120, billing_cycle: 'MONTHLY', monthly_fee_fen: 2500000,
    effective_mode: 'IMMEDIATE', effective_from: '2026-10-18T08:00:00Z',
    updated_by: id('2'), updated_at: '2026-10-18T08:00:00Z', customer_approval_status: 'UNVERIFIED',
  };
  assert.ok(validates('ServiceTierSnapshot', snap));
  assert.equal(validates('ServiceTierSnapshot', { ...snap, customer_approval_status: 'APPROVED' }), false);

  const change = {
    id: id('9'), tenant_id: id('1'), version: 2, previous_version: 1,
    from_tier: 'STANDARD', to_tier: 'ADVANCED', availability_target: 99.99,
    contract_device_count: 120, billing_cycle: 'MONTHLY', monthly_fee_fen: 2500000,
    effective_mode: 'IMMEDIATE', effective_from: '2026-10-18T08:00:00Z',
    reason: 'SYNTHETIC_TIER_UPGRADE', approved_by: id('2'), changed_at: '2026-10-18T08:00:00Z',
    customer_approval_status: 'UNVERIFIED',
  };
  assert.ok(validates('ServiceTierChange', change));
  assert.ok(validates('ServiceTierPageResult', {
    data: {
      tenant_id: id('1'), current: snap, items: [change], has_more: false, next_before_version: null,
      observed_at: '2026-10-18T08:00:01Z', customer_approval_status: 'UNVERIFIED',
      in_flight_ticket_retroactive: false, source: 'postgresql',
    },
    trace_id: 'synthetic',
  }));
  assert.equal(validates('ServiceTierPageResult', {
    data: {
      tenant_id: id('1'), current: snap, items: [change], has_more: false, next_before_version: null,
      observed_at: '2026-10-18T08:00:01Z', customer_approval_status: 'UNVERIFIED',
      in_flight_ticket_retroactive: true, source: 'postgresql',
    },
    trace_id: 'synthetic',
  }), false);

  const clock = {
    name: 'RESPONSE', due_at: '2026-10-18T08:10:00Z', completed_at: '2026-10-18T08:04:00Z',
    paused: false, breached: false, status: 'MET', missing_reason: null,
    thresholds: { p50_at: '2026-10-18T08:05:00Z', p80_at: '2026-10-18T08:08:00Z', p100_at: '2026-10-18T08:10:00Z' },
    working_windows_count: 0,
  };
  const sla = {
    ticket_id: id('7'), tenant_id: id('1'), receipt_no: 'MOP-20261018-0001', registration_created_at: '2026-10-18T08:00:00Z',
    workflow_version: 3, state: 'RESOLVED', priority: 'P1', frozen_service_tier: 'STANDARD', current_tenant_service_tier: 'ADVANCED',
    tier_changed_since_open: true, response_clock: clock,
    recovery_clock: { ...clock, name: 'RECOVERY', due_at: '2026-10-18T10:00:00Z', completed_at: '2026-10-18T09:00:00Z', paused: true, status: 'PAUSED_FOR_CUSTOMER_CONFIRM' },
    clock_tasks: [{ clock_key: 'response:50', kind: 'SLA', due_at: '2026-10-18T08:05:00Z', outcome: 'EMITTED', completed_at: '2026-10-18T08:05:01Z' }],
    sla_missing: {}, sla_references: [], reopen_count: 0, reopen_policy_status: 'NOT_REOPENED',
    metric_version: 'SLA_CLOCK_V2', observed_at: '2026-10-18T09:10:00Z', source: 'postgresql',
  };
  assert.ok(validates('TicketSlaInspectionResult', { data: sla, trace_id: 'synthetic' }));
});

test('值班看板、18:00交接班、升级链与SLA月报契约保持Q15/Q26待确认约束（M5-9/M5-10/M5-12/M5-13/M5-19）', () => {
  assert.equal(contract.info.version, '0.85.0');
  assert.equal(contract.paths['/oncall/board'].get['x-mop-capability'], 'tickets:read');
  assert.equal(contract.paths['/oncall/rosters'].post['x-mop-capability'], 'ticket_notes:write_internal');
  assert.equal(contract.paths['/oncall/handoffs'].post['x-mop-capability'], 'ticket_notes:write_internal');
  assert.equal(contract.paths['/escalation-policies'].patch['x-mop-capability'], 'configurations:write');
  assert.equal(contract.paths['/oncall/escalations'].post['x-mop-capability'], 'ticket_notes:write_internal');
  assert.equal(contract.paths['/sla/reports'].get['x-mop-capability'], 'sla_scenarios:read');
  assert.equal(contract.paths['/sla/monthly-results'].post['x-mop-capability'], 'ticket_notes:write_internal');

  const board = {
    tenant_id: id('1'),
    kpis: {
      unacknowledged_count: 2, critical_count: 1, in_progress_count: 3,
      pending_review_count: 1, recovered_today_count: 4, mtta_p50_sec: 210, mtta_target_sec: 900,
    },
    queue: [{
      ticket_id: id('7'), priority: 'P1', state: 'OPEN', tier_snapshot: 'STANDARD',
      assignee_user_id: id('2'), created_at: '2026-10-18T10:00:00Z',
      response_due_at: '2026-10-18T10:10:00Z', recovery_due_at: '2026-10-18T12:00:00Z',
      countdown_remaining_sec: 300, countdown_progress_pct: 0.5, breached: false,
      current_escalation_step: 1, next_escalation_step: 2, next_escalation_target_role: 'L2_ONCALL',
    }],
    rosters: [{
      id: id('3'), tenant_id: id('1'), version: 1, shift_date: '2026-10-18', shift_code: 'DAY',
      handoff_local_time: '18:00', primary_oncall_user_id: id('2'), backup_oncall_user_id: id('4'),
      note: 'SYNTHETIC_DAY_ROSTER', roster_policy_status: 'CONFIG_PENDING_Q15',
      updated_by: id('2'), updated_at: '2026-10-18T08:00:00Z',
    }],
    handoffs: [{
      id: id('5'), tenant_id: id('1'), roster_id: id('3'), outgoing_user_id: id('2'), incoming_user_id: id('4'),
      handoff_local_time: '18:00', unacknowledged_count: 2, in_progress_count: 3, pending_review_count: 1,
      note: 'SYNTHETIC_HANDOFF', handed_off_at: '2026-10-18T10:00:00Z',
    }],
    policies: [{
      tenant_id: id('1'), tier: 'STANDARD', version: 1, unanswered_escalate_count: 2,
      unanswered_retry_interval_sec: null,
      steps: [
        { step_order: 1, trigger_condition: 'IMMEDIATE', wait_minutes: 0, target_role: 'L1_ONCALL' },
        { step_order: 2, trigger_condition: 'UNACKNOWLEDGED', wait_minutes: 15, target_role: 'L2_ONCALL' },
      ],
      policy_status: 'CONFIG_PENDING_Q15', updated_by: id('2'), updated_at: '2026-10-18T08:00:00Z', persisted: true,
    }],
    step_runs: [{
      id: id('6'), tenant_id: id('1'), ticket_id: id('7'), tier: 'STANDARD', policy_version: 1,
      step_order: 1, trigger_condition: 'PHONE_UNANSWERED', wait_minutes: 0, target_role: 'L1_ONCALL',
      unanswered_phone_count: 2, escalated_to_step: 2, escalated_target_role: 'L2_ONCALL',
      sms_notice_status: 'INTENT_RECORDED_PENDING_GATEWAY', sms_notice_summary: '电话连续2次未接听自动升级并登记短信记录',
      recorded_by: id('2'), recorded_at: '2026-10-18T10:05:00Z',
    }],
    auto_refresh_sec: 30, handoff_local_time: '18:00', roster_policy_status: 'CONFIG_PENDING_Q15',
    observed_at: '2026-10-18T10:05:00Z', source: 'postgresql',
  };
  assert.ok(validates('OnCallBoardResult', { data: board, trace_id: 'synthetic' }));
  assert.equal(validates('OnCallBoardResult', { data: { ...board, handoff_local_time: '09:00' }, trace_id: 'synthetic' }), false);

  const latency = { sample_count: 3, unrecovered_count: 0, mean_sec: 180, p50_sec: 180, p95_sec: 240 };
  const evalObj = {
    tier_snapshot: 'STANDARD', total_ticket_count: 10, applicable_count: 8, met_count: 6,
    not_met_count: 2, unresolved_count: 1, not_applicable_count: 1,
    achievement_numerator: 6, achievement_denominator: 8, achievement_rate_pct: 75,
    compensation_ratio: 0.2, monthly_fee_fen: 2000000, compensation_amount_fen: null,
    compensation_status: 'CONFIG_PENDING_Q26', candidate_fixed_ratio_fen: 400000,
    candidate_shortfall_weighted_fen: 100000,
    formula_version: 'customer-20261020-availability-v2', items: [],
  };
  assert.ok(validates('SlaMonthlyEvaluation', evalObj));
  assert.equal(validates('SlaMonthlyEvaluation', { ...evalObj, compensation_amount_fen: 400000 }), false);
  assert.ok(validates('SlaLatencyStats', latency));
});

test('变更报备审批契约绑定修订并保留未生效边界', () => {
  const base = '/change-notice-drafts/{id}/revisions/{draft_revision}/approval';
  assert.ok(contract.paths[base].get);
  assert.ok(contract.paths[base + '/history'].get);
  const action = contract.paths[base + '/actions'].post;
  assert.equal(action['x-mop-idempotent-replay-reauthorization'], true);
  assert.equal(action['x-mop-review-policy'], 'change_notice.approver_policy');
  const snapshot = contract.components.schemas.ChangeNoticeApprovalSnapshot;
  assert.equal(snapshot.properties.effective.const, false);
  assert.equal(snapshot.properties.device_execution_allowed.const, false);
  assert.equal(snapshot.properties.alert_suppression_allowed.const, false);
  assert.ok(snapshot.required.includes('allowed_actions'));
  const input = contract.components.schemas.ChangeNoticeApprovalAction;
  assert.equal(input.additionalProperties, false);
  assert.equal(validates('ChangeNoticeApprovalAction', { expected_version: 2, action: 'REJECT', note: null }), false);
  assert.equal(validates('ChangeNoticeApprovalAction', { expected_version: 2, action: 'APPROVE', note: null }), true);
  assert.equal(validates('ChangeNoticeApprovalAction', { expected_version: 2, action: 'APPROVE', note: null, actor_id: 'fake' }), false);
});

test('customer SLA contract accepts the actual Java controller availability and detail payloads',()=>{
  const actual=read('../fixtures/customer-sla-controller.json');
  assert.equal(validates('SlaReportResult',{data:actual.report,trace_id:'synthetic-customer-sla'}),true);
  assert.equal(validates('SlaServiceDetailResult',{data:actual.detail,trace_id:'synthetic-customer-sla'}),true);
  const historical=structuredClone(actual.report);
  historical.monthly_result.formula_version='contract-m5-9-m5-19-v1';
  assert.equal(validates('SlaReportResult',{data:historical,trace_id:'synthetic-historical-sla'}),true);
});

test('D09 report draft contract requires explicit frozen source and nullable unapproved configuration',()=>{
  const configuration={revision:null,time_zone:null,duplicate_rule:null,archived_rule:null,metric_version:null,report_template_version:null};
  const input={expected_version:0,label:'SYNTHETIC_DRAFT',source_collection_id:id('1'),expected_source_document_sha256:'a'.repeat(64),expected_source_manifest_sha256:'b'.repeat(64),year:2026,quarter:4,configuration};
  assert.equal(validates('InspectionQuarterlyReportCreate',input),true);
  for(const patch of [{summary:{}},{tenant_id:id('2')},{quarter:5},{expected_version:1},{configuration:{...configuration,duplicate_rule:'GUESS_LATEST'}},{configuration:{}}])assert.equal(validates('InspectionQuarterlyReportCreate',{...input,...patch}),false);
  const post=contract.paths['/inspection-quarterly-reports'].post;
  assert.deepEqual(post['x-mop-roles'],['padmin']);assert.equal(post['x-mop-capability'],'inspection_quarterly_reports:write');assert.equal(post['x-mop-idempotent-replay-reauthorization'],true);
  assert.equal(contract.components.schemas.InspectionQuarterlyReportDetail.properties.projection.const,'FROZEN_INPUT_OUTPUT');
  assert.equal(contract.components.schemas.InspectionQuarterlyReportFrozenDocument.properties.output.properties.report_published.const,false);
});

test('D09 Java-produced frozen details match the exact full input/output contract',()=>{
  for(const name of ['pending','selected']){
    const value=read(`../fixtures/inspection-quarterly-report/${name}.json`);
    assert.equal(validates('InspectionQuarterlyReportDetailResult',value),true,JSON.stringify(ajv.getSchema('urn:mop:actual-api#/components/schemas/InspectionQuarterlyReportDetailResult').errors));
    assert.equal(createHash('sha256').update(JSON.stringify(value.data.frozen_document),'utf8').digest('hex'),value.data.document_sha256);
    const forged=structuredClone(value);forged.data.frozen_document.output.report_published=true;assert.equal(validates('InspectionQuarterlyReportDetailResult',forged),false);
  }
});

test('D15 actual MVC fixture envelopes and write exchanges validate against published routes', () => {
  const fixture = read('../fixtures/monitoring-event-center-controller.json');
  const check = (schema, value, label) => {
    assert.equal(validates(schema, value), true, `${label}: ${JSON.stringify(ajv.getSchema(`urn:mop:actual-api#/components/schemas/${schema}`).errors)}`);
  };
  check('MonitoringEventWorkspaceResult', fixture.workspace, 'workspace');
  check('MonitoringEventDetailResult', fixture.detail, 'detail');
  check('MonitoringNoiseFunnelResult', fixture.noise_funnel, 'noise funnel');
  assert.ok(fixture.exchanges.length >= 9, 'all actual read/write routes need a controller-produced exchange');
  const matched = new Set();
  for (const exchange of fixture.exchanges) {
    const path = new URL(exchange.path, 'https://synthetic.invalid').pathname.replace(/^\/api\/v1/, '').replace(/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?=\/|$)/g, '/{id}');
    const method = exchange.method.toLowerCase();
    const operation = contract.paths[path]?.[method];
    assert.ok(operation, `fixture ${exchange.name}: ${method} ${path} missing from contract`);
    assert.equal(operation.responses[200].content['application/json'].schema.$ref, `#/components/schemas/${exchange.response_schema}`);
    check(exchange.response_schema, exchange.response, exchange.name);
    if (method === 'post') {
      assert.equal(operation.requestBody.content['application/json'].schema.$ref, `#/components/schemas/${exchange.request_schema}`);
      check(exchange.request_schema, exchange.request, exchange.name + ' request');
      assert.equal(operation['x-mop-idempotent-replay-reauthorization'], true);
      assert.deepEqual(operation.parameters.filter(x => x.in === 'header').map(x => x.name), ['Idempotency-Key', 'X-CSRF-Token']);
    }
    matched.add(`${method} ${path}`);
  }
  for (const route of ['get /events', 'get /events/{id}', 'get /events/noise-funnel', 'post /events/ingest', 'post /events/{id}/transitions', 'post /maintenance-windows', 'post /maintenance-windows/{id}/actions', 'post /monitoring/quarantine/{id}/decisions', 'post /autoticket-attempts/{id}/manual-create']) assert.ok(matched.has(route), route);
});

test('D15 workspace has the actual eight fields and no invented global policy statuses', () => {
  const fixture = read('../fixtures/monitoring-event-center-controller.json');
  const fields = ['tenant_id', 'events', 'noise_funnel', 'maintenance_windows', 'quarantine_items', 'autoticket_attempts', 'observed_at', 'source'];
  assert.deepEqual(Object.keys(fixture.workspace.data).sort(), [...fields].sort());
  assert.deepEqual([...contract.components.schemas.MonitoringEventWorkspaceResult.properties.data.required].sort(), [...fields].sort());
  for (const key of ['review_policy_status', 'mapping_policy_status']) {
    const bad = structuredClone(fixture.workspace); bad.data[key] = 'CONFIG_PENDING';
    assert.equal(validates('MonitoringEventWorkspaceResult', bad), false);
  }
  for (const key of fields) {
    const bad = structuredClone(fixture.workspace); delete bad.data[key];
    assert.equal(validates('MonitoringEventWorkspaceResult', bad), false, key);
  }
  assert.equal(contract.paths['/events'].post, undefined);
  assert.equal(contract.paths['/events/{id}/actions'], undefined);
  assert.equal(contract.paths['/monitoring-quarantine/{id}/decisions'], undefined);
  const size = contract.paths['/events'].get.parameters.find(x => x.name === 'page_size').schema;
  assert.deepEqual(size, { type: 'integer', minimum: 1, maximum: 50, default: 25 });
  assert.equal(contract.components.schemas.MonitoringEventDetailResult.properties.data.properties.members.maxItems, undefined);
  assert.equal(contract.components.schemas.MonitoringEventDetailResult.properties.data.properties.noise_records.maxItems, undefined);
});

test('D15 all production domain enums and exact notification labels are accepted without legacy invented states', () => {
  const fixture = read('../fixtures/monitoring-event-center-controller.json');
  const summary = contract.components.schemas.MonitoringEventSummary.properties;
  const pairs = [[summary.severity.enum, fixture.enums.severity], [summary.monitor_state.enum, fixture.enums.monitor_state], [summary.handling_state.enum, fixture.enums.handling_state], [summary.suppression_state.enum, fixture.enums.suppression_state], [summary.notification_empty_state.enum, fixture.enums.notification_empty_state], [contract.components.schemas.MonitoringNoiseRecord.properties.layer.enum, fixture.enums.noise_layer], [contract.components.schemas.AutoTicketAttemptEntry.properties.status.enum, fixture.enums.autoticket_status]];
  for (const [declared, actual] of pairs) assert.deepEqual([...declared].sort(), [...actual].sort());
  for (const event of fixture.workspace.data.events) assert.equal(validates('MonitoringEventSummary', event), true);
  const event = fixture.workspace.data.events[0];
  for (const patch of [{ notification_empty_state: 'RECOVERED_within_2MIN_CANCELLED' }, { notification_empty_state: 'SILENCED_NO_NOTIFICATION' }, { notification_empty_state: 'BELOW_SUBSCRIPTION_LEVEL_UNSENT' }, { notification_empty_state_label: '符合外发条件（按已启用渠道投递）' }, { review_policy_status: 'CONFIRMED' }, { root_cause_note: 'x'.repeat(501) }, { rule_name: 'x'.repeat(129) }]) assert.equal(validates('MonitoringEventSummary', { ...event, ...patch }), false);
  const anotherLabel = fixture.workspace.data.events.find(x => x.notification_empty_state !== event.notification_empty_state).notification_empty_state_label;
  assert.equal(validates('MonitoringEventSummary', { ...event, notification_empty_state_label: anotherLabel }), false, 'enum membership alone cannot validate a mismatched label');
  const attempt = fixture.workspace.data.autoticket_attempts[0];
  for (const patch of [{ status: 'CREATED' }, { status: 'NOT_REQUIRED' }, { mapped_priority: 'P4' }, { attempt_count: 0 }, { attempt_count: 7 }, { mapping_policy_status: 'CONFIRMED' }]) assert.equal(validates('AutoTicketAttemptEntry', { ...attempt, ...patch }), false);
  assert.ok(fixture.workspace.data.autoticket_attempts.some(x => x.mapped_priority === null));
});

test('D15 request actions and exact fields follow controller/domain boundaries', () => {
  const fixture = read('../fixtures/monitoring-event-center-controller.json');
  const request = schema => fixture.exchanges.find(x => x.request_schema === schema).request;
  const ingest = request('MonitoringIngestEventInput');
  assert.deepEqual(Object.keys(ingest).sort(), ['asset_id', 'rule_code', 'rule_name', 'severity', 'source_phase', 'source_event_id', 'occurred_at', 'simulate_autoticket_failure'].sort());
  assert.equal(validates('MonitoringIngestEventInput', { ...ingest, occurred_at: null }), true);
  for (const patch of [{ simulate_autoticket_failure: 1 }, { simulate_autoticket_failures: 1 }, { subscription_min_severity: 'HIGH' }, { flap_flip_count_in_10m: 3 }, { rule_name: 'x'.repeat(129) }, { source_event_id: 'x'.repeat(257) }, { source_phase: 'RECOVERY' }]) assert.equal(validates('MonitoringIngestEventInput', { ...ingest, ...patch }), false);
  const transition = request('MonitoringTransitionEventInput');
  for (const action of ['ACKNOWLEDGE', 'START', 'MARK_PENDING_REVIEW', 'RESOLVE', 'CLOSE']) assert.equal(validates('MonitoringTransitionEventInput', { ...transition, action, root_cause_note: 'synthetic root cause and verified repair' }), true);
  assert.equal(validates('MonitoringTransitionEventInput', { ...transition, action: 'START_PROGRESS' }), false);
  const supplementaryRootCause = '😀😀x';
  assert.equal(supplementaryRootCause.length, 5, 'RESOLVE minimum follows Java UTF-16 units');
  assert.equal([...supplementaryRootCause].length, 3, 'JSON Schema length counts Unicode code points');
  assert.equal(validates('MonitoringTransitionEventInput', { ...transition, action: 'RESOLVE', root_cause_note: supplementaryRootCause }), false);
  assert.equal(validates('MonitoringTransitionEventInput', { ...transition, action: 'RESOLVE', root_cause_note: '字'.repeat(20) }), true);
  assert.match(contract.components.schemas.MonitoringTransitionEventInput.description, /20个Unicode/);
  for (const expected_version of [0, -1, 1.5, '1', 9007199254740992]) assert.equal(validates('MonitoringTransitionEventInput', { ...transition, expected_version }), false);
  assert.equal(validates('MonitoringTransitionEventInput', { ...transition, expected_version: 9007199254740991 }), true);
  const create = request('CreateMaintenanceWindowInput');
  for (const silence_mode of ['SUPPRESS_NOTIFY', 'PAUSE_COLLECTION']) assert.equal(validates('CreateMaintenanceWindowInput', { ...create, silence_mode }), true);
  for (const patch of [{ asset_id: null }, { name: 'x'.repeat(129) }, { silence_mode: 'SILENCE_ALL_EXCEPT_DISASTER' }]) assert.equal(validates('CreateMaintenanceWindowInput', { ...create, ...patch }), false);
  const update = request('UpdateMaintenanceWindowInput');
  for (const action of ['EXTEND', 'END', 'CONFIRM_SLA_EXCLUSION']) assert.equal(validates('UpdateMaintenanceWindowInput', { ...update, action, new_ends_at: '2026-10-19T14:00:00Z' }), true);
  for (const action of ['END_EARLY', 'CANCEL']) assert.equal(validates('UpdateMaintenanceWindowInput', { ...update, action }), false);
  assert.equal(validates('UpdateMaintenanceWindowInput', { ...update, action: 'EXTEND', new_ends_at: null }), false);
  assert.deepEqual(contract.paths['/maintenance-windows/{id}/actions'].post['x-mop-action-authorization'].CONFIRM_SLA_EXCLUSION, { roles: ['padmin'], capability: 'configurations:write' });
  const decision = request('MonitoringQuarantineDecisionInput');
  assert.equal(validates('MonitoringQuarantineDecisionInput', { ...decision, decision: 'BIND_ASSET', bound_asset_id: id('2') }), true);
  assert.equal(validates('MonitoringQuarantineDecisionInput', { ...decision, decision: 'DISCARD', bound_asset_id: null }), true);
  for (const patch of [{ decision: 'BOUND' }, { decision: 'DISCARDED' }, { decision: 'BIND_ASSET', bound_asset_id: null }, { decision: 'DISCARD', bound_asset_id: id('2') }]) assert.equal(validates('MonitoringQuarantineDecisionInput', { ...decision, ...patch }), false);
});


test('D15 manual-ticket reason reserves the actual persistence prefix without narrowing unrelated notes', () => {
  const fixture = read('../fixtures/monitoring-event-center-controller.json');
  const request = schema => fixture.exchanges.find(x => x.request_schema === schema).request;
  const manual = request('ManualAutoTicketInput');
  const prefix = '人工补建成功: ';
  assert.equal([...prefix].length, 8);
  assert.equal(contract.components.schemas.ManualAutoTicketInput.properties.reason.maxLength, 492);
  for (const character of ['x', '根', '😀']) {
    const safe = character.repeat(492);
    assert.equal([...safe].length, 492);
    assert.equal([...(prefix + safe)].length, 500);
    assert.equal(validates('ManualAutoTicketInput', { ...manual, reason: safe }), true);
    for (const count of [493, 500, 501]) assert.equal(validates('ManualAutoTicketInput', { ...manual, reason: character.repeat(count) }), false, `${character} × ${count}`);
  }
  assert.equal(validates('ManualAutoTicketInput', { ...manual, reason: '' }), false);
  for (const [schema, field] of [['CreateMaintenanceWindowInput', 'reason'], ['MonitoringQuarantineDecisionInput', 'reason'], ['MonitoringTransitionEventInput', 'root_cause_note']]) {
    const input = request(schema);
    assert.equal(validates(schema, { ...input, [field]: '😀'.repeat(500) }), true, schema);
    assert.equal(validates(schema, { ...input, [field]: '😀'.repeat(501) }), false, schema);
  }
  const attempt = fixture.exchanges.find(x => x.request_schema === 'ManualAutoTicketInput').response.data;
  assert.equal(validates('AutoTicketAttemptEntry', { ...attempt, last_error: prefix + '😀'.repeat(492) }), true);
  assert.equal(validates('AutoTicketAttemptEntry', { ...attempt, last_error: prefix + '😀'.repeat(493) }), false);
});

test('真实控制器全局搜索夹具覆盖角色、数量、合法状态并保持事件最小投影', () => {
  const fixture = read('../fixtures/global-search-controller.json');
  assert.equal(fixture.fixture_kind, 'SYNTHETIC_PRODUCTION_CONTROLLER_JSON');
  assert.equal(fixture.database_evidence, false);
  const storedText = fixture.exchanges.find(e => e.name === 'stored_rule_control_text').response.data.groups[1].items[0];
  assert.equal(storedText.rule_name, 'Link\nDown'); assert.equal(storedText.rule_code, 'LINK\tRULE');
  const observedSeverity = new Set(), observedMonitor = new Set(), observedHandling = new Set();
  for (const example of fixture.exchanges) {
    assert.equal(validates(example.response_schema, example.response), true, example.name);
    const group = example.response.data.groups[1];
    for (const event of group.items ?? []) {
      observedSeverity.add(event.severity); observedMonitor.add(event.monitor_state); observedHandling.add(event.handling_state);
      assert.equal(validates('SearchEventHit', event), true);
      for (const key of ['root_cause_note', 'updated_by', 'fingerprint', 'raw_payload', 'management_ip', 'quarantine_id', 'asset_id', 'ticket_id', 'description']) {
        assert.equal(validates('SearchEventHit', { ...event, [key]: 'private' }), false, key);
      }
      for (const patch of [{ resource_kind: 'ASSET_RECORD' }, { occurrence_count: 0 }, { version: 0 }, { severity: 'OK' }, { monitor_state: 'NORMAL' }, { handling_state: 'OPEN' }, { rule_code: 'x'.repeat(65) }, { rule_name: 'x'.repeat(129) }, { last_occurred_at: 'not-a-time' }]) assert.equal(validates('SearchEventHit', { ...event, ...patch }), false);
    }
  }
  assert.deepEqual([...observedSeverity].sort(), ['AVERAGE', 'DISASTER', 'HIGH', 'INFO', 'WARNING']);
  assert.deepEqual([...observedMonitor].sort(), ['PROBLEM', 'RECOVERED']);
  assert.deepEqual([...observedHandling].sort(), ['ACKNOWLEDGED', 'CLOSED', 'IN_PROGRESS', 'NEW', 'PENDING_REVIEW', 'RESOLVED']);
});
test('事件搜索可见性、覆盖原因、报障范围一致，拒绝伪造空集、溢出和旧占位状态', () => {
  const fixture = read('../fixtures/global-search-controller.json');
  const internal = fixture.exchanges.find(e => e.name === 'role_msp').response.data;
  const customer = fixture.exchanges.find(e => e.name === 'role_viewer').response.data;
  for (const value of [internal, customer]) {
    const mismatch = structuredClone(value); mismatch.coverage_reason = value === internal ? customer.coverage_reason : internal.coverage_reason;
    assert.equal(validates('GroupedSearch', mismatch), false);
    const old = structuredClone(value); old.groups[1].status = 'NOT_IMPLEMENTED'; assert.equal(validates('GroupedSearch', old), false);
  }
  for (const patch of [{ items: [] }, { returned: 0 }, { has_more: false }, { source_scope: 'CURRENT_TENANT_INTERNAL' }]) {
    const mutated = structuredClone(customer); Object.assign(mutated.groups[1], patch); assert.equal(validates('GroupedSearch', mutated), false);
  }
  for (const patch of [{ has_more: true }, { returned: 4 }, { matched_fields: ['root_cause_note'] }, { limit: 5 }]) {
    const mutated = structuredClone(internal); Object.assign(mutated.groups[1], patch); assert.equal(validates('GroupedSearch', mutated), false);
  }
});


test('CMDB catalog matches actual controller wire fixture and stays readonly',()=>{
 const fixture=read('../fixtures/cmdb-type-catalog-controller.json');
 assert.ok(validates('CmdbTypeCatalogResult',fixture));
 assert.equal(contract.paths['/cmdb/type-catalog'].post,undefined);
 assert.equal(contract.paths['/cmdb/type-catalog'].get['x-mop-read-only'],true);
 for(const patch of [{asset_mutations:true},{runtime_publication:true},{production_approved:true},{credential_value:'not-allowed'}])assert.equal(validates('CmdbTypeCatalogResult',{...fixture,data:{...fixture.data,...patch}}),false);
});
test('CMDB contract does not erase unknown health or unresolved dictionary issues',()=>{
 const fixture=read('../fixtures/cmdb-type-catalog-controller.json');
 assert.equal(fixture.data.subtypes.length,16);
 assert.ok(fixture.data.state_dimensions.find(x=>x.field==='health_state').values.some(x=>x.code==='unknown'));
 const altered=structuredClone(fixture);altered.data.unresolved[0].resolution='approved';assert.equal(validates('CmdbTypeCatalogResult',altered),false);
});

test('CMDB durable-source preflight matches Java wire output and cannot authorize import',()=>{
 const route=contract.paths['/assets/import-sources/{id}/cmdb-preflight'];assert.ok(route.get);assert.equal(route.post,undefined);assert.equal(route.get['x-mop-read-only'],true);
 const fixture=read('../fixtures/cmdb-source-preflight-controller.json');assert.equal(validates('CmdbSourcePreflightResult',fixture),true);
 for(const patch of [{can_import:true},{database_checks:'PASS'},{asset_mutations:true},{production_approved:true}])assert.equal(validates('CmdbSourcePreflightResult',{...fixture,data:{...fixture.data,...patch}}),false);
});

test('monitoring registration history has explicit new and legacy origins, with unknown contact allowed',()=>{
 const history=contract.components.schemas.TicketRegistration.properties.history.items
 for(const action of ['MONITORING_REGISTER','LEGACY_MONITORING_REGISTER'])assert.equal(ajv.compile(history)({action,actor_id:'64000000-0000-4000-8000-000000000001',occurred_at:'2026-10-21T00:00:00Z',version:1}),true)
 assert.equal(ajv.compile(history)({action:'AUTO_CREATE_FROM_MONITORING_EVENT'}),false)
 assert.equal(ajv.compile(contract.components.schemas.TicketRegistration.properties.contact_phone)(''),true)
});

test('actual isolated on-call HTTP responses match 30-second schema and original receipt contracts', () => {
  const captured = read('../fixtures/oncall-http-synthetic-20261021.json');
  assert.ok(validates('OnCallBoardResult', { data: captured.board, trace_id: 'synthetic-actual-http' }));
  assert.equal(validates('OnCallBoardResult', { data: { ...captured.board, auto_refresh_sec: 15 }, trace_id: 'synthetic' }), false);
  const names = { roster: 'OnCallRosterResult', handoff: 'OnCallHandoffResult', policy: 'EscalationPolicyResult', escalation: 'EscalationStepRunResult' };
  for (const exchange of captured.exchanges) assert.ok(validates(names[exchange.kind], { data: exchange.response, trace_id: 'synthetic-actual-http' }), exchange.kind);
  assert.ok(validates('SlaMonthlyResultEnvelope', { data: captured.monthly.response, trace_id: 'synthetic-actual-http' }));
  assert.ok(validates('SlaReportResult', { data: captured.monthly.after, trace_id: 'synthetic-actual-http' }));
});

test('actual isolated service-tier zero/null fees and frozen SLA responses match contracts', () => {
  const captured = read('../fixtures/service-tier-http-synthetic-20261021.json');
  for (const exchange of captured.exchanges) {
    assert.ok(validates('ServiceTierResult', { data: exchange.response, trace_id: 'synthetic-actual-http' }));
    assert.ok(validates('ServiceTierPageResult', { data: exchange.after, trace_id: 'synthetic-actual-http' }));
  }
  assert.ok(validates('TicketSlaInspectionResult', { data: captured.inspection, trace_id: 'synthetic-actual-http' }));
});

test('actual backup calendar candidates and Shanghai historical gap match strict response contract', () => {
  const captured = read('../fixtures/backup-calendar-http-synthetic-20261021.json');
  assert.equal(captured.captures.length,3);
  for (const item of captured.captures) assert.ok(validates('BackupPlanSchedulePreviewResult', {data:item.response,trace_id:'synthetic-actual-http'}));
});

test('actual runtime catalog and unpublished current read retain all registered keys and no production approval', () => {
  const captured = read('../fixtures/runtime-catalog-http-synthetic-20261021.json');
  assert.ok(validates('RuntimeConfigurationCatalog', {data:captured.catalog,trace_id:'synthetic-actual-http'}));
  assert.deepEqual(captured.catalog.items.map(r=>r.key).sort(),read('../contracts/config-baseline.json').settings.map(r=>r.key).sort());
  assert.equal(captured.current.latest_version,0);
  assert.equal(captured.current.effective_value,null);
  assert.equal(captured.current.production_approved,false);
});


test('observation window diagnostic requires explicit bounded samples and never authorizes recovery',()=>{
 const input={condition:{comparison:'LE',threshold:'5',unit:'%'},hold_seconds:null,max_gap_seconds:null,observed_at:'2026-10-21T00:00:00Z',samples:[]};
 assert.ok(validates('ObservationWindowInput',input));
 for(const time of ['2026-10-21T24:00:00Z','2026-10-21T23:59:60Z','2026-02-30T00:00:00Z'])assert.equal(validates('ObservationWindowInput',{...input,observed_at:time}),false);
 assert.equal(validates('ObservationWindowInput',{...input,tenant_id:'override'}),false);
 assert.equal(validates('ObservationWindowInput',{...input,hold_seconds:0}),false);
 assert.equal(validates('ObservationWindowSample',{recorded_at:input.observed_at,state:'UNKNOWN',value:'0',unit:'%'}),false);
 assert.ok(validates('ObservationWindowSample',{recorded_at:input.observed_at,state:'UNKNOWN',value:null,unit:'%'}));
});

test('actual isolated observation-window HTTP results cover all statuses without publication authority',()=>{
 const capture=read('../fixtures/observation-window-http-synthetic-20261021.json');assert.equal(capture.captures.length,15);
 assert.equal(new Set(capture.captures.map(v=>v.response.status)).size,8);
 for(const item of capture.captures){assert.ok(validates('ObservationWindowInput',item.input),item.case);assert.ok(validates('ObservationWindowDiagnosticResult',{data:item.response,trace_id:'actual-synthetic-http'}),item.case);for(const flag of ['publishable','monitoring_applied','event_state_updated','recovery_confirmed','writes_performed'])assert.equal(validates('ObservationWindowDiagnostic',{...item.response,[flag]:true}),false);}
 const met=capture.captures.find(v=>v.case==='exact_boundary').response;assert.equal(validates('ObservationWindowDiagnostic',{...met,candidate_span_reached:null}),false);assert.equal(validates('ObservationWindowDiagnostic',{...met,matching_sample_count:0}),false);
 const empty=capture.captures.find(v=>v.case==='empty').response;assert.equal(validates('ObservationWindowDiagnostic',{...empty,candidate_span_reached:false}),false);
});

test('CMDB exact asset name check has a separate bounded read-only contract',()=>{
 const route=contract.paths['/assets/import-sources/{id}/cmdb-name-check'];assert.ok(route.get);assert.equal(route.post,undefined);assert.equal(route.get.requestBody,undefined);assert.equal(route.get['x-mop-read-only'],true);assert.deepEqual(route.get['x-mop-capabilities'],['assets:write']);
 const original=read('../fixtures/cmdb-source-preflight-controller.json');const named={...original,data:{...original.data,scope:'SOURCE_DICTIONARY_AND_ASSET_NAMES',database_checks:'EXACT_ASSET_NAMES_ONLY'}};
 assert.equal(validates('CmdbAssetNameCheckResult',named),true);assert.equal(validates('CmdbSourcePreflightResult',named),false);assert.equal(validates('CmdbAssetNameCheckResult',original),false);
 for(const patch of [{can_import:true},{database_checks:'PASS'},{asset_mutations:true},{production_approved:true},{existing_names:['private']}])assert.equal(validates('CmdbAssetNameCheckResult',{...named,data:{...named.data,...patch}}),false);
});
test('actual CMDB name-check HTTP capture conforms without exposing stored names',()=>{
 const fixture=read('../fixtures/cmdb-name-http-synthetic-20261021.json');assert.equal(validates('CmdbAssetNameCheckResult',{data:fixture.name_check,trace_id:'synthetic'}),true);assert.equal(validates('CmdbSourcePreflightResult',{data:fixture.dictionary,trace_id:'synthetic'}),true)
 assert.equal(fixture.name_check.error_rows,1);assert.equal(JSON.stringify(fixture.name_check).includes('SYNTHETIC_FOREIGN_ONLY'),false)
});
test('owner source listing preserves upload route and validates actual 20+3 metadata pages',()=>{
 const route=contract.paths['/assets/import-sources'];assert.ok(route.post);assert.ok(route.get);assert.equal(route.get['x-mop-read-only'],true);assert.equal(route.get.requestBody,undefined)
 const schema=route.get.responses[200].content['application/json'].schema,validate=ajv.compile(schema),capture=read('../fixtures/import-source-list-http-synthetic-20261021.json')
 for(const data of [capture.first,capture.second])assert.equal(validate({data,trace_id:'synthetic'}),true)
 for(const patch of [{can_import:true},{scope:'ALL'},{object_key:'secret'}])assert.equal(validate({data:{...capture.first,...patch},trace_id:'synthetic'}),false)
 const item={...capture.first.items[0],parsed_artifact:{}};assert.equal(validate({data:{...capture.first,items:[item],next_cursor:null},trace_id:'synthetic'}),false)
});
test('PDF draft download requires a strong frozen hash and keeps binary and JSON failures distinct',()=>{
 const route=contract.paths['/inspection-quarterly-reports/{id}/preview.pdf'];assert.ok(route.get);assert.equal(route.post,undefined);assert.equal(route.get.requestBody,undefined);assert.equal(route.get['x-mop-read-only'],true);assert.deepEqual(route.get['x-mop-capabilities'],['inspection_quarterly_reports:read'])
 const match=route.get.parameters.find(p=>p.name==='If-Match');assert.equal(match.required,true);assert.equal(ajv.compile(match.schema)('"'+'a'.repeat(64)+'"'),true);for(const bad of ['*','a'.repeat(64),'W/"'+'a'.repeat(64)+'"'])assert.equal(ajv.compile(match.schema)(bad),false)
 assert.ok(route.get.responses[200].content['application/pdf']);for(const status of [401,403,412,413,428,503])assert.ok(route.get.responses[status].content['application/json']);assert.equal(route.get.responses[200].headers['X-MOP-Preview-Status'].schema.const,'DRAFT')
})

test('登记监控测试只接受配置摘要并保留CSRF、权限复核和单次含义',()=>{
 const op=contract.paths['/monitoring-onboarding/registered-targets/{id}/test'].post;
 assert.deepEqual(op['x-mop-capabilities'],['assets:read','configurations:read','configurations:write']);assert.equal(op['x-mop-csrf-required'],true);assert.equal(op['x-mop-arbitrary-destination-accepted'],false);assert.equal(op['x-mop-current-membership-recheck'],true);
 const schema=op.requestBody.content['application/json'].schema,validate=ajv.compile(schema);assert.equal(validate({configuration_sha256:'a'.repeat(64)}),true);assert.equal(validate({configuration_sha256:'a'.repeat(64),endpoint:'http://192.0.2.1:1/'}),false);
 const report={target_id:id('1'),configuration_sha256:'a'.repeat(64),probe_status:'FAILED',continuous_collection_verified:false,device_identity_verified:false,health_verified:false,sample_count:null,matched_metrics:[],reason:'CONNECTION_FAILED'};
 assert.equal(validates('MonitoringRegisteredProbeReport',report),true);for(const patch of [{health_verified:true},{continuous_collection_verified:true},{password:'secret'},{probe_status:'HEALTHY'}])assert.equal(validates('MonitoringRegisteredProbeReport',{...report,...patch}),false);
 for(const status of ['400','401','403','404','409','429','503'])assert.equal(op.responses[status].content['application/json'].schema.$ref,'#/components/schemas/Error');
});

test('expanded monitoring view is explicit and does not add arbitrary destination input', () => {
  const operation = contract.paths['/monitoring-onboarding/registered-targets'].get;
  assert.equal(operation.parameters.length, 1);
  assert.equal(operation.parameters[0].name, 'view');
  assert.equal(operation.parameters[0].in, 'query');
  assert.equal(operation.parameters[0].required, false);
  assert.deepEqual(operation.parameters[0].schema.enum, ['expanded']);
  assert.match(operation.parameters[0].description, /CONFIGURATION_VIEW_REQUIRED/);
  assert.ok(operation.responses['409']);
  assert.equal(operation['x-mop-arbitrary-destination-accepted'], false);
  assert.equal(operation['x-mop-read-only'], true);
});

test('monitoring plan request accepts bounded drafts but rejects identity and credential overrides', () => {
  const inventory = read('../deploy/examples/monitoring/inventory.disabled.json');
  const body = { version: 1, defaults: inventory.defaults, assets: inventory.assets.map(({ address, ...asset }) => asset) };
  assert.equal(validates('MonitoringPlanRequest', body), true);
  for (const key of ['tenant_id', 'collectors', 'endpoint', 'password', 'enabled']) assert.equal(validates('MonitoringPlanRequest', { ...body, [key]: 'forbidden' }), false);
  const overridden = structuredClone(body);
  overridden.assets[0].address = '192.0.2.99';
  assert.equal(validates('MonitoringPlanRequest', overridden), false);
  overridden.assets[0] = { asset_id: body.assets[0].asset_id, profiles: [{ preset_id: 'network-snmp', auth_profile: 'unapproved' }] };
  assert.equal(validates('MonitoringPlanRequest', overridden), false);
});

test('prepared monitoring configuration can never enable collection', () => {
  const golden = read('../fixtures/monitoring-inventory-golden.json');
  assert.equal(validates('MonitoringPreparedConfiguration', golden.configuration), true);
  const active = structuredClone(golden.configuration);
  active.targets[0].enabled = true;
  assert.equal(validates('MonitoringPreparedConfiguration', active), false);
});

const preparedPlanFixture = () => {
  const golden = read('../fixtures/monitoring-inventory-golden.json');
  return { valid: true, diagnostics: [], configuration: golden.configuration, configuration_sha256: golden.configuration_sha256,
    catalog_sha256: 'a'.repeat(64), target_count: 15, network_accessed: false, applied: false,
    continuous_collection_verified: false, device_identity_verified: false };
};

test('monitoring plan distinguishes valid preparation from invalid drafts and partial output', () => {
  const valid = preparedPlanFixture();
  assert.equal(validates('MonitoringPreparedPlan', valid), true);
  const invalid = { ...valid, valid: false, diagnostics: [{ asset_index: 1, profile_index: 2, code: 'COLLECTOR_REQUIRED' }], configuration: null, configuration_sha256: null, target_count: 0 };
  assert.equal(validates('MonitoringPreparedPlan', invalid), true);
  assert.equal(validates('MonitoringPreparedPlan', { ...invalid, configuration: valid.configuration }), false);
  assert.equal(validates('MonitoringPreparedPlan', { ...invalid, diagnostics: [] }), false);
  assert.equal(validates('MonitoringPreparedPlan', { ...valid, diagnostics: invalid.diagnostics }), false);
  for (const field of ['network_accessed', 'applied', 'continuous_collection_verified', 'device_identity_verified']) assert.equal(validates('MonitoringPreparedPlan', { ...valid, [field]: true }), false);
});

test('valid monitoring plan requires current asset scope to have been checked', () => {
  const data = { tenant_id: '79000000-0000-4000-8000-000000000001', actor_id: '79000000-0000-4000-8000-000000000002',
    permission_version: 7, plan: preparedPlanFixture(), asset_scope_checked: true, source: 'SERVER_AUTHORIZED_PREPARATION' };
  assert.equal(validates('MonitoringPlanResult', { data, trace_id: 'test' }), true);
  assert.equal(validates('MonitoringPlanResult', { data: { ...data, asset_scope_checked: false }, trace_id: 'test' }), false);
});

test('monitoring catalog carries parameter schemas without claiming verified device setup', () => {
  const catalog = read('../config/monitoring/presets.v1.json');
  const result = { version: 1, catalog_sha256: 'a'.repeat(64), presets: catalog.presets.map(preset => ({ ...preset,
    profile_schema: { type: 'object', additionalProperties: false, properties: { preset_id: { const: preset.id } }, required: ['preset_id'] },
    server_collector_required: ['snmp', 'blackbox'].includes(preset.test_kind), server_collector_configured: false, device_setup_verified: false })) };
  assert.equal(validates('MonitoringPresetCatalog', result), true);
  result.presets[0].device_setup_verified = true;
  assert.equal(validates('MonitoringPresetCatalog', result), false);
});

test('monitoring preparation endpoint is privileged CSRF-protected and has no network or write receipt', () => {
  const operation = contract.paths['/monitoring-onboarding/plans'].post;
  assert.equal(operation['x-mop-read-only'], true);
  assert.equal(operation['x-mop-csrf-required'], true);
  assert.equal(operation['x-mop-network-io'], false);
  assert.equal(operation['x-mop-idempotency-receipt'], false);
  assert.equal(operation['x-mop-max-body-bytes'], 65536);
  assert.deepEqual(operation['x-mop-roles'], ['padmin']);
  assert.deepEqual(operation['x-mop-capabilities'], ['assets:read', 'configurations:read', 'configurations:write']);
  assert.ok(operation.parameters.some(parameter => parameter.name === 'X-CSRF-Token' && parameter.required));
  assert.equal(contract.paths['/monitoring-onboarding/presets'].get['x-mop-network-io'], false);
});

const scrapeObservation = () => ({ target_id: '79000000-0000-4000-8000-000000000003', configuration_sha256: 'a'.repeat(64),
  observation_status: 'SCRAPE_SUCCEEDED', reason: 'SCRAPE_SAMPLE_CURRENT', evaluated_at: '2026-10-22T12:00:00Z', checked_at: '2026-10-22T12:00:00Z',
  sample_at: '2026-10-22T11:59:55Z', sample_age_seconds: 5, scrape_up: 1, max_age_seconds: 60, lookback_seconds: 300,
  network_attempted: true, continuous_collection_verified: false, active_configuration_verified: false, device_identity_verified: false, health_verified: false });

test('scrape observation separates current up, failure and stale null state', () => {
  const r = scrapeObservation();
  assert.equal(validates('MonitoringScrapeObservationReport', r), true);
  assert.equal(validates('MonitoringScrapeObservationReport', { ...r, observation_status: 'SCRAPE_FAILED', scrape_up: 0 }), true);
  assert.equal(validates('MonitoringScrapeObservationReport', { ...r, observation_status: 'SCRAPE_FAILED' }), false);
  const stale = { ...r, observation_status: 'STALE', sample_age_seconds: 61, scrape_up: null };
  assert.equal(validates('MonitoringScrapeObservationReport', stale), true);
  assert.equal(validates('MonitoringScrapeObservationReport', { ...stale, scrape_up: 1 }), false);
  assert.equal(validates('MonitoringScrapeObservationReport', { ...r, sample_at: null }), false);
});

test('scrape no-data and invalid-source cannot invent a sample or healthy flags', () => {
  const r = scrapeObservation();
  for (const state of ['NO_DATA', 'SOURCE_INVALID_RESPONSE', 'SOURCE_UNAVAILABLE']) {
    const missing = { ...r, observation_status: state, sample_at: null, sample_age_seconds: null, scrape_up: null };
    assert.equal(validates('MonitoringScrapeObservationReport', missing), true);
    assert.equal(validates('MonitoringScrapeObservationReport', { ...missing, scrape_up: 0 }), false);
  }
  for (const flag of ['continuous_collection_verified', 'active_configuration_verified', 'device_identity_verified', 'health_verified'])
    assert.equal(validates('MonitoringScrapeObservationReport', { ...r, [flag]: true }), false);
});

test('unconfigured disabled and unsupported observations cannot claim network work', () => {
  const base = { ...scrapeObservation(), network_attempted: false, evaluated_at: null, checked_at: null, sample_at: null,
    sample_age_seconds: null, scrape_up: null, max_age_seconds: null, lookback_seconds: null };
  for (const state of ['CONFIG_PENDING', 'TARGET_DISABLED', 'UNSUPPORTED_ENGINE']) {
    const r = { ...base, observation_status: state };
    assert.equal(validates('MonitoringScrapeObservationReport', r), true);
    assert.equal(validates('MonitoringScrapeObservationReport', { ...r, network_attempted: true }), false);
  }
});

test('scrape observation accepts only an authenticated registered id and has no cached success', () => {
  const operation = contract.paths['/monitoring-onboarding/registered-targets/{id}/observation'].get;
  assert.deepEqual(operation['x-mop-capabilities'], ['assets:read', 'configurations:read']);
  assert.equal(operation['x-mop-read-only'], true);
  assert.equal(operation['x-mop-arbitrary-destination-accepted'], false);
  assert.equal(operation['x-mop-cache-fallback'], false);
  assert.deepEqual(operation.parameters.map(p => [p.name, p.in]), [['id', 'path']]);
  assert.equal(operation.requestBody, undefined);
});

test('service observation can report healthy scrape but failed target signal', () => {
  const signal = { metric: 'redis_up', status: 'SIGNAL_FAILURE', value: 0, sample_at: '2026-10-22T11:59:55Z', sample_age_seconds: 5, health_verified: false };
  const r = { ...scrapeObservation(), service_signal: signal };
  assert.equal(validates('MonitoringServiceObservationReport', r), true);
  assert.equal(validates('MonitoringServiceObservationReport', { ...r, observation_status: 'SCRAPE_FAILED', scrape_up: 0 }), false);
  assert.equal(validates('MonitoringServiceSignal', { ...signal, value: 1 }), false);
  assert.equal(validates('MonitoringServiceSignal', { ...signal, health_verified: true }), false);
});

test('stale or out-of-sync service samples never expose a successful value', () => {
  for (const status of ['STALE','OUT_OF_SYNC']) {
    const r = { metric: 'probe_success', status, value: null, sample_at: '2026-10-22T11:59:55Z', sample_age_seconds: 5, health_verified: false };
    assert.equal(validates('MonitoringServiceSignal', r), true);
    assert.equal(validates('MonitoringServiceSignal', { ...r, value: 1 }), false);
  }
});

test('unsupported unchecked and blocked service signals do not invent samples', () => {
  for (const status of ['NOT_SUPPORTED','NOT_CHECKED','BLOCKED_SCRAPE','NO_DATA','INVALID_RESPONSE']) {
    const r = { metric: status === 'NOT_SUPPORTED' ? null : 'pg_up', status, value: null, sample_at: null, sample_age_seconds: null, health_verified: false };
    assert.equal(validates('MonitoringServiceSignal', r), true);
    assert.equal(validates('MonitoringServiceSignal', { ...r, value: 0 }), false);
  }
});

test('service observation preserves read-only scope and has no metric override', () => {
  const op = contract.paths['/monitoring-onboarding/registered-targets/{id}/service-observation'].get;
  assert.equal(op['x-mop-read-only'], true);
  assert.deepEqual(op['x-mop-capabilities'], ['assets:read','configurations:read']);
  assert.deepEqual(op.parameters.map(p => p.name), ['id']);
  assert.equal(op['x-mop-current-membership-recheck'], true);
});

test('customer topology is bounded read-only current tenant data with version-pinned pagination',()=>{
 const op=contract.paths['/customer-topology'].get;
 assert.equal(op['x-mop-capability'],'assets:read');assert.equal(op['x-mop-read-only'],true);assert.equal(op['x-mop-permission-version-recheck'],true);
 assert.deepEqual(op.parameters.map(p=>p.name),['offset','graph_version','asset_id']);assert.equal(contract.paths['/customer-topology'].post,undefined);
});
test('customer topology never labels drafts as verified physical or applied monitoring links',()=>{
 const value={tenant_id:id('1'),version:0,updated_at:null,source:'postgresql',scope:'CURRENT_TENANT',lifecycle_status:'DRAFT',physical_topology_verified:false,monitoring_applied:false,focus_asset_id:null,offset:0,page_size:50,total_edges:0,has_more:false,nodes:[],edges:[]};
 assert.equal(validates('CustomerTopology',value),true);
 for(const patch of [{physical_topology_verified:true},{monitoring_applied:true},{lifecycle_status:'ACTIVE'},{page_size:100},{tenant_id:null},{total_edges:10001}])assert.equal(validates('CustomerTopology',{...value,...patch}),false);
});
test('customer topology rejects oversized node and edge payloads',()=>{
 const schema=contract.components.schemas.CustomerTopology;assert.equal(schema.properties.nodes.maxItems,101);assert.equal(schema.properties.edges.maxItems,50);assert.equal(schema.additionalProperties,false);
});

test('customer topology accepts the same opaque site codes as the asset ledger, not only UUIDs',()=>{
 const node={id:id('2'),name:'Synthetic switch',type:'switch',management_ip:'192.0.2.1',site_id:'site-a-main'};
 const value={tenant_id:id('1'),version:0,updated_at:null,source:'postgresql',scope:'CURRENT_TENANT',lifecycle_status:'DRAFT',physical_topology_verified:false,monitoring_applied:false,focus_asset_id:node.id,offset:0,page_size:50,total_edges:0,has_more:false,nodes:[node],edges:[]};
 assert.equal(validates('CustomerTopology',value),true);assert.equal(validates('CustomerTopology',{...value,nodes:[{...node,site_id:17}]}),false);
});


test('explicit demo samples are tenant-bound inputs and unavailable sessions cannot carry samples', () => {
  const data={tenant_id:id('1'),actor_id:id('2'),page:'capacity-estimates',available:false,source:'EXPLICIT_DEMO_INPUT',input:null,business_data_written:false,provider_accessed:false};
  assert.ok(validates('DemoPageExampleResult',{data,trace_id:'demo'}));
  for(const patch of [{input:{hosts:7}},{business_data_written:true},{provider_accessed:true}]) assert.equal(validates('DemoPageExampleResult',{data:{...data,...patch},trace_id:'demo'}),false);
  assert.ok(validates('DemoPageExampleResult',{data:{...data,available:true,input:{hosts:'7'}},trace_id:'demo'}));
  assert.equal(contract.paths['/demo/examples/{page}'].get['x-mop-current-membership-recheck'],true);
});

test('header bell is an internal read-only exact filter and cannot acknowledge events', () => {
  const path = contract.paths['/dashboard/alert-bell'];
  assert.deepEqual(Object.keys(path), ['get']);
  assert.deepEqual(path.get['x-mop-capabilities'], ['dashboard:read', 'tickets:read']);
  assert.deepEqual(path.get['x-mop-roles'], ['padmin', 'msp']);
  assert.equal(path.get['x-mop-snapshot-isolation'], 'REPEATABLE_READ');
  assert.equal(path.get['x-mop-suppression-excluded'], false);
  const data = { tenant_id:id('1'), actor_id:id('2'), permission_version:1, observed_at:'2026-10-23T12:00:00Z',
    scope:'CURRENT_TENANT_INTERNAL', source:'postgresql', metric_version:'MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1',
    filter:{monitor_state:'PROBLEM',handling_state:'NEW'}, total:0, item_limit:5,
    sort:'LAST_OCCURRED_AT_DESC_ID_DESC',items:[],writes_performed:false };
  assert.ok(validates('DashboardAlertBell',data));
  for (const patch of [{total:-1},{item_limit:6},{writes_performed:true},{scope:'ALL_TENANTS'},
    {filter:{monitor_state:'RECOVERED',handling_state:'NEW'}}]) {
    assert.equal(validates('DashboardAlertBell',{...data,...patch}), false);
  }
});

test('event metrics contract exposes read-only authorized event-time statistics without verification claims',()=>{
 const op=contract.paths['/metrics/monitoring-events'];assert.deepEqual(Object.keys(op),['get']);assert.equal(op.get['x-mop-current-membership-recheck'],true);assert.deepEqual(op.get['x-mop-required-capabilities'],['dashboard:read','tickets:read']);assert.equal(op.get['x-mop-transaction-isolation'],'READ_ONLY_REPEATABLE_READ');
 const data={tenant_id:id('1'),actor_id:id('2'),permission_version:1,observed_at:'2026-10-23T20:00:00Z',timezone:'Asia/Shanghai',window:'HOURS_24',view:'RECENT_PROBLEMS',period:{from:'2026-10-22T20:00:00Z',to:'2026-10-23T20:00:00Z'},metric_version:'mop-event-acknowledgement-v1',statistics_version:'mop-elapsed-statistics-v1',current_problems:{total:0,by_severity:{DISASTER:0,HIGH:0,AVERAGE:0,WARNING:0,INFO:0},population_basis:'PROBLEM_AND_SUPPRESSION_NONE'},mtta:{status:'NO_ACKNOWLEDGED_SAMPLES',population_count:0,sample_count:0,unacknowledged_count:0,invalid_count:0,mean_millis:null,median_millis:null,p95_millis:null,target_millis:300000,target_breached:null,source_manifest_sha256:'a'.repeat(64),population_basis:'PROBLEM_AT_IN_HALF_OPEN_WINDOW_ALL_SUPPRESSION_STATES',timestamp_source:'RECORDED_FIRST_ACK_AT_NOT_TICKET_ACCEPTANCE',maximum_samples:10000},items:[],next:null,source:'CURRENT_AUTHORIZED_POSTGRESQL_EVENT_PROJECTION',collection_freshness_verified:false,clock_accuracy_verified:false,frozen_report:false,writes_performed:false};
 assert.ok(validates('EventOperationalMetrics',data));for(const patch of [{writes_performed:true},{frozen_report:true},{collection_freshness_verified:true},{clock_accuracy_verified:true},{metric_version:'legacy'},{current_problems:{...data.current_problems,total:-1}},{mtta:{...data.mtta,mean_millis:0}}])assert.equal(validates('EventOperationalMetrics',{...data,...patch}),false);
});

test('backup run history requires independent read grant and keeps undefined failure policy null',()=>{
 const op=contract.paths['/assets/{assetId}/backup-runs'];assert.deepEqual(Object.keys(op),['get']);assert.equal(op.get['x-mop-independent-operation-grant'],'RUN_HISTORY');assert.equal(op.get['x-mop-current-membership-recheck'],true);
 const data={tenant_id:id('1'),actor_id:id('2'),permission_version:1,asset_id:id('3'),observed_at:'2026-10-23T20:00:00Z',page_size:20,total_count:0,items:[],next:null,failure_count_semantics:null,consecutive_failure_count:null,automatic_ticket_status:'CONFIG_PENDING_Q22',automatic_ticket_allowed:false,failure_ticket_threshold:2,ticket_deadline_seconds:600,snapshot_scope:'SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT',source:'CURRENT_AUTHORIZED_BACKUP_RUN_JOURNAL',snapshot_payload_verified:true,full_journal_chain_verified:false,object_storage_accessed:false,device_execution_authorized:false,writes_performed:false};assert.ok(validates('BackupRunHistory',data));for(const patch of [{automatic_ticket_allowed:true},{consecutive_failure_count:0},{failure_count_semantics:'ATTEMPT'},{object_storage_accessed:true},{full_journal_chain_verified:true},{writes_performed:true},{total_count:-1}])assert.equal(validates('BackupRunHistory',{...data,...patch}),false);
});

test('noise trend keeps final notification counts unknown and fixes complete common bucket presets',()=>{
 const zero={RAW_INTAKE:0,MERGE:0,FLAP:0,DEPENDENCY:0,MAINTENANCE:0,EFFECTIVE_ALERT:0};
 const data={tenant_id:id('1'),actor_id:id('2'),permission_version:1,observed_at:'2026-10-23T20:00:00Z',timezone:'Asia/Shanghai',window:'HOURS_24',bucket_seconds:3600,metric_version:'MOP_EVENT_NOISE_TREND_V1',period:{from:'2026-10-22T20:00:00Z',to:'2026-10-23T20:00:00Z'},buckets:Array.from({length:24},(_,n)=>({from:new Date(Date.parse('2026-10-22T20:00:00Z')+n*3600000).toISOString(),to:new Date(Date.parse('2026-10-22T20:00:00Z')+(n+1)*3600000).toISOString(),raw_count:0,after_fingerprint_merge_count:0,layers:zero})),totals:zero,markers:[],markers_truncated:false,marker_limit:8,event_metadata_basis:'CURRENT_EVENT_PROJECTION',final_notification_count:null,noise_reduction_percent:null,noise_reduction_status:'FINAL_NOTIFICATION_SOURCE_UNAVAILABLE',timestamp_basis:'NOISE_LEDGER_RECORDED_AT',source:'CURRENT_AUTHORIZED_NOISE_LEDGER',collection_freshness_verified:false,final_notifications_verified:false,writes_performed:false};
 assert.ok(validates('EventNoiseTrend',data));for(const patch of [{final_notification_count:0},{noise_reduction_percent:0},{collection_freshness_verified:true},{final_notifications_verified:true},{buckets:[...data.buckets,data.buckets[0]]},{window:'DAYS_7'},{bucket_seconds:60}])assert.equal(validates('EventNoiseTrend',{...data,...patch}),false);
 assert.deepEqual(Object.keys(contract.paths['/metrics/event-noise-trend']),['get']);assert.equal(contract.paths['/metrics/event-noise-trend/records'].get['x-mop-original-record-deletion'],false);
});
test('raw noise detail contract retains paired cursor and does not authorize deletion',()=>{
 const data={tenant_id:id('1'),actor_id:id('2'),permission_version:1,observed_at:'2026-10-23T20:00:00Z',from:'2026-10-23T19:00:00Z',to:'2026-10-23T20:00:00Z',page_size:20,total_count:0,items:[],next:null,layer:'RAW_INTAKE',timestamp_basis:'NOISE_LEDGER_RECORDED_AT',source:'CURRENT_AUTHORIZED_NOISE_LEDGER',snapshot_scope:'SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT',writes_performed:false};assert.ok(validates('EventNoiseRecords',data));assert.equal(validates('EventNoiseRecords',{...data,next:{before:data.from}}),false);assert.equal(validates('EventNoiseRecords',{...data,writes_performed:true}),false);assert.equal(validates('EventNoiseRecords',{...data,layer:'EFFECTIVE_ALERT'}),false);
});
