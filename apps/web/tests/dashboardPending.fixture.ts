import type { PendingTicketContext, PendingTicketItem, PendingTicketSnapshot } from '../src/utils/dashboardPendingTickets'

export const pendingId = (value: number) => '79000000-0000-4000-8000-' + String(value).padStart(12, '0')
export const pendingContext: PendingTicketContext = { tenantId: pendingId(1), actorId: pendingId(2), permissionVersion: 7, role: 'padmin' }
export function pendingItem(value = 20, model: PendingTicketItem['workflow_model'] = 'WORKFLOW_V33', state = 'OPEN'): PendingTicketItem {
  return { ticket_id: pendingId(value), receipt_no: 'SYN-' + value, title: 'Synthetic pending ' + value,
    registered_at: '2026-10-22T12:00:00.000000001Z', workflow_model: model, state, version: 1 }
}
export function pendingSnapshot(overrides: Partial<PendingTicketSnapshot> = {}): PendingTicketSnapshot {
  return {
    tenant_id: pendingContext.tenantId, actor_id: pendingContext.actorId, permission_version: 7,
    observed_at: '2026-10-23T12:00:00.001Z', scope: 'CURRENT_TENANT_INTERNAL', source: 'postgresql',
    metric_version: 'MOP_VISIBLE_NONTERMINAL_TICKETS_V1', registrations: 3, known_pending: 2, terminal: 1,
    unconfigured: 0, pending_tickets: 2, state: 'AVAILABLE', unavailable_reason: null, item_limit: 5,
    sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC', items: [pendingItem(), pendingItem(19, 'LIFECYCLE_V42', 'WAITING_VENDOR')],
    overdue_sla: null, writes_performed: false, ...overrides,
  }
}
