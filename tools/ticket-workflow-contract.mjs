export function addTicketWorkflowContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false })
  const ref = name => ({ $ref: `#/components/schemas/${name}` })
  const uuid = { type: 'string', format: 'uuid' }, time = { type: 'string', format: 'date-time' }
  const nullable = value => ({ anyOf: [value, { type: 'null' }] })
  const version = { type: 'integer', minimum: 0, maximum: 9007199254740990 }
  const errors = Object.fromEntries([400, 401, 403, 404, 409, 413, 422, 503].map(code => [code, { description: '工单授权、状态、版本或真实依赖错误；未知写入结果保留原幂等请求', content: { 'application/json': { schema: ref('Error') } } }]))
  const envelope = data => object({ data, trace_id: { type: 'string' } })
  const actionHeader = [
    { name: 'Idempotency-Key', in: 'header', required: true, schema: { type: 'string', pattern: '^[A-Za-z0-9._:-]{1,128}$' } },
    { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } },
  ]
  const workflowState = { enum: ['OPEN', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'] }
  const workflow = object({
    ticket_id: uuid, tenant_id: uuid, title: { type: 'string' }, receipt_no: { type: 'string' }, registration_created_at: time,
    version, state: nullable(workflowState), priority: nullable({ enum: ['P1', 'P2', 'P3'] }), service_tier: nullable({ enum: ['BASIC', 'STANDARD', 'ADVANCED'] }),
    assignee_id: nullable(uuid), collaborator_ids: { type: 'array', items: uuid, maxItems: 100 }, accepted_at: nullable(time), resolved_at: nullable(time),
    closed_at: nullable(time), confirm_due_at: nullable(time), close_mode: nullable({ enum: ['customer_confirm', 'timeout_auto'] }),
    reopen_count: { type: 'integer', minimum: 0 }, confirmation_cycle: { type: 'integer', minimum: 0 }, resolution_note: nullable({ type: 'string' }),
    response_due_at: nullable(time), recovery_due_at: nullable(time), sla_missing: { type: 'object', additionalProperties: { type: 'string' } }, sla_references: { type: 'array', maxItems: 20 },
    snapshot_hash: nullable({ type: 'string', pattern: '^[a-f0-9]{64}$' }), updated_at: nullable(time), observed_at: time, authority_source: { const: 'MOP' }, source: { const: 'postgresql' },
    scope: { enum: ['REPORTER_ONLY', 'CURRENT_TENANT_INTERNAL'] }, customer_confirmation_status: { const: 'UNVERIFIED' }, production_approved: { const: false }, external_notification_delivery: { const: 'NOT_CONFIRMED' },
  })
  const event = object({ id: uuid, version: { type: 'integer', minimum: 1 }, action: { type: 'string' }, state: workflowState, actor_id: nullable(uuid), note: nullable({ type: 'string' }), details: {}, created_at: time, snapshot_hash: { type: 'string', pattern: '^[a-f0-9]{64}$' } })
  const input = object({ expected_version: version })
  const result = envelope(ref('TicketWorkflow'))
  Object.assign(contract.components.schemas, {
    TicketWorkflow: workflow,
    TicketWorkflowResult: result,
    TicketWorkflowEvent: event,
    TicketWorkflowEventsResult: envelope(object({ items: { type: 'array', items: event, maxItems: 100 }, has_more: { type: 'boolean' }, next_before_version: nullable({ type: 'integer', minimum: 1 }), source: { const: 'postgresql' } })),
    TicketWorkflowPageResult: envelope(object({ items: { type: 'array', items: workflow, maxItems: 100 }, has_more: { type: 'boolean' }, next_cursor: nullable({ type: 'string' }), source: { const: 'postgresql' } })),
    TicketWorkflowActivate: object({ expected_version: { const: 0 }, priority: { enum: ['P1', 'P2', 'P3'] }, service_tier: nullable({ enum: ['BASIC', 'STANDARD', 'ADVANCED'] }), reason: { type: 'string', minLength: 1, maxLength: 3000 } }),
    TicketWorkflowAction: object({ expected_version: { type: 'integer', minimum: 1, maximum: 9007199254740990 }, note: { type: 'string', maxLength: 3000 } }),
    TicketWorkflowReassign: object({ expected_version: { type: 'integer', minimum: 1, maximum: 9007199254740990 }, assignee_id: uuid, note: { type: 'string', minLength: 1, maxLength: 3000 }, reason_code: { type: 'string', pattern: '^[A-Z][A-Z0-9_]{0,63}$' }, retain_previous_as_collaborator: { type: 'boolean' } }),
  })
  const base = (operationId, summary, response, options = {}) => ({ operationId, summary, 'x-mop-current-membership-recheck': true, 'x-mop-source': 'postgresql', responses: { 200: { description: summary, content: { 'application/json': { schema: response } } }, ...errors }, ...options })
  contract.paths['/ticket-workflows'] = { get: base('listTicketWorkflows', '读取当前租户正式工单处理列表；客户仅本人，游标绑定身份、租户、权限版本和筛选', ref('TicketWorkflowPageResult'), { parameters: [{ name: 'state', in: 'query', schema: workflowState }, { name: 'priority', in: 'query', schema: { enum: ['P1', 'P2', 'P3'] } }, { name: 'assignee', in: 'query', schema: uuid }, { name: 'cursor', in: 'query', schema: { type: 'string' } }, { name: 'limit', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 25 } }] }) }
  contract.paths['/ticket-workflows/assignees'] = { get: base('listTicketAssignees', '读取当前租户有权处理工单的内部人员', envelope(object({ items: { type: 'array', maxItems: 100, items: object({ id: uuid, display_name: { type: 'string' }, role: { enum: ['msp', 'padmin'] } }) }, source: { const: 'postgresql' } }))) }
  contract.paths['/tickets/{id}/workflow'] = { get: base('readTicketWorkflow', '读取正式工单处理状态；客户投影不暴露内部负责人和发布来源', ref('TicketWorkflowResult'), { parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }] }) }
  contract.paths['/tickets/{id}/events'] = { get: base('listTicketWorkflowEvents', '读取只追加处理历史；客户投影隐藏内部身份和转派细节', ref('TicketWorkflowEventsResult'), { parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, { name: 'before_version', in: 'query', schema: { type: 'integer', minimum: 1 } }, { name: 'limit', in: 'query', schema: { type: 'integer', minimum: 1, maximum: 100, default: 25 } }] }) }
  contract.paths['/tickets/{id}/activate'] = { post: base('activateTicketWorkflow', '以明确优先级、服务档位和运行配置快照建立正式处理流程；返回原幂等回执', result, { parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...actionHeader], requestBody: { required: true, content: { 'application/json': { schema: ref('TicketWorkflowActivate') } } }, 'x-mop-roles': ['msp', 'padmin'], 'x-mop-capability': 'ticket_notes:write_internal', 'x-mop-returns': '202_or_durable_receipt' }) }
  for (const [action, roles, capability] of [['accept', ['msp', 'padmin'], 'ticket_notes:write_internal'], ['start', ['msp', 'padmin'], 'ticket_notes:write_internal'], ['resolve', ['msp', 'padmin'], 'ticket_notes:write_internal'], ['cancel', ['padmin'], 'ticket_notes:write_internal'], ['close', ['viewer', 'tadmin'], 'tickets:report'], ['reopen', ['viewer', 'tadmin'], 'tickets:report']]) {
    contract.paths[`/tickets/{id}/${action}`] = { post: base(`${action}TicketWorkflow`, `执行${action}工单动作；CAS、权限、审计和outbox同事务`, result, { parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...actionHeader], requestBody: { required: true, content: { 'application/json': { schema: ref('TicketWorkflowAction') } } }, 'x-mop-roles': roles, 'x-mop-capability': capability, 'x-mop-returns': '202_or_durable_receipt' }) }
  }
  contract.paths['/tickets/{id}/reassign'] = { post: base('reassignTicketWorkflow', '在保持原SLA快照和首次响应事实的前提下转派工单', result, { parameters: [{ name: 'id', in: 'path', required: true, schema: uuid }, ...actionHeader], requestBody: { required: true, content: { 'application/json': { schema: ref('TicketWorkflowReassign') } } }, 'x-mop-roles': ['msp', 'padmin'], 'x-mop-capability': 'ticket_notes:write_internal', 'x-mop-returns': '202_or_durable_receipt' }) }
  contract.info.version = '0.53.0'
}
