export function addAttachmentSecurityContract(contract) {
  const uuid = { type: 'string', format: 'uuid' };
  const nullableTime = { type: ['string', 'null'], format: 'date-time' };
  const properties = {
    file_id: uuid, tenant_id: uuid, version: { type: 'integer', minimum: 0, maximum: 9007199254740991 },
    state: { enum: ['QUARANTINED', 'QUEUED', 'CANCELLED', 'CLEAN', 'REJECTED', 'UNKNOWN', 'RELEASED'] },
    scan_request_id: { type: ['string', 'null'], format: 'uuid' }, content_sha256: { type: 'string', pattern: '^[a-f0-9]{64}$' },
    scanned_at: nullableTime, valid_until: nullableTime, recorded_at: nullableTime,
    release_allowed: { type: 'boolean' }, content_allowed: { type: 'boolean' }, can_manage_release: { type: 'boolean' },
    can_request_scan: { type: 'boolean' }, scanner_status: { enum: ['NOT_CONFIGURED', 'CONFIGURED_NOT_VERIFIED'] },
  };
  contract.components.schemas.AttachmentSecuritySnapshot = {
    type: 'object', additionalProperties: false, properties, required: Object.keys(properties),
    'x-mop-invariants': ['Content requires RELEASED, unchanged file digest and unexpired scan evidence.',
      'CLEAN is not RELEASED. No public endpoint accepts scanner verdicts.',
      'Storage READY alone never grants content. Existing grants recheck release before and after storage reads.'],
  };
  const success = { description: '当前租户可见文件的安全状态；未配置扫描器不代表已完成扫描', content: {
    'application/json': { schema: { type: 'object', additionalProperties: false, required: ['data', 'trace_id'],
      properties: { data: { $ref: '#/components/schemas/AttachmentSecuritySnapshot' }, trace_id: { type: 'string' } } } },
  } };
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 422, 503].map(code => [code, {
    description: '严格输入／当前授权／同租户404／幂等CAS／未配置或隔离门禁',
    content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
  }]));
  const id = { name: 'id', in: 'path', required: true, schema: uuid };
  contract.paths['/files/{id}/security'] = { get: { operationId: 'getAttachmentSecurity', summary: '读取当前附件隔离与发布状态',
    parameters: [id], responses: { 200: success, ...errors } } };
  contract.paths['/files/{id}/security/actions'] = { post: { operationId: 'changeAttachmentSecurity',
    summary: '上传者或安全管理成员申请／取消扫描；configurations:write成员可发布有效CLEAN附件或重新隔离',
    parameters: [id, { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', minLength: 1, maxLength: 128 } },
      { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } }],
    requestBody: { required: true, content: { 'application/json': { schema: { type: 'object', additionalProperties: false,
      required: ['action', 'expected_version'], properties: {
        action: { enum: ['REQUEST_SCAN', 'CANCEL_SCAN', 'RELEASE', 'QUARANTINE'] },
        expected_version: { type: 'integer', minimum: 0, maximum: 9007199254740990 },
      } } } } }, responses: { 200: success, ...errors } } };
  contract.paths['/files/{id}/content-grants'].post.summary = '仅为当前有效RELEASED原图签发平台grant；重放也重新核验隔离状态';
}
