/** Exact M1-8 bell predicate; a read never acknowledges an event or represents notification delivery. */
export function addDashboardAlertBellContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
  const uuid = { type: 'string', format: 'uuid' };
  const count = { type: 'integer', minimum: 0, maximum: 9007199254740990 };
  const time = { type: 'string', format: 'date-time' };
  const item = object({
    event_id: uuid, asset_id: uuid, rule_name: { type: 'string', minLength: 1, maxLength: 128 },
    severity: { enum: ['INFO', 'WARNING', 'AVERAGE', 'HIGH', 'DISASTER'] },
    monitor_state: { const: 'PROBLEM' }, handling_state: { const: 'NEW' },
    suppression_state: { enum: ['NONE', 'FLAP_SILENCED', 'DEPENDENCY_SUPPRESSED', 'MAINTENANCE_SILENCED', 'INFO_DEFAULT_SILENT'] },
    occurrence_count: { type: 'integer', minimum: 1, maximum: 2147483647 },
    last_occurred_at: time, version: { ...count, minimum: 1 },
  });
  contract.components.schemas.DashboardAlertBell = object({
    tenant_id: uuid, actor_id: uuid, permission_version: { ...count, minimum: 1 }, observed_at: time,
    scope: { const: 'CURRENT_TENANT_INTERNAL' }, source: { const: 'postgresql' },
    metric_version: { const: 'MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1' },
    filter: object({ monitor_state: { const: 'PROBLEM' }, handling_state: { const: 'NEW' } }),
    total: count, item_limit: { const: 5 }, sort: { const: 'LAST_OCCURRED_AT_DESC_ID_DESC' },
    items: { type: 'array', maxItems: 5, items: item }, writes_performed: { const: false },
  });
  contract.components.schemas.DashboardAlertBellResult = object({
    data: { $ref: '#/components/schemas/DashboardAlertBell' }, trace_id: { type: 'string' },
  });
  contract.paths['/dashboard/alert-bell'] = { get: {
    operationId: 'readDashboardAlertBell', summary: '当前租户PROBLEM且NEW事件的精确数量及最近五条',
    'x-mop-capabilities': ['dashboard:read', 'tickets:read'], 'x-mop-roles': ['padmin', 'msp'],
    'x-mop-read-only': true, 'x-mop-allowed-query-keys': [], 'x-mop-current-membership-recheck': true,
    'x-mop-snapshot-isolation': 'REPEATABLE_READ', 'x-mop-suppression-excluded': false,
    responses: {
      200: { description: 'Exact filter count and latest five from the same authorized snapshot',
        content: { 'application/json': { schema: { $ref: '#/components/schemas/DashboardAlertBellResult' } } } },
      ...Object.fromEntries([400, 401, 403, 503].map(code => [code, {
        description: 'Rejected scope, membership, input or unavailable dependency',
        content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
      }])),
    },
  } };
}
