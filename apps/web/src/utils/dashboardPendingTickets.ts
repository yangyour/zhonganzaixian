import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { utcInstantNanos } from './utcInstant'

export interface PendingTicketContext {
  tenantId: string
  actorId: string
  permissionVersion: number
  role: 'padmin' | 'msp' | 'tadmin' | 'viewer'
}

export const pendingWorkflowLabels = {
  WORKFLOW_V33: { OPEN: '待接单', ACCEPTED: '已接单', IN_PROGRESS: '处理中', RESOLVED: '待客户确认' },
  LIFECYCLE_V42: { NEW: '新建', IN_PROGRESS: '处理中', WAITING_VENDOR: '等待厂商', PENDING_CUSTOMER: '待客户确认', RESOLVED: '已解决', REOPENED: '已重开' },
} as const

export interface PendingTicketItem {
  ticket_id: string
  receipt_no: string
  title: string
  registered_at: string
  workflow_model: keyof typeof pendingWorkflowLabels
  state: string
  version: number
}

export interface PendingTicketSnapshot {
  tenant_id: string
  actor_id: string
  permission_version: number
  observed_at: string
  scope: 'CURRENT_TENANT_INTERNAL' | 'REPORTER_ONLY'
  source: 'postgresql'
  metric_version: 'MOP_VISIBLE_NONTERMINAL_TICKETS_V1'
  registrations: number
  known_pending: number
  terminal: number
  unconfigured: number
  pending_tickets: number | null
  state: 'AVAILABLE' | 'PARTIAL'
  unavailable_reason: null | 'REGISTRATIONS_WITHOUT_WORKFLOW'
  item_limit: 5
  sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC'
  items: PendingTicketItem[]
  overdue_sla: null
  writes_performed: false
}

export interface PendingTicketState {
  current: PendingTicketSnapshot | null
  busy: boolean
  cancelled: boolean
  canRetry: boolean
  error: unknown
}

export const emptyPendingTickets = (): PendingTicketState => ({ current: null, busy: false, cancelled: false, canRetry: false, error: null })
const limit = 9_007_199_254_740_990
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const count = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= limit
const exact = (value: unknown, keys: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const validContext = (value: PendingTicketContext | null): value is PendingTicketContext => Boolean(value && uuid(value.tenantId) && uuid(value.actorId) && count(value.permissionVersion) && value.permissionVersion > 0 && ['padmin', 'msp', 'tadmin', 'viewer'].includes(value.role))
const bad = () => new ApiError(200, 'INVALID_PENDING_TICKET_SNAPSHOT', '待办汇总的授权范围、数量或状态未通过核对，未显示替代数量。', '—')
const snapshotKeys = ['tenant_id', 'actor_id', 'permission_version', 'observed_at', 'scope', 'source', 'metric_version', 'registrations', 'known_pending', 'terminal', 'unconfigured', 'pending_tickets', 'state', 'unavailable_reason', 'item_limit', 'sort', 'items', 'overdue_sla', 'writes_performed']
const itemKeys = ['ticket_id', 'receipt_no', 'title', 'registered_at', 'workflow_model', 'state', 'version']

/** Validate the same server snapshot used by the KPI and list, including complete versus partial totals. */
export function readPendingTickets(raw: unknown, context: PendingTicketContext): PendingTicketSnapshot {
  if (!validContext(context) || !exact(raw, snapshotKeys)) throw bad()
  const expectedScope = ['viewer', 'tadmin'].includes(context.role) ? 'REPORTER_ONLY' : 'CURRENT_TENANT_INTERNAL'
  if (raw.tenant_id !== context.tenantId || raw.actor_id !== context.actorId || raw.permission_version !== context.permissionVersion
      || raw.scope !== expectedScope || raw.source !== 'postgresql' || raw.metric_version !== 'MOP_VISIBLE_NONTERMINAL_TICKETS_V1'
      || raw.item_limit !== 5 || raw.sort !== 'REGISTRATION_CREATED_AT_DESC_ID_DESC' || raw.overdue_sla !== null || raw.writes_performed !== false) throw bad()
  const observed = utcInstantNanos(raw.observed_at)
  if (observed === null || !count(raw.registrations) || !count(raw.known_pending) || !count(raw.terminal) || !count(raw.unconfigured)
      || BigInt(raw.known_pending) + BigInt(raw.terminal) + BigInt(raw.unconfigured) !== BigInt(raw.registrations)) throw bad()
  if (raw.unconfigured === 0
    ? raw.pending_tickets !== raw.known_pending || raw.state !== 'AVAILABLE' || raw.unavailable_reason !== null
    : raw.pending_tickets !== null || raw.state !== 'PARTIAL' || raw.unavailable_reason !== 'REGISTRATIONS_WITHOUT_WORKFLOW') throw bad()
  if (!Array.isArray(raw.items) || raw.items.length !== Math.min(5, raw.known_pending)) throw bad()
  const seen = new Set<string>()
  let previous: { time: bigint; id: string } | null = null
  for (const item of raw.items) {
    if (!exact(item, itemKeys) || !uuid(item.ticket_id) || seen.has(item.ticket_id)
        || typeof item.receipt_no !== 'string' || !item.receipt_no.trim() || typeof item.title !== 'string' || !item.title.trim()
        || !count(item.version) || item.version < 1 || typeof item.workflow_model !== 'string'
        || !Object.hasOwn(pendingWorkflowLabels, item.workflow_model) || typeof item.state !== 'string'
        || !Object.hasOwn(pendingWorkflowLabels[item.workflow_model as keyof typeof pendingWorkflowLabels], item.state)) throw bad()
    const time = utcInstantNanos(item.registered_at)
    if (time === null || time > observed || previous && (time > previous.time || time === previous.time && item.ticket_id >= previous.id)) throw bad()
    seen.add(item.ticket_id)
    previous = { time, id: item.ticket_id }
  }
  return structuredClone(raw) as unknown as PendingTicketSnapshot
}

export function pendingTicketLabel(item: PendingTicketItem): string {
  return (pendingWorkflowLabels[item.workflow_model] as Record<string, string>)[item.state] ?? '未知状态'
}

/** V42 currently has no complete workflow editor in the web app; its link is explicitly the registration view. */
export function pendingTicketTarget(item: PendingTicketItem) {
  return { path: item.workflow_model === 'WORKFLOW_V33' ? '/ticket-workflows' : '/tickets', query: { ticket_id: item.ticket_id } }
}

export function createPendingTickets(client: Pick<ApiClient, 'request'>, changed: (state: PendingTicketState) => void) {
  let context: PendingTicketContext | null = null
  let state = emptyPendingTickets()
  let epoch = 0
  let disposed = false
  let controller: AbortController | null = null
  let lastObserved: bigint | null = null
  const emit = () => changed(detachedWorkspaceState(state))
  function reset(next: PendingTicketContext | null) {
    controller?.abort()
    controller = null
    epoch++
    context = !disposed && validContext(next) ? { ...next } : null
    state = emptyPendingTickets()
    lastObserved = null
    emit()
  }
  async function refresh() {
    if (!context || disposed || state.busy) return false
    const owner = { ...context }, turn = ++epoch, current = new AbortController()
    controller = current
    state = { current: null, busy: true, cancelled: false, canRetry: false, error: null }
    emit()
    try {
      if (disposed || turn !== epoch || current.signal.aborted) return false
      const raw = await client.request<unknown>('/dashboard/pending-tickets', { method: 'GET', signal: current.signal })
      if (disposed || turn !== epoch || current.signal.aborted) return false
      const snapshot = readPendingTickets(raw, owner), observed = utcInstantNanos(snapshot.observed_at)!
      if (lastObserved !== null && observed < lastObserved) throw bad()
      lastObserved = observed
      state.current = snapshot
      return true
    } catch (error) {
      if (!disposed && turn === epoch && !isCancelled(error)) state = { ...state, current: null, error, canRetry: true }
      return false
    } finally {
      if (!disposed && turn === epoch) {
        controller = null
        state.busy = false
        emit()
      }
    }
  }
  emit()
  return {
    reset, refresh,
    retry: () => state.canRetry ? refresh() : Promise.resolve(false),
    cancel() {
      if (!state.busy) return
      epoch++
      controller?.abort()
      controller = null
      state = { current: null, busy: false, cancelled: true, canRetry: true, error: null }
      emit()
    },
    dispose() { disposed = true; reset(null) },
  }
}
