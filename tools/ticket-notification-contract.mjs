export function addTicketNotificationContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const uuid = { type: 'string', format: 'uuid' }, time = { type: 'string', format: 'date-time' }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 503].map(code => [code, { description: '通知授权、范围、版本或真实依赖错误；未知写入结果保留原幂等请求', content: { 'application/json': { schema: ref('Error') } } }]))
  const envelope = data => object({ data, trace_id: { type: 'string' } })
  const eventType = { type: 'string', pattern: '^ticket\\.[a-z0-9_]{1,64}$' }
  const message = object({
    id: uuid, ticket_id: uuid, audience: { enum: ['CUSTOMER', 'INTERNAL'] }, event_type: eventType, title: { type: 'string', minLength: 1, maxLength: 160 }, body: { type: 'string', minLength: 1, maxLength: 600 },
    created_at: time, version: { enum: [0, 1] }, read_at: nullable(time), channel: { const: 'IN_APP' }, source: { const: 'postgresql' },
  })
  const intent = object({
    id: uuid, ticket_id: uuid, receipt_no: { type: 'string' }, event_type: eventType, phase: { type: 'string', pattern: '^[A-Z][A-Z0-9_]{0,63}$' }, priority: nullable({ enum: ['P1', 'P2', 'P3'] }),
    recipient_status: { enum: ['COMPLETE', 'PARTIAL', 'PENDING_RULE'] }, recipient_count: { type: 'integer', minimum: 0, maximum: 100 },
    pending_reasons: { type: 'array', maxItems: 16, items: { enum: ['Q15_ONCALL_QUEUE_RECIPIENTS', 'Q15_ESCALATION_CHAIN', 'Q09_SERVICE_MANAGER_ROLE', 'UNMAPPED_EVENT_TYPE', 'RECIPIENT_INACTIVE'] } },
    external_dispatch: { const: 'NOT_CONFIGURED' }, external_blockers: { type: 'array', maxItems: 16, items: { type: 'string' }, contains: { const: 'Q14_CHANNEL_MATRIX' } }, created_at: time,
  })
  const cursor = [{ name: 'before_created_at', in: 'query', schema: time }, { name: 'before_id', in: 'query', schema: uuid }]
  Object.assign(contract.components.schemas, {
    TicketNotification: message,
    TicketNotificationPageResult: envelope(object({ items: { type: 'array', items: message, maxItems: 50 }, has_more: { type: 'boolean' }, next_before_created_at: nullable(time), next_before_id: nullable(uuid), unread_count: { type: 'integer', minimum: 0 }, observed_at: time, external_dispatch: { const: 'NOT_CONFIGURED' }, source: { const: 'postgresql' } })),
    TicketNotificationRead: object({ expected_version: { enum: [0, 1] } }),
    TicketNotificationReadResult: envelope(object({ id: uuid, version: { const: 1 }, read_at: time })),
    NotificationIntent: intent,
    NotificationIntentPageResult: envelope(object({ items: { type: 'array', items: intent, maxItems: 100 }, has_more: { type: 'boolean' }, next_before_created_at: nullable(time), next_before_id: nullable(uuid), observed_at: time, source: { const: 'postgresql' } })),
  })
  const base = (operationId, summary, response, options = {}) => ({ operationId, summary, 'x-mop-current-membership-recheck': true, 'x-mop-source': 'postgresql', 'x-mop-external-dispatch': 'NOT_CONFIGURED', responses: { 200: { description: summary, content: { 'application/json': { schema: response } } }, ...errors }, ...options })
  contract.paths['/my/notifications'] = { get: base('listOwnTicketNotifications', '读取本人在当前租户的工单站内通知；不代表外部渠道已推送或送达', ref('TicketNotificationPageResult'), { parameters: [{ name: 'page_size', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 50, default: 20 } }, { name: 'unread_only', in: 'query', schema: { type: 'boolean' } }, ...cursor], 'x-mop-capability': 'tickets:read' }) }
  contract.paths['/my/notifications/{id}/read'] = { post: base('markOwnTicketNotificationRead', '记录本人首次阅读时间；已读后不可覆盖，跨租户或他人通知统一404', ref('TicketNotificationReadResult'), {
    parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' } }, { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } }],
    requestBody: { required: true, content: { 'application/json': { schema: ref('TicketNotificationRead') } } }, 'x-mop-capability': 'tickets:read' }) }
  contract.paths['/notification-intents'] = { get: base('listNotificationIntents', '读取当前租户通知意图台账；接收对象依赖Q09/Q15未确认规则时显示待确认，外部渠道受Q14/Q18阻塞', ref('NotificationIntentPageResult'), { parameters: [{ name: 'page_size', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 25 } }, ...cursor], 'x-mop-roles': ['msp', 'padmin'], 'x-mop-capability': 'ticket_notes:read_internal' }) }
  contract.info.version = '0.54.0'
}
