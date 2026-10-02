import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const string = { type: 'string' };
const nullableString = { type: ['string', 'null'] };
const ref = (name) => ({ $ref: `#/components/schemas/${name}` });
const object = (properties, required = Object.keys(properties)) => ({ type: 'object', properties, required, additionalProperties: false });
const envelope = (data) => object({ data, trace_id: string });
const list = (items) => envelope(object({ items: { type: 'array', items }, next_cursor: nullableString, has_more: { type: 'boolean' }, source: { const: 'postgresql' }, page_size_limit: { const: 100 }, page_size_limit_kind: { const: 'test_technical_limit' } }));
const types = ['router', 'switch', 'firewall', 'ap', 'wireless_controller', 'other'];
const errorResponse = (description) => ({ description, content: { 'application/json': { schema: ref('Error') } } });
const errors = Object.fromEntries(Object.entries({ 400: '参数或未知字段错误', 401: '会话失效', 403: '动作权限或CSRF错误', 404: '对象不存在或不可见', 405: '方法不允许', 409: '版本或幂等冲突', 415: '内容类型不支持', 422: '状态禁止或CONFIG_PENDING', 429: '配额限制', 500: '内部错误；按trace_id排查', 503: '依赖未就绪' }).map(([code, label]) => [code, errorResponse(label)]));
const parameter = (name, location, schema, required = true) => ({ name, in: location, required, schema });
const headers = [parameter('Idempotency-Key', 'header', { type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9._:-]{1,128}$' }), parameter('X-CSRF-Token', 'header', string)];
const jsonBody = (schema) => ({ required: true, content: { 'application/json': { schema } } });
const action = (operationId, summary, schema, options = {}) => ({ operationId, summary, ...options, responses: { 200: { description: '共享测试环境事务完成或查询成功；不代表真实监控纳管或生产验收', content: { 'application/json': { schema } } }, ...errors } });
const mutation = (operationId, summary, input, output, options = {}) => action(operationId, summary, output, { parameters: headers, requestBody: jsonBody(input), ...options });
const pathId = parameter('id', 'path', { type: 'string', format: 'uuid' });
const pathKey = parameter('key', 'path', string);
const version = object({ expected_version: { type: 'integer', minimum: 1 } });
const evidence = object({ reference: { type: 'string', pattern: '^document-ref:[A-Za-z0-9._:-]+$', maxLength: 160, description: '仅非敏感资料标识；后缀不得是磁盘路径或包含token/password/secret/credential/private-key等凭据正文标记。不是附件下载地址，不核验文档真实性。' }, title: { type: 'string', minLength: 1, maxLength: 200 }, sha256: { type: 'string', pattern: '^[A-Fa-f0-9]{64}$' } });
const boundedComment = { type: 'string', minLength: 1, maxLength: 500, description: '按Unicode code point计数，拒绝控制字符；不能包含凭据或token。' };
const revision = object({
  id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, configuration_key: string,
  proposed_value: { not: { type: 'null' }, description: '按登记类型校验；任意嵌套层级的数值转为double后须为有限值，否则400；嵌套null保留未知语义。' }, reason: boundedComment, evidence_refs: { type: 'array', minItems: 1, maxItems: 5, items: evidence },
  workflow_status: { type: 'string', enum: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'] }, version: { type: 'integer', minimum: 1 },
  created_by: { type: 'string', format: 'uuid' }, created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' },
  reviewed_by: { type: ['string', 'null'], format: 'uuid' }, reviewed_at: { type: ['string', 'null'], format: 'date-time' }, review_comment: nullableString,
  decision_status: { type: 'string', enum: ['pending', 'contract_fixed'] }, effective_value: { type: 'null' }, confirmation_status: { const: 'UNVERIFIED' }, source: { const: 'postgresql' },
  history: { type: 'array', items: object({ action: { type: 'string', enum: ['CREATE', 'SUBMIT', 'APPROVE', 'REJECT', 'WITHDRAW'] }, actor_id: { type: 'string', format: 'uuid' }, occurred_at: { type: 'string', format: 'date-time' }, from_status: nullableString, to_status: string, comment: nullableString }) },
});
const blocked = (operationId, summary, pathParameter) => ({ ...mutation(operationId, summary, version, ref('ResourceResult'), { parameters: [pathParameter, ...headers] }), responses: errors });
const tenant = object({ id: { type: 'string', format: 'uuid' }, name: string });
const asset = object({ id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, name: string, type: { type: 'string', enum: types }, management_ip: string, site_id: string, status: { type: 'string', enum: ['unknown', 'ok', 'problem', 'maintenance', 'disabled'] }, lifecycle_status: string, source: { type: 'string', enum: ['test_seed', 'manual'] }, sampled_at: nullableString, unknown_reason: nullableString, version: { type: 'integer', minimum: 1 }, created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' }, availability: string, sync_state: string }, ['id', 'tenant_id', 'name', 'type', 'management_ip', 'site_id', 'status', 'lifecycle_status', 'source', 'sampled_at', 'unknown_reason', 'version', 'created_at', 'updated_at']);
const ticketCreate = JSON.parse(readFileSync(new URL('../contracts/design-core.openapi.json', import.meta.url), 'utf8')).components.schemas.TicketCreate;
ticketCreate.properties.attachment_ids.maxItems = 3;
ticketCreate.properties.attachment_ids.description = '可省略或为空；仅绑定同租户、同报障人上传的READY原图。每张1..5_000_000 bytes，原登记累计最多3张；图片失败可先无图登记后CAS追加。';
const uuid = { type: 'string', format: 'uuid' };
const dateTime = { type: 'string', format: 'date-time' };
const positiveVersion = { type: 'integer', minimum: 1, description: '服务端64位正整数版本；客户端使用所见版本，冲突409，不自动覆盖。' };
const configurationKey = { type: 'string', minLength: 1, maxLength: 128, pattern: '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' };
const diagnosticQuestions = { type: ['array', 'null'], items: { type: 'string', pattern: '^Q[0-9]{2}$' }, description: '保留登记中的原Q编号；null表示来源缺失，空数组表示来源明确没有关联Q，不补造结论。' };
const configurationDiagnostic = object({
  key: configurationKey, status: { const: 'CONFIG_PENDING' }, effective_value: { type: 'null' },
  context: object({ tenant_id: uuid, environment: { const: 'remote_test' } }), observed_at: dateTime,
  registry: object({ origin: { const: 'DESIGN_REGISTRY' }, version: positiveVersion, type_label: nullableString, unit_label: nullableString, scope_label: nullableString, source_ref: nullableString, decision_status_label: nullableString, classification_label: nullableString, question_ids: diagnosticQuestions }),
  recent_proposals: object({ items: { type: 'array', maxItems: 5, items: object({ id: uuid, version: positiveVersion, workflow_status: { type: 'string', enum: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'] }, origin: { const: 'INTERNAL_REVISION' }, confirmation_status: { const: 'UNVERIFIED' }, evidence_input_status: { const: 'REFERENCE_ONLY' }, evidence_reference_count: { type: ['integer', 'null'], minimum: 0 } }) }, truncated: { type: 'boolean' } }),
  runtime_definition: object({ availability: { const: 'NOT_IMPLEMENTED' }, schema_version: { type: 'null' }, allowed_layers: { type: 'null' }, precedence: { type: 'null' }, resolved_scope: { type: 'null' } }),
  publication: object({ availability: { const: 'NOT_IMPLEMENTED' }, record_ref: { type: 'null' }, revision_id: { type: 'null' }, revision_version: { type: 'null' }, effective_at: { type: 'null' }, authorization_verified: { type: 'null' }, production_approval_verified: { type: 'null' } }),
  customer_verification: object({ availability: { const: 'NOT_IMPLEMENTED' }, required: { type: 'null' }, decision_ref: { type: 'null' }, authority_verified: { type: 'null' }, authenticity_verified: { type: 'null' } }),
  reasons: { type: 'array', minItems: 1, items: object({ code: { type: 'string', enum: ['DESIGN_REGISTRY_NOT_RUNTIME_CONFIG', 'RUNTIME_DEFINITION_SOURCE_UNAVAILABLE', 'PUBLICATION_SOURCE_UNAVAILABLE', 'CUSTOMER_VERIFICATION_SOURCE_UNAVAILABLE', 'INTERNAL_REVISION_NOT_RUNTIME_PUBLICATION', 'PROPOSAL_EVIDENCE_REFERENCES_UNAVAILABLE', 'REGISTRY_QUESTIONS_UNAVAILABLE'] }, source_kind: { type: 'string', enum: ['DESIGN_REGISTRY', 'INTERNAL_REVISION', 'RUNTIME_DEFINITION', 'RUNTIME_PUBLICATION', 'CUSTOMER_VERIFICATION'] }, source_id: nullableString, source_version: { type: ['integer', 'null'], minimum: 1 } }) },
  question_ids: diagnosticQuestions,
});
configurationDiagnostic.properties.recent_proposals.allOf = [{ if: { properties: { truncated: { const: true } } }, then: { properties: { items: { minItems: 5 } } } }];
const photoFacts = { purpose: { const: 'customer_report_photo' }, scan_status: { const: 'UNSCANNED' }, retention_until: { type: 'null' }, production_approval: { const: 'UNVERIFIED' }, policy_source: { const: 'USER_DEVELOPMENT_DIRECTION' } };
const photoPolicy = object({ ...photoFacts, allowed_media_types: { type: ['array', 'null'], minItems: 1, maxItems: 2, uniqueItems: true, items: { type: 'string', enum: ['image/jpeg', 'image/png'] }, description: 'null表示白名单未配置或无效，上传返回CONFIG_PENDING；解码不等于病毒扫描或客户生产批准。' }, max_size_bytes: { const: 5000000 }, max_attachments: { const: 3 } });
const reportPhoto = object({
  id: uuid, tenant_id: uuid, ...photoFacts, original_name: { type: 'string', minLength: 1, maxLength: 200 },
  media_type: { type: 'string', enum: ['image/jpeg', 'image/png'] }, size_bytes: { type: 'integer', minimum: 1, maximum: 5000000 }, sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' },
  upload_status: { type: 'string', enum: ['PENDING', 'READY', 'FAILED'] }, created_by: uuid, ticket_id: { type: ['string', 'null'], format: 'uuid' }, operation_id: uuid,
  version: positiveVersion, created_at: dateTime, updated_at: dateTime, source: { const: 'postgresql' },
});
const photoUpload = object({ file: { type: 'string', format: 'binary', 'x-mop-max-bytes': 5000000, description: '原图不重编码；仅jpg/jpeg/png文件名，扩展名须匹配真实解码内容。未声明MIME或application/octet-stream视为未指定声明；其余声明MIME须匹配实际类型。每张1..5_000_000 bytes；尺寸<=16_384且像素<=25_000_000为技术解码防护。未知/重复part及超大multipart均400。' }, purpose: { const: 'customer_report_photo' } });
const photoAttachments = object({ expected_version: positiveVersion, attachment_ids: { type: 'array', minItems: 1, maxItems: 3, uniqueItems: true, items: uuid, description: '仅报障人可追加自己上传的READY照片；同租户，未绑定其他登记，原登记累计最多3张。同键重放复用结果，旧版本409。' } });
const uuidPattern = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const contentGrant = object({ content_url: { type: 'string', pattern: `^/api/v1/files/${uuidPattern}/content\\?grant=${uuidPattern}$`, description: '平台相对地址；grant绑定actor/tenant/file/permission_version，15分钟内每次访问仍重新授权。不是S3预签名直链；同键重放不会延长过期时间。' }, expires_at: dateTime });
const ticketRegistration = object({
  id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, receipt_no: string,
  title: string, description: string, source: { const: 'customer_report' }, category: { type: 'string', enum: ['网络不通', '网速慢', 'WiFi问题', '设备异常', '其他'] },
  impact_scope: { type: 'string', enum: ['company', 'partial_area', 'individual'] }, urgency: { type: 'string', enum: ['normal', 'urgent'] },
  asset_id: { type: ['string', 'null'], format: 'uuid' }, asset_name: nullableString,
  reporter_id: { type: 'string', format: 'uuid' }, contact_name: string, contact_phone: string, contact_binding_status: { const: 'UNVERIFIED' },
  registration_status: { const: 'REGISTERED' }, state: { type: 'null' }, external_id: { type: 'null' }, external_state: { type: 'null' }, sync_state: { const: 'NOT_APPLICABLE' },
  authority_source: { const: 'MOP' }, authority_status: { const: 'RULES_PENDING' }, priority: { type: 'null' }, response_due_at: { type: 'null' }, recovery_due_at: { type: 'null' },
  operation_id: uuid, version: positiveVersion, created_at: dateTime, updated_at: dateTime,
  attachments: { type: 'array', maxItems: 3, items: ref('ReportPhoto') },
  history: { type: 'array', items: object({ action: { type: 'string', enum: ['REGISTER', 'ADD_ATTACHMENTS'] }, actor_id: uuid, occurred_at: dateTime, version: positiveVersion }) },
});
const operation = object({
  id: { type: 'string', format: 'uuid' }, tenant_id: { type: 'string', format: 'uuid' }, kind: { const: 'CUSTOMER_REPORT_REGISTRATION' },
  status: { const: 'PENDING' }, step: { const: 'AWAITING_TICKET_CONFIGURATION' }, progress: { type: 'null' }, result_ref: { type: 'string', pattern: `^/api/v1/tickets/${uuidPattern}$` },
  error: object({ code: { const: 'CONFIG_PENDING' }, message: string, retryable: { const: false }, question_ids: { type: 'array', items: string } }),
  created_at: { type: 'string', format: 'date-time' }, updated_at: { type: 'string', format: 'date-time' },
});
const fileOperation = object({
  id: uuid, tenant_id: uuid, kind: { const: 'FILE_UPLOAD' }, status: { type: 'string', enum: ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'] },
  step: { type: 'string', enum: ['QUEUED', 'UPLOADING', 'RETRY_WAIT', 'STORED', 'AUTHORIZATION_REVOKED', 'STORAGE_CONFLICT'] }, progress: { type: 'null' },
  result_ref: { type: 'string', pattern: `^/api/v1/files/${uuidPattern}$` },
  error: { anyOf: [{ type: 'null' }, object({ code: { type: 'string', enum: ['UPSTREAM_UNAVAILABLE', 'ACTION_FORBIDDEN', 'FILE_INTEGRITY_CONFLICT'] }, message: string, retryable: { type: 'boolean' }, question_ids: { type: 'array', items: string } })] },
  created_at: dateTime, updated_at: dateTime,
});
fileOperation.allOf = [{ oneOf: [
  ...[['QUEUED', 'PENDING'], ['UPLOADING', 'RUNNING'], ['STORED', 'SUCCEEDED']].map(([step, status]) => ({ properties: { step: { const: step }, status: { const: status }, error: { type: 'null' } } })),
  ...[['RETRY_WAIT', 'PENDING', 'UPSTREAM_UNAVAILABLE', true], ['AUTHORIZATION_REVOKED', 'FAILED', 'ACTION_FORBIDDEN', false], ['STORAGE_CONFLICT', 'FAILED', 'FILE_INTEGRITY_CONFLICT', false]].map(([step, status, code, retryable]) => ({ properties: { step: { const: step }, status: { const: status }, error: { type: 'object', properties: { code: { const: code }, retryable: { const: retryable } } } } })),
] }];
const acceptedResponse = (description) => ({ 202: { description, content: { 'application/json': { schema: ref('Accepted') } } }, ...errors });
const photosReady = object({ enabled: { type: 'boolean' }, ready: { type: 'boolean' }, policy_ready: { type: 'boolean' }, schema_ready: { type: 'boolean' }, storage_ready: { type: 'boolean' }, policy_source: { type: 'string', enum: ['USER_DEVELOPMENT_DIRECTION', 'PENDING'] }, reasons: { type: 'array', items: string } });
photosReady.oneOf = [
  { properties: { enabled: { const: true }, ready: { const: true }, policy_ready: { const: true }, schema_ready: { const: true }, storage_ready: { const: true }, policy_source: { const: 'USER_DEVELOPMENT_DIRECTION' }, reasons: { maxItems: 0 } } },
  { properties: { enabled: { const: false }, ready: { const: false }, policy_ready: { const: false }, schema_ready: { const: false }, storage_ready: { const: false }, policy_source: { const: 'PENDING' }, reasons: { const: ['PHOTO_NOT_CONFIGURED'] } } },
];
const readiness = envelope(object({ status: { const: 'READY_REMOTE_TEST' }, ready: { const: true }, mode: { const: 'remote_test' }, source: { const: 'postgresql' }, durable: { const: true }, production_ready: { const: false }, photos: photosReady }));
const importLabels = { NAME: '设备名称', TYPE: '设备类型', ADDRESS: 'IP或域名', BRAND: '品牌', MODEL: '型号', SERIAL_NUMBER: '序列号', SITE: '站点', LOCATION: '位置', OWNER: '负责人', BUSINESS: '所属业务', PURCHASE_DATE: '采购日期', WARRANTY_EXPIRY: '维保到期日', OVERDUE_SERVICE_DATE: '超期服役日期', COLLECTION_METHOD: '采集方式', NOTE: '备注' };
const importTargets = Object.keys(importLabels);
const importHash = { type: 'string', pattern: '^[0-9a-f]{64}$' };
const importFormat = { type: 'string', enum: ['CSV', 'XLSX'] };
const nullableUuid = { type: ['string', 'null'], format: 'uuid' };
const sourceIndex = { type: 'integer', minimum: 0, maximum: 63 };
const nullableSourceIndex = { type: ['integer', 'null'], minimum: 0, maximum: 63 };
const headerRow = { type: ['integer', 'null'], minimum: 1, maximum: 20000 };
const importQuestions = { const: ['Q20', 'Q31'] };
const importFacts = { business_status: { const: 'CONFIG_PENDING' }, can_import: { const: false }, question_ids: importQuestions, scan_status: { const: 'UNSCANNED' }, production_approval: { const: 'UNVERIFIED' }, retention_until: { type: 'null' } };
const importReadiness = object({ enabled: { type: 'boolean' }, ready: { type: 'boolean' }, policy_ready: { type: 'boolean' }, schema_ready: { type: 'boolean' }, storage_ready: { type: 'boolean' }, worker_activity: { const: 'NOT_PROBED' }, business_status: { const: 'CONFIG_PENDING' }, can_import: { const: false }, reasons: { type: 'array', items: string } });
importReadiness.oneOf = [
  { properties: { enabled: { const: true }, ready: { const: true }, policy_ready: { const: true }, schema_ready: { const: true }, storage_ready: { const: true }, reasons: { maxItems: 0 } } },
  { properties: { enabled: { const: false }, ready: { const: false }, policy_ready: { const: false }, schema_ready: { const: false }, storage_ready: { const: false }, reasons: { const: ['ASSET_IMPORT_NOT_CONFIGURED'] } } },
];
readiness.properties.data.properties.asset_imports = importReadiness;
readiness.properties.data.required.push('asset_imports');
const importSelection = object({ declared_format: importFormat, encoding: { type: ['string', 'null'], enum: ['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030', null] }, delimiter: { type: ['string', 'null'], enum: [',', ';', '\t', null] }, sheet_index: { type: ['integer', 'null'], minimum: 0, maximum: 31 }, header_row: headerRow });
importSelection.allOf = [{ if: { properties: { declared_format: { const: 'CSV' } } }, then: { properties: { encoding: { type: 'string' }, delimiter: { type: 'string' }, sheet_index: { type: 'null' }, header_row: { type: 'integer' } } }, else: { properties: { encoding: { type: 'null' }, delimiter: { type: 'null' } } } }];
const importParseRequest = { ...importSelection, properties: { expected_version: positiveVersion, ...importSelection.properties }, required: ['expected_version', ...importSelection.required], description: 'CSV必须明确编码/分隔符/1起表头；XLSX编码与分隔符必须null。XLSX工作表或表头为null时只返回SELECTION_REQUIRED目录，之后以0起sheet与1起header显式请求；不得猜测隐藏工作表、日期或业务规则。' };
const importTargetsMapping = object(Object.fromEntries(importTargets.map(code => [code, code === 'NOTE' ? sourceIndex : nullableSourceIndex])));
const importIgnoredColumns = { type: 'array', maxItems: 64, uniqueItems: true, items: sourceIndex };
const importMappingRequest = object({ expected_version: positiveVersion, source_parse_hash: importHash, target_source_columns: importTargetsMapping, ignored_source_columns: importIgnoredColumns });
importMappingRequest['x-mop-semantic-checks'] = ['source_parse_hash绑定当前租户/创建者/来源/原始bytes/解析选择/schema的已保存artifact', '全部15个目标显式index或null；NOTE必须index', '非null源列不得重复分配；忽略集合不得与映射集合交叉', '所有当前源列必须显式映射或忽略；不接受不存在的源列', 'expected_version CAS且重新授权；失败不运行资产导入或配额占用'];
const importCell = object({ column_index: sourceIndex, kind: { type: 'string', enum: ['TEXT', 'NUMERIC', 'BOOLEAN', 'ERROR', 'ISO_DATE', 'BLANK'] }, lexical: { type: ['string', 'null'], maxLength: 4096 }, display: { type: ['string', 'null'], maxLength: 4096 }, style_index: { type: ['integer', 'null'], minimum: 0 }, format_code: { type: ['string', 'null'], maxLength: 4096 } });
const importRowNumbers = { logical_record_number: { type: 'integer', minimum: 1 }, source_row_number: { type: 'integer', minimum: 1, maximum: 20000 }, physical_start_line: { type: ['integer', 'null'], minimum: 1 }, physical_end_line: { type: ['integer', 'null'], minimum: 1 } };
const importRawRow = object({ ...importRowNumbers, cells: { type: 'array', maxItems: 64, items: ref('AssetImportSourceCell') } });
const importMappedRow = object({ ...importRowNumbers, cells: object(Object.fromEntries(importTargets.map(code => [code, { anyOf: [{ type: 'null' }, ref('AssetImportSourceCell')] }]))) });
const importArtifact = object({
  id: uuid, tenant_id: uuid, source_id: uuid, operation_id: uuid, parse_hash: importHash, source_sha256: importHash, schema_version: { const: 'contract-m3-6-standard15-v1' }, selection: ref('AssetImportParseSelection'), created_at: dateTime,
  state: { type: 'string', enum: ['PARSED', 'SELECTION_REQUIRED'] }, detected_format: importFormat,
  sheets: { type: 'array', minItems: 1, maxItems: 32, items: object({ index: { type: 'integer', minimum: 0, maximum: 31 }, name: { type: 'string', maxLength: 255 }, hidden: { type: 'boolean' } }) }, selected_sheet_index: { type: ['integer', 'null'], minimum: 0, maximum: 31 }, header_row: headerRow,
  date_epoch: { type: 'string', enum: ['NOT_APPLICABLE', 'WINDOWS_1900', 'MAC_1904'] }, columns: { type: 'array', maxItems: 64, items: object({ index: sourceIndex, header: { type: ['string', 'null'], maxLength: 4096 }, target_suggestions: { type: 'array', maxItems: 15, uniqueItems: true, items: { type: 'string', enum: importTargets } } }) },
  rows: { type: 'array', maxItems: 500, items: ref('AssetImportSourceRow') }, parser_version: { type: 'string', minLength: 1 }, resource_policy_version: { const: 'asset-import-technical-v1' },
});
importArtifact.allOf = [
  { if: { properties: { state: { const: 'SELECTION_REQUIRED' } } }, then: { properties: { detected_format: { const: 'XLSX' }, selected_sheet_index: { type: 'null' }, header_row: { type: 'null' }, columns: { maxItems: 0 }, rows: { maxItems: 0 } } }, else: { properties: { selected_sheet_index: { type: 'integer' }, header_row: { type: 'integer' }, columns: { minItems: 1 }, rows: { minItems: 1 } } } },
  { if: { properties: { detected_format: { const: 'CSV' } } }, then: { properties: { date_epoch: { const: 'NOT_APPLICABLE' }, selected_sheet_index: { const: 0 }, sheets: { maxItems: 1 } } }, else: { properties: { date_epoch: { enum: ['WINDOWS_1900', 'MAC_1904'] } } } },
];
importArtifact['x-mop-binding-checks'] = ['tenant/source/operation与来源和操作投影相同', 'source_sha256与不可变原始bytes摘要相同', '保留lexical/display/kind/style/format/date_epoch；没有业务日期或字典规范化', '表头重名不合并，源列index和原始行号不重排', 'sheet index、column index、source row number及logical record number各自唯一；选中sheet必须存在；数据行在表头后且所有cell属于声明列', 'XLSX公式、外部链接、宏及加密安全拒绝；不读取公式缓存替代公式解析'];
const importMapping = object({ id: uuid, tenant_id: uuid, source_id: uuid, artifact_id: uuid, version: positiveVersion, source_parse_hash: importHash, preview_hash: importHash, schema_version: { const: 'contract-m3-6-standard15-v1' }, target_source_columns: importTargetsMapping, ignored_source_columns: importIgnoredColumns, mapped_raw_rows: { type: 'array', maxItems: 500, items: ref('AssetImportMappedRow') }, precheck_status: { const: 'NOT_RUN' }, created_at: dateTime });
const importSource = object({
  id: uuid, tenant_id: uuid, created_by: uuid, purpose: { const: 'asset_import_source' }, original_name: { type: 'string', minLength: 1, maxLength: 200 }, declared_format: importFormat, size_bytes: { type: 'integer', minimum: 1, maximum: 20971520 }, sha256: importHash,
  storage_status: { type: 'string', enum: ['PENDING', 'READY', 'FAILED'] }, parse_status: { type: 'string', enum: ['NOT_REQUESTED', 'PENDING', 'PARSED', 'SELECTION_REQUIRED', 'FAILED'] }, store_operation_id: uuid, parse_operation_id: nullableUuid, latest_artifact_id: nullableUuid, latest_mapping_id: nullableUuid,
  version: positiveVersion, created_at: dateTime, updated_at: dateTime, parsed_artifact: { anyOf: [{ type: 'null' }, ref('AssetImportParsedArtifact')] }, mapping: { anyOf: [{ type: 'null' }, ref('AssetImportMapping')] }, ...importFacts, effective_rule_versions: { type: 'null' }, quota_reservation: { type: 'null' },
});
importSource.allOf = [
  { if: { properties: { parsed_artifact: { type: 'null' } } }, then: { properties: { latest_artifact_id: { type: 'null' } } }, else: { properties: { latest_artifact_id: uuid } } },
  { if: { properties: { mapping: { type: 'null' } } }, then: { properties: { latest_mapping_id: { type: 'null' } } }, else: { properties: { latest_mapping_id: uuid, parse_status: { const: 'PARSED' } } } },
  { if: { properties: { parse_status: { const: 'NOT_REQUESTED' } } }, then: { properties: { parse_operation_id: { type: 'null' }, parsed_artifact: { type: 'null' }, mapping: { type: 'null' } } }, else: { properties: { parse_operation_id: uuid } } },
  { if: { properties: { parse_status: { enum: ['PENDING', 'FAILED'] } } }, then: { properties: { parsed_artifact: { type: 'null' }, mapping: { type: 'null' } } } },
  ...['PARSED', 'SELECTION_REQUIRED'].map(state => ({ if: { properties: { parse_status: { const: state } } }, then: { properties: { storage_status: { const: 'READY' }, parsed_artifact: { type: 'object', properties: { state: { const: state } } } } } })),
];
importSource['x-mop-binding-checks'] = ['仅当前授权租户且created_by为当前actor可读取', 'RR一致快照内读取来源、当前artifact与当前mapping；缺投影返回503', 'READY只表示原始bytes存储成功；PARSED只表示技术解析成功；映射保存仍CONFIG_PENDING/can_import=false', '再次请求解析会清空当前artifact/mapping引用；历史artifact/mapping保留', '所有失败保持明确状态；不回退内存仓储或fixture'];
importSource['x-mop-client-readback'] = ['来源身份/原文件摘要/单调版本及资源政策校验全部通过后，清除不匹配当前store_operation_id/parse_operation_id或result_artifact_id绑定的旧显示操作', '历史请求、幂等键、原参数与映射收据不因普通刷新而改写；不自动GET新操作或POST'];
const importOperation = object({ id: uuid, tenant_id: uuid, source_id: uuid, created_by: uuid, kind: { type: 'string', enum: ['STORE_RAW', 'PARSE'] }, status: { type: 'string', enum: ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'] }, step: { type: 'string', enum: ['QUEUED', 'STORING', 'PARSING', 'RETRY_WAIT', 'STORED', 'PARSED', 'SELECTION_REQUIRED', 'AUTHORIZATION_REVOKED', 'STORAGE_CONFLICT', 'PARSE_INVALID'] }, result_artifact_id: nullableUuid, result_ref: { type: 'string', pattern: `^/api/v1/assets/import-sources/${uuidPattern}$` }, error: { anyOf: [{ type: 'null' }, object({ code: { type: 'string', enum: ['UPSTREAM_UNAVAILABLE', 'ACTION_FORBIDDEN', 'FILE_INTEGRITY_CONFLICT', 'IMPORT_SOURCE_INVALID'] }, message: string, retryable: { type: 'boolean' }, question_ids: { const: [] } })] }, created_at: dateTime, updated_at: dateTime });
importOperation.allOf = [{ oneOf: [
  { properties: { step: { const: 'QUEUED' }, status: { const: 'PENDING' }, error: { type: 'null' }, result_artifact_id: { type: 'null' } } },
  ...[['STORING', 'STORE_RAW'], ['PARSING', 'PARSE']].map(([step, kind]) => ({ properties: { step: { const: step }, kind: { const: kind }, status: { const: 'RUNNING' }, error: { type: 'null' }, result_artifact_id: { type: 'null' } } })),
  { properties: { step: { const: 'STORED' }, kind: { const: 'STORE_RAW' }, status: { const: 'SUCCEEDED' }, error: { type: 'null' }, result_artifact_id: { type: 'null' } } },
  ...['PARSED', 'SELECTION_REQUIRED'].map(step => ({ properties: { step: { const: step }, kind: { const: 'PARSE' }, status: { const: 'SUCCEEDED' }, error: { type: 'null' }, result_artifact_id: uuid } })),
  ...[['RETRY_WAIT', 'PENDING', 'UPSTREAM_UNAVAILABLE', true], ['AUTHORIZATION_REVOKED', 'FAILED', 'ACTION_FORBIDDEN', false], ['STORAGE_CONFLICT', 'FAILED', 'FILE_INTEGRITY_CONFLICT', false], ['PARSE_INVALID', 'FAILED', 'IMPORT_SOURCE_INVALID', false]].map(([step, status, code, retryable]) => ({ properties: { step: { const: step }, status: { const: status }, result_artifact_id: { type: 'null' }, error: { type: 'object', properties: { code: { const: code }, retryable: { const: retryable } } } } })),
] }];
const importPolicy = object({ ...importFacts, purpose: { const: 'asset_import_source' }, allowed_formats: { const: ['CSV', 'XLSX'] }, format_source: { const: 'CONTRACT_M3_6' }, max_source_bytes: { const: 20971520 }, max_data_rows: { const: 500 }, max_source_columns: { const: 64 }, max_sheets: { const: 32 }, max_header_row: { const: 20000 }, max_cell_chars: { const: 4096 }, resource_policy_version: { const: 'asset-import-technical-v1' }, resource_limit_kind: { const: 'TECHNICAL_CONTAINMENT' }, schema_version: { const: 'contract-m3-6-standard15-v1' }, target_columns: { type: 'array', minItems: 15, maxItems: 15, uniqueItems: true, items: { oneOf: importTargets.map(code => object({ code: { const: code }, label: { const: importLabels[code] }, contract_always_required: { const: code === 'NOTE' } })) } }, csv_encodings: { const: ['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030'] }, csv_delimiters: { const: [',', ';', '\t'] }, sheet_index_base: { const: 0 }, column_index_base: { const: 0 }, row_number_base: { const: 1 } });
const importUpload = object({ file: { type: 'string', format: 'binary', 'x-mop-min-bytes': 1, 'x-mop-max-bytes': 20971520, description: '只受理匹配声明的.csv/.xlsx安全文件名及原bytes。实际格式在受限解析器验证；禁止JSON/base64、未知或重复part。全局multipart文件上限20MiB不改变照片独立5_000_000 bytes限制。' }, purpose: { const: 'asset_import_source' }, format: importFormat });
const importAccess = { 'x-mop-capability': 'assets:write', 'x-mop-roles': ['padmin', 'msp', 'tadmin'], 'x-mop-creator-only': true, 'x-mop-permission-version-recheck': true, 'x-mop-reject-unknown-query-keys': true };
const importAcceptanceResponses = { 202: { description: '原bytes或解析意图、来源、job、operation、审计与幂等结果已在PG事务受理；需以GET操作与来源读取当前状态。不是资产导入或配额承诺。', content: { 'application/json': { schema: ref('AssetImportAccepted') } } }, ...errors };
const contract = {
  openapi: '3.1.0',
  info: { title: 'MOP 共享测试环境 API', version: '0.8.0', description: '本地接续开发复用指定服务器的PostgreSQL、Keycloak与MinIO；已启用的照片能力纳入实际就绪检查，缺策略/schema/存储返回503。配置诊断只读取当前授权租户的一致PG元数据，不返回原值或资料正文；定义、发布与客户核验来源尚未接入，生效值仍null。内部审核不形成客户确认。报障202只代表耐久登记，正式状态/优先级/SLA仍为null。照片202需原图/file/job/operation/审计/重放结果同事务；READY表示存储完成，仍UNSCANNED、生产UNVERIFIED、保留null。未绑定照片仅上传人可见，内部只读查询须已有当前租户登记绑定或属于本人。资产来源新增CSV/XLSX原文件受理、显式解析、15列原始映射与creator-only回读代码契约；仍CONFIG_PENDING/can_import=false，有效规则版本与配额null。原始存储、技术解析、映射保存各自独立，不代表实际资产导入；新增应用链路须独立验证。fixture身份入口在remote_test不可调用。' },
  servers: [{ url: '/api/v1' }],
  security: [{ SessionCookie: [] }],
  paths: {
    '/health/live': { get: action('liveness', '本地进程存活', ref('ResourceResult'), { security: [] }) },
    '/health/ready': { get: action('readiness', '实际PG/OIDC及已启用照片/导入源策略/schema/ACL/MinIO就绪；失败503及安全原因。导入worker_activity=NOT_PROBED，不证明Worker运行、业务导入或生产就绪', ref('ReadinessResult'), { security: [] }) },
    '/auth/options': { get: action('authOptions', '读取Keycloak授权登录入口；不返回秘密', envelope(object({ mode: { const: 'remote_test' }, identity_source: { const: 'test_oidc' }, login_url: { const: '/oauth2/authorization/mop' } })), { security: [] }) },
    '/session': { get: action('getSession', '读取可信会话与能力', ref('SessionResult')), delete: action('logout', '注销会话', ref('ResourceResult'), { parameters: headers }) },
    '/session/tenant': { post: mutation('switchTenant', '在服务端授权集合中切换租户', object({ tenant_id: { type: 'string', format: 'uuid' } }), ref('SessionResult')) },
    '/assets': {
      get: action('listAssets', '读取当前租户资产；游标绑定筛选和权限版本', ref('AssetListResult'), { parameters: [parameter('cursor', 'query', string, false), parameter('page_size', 'query', { type: 'integer', enum: [10, 15, 25, 50, 100], default: 15 }, false), ...['type', 'site_id', 'status', 'q'].map((name) => parameter(name, 'query', string, false))] }),
      post: mutation('createAssetDraft', '创建本租户持久资产草稿；不自动纳管', object({ name: { type: 'string', minLength: 1, maxLength: 100 }, type: { type: 'string', enum: types }, management_ip: string, site_id: string }), ref('AssetResult')),
    },
    '/assets/{id}': { get: action('getAsset', '读取本租户资产详情；不存在及无权均404', ref('AssetResult'), { parameters: [pathId] }) },
    '/assets/{id}/onboarding': { post: blocked('requestOnboarding', '接入门禁：Q02/Q03/Q06待确认，拒绝执行', pathId) },
    '/assets/import-policy': { get: action('getAssetImportPolicy', '读取CSV/XLSX来源技术政策与合同15列；不批准最终导入规则', ref('AssetImportPolicyResult'), importAccess) },
    '/assets/import-sources': { post: { ...mutation('acceptAssetImportSource', '耐久受理原始CSV/XLSX来源；不是导入资产', ref('AssetImportSourceUpload'), ref('AssetImportAccepted'), { ...importAccess, parameters: headers }), requestBody: { required: true, content: { 'multipart/form-data': { schema: ref('AssetImportSourceUpload') } } }, responses: importAcceptanceResponses } },
    '/assets/import-sources/{id}': { get: action('getAssetImportSource', '当前租户创建者读取来源及一致快照的解析artifact/mapping；跨范围统一404', ref('AssetImportSourceResult'), { ...importAccess, parameters: [pathId] }) },
    '/assets/import-sources/{id}/parses': { post: { ...mutation('parseAssetImportSource', 'CAS受理明确解析选择；XLSX缺sheet/header只读取目录，公式拒绝', ref('AssetImportParseRequest'), ref('AssetImportAccepted'), { ...importAccess, parameters: [pathId, ...headers] }), responses: importAcceptanceResponses } },
    '/assets/import-sources/{id}/mappings': { post: mutation('saveAssetImportMapping', 'CAS保存15列映射与原始预览hash；业务预检NOT_RUN，资产写入与配额未运行', ref('AssetImportMappingRequest'), ref('AssetImportSourceResult'), { ...importAccess, parameters: [pathId, ...headers] }) },
    '/asset-import-operations/{id}': { get: action('getAssetImportOperation', '创建者回读独立STORE_RAW/PARSE操作；GET不执行任务，等待不代表失败', ref('AssetImportOperationResult'), { ...importAccess, parameters: [pathId] }) },
    '/configurations': { get: action('listConfigurations', '仅平台管理员读取设计登记和测试环境持久草稿', ref('ResourceResult'), { parameters: [parameter('decision_status', 'query', { type: 'string', enum: ['pending', 'contract_fixed'] }, false)] }) },
    '/settings/effective': { get: action('readConfigurationDiagnostic', '当前padmin读取单key配置来源诊断；200只表示PG元数据读取成功，CONFIG_PENDING/null不代表生效', ref('ConfigurationDiagnosticResult'), { parameters: [parameter('key', 'query', configurationKey)], 'x-mop-allowed-query-keys': ['key'], 'x-mop-reject-duplicate-query-keys': true, description: '拒绝额外或重复query，客户端不能提供tenant、对象scope、precedence或核验事实。未知key404、无权403、失效会话401、PG不可用503。授权、登记版本及本租户最多5条近期提案来自一个只读REPEATABLE READ快照；后续提交在下一请求可见。近期提案不是选中的有效版本，APPROVED也未发布。运行定义、发布及客户核验能力明确NOT_IMPLEMENTED/null，不以空集合冒充已接入；不返回原值、旧草稿、提案、意见或资料正文。' }) },
    '/configurations/{key}/draft': { post: mutation('saveConfigurationDraft', '保存独立草稿；不改变value或客户确认状态，动态校验登记类型', object({ proposed_value: { not: { type: 'null' }, description: '按登记类型校验；任意嵌套层级的数值转为double后须为有限值，否则400；嵌套null保留未知语义。' }, expected_version: { type: 'integer', minimum: 1 } }), ref('ResourceResult'), { parameters: [pathKey, ...headers] }) },
    '/configurations/{key}/publish': { post: blocked('publishConfiguration', '发布门禁；管理员保存不代表客户书面确认', pathKey) },
    '/configurations/{key}/revisions': {
      get: action('listConfigurationRevisions', '本租户配置修订列表；不跨租户聚合', envelope(object({ items: { type: 'array', items: ref('ConfigurationRevision') } })), { parameters: [pathKey] }),
      post: mutation('createConfigurationRevision', '创建持久配置提案和未核验书面资料引用', object({ proposed_value: { not: { type: 'null' }, description: '按登记类型校验；任意嵌套层级的数值转为double后须为有限值，否则400；嵌套null保留未知语义。' }, reason: boundedComment, evidence_refs: { type: 'array', minItems: 1, maxItems: 5, items: evidence } }), ref('ConfigurationRevisionResult'), { parameters: [pathKey, ...headers] }),
    },
    '/configuration-revisions/{id}': { get: action('getConfigurationRevision', '读取本租户配置修订、状态及历史', ref('ConfigurationRevisionResult'), { parameters: [pathId] }) },
    '/configuration-revisions/{id}/submit': { post: mutation('submitConfigurationRevision', '创建者提交DRAFT进入内部审核', version, ref('ConfigurationRevisionResult'), { parameters: [pathId, ...headers] }) },
    '/configuration-revisions/{id}/review': { post: mutation('reviewConfigurationRevision', '非创建者平台管理员审核；不形成客户确认或生效', object({ expected_version: { type: 'integer', minimum: 1 }, decision: { type: 'string', enum: ['approve', 'reject'] }, comment: boundedComment }), ref('ConfigurationRevisionResult'), { parameters: [pathId, ...headers] }) },
    '/configuration-revisions/{id}/withdraw': { post: mutation('withdrawConfigurationRevision', '创建者撤回DRAFT或IN_REVIEW，保留历史', object({ expected_version: { type: 'integer', minimum: 1 }, reason: boundedComment }), ref('ConfigurationRevisionResult'), { parameters: [pathId, ...headers] }) },
    '/tickets': {
      get: action('listCustomerRegistrations', '查询本租户获准的报障登记；客户仅本人，内部角色仅授权租户', ref('TicketRegistrationListResult'), { parameters: [parameter('cursor', 'query', string, false), parameter('page_size', 'query', { type: 'integer', enum: [10, 15, 25, 50, 100], default: 15 }, false), ...['category', 'urgency', 'q'].map(name => parameter(name, 'query', string, false))] }),
      post: { ...mutation('registerCustomerReport', '耐久登记客户报障；不保证正式业务建单或SLA承诺', ref('TicketCreate'), ref('Accepted')), responses: { 202: { description: '真实PG事务已提交；只返回operation_id/PENDING，需回读本平台受理记录。', content: { 'application/json': { schema: ref('Accepted') } } }, ...errors } },
    },
    '/tickets/{id}': { get: action('getCustomerRegistration', '读取本人或授权租户内部报障登记；跨范围404', ref('TicketRegistrationResult'), { parameters: [pathId] }) },
    '/tickets/{id}/attachments': { post: mutation('appendCustomerReportPhotos', '报障人CAS追加READY原图；不改变原正文、联系人或正式工单规则', ref('PhotoAttachmentCreate'), ref('TicketRegistrationResult'), { parameters: [pathId, ...headers] }) },
    '/files/policy': { get: action('getReportPhotoPolicy', '读取当前开发照片策略；无白名单拒绝上传，不表示客户生产批准', ref('ReportPhotoPolicyResult')) },
    '/files': { post: { ...mutation('acceptReportPhoto', '耐久受理原图；存储不可用时新请求503，已受理同键重放无需再次上传', ref('ReportPhotoUpload'), ref('Accepted')), requestBody: { required: true, content: { 'multipart/form-data': { schema: ref('ReportPhotoUpload') } } }, responses: acceptedResponse('PG原图内容、file、job、operation、成功审计和幂等结果已同事务持久保存；后续GET回读真实存储状态。') } },
    '/files/{id}': { get: action('getReportPhoto', '读取本人或授权租户照片元数据；不返回桶、对象key或暂存原图bytes', ref('ReportPhotoResult'), { parameters: [pathId] }) },
    '/files/{id}/content-grants': { post: mutation('createReportPhotoContentGrant', '为READY原图签发15分钟平台grant；actor/tenant/file/permission_version绑定', object({}), ref('ReportPhotoContentGrantResult'), { parameters: [pathId, ...headers] }) },
    '/files/{id}/content': { get: { operationId: 'readReportPhotoContent', summary: '通过当前会话及持久grant回读原图；读前后复核授权。grant过期、权限版本失配或其他主体404；会话失效401，当前动作权限不足403', parameters: [pathId, parameter('grant', 'query', { type: 'string', format: 'uuid', pattern: `^${uuidPattern}$` })], responses: { 200: { description: '实际JPEG/PNG原图；校验大小及SHA，不重编码。Cache-Control:no-store、X-Content-Type-Options:nosniff、Content-Disposition:inline。', headers: { 'Cache-Control': { schema: { const: 'no-store' } }, 'X-Content-Type-Options': { schema: { const: 'nosniff' } }, 'Content-Disposition': { schema: string } }, content: Object.fromEntries(['image/jpeg', 'image/png'].map(media => [media, { schema: { type: 'string', format: 'binary' } }])) }, ...errors } } },
    '/operations/{id}': { get: action('getOperation', '读取耐久报障或FILE_UPLOAD操作；GET不执行任务，等待超时不表示FAILED', ref('OperationResult'), { parameters: [pathId] }) },
    '/audit': { get: action('listAudit', '仅平台管理员读取脱敏持久审计', ref('ResourceResult')) },
  },
  components: {
    securitySchemes: { SessionCookie: { type: 'apiKey', in: 'cookie', name: 'MOP_SESSION' } },
    schemas: {
      Error: object({ error: object({ code: string, message: string, details: { type: 'array', items: { type: 'object', additionalProperties: true } } }), trace_id: string }),
      ResourceResult: envelope({ type: 'object', additionalProperties: true }),
      ReadinessResult: readiness,
      SessionResult: envelope(object({ user: object({ id: string, display_name: string, role: { type: 'string', enum: ['viewer', 'tadmin', 'msp', 'padmin'] } }), tenant, allowed_tenants: { type: 'array', items: tenant }, capabilities: { type: 'array', items: string }, permission_version: { type: 'integer' }, csrf_token: string, mode: { const: 'remote_test' }, identity_source: { const: 'test_oidc' } })),
      Asset: asset,
      AssetResult: envelope(ref('Asset')),
      AssetListResult: list(ref('Asset')),
      ConfigurationRevision: revision,
      ConfigurationRevisionResult: envelope(ref('ConfigurationRevision')),
      ConfigurationDiagnostic: configurationDiagnostic,
      ConfigurationDiagnosticResult: envelope(ref('ConfigurationDiagnostic')),
      TicketCreate: ticketCreate,
      TicketRegistration: ticketRegistration,
      TicketRegistrationResult: envelope(ref('TicketRegistration')),
      TicketRegistrationListResult: list(ref('TicketRegistration')),
      Accepted: envelope(object({ operation_id: { type: 'string', format: 'uuid' }, status: { const: 'PENDING' } })),
      CustomerReportOperation: operation,
      FileUploadOperation: fileOperation,
      ReportPhotoPolicy: photoPolicy,
      ReportPhotoPolicyResult: envelope(ref('ReportPhotoPolicy')),
      ReportPhoto: reportPhoto,
      ReportPhotoResult: envelope(ref('ReportPhoto')),
      ReportPhotoUpload: photoUpload,
      PhotoAttachmentCreate: photoAttachments,
      ReportPhotoContentGrant: contentGrant,
      ReportPhotoContentGrantResult: envelope(ref('ReportPhotoContentGrant')),
      OperationResult: envelope({ oneOf: [ref('CustomerReportOperation'), ref('FileUploadOperation')] }),
      AssetImportPolicy: importPolicy,
      AssetImportReadiness: importReadiness,
      AssetImportPolicyResult: envelope(ref('AssetImportPolicy')),
      AssetImportSourceUpload: importUpload,
      AssetImportAccepted: envelope(object({ source_id: uuid, operation_id: uuid, status: { const: 'PENDING' } })),
      AssetImportParseSelection: importSelection,
      AssetImportParseRequest: importParseRequest,
      AssetImportMappingRequest: importMappingRequest,
      AssetImportSourceCell: importCell,
      AssetImportSourceRow: importRawRow,
      AssetImportMappedRow: importMappedRow,
      AssetImportParsedArtifact: importArtifact,
      AssetImportMapping: importMapping,
      AssetImportSource: importSource,
      AssetImportSourceResult: envelope(ref('AssetImportSource')),
      AssetImportOperation: importOperation,
      AssetImportOperationResult: envelope(ref('AssetImportOperation')),
    },
  },
};
const target = fileURLToPath(new URL('../contracts/local-api.openapi.json', import.meta.url));
const serialized = JSON.stringify(contract, null, 2) + '\n';
if (process.argv.includes('--check')) {
  if (readFileSync(target, 'utf8') !== serialized) throw new Error('local-api.openapi.json 与生成器不一致；先运行 node tools/build-api-contract.mjs');
  console.log('API契约生成一致性检查通过');
} else {
  writeFileSync(target, serialized, 'utf8');
  console.log('生成 contracts/local-api.openapi.json');
}
