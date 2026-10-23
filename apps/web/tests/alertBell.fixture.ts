import type { AlertBellContext, AlertBellItem, AlertBellSnapshot } from '../src/utils/alertBell'
export const bellId = (n: number) => '10000000-0000-4000-8000-' + String(n).padStart(12, '0')
export const bellContext: AlertBellContext = { tenantId: bellId(1), actorId: bellId(2), permissionVersion: 7, role: 'padmin' }
export const bellItem = (n = 20): AlertBellItem => ({ event_id: bellId(n), asset_id: bellId(3), rule_name: 'Synthetic event ' + n,
  severity: 'HIGH', monitor_state: 'PROBLEM', handling_state: 'NEW', suppression_state: 'NONE',
  occurrence_count: 1, last_occurred_at: '2026-10-23T12:00:00.000000001Z', version: 1 })
export const bellSnapshot = (patch: Partial<AlertBellSnapshot> = {}): AlertBellSnapshot => ({
  tenant_id: bellContext.tenantId, actor_id: bellContext.actorId, permission_version: 7, observed_at: '2026-10-23T14:00:00Z',
  scope: 'CURRENT_TENANT_INTERNAL', source: 'postgresql', metric_version: 'MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1',
  filter: { monitor_state: 'PROBLEM', handling_state: 'NEW' }, total: 2, item_limit: 5,
  sort: 'LAST_OCCURRED_AT_DESC_ID_DESC', items: [bellItem(), { ...bellItem(19), occurrence_count: 5, suppression_state: 'MAINTENANCE_SILENCED' }],
  writes_performed: false, ...patch,
})
