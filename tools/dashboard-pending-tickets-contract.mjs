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
}
