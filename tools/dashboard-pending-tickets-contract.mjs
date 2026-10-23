/** Current persisted ticket facts; no guessed registration state or SLA business metric. */
export function addDashboardPendingTicketsContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
  const ref = name => ({ $ref: `#/components/schemas/${name}` });
  const text = { type: 'string' };
  const uuid = { type: 'string', format: 'uuid' };
  const count = { type: 'integer', minimum: 0, maximum: 9007199254740990 };
  const time = { type: 'string', format: 'date-time' };
  const item = object({ ticket_id: uuid, receipt_no: text, title: text, registered_at: time,
    workflow_model: { enum: ['WORKFLOW_V33', 'LIFECYCLE_V42'] }, state: text,
    version: { ...count, minimum: 1 } });
  item.allOf = [{ if: { properties: { workflow_model: { const: 'WORKFLOW_V33' } } },
    then: { properties: { state: { enum: ['OPEN', 'ACCEPTED', 'IN_PROGRESS', 'RESOLVED'] } } },
    else: { properties: { state: { enum: ['NEW', 'IN_PROGRESS', 'WAITING_VENDOR', 'PENDING_CUSTOMER', 'RESOLVED', 'REOPENED'] } } } }];
  const snapshot = object({ tenant_id: uuid, actor_id: uuid, permission_version: { ...count, minimum: 1 },
    observed_at: time, scope: { enum: ['CURRENT_TENANT_INTERNAL', 'REPORTER_ONLY'] }, source: { const: 'postgresql' },
    metric_version: { const: 'MOP_VISIBLE_NONTERMINAL_TICKETS_V1' }, registrations: count,
    known_pending: count, terminal: count, unconfigured: count, pending_tickets: { anyOf: [count, { type: 'null' }] },
    state: { enum: ['AVAILABLE', 'PARTIAL'] }, unavailable_reason: { enum: [null, 'REGISTRATIONS_WITHOUT_WORKFLOW'] },
    item_limit: { const: 5 }, sort: { const: 'REGISTRATION_CREATED_AT_DESC_ID_DESC' },
    items: { type: 'array', items: item, maxItems: 5 }, overdue_sla: { type: 'null' }, writes_performed: { const: false } });
  snapshot.allOf = [{ if: { properties: { unconfigured: { const: 0 } } },
    then: { properties: { pending_tickets: count, state: { const: 'AVAILABLE' }, unavailable_reason: { type: 'null' } } },
    else: { properties: { pending_tickets: { type: 'null' }, state: { const: 'PARTIAL' }, unavailable_reason: { const: 'REGISTRATIONS_WITHOUT_WORKFLOW' } } } }];
  contract.components.schemas.DashboardPendingTickets = snapshot;
  contract.components.schemas.DashboardPendingTicketsResult = object({ data: ref('DashboardPendingTickets'), trace_id: text });
  contract.paths['/dashboard/pending-tickets'] = { get: {
    operationId: 'readDashboardPendingTickets', summary: '当前可见工单的非终态数量与最近五条；未配置流程单列为未知',
    'x-mop-capabilities': ['dashboard:read', 'tickets:read'], 'x-mop-read-only': true,
    'x-mop-allowed-query-keys': [], 'x-mop-customer-scope': 'REPORTER_ONLY',
    'x-mop-snapshot-isolation': 'REPEATABLE_READ',
    responses: { 200: { description: 'Same-snapshot counts and top five, no customer/SLA rule inference',
      content: { 'application/json': { schema: ref('DashboardPendingTicketsResult') } } },
    ...Object.fromEntries([400, 401, 403, 503].map(code => [code, { description: 'Invalid scope, permission, workflow state or dependency',
      content: { 'application/json': { schema: ref('Error') } } }])) }
  } };

  const nullable = schema => ({ anyOf: [schema, { type: 'null' }] });
  const clock = object({ status: { enum: ['RUNNING', 'PENDING_CONFIG', 'CONFIG_PENDING', 'BREACHED', 'MET',
    'PAUSED_FOR_CUSTOMER_CONFIRM', 'PAUSED', 'COMPLETED', 'CANCELLED'] }, breached: nullable({ type: 'boolean' }),
    missing_reason: nullable(text), deadline_at: nullable(time), completed_at: nullable(time),
    remaining_millis: nullable(count), remaining_basis: { enum: [null, 'FROZEN_SERVICE_WINDOWS', 'EXPLICIT_BUDGET_MINUS_ELAPSED'] } });
  const slaItem = object({ ...item.properties, priority: { enum: [null, 'P1', 'P2', 'P3'] },
    frozen_service_tier: { enum: [null, 'BASIC', 'STANDARD', 'ADVANCED'] },
    sla_metric_version: { enum: [null, 'SLA_CLOCK_V2', 'LIFECYCLE_SLA_CLOCK_V1'] }, sla_version: { ...count, minimum: 1 },
    overdue: nullable({ type: 'boolean' }), response_clock: clock, recovery_clock: clock });
  slaItem.allOf = item.allOf;
  const enhanced = object({ tenant_id: uuid, actor_id: uuid, permission_version: { ...count, minimum: 1 },
    observed_at: time, scope: snapshot.properties.scope, source: { const: 'postgresql' },
    projection_version: { const: 'MOP_PENDING_TICKETS_SLA_V2' }, registrations: count, known_pending: count,
    terminal: count, unconfigured: count, pending_tickets: nullable(count), overdue_sla: nullable(count),
    known_overdue: { ...count, maximum: 100 }, unknown_sla: count, evaluated_pending: { ...count, maximum: 100 },
    unevaluated_pending: count, evaluation_limit: { const: 100 },
    unavailable_reasons: { type: 'array', maxItems: 3, uniqueItems: true, items: { enum: ['REGISTRATIONS_WITHOUT_WORKFLOW',
      'SLA_INPUTS_OR_READER_UNAVAILABLE', 'EVALUATION_LIMIT_EXCEEDED'] } }, item_limit: { const: 5 },
    sort: snapshot.properties.sort, writes_performed: { const: false }, items: { type: 'array', maxItems: 5, items: slaItem } });
  enhanced.allOf = [{ if: { properties: { unknown_sla: { const: 0 } } },
    then: { properties: { overdue_sla: count } }, else: { properties: { overdue_sla: { type: 'null' } } } },
  { if: { properties: { unconfigured: { const: 0 } } }, then: { properties: { pending_tickets: count } },
    else: { properties: { pending_tickets: { type: 'null' } } } }];
  contract.components.schemas.DashboardPendingTicketSla = enhanced;
  contract.components.schemas.DashboardPendingTicketSlaResult = object({ data: ref('DashboardPendingTicketSla'), trace_id: text });
  contract.paths['/dashboard/pending-tickets/sla'] = { get: {
    ...contract.paths['/dashboard/pending-tickets'].get, operationId: 'readDashboardPendingTicketSla',
    summary: '同快照服务端SLA状态、剩余服务时段预算及完整/未知的超时数量；V1不变',
    'x-mop-technical-evaluation-limit': 100,
    responses: { ...contract.paths['/dashboard/pending-tickets'].get.responses,
      200: { description: 'Versioned V2 projection; no browser time calculation or unknown-to-zero conversion',
        content: { 'application/json': { schema: ref('DashboardPendingTicketSlaResult') } } } }
  } };
}
