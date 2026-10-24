import type { ActionableTicketClock, ActionableTicketItem, ActionableTicketSnapshot } from '../src/utils/dashboardActionableTickets'
import { pendingContext, pendingItem } from './dashboardPending.fixture'
export { pendingContext as actionableContext, pendingId as actionableId } from './dashboardPending.fixture'

export function actionableClock(overrides: Partial<ActionableTicketClock> = {}): ActionableTicketClock {
  return { status: 'RUNNING', breached: false, missing_reason: null, deadline_at: '2026-10-23T13:00:00Z', completed_at: null,
    remaining_millis: 3_600_000, remaining_basis: 'FROZEN_SERVICE_WINDOWS', ...overrides }
}
export function unknownActionableClock(): ActionableTicketClock {
  return actionableClock({ status: 'CONFIG_PENDING', breached: null, missing_reason: 'CONFIGURATION_UNAVAILABLE', deadline_at: null, remaining_millis: null, remaining_basis: null })
}
export function actionableItem(value = 20, model: ActionableTicketItem['workflow_model'] = 'WORKFLOW_V33', state = 'OPEN', overrides: Partial<ActionableTicketItem> = {}): ActionableTicketItem {
  return { ...pendingItem(value, model, state), priority: 'P1', frozen_service_tier: 'STANDARD', sla_metric_version: 'SLA_CLOCK_V2', sla_version: 1, overdue: false,
    response_clock: actionableClock(), recovery_clock: actionableClock(), ...overrides }
}
export function actionableSnapshot(overrides: Partial<ActionableTicketSnapshot> = {}): ActionableTicketSnapshot {
  return {
    tenant_id: pendingContext.tenantId, actor_id: pendingContext.actorId, permission_version: 7,
    observed_at: '2026-10-23T12:00:00.001Z', scope: 'CURRENT_TENANT_INTERNAL', source: 'postgresql',
    metric_version: 'MOP_ACTIONABLE_TICKETS_V1', projection_version: 'MOP_ACTIONABLE_TICKETS_SLA_V1',
    excluded_states: ['CLOSED', 'CANCELLED', 'RESOLVED'], registrations: 3, known_pending: 2, excluded: 1, unconfigured: 0,
    pending_tickets: 2, overdue_sla: 0, known_overdue: 0, unknown_sla: 0, evaluated_pending: 2, unevaluated_pending: 0,
    evaluation_limit: null, unavailable_reasons: [], page: 1, page_size: 5, total_pages: 1, item_limit: 5,
    sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC', writes_performed: false,
    items: [actionableItem(), actionableItem(19, 'LIFECYCLE_V42', 'WAITING_VENDOR')], ...overrides,
  }
}

/** Pure synthetic source for pagination tests, never seeded into a live tenant. */
export function actionablePage(page: number, pageSize = 20, total = 42): ActionableTicketSnapshot {
  const start = (page - 1) * pageSize
  return actionableSnapshot({ registrations: total + 3, excluded: 3, known_pending: total, pending_tickets: total,
    evaluated_pending: total, page, page_size: pageSize, total_pages: Math.ceil(total / pageSize), item_limit: pageSize,
    items: Array.from({ length: Math.max(0, Math.min(pageSize, total - start)) }, (_, index) => actionableItem(500 - start - index)) })
}
