/** D10 immutable-revision review; no default grants and no activation endpoint. */
export function addChangeNoticeApprovalContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const nullable = schema => ({ anyOf: [schema, { type: 'null' }] })
  const uuid = { type: 'string', format: 'uuid', pattern: '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' }
  const time = { type: 'string', format: 'date-time' }
  const version = { type: 'integer', minimum: 1, maximum: 9007199254740990 }
  const note = nullable({ type: 'string', minLength: 1, maxLength: 2000, description: 'Nonblank valid Unicode text, maximum 2000 code points; preserved without trimming. REJECT requires non-null.' })
  const state = { enum: ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN'] }
  const action = { enum: ['SUBMIT', 'WITHDRAW', 'APPROVE', 'REJECT'] }
  const safety = { effective: { const: false }, device_execution_allowed: { const: false }, alert_suppression_allowed: { const: false }, automatic_p3_decision: { type: 'null' }, source: { const: 'postgresql' }, record_source: { const: 'REVISION_REVIEW_ONLY' } }
  const identity = { notice_id: uuid, draft_revision: version, content_sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' }, version }
  const envelope = data => object({ data, trace_id: { type: 'string' } })
  Object.assign(contract.components.schemas, {
    ChangeNoticeApprovalSnapshot: object({ ...identity, allowed_actions: { type: 'array', uniqueItems: true, maxItems: 4, items: action, description: 'GET: current server-authorized actions. Mutation receipts: empty; reload GET after success.' }, tenant_id: uuid, state, applicant_id: uuid, change_summary: { type: 'string', minLength: 1, maxLength: 2000 }, asset_ids: { type: 'array', minItems: 1, maxItems: 1000, uniqueItems: true, items: uuid }, ticket_id: nullable(uuid), planned_start_at: time, planned_end_at: nullable(time), timezone: { const: 'Asia/Shanghai' }, created_at: time, submitted_at: nullable(time), reviewer_id: nullable(uuid), decided_at: nullable(time), review_policy_release_id: nullable(uuid), decision_note: note, updated_at: time, ...safety }),
    ChangeNoticeApprovalEvent: object({ ...identity, from_state: state, to_state: state, action, actor_id: uuid, permission_version: version, review_policy_release_id: nullable(uuid), note, occurred_at: time, ...safety }),
    ChangeNoticeApprovalAction: { ...object({ expected_version: { ...version, maximum: 9007199254740989 }, action, note }), allOf: [{ if: { properties: { action: { const: 'REJECT' } } }, then: { properties: { note: { type: 'string', minLength: 1, maxLength: 2000 } } } }] },
    ChangeNoticeApprovalResult: envelope(ref('ChangeNoticeApprovalSnapshot')),
    ChangeNoticeApprovalHistoryResult: envelope(object({ tenant_id: uuid, notice_id: uuid, draft_revision: version, items: { type: 'array', maxItems: 20, items: ref('ChangeNoticeApprovalEvent') }, has_more: { type: 'boolean' }, next_before_version: nullable({ ...version, minimum: 2 }), page_size_limit: { const: 20 }, source: { const: 'postgresql' } })),
  })
  const parameters = [{ name: 'id', in: 'path', required: true, schema: uuid }, { name: 'draft_revision', in: 'path', required: true, schema: version }]
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 413, 415, 422, 503].map(code => [code, { description: 'Sanitized authorization, stale revision/version, configuration, body or dependency failure. Unknown outcome: preserve original idempotency key.', content: { 'application/json': { schema: ref('Error') } } }]))
  const operation = (operationId, result, extra = {}) => ({ operationId, summary: 'Review an exact draft revision; approval is not activation, device execution, automatic P3 or suppression', 'x-mop-capability': 'change_notice_drafts:read', 'x-mop-current-membership-recheck': true, 'x-mop-source': 'postgresql', 'x-mop-runtime-activation': false, parameters, responses: { 200: { description: 'Immutable revision review evidence', content: { 'application/json': { schema: ref(result) } } }, ...errors }, ...extra })
  const base = '/change-notice-drafts/{id}/revisions/{draft_revision}/approval'
  contract.paths[base] = { get: operation('getChangeNoticeApproval', 'ChangeNoticeApprovalResult') }
  contract.paths[`${base}/history`] = { get: operation('getChangeNoticeApprovalHistory', 'ChangeNoticeApprovalHistoryResult', { parameters: [...parameters, { name: 'page_size', in: 'query', schema: { type: 'integer', enum: [10, 15, 20], default: 15 } }, { name: 'before_version', in: 'query', schema: { ...version, minimum: 2 } }] }) }
  contract.paths[`${base}/actions`] = { post: operation('transitionChangeNoticeApproval', 'ChangeNoticeApprovalResult', { 'x-mop-capability': 'change_notice_drafts:write', 'x-mop-review-policy': 'change_notice.approver_policy', 'x-mop-idempotent-replay-reauthorization': true, 'x-mop-max-body-bytes': 16384, parameters: [...parameters, { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' } }, { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } }], requestBody: { required: true, content: { 'application/json': { schema: ref('ChangeNoticeApprovalAction') } } } }) }
  const readiness = contract.components.schemas.ReadinessResult?.properties?.data
  if (readiness) {
    readiness.properties.change_notice_approvals = object({ schema_access_ready: { type: 'boolean' }, record_source: { const: 'REVISION_REVIEW_ONLY' }, effective: { const: false }, device_execution_allowed: { const: false }, alert_suppression_allowed: { const: false } })
    if (!readiness.required.includes('change_notice_approvals')) readiness.required.push('change_notice_approvals')
  }

  ;(contract.info ??= {}).version = '0.58.0'
}
