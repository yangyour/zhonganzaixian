import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { pendingWorkflowLabels, type PendingTicketContext, type PendingTicketItem } from './dashboardPendingTickets'
import { utcInstantNanos } from './utcInstant'

export type ActionableTicketContext = PendingTicketContext
export interface ActionableTicketClock {
  status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CONFIG_PENDING' | 'PENDING_CONFIG' | 'BREACHED' | 'MET' | 'PAUSED_FOR_CUSTOMER_CONFIRM' | 'CANCELLED'
  breached: boolean | null
  missing_reason: string | null
  deadline_at: string | null
  completed_at: string | null
  remaining_millis: number | null
  remaining_basis: 'FROZEN_SERVICE_WINDOWS' | 'EXPLICIT_BUDGET_MINUS_ELAPSED' | null
}
export interface ActionableTicketItem extends PendingTicketItem {
  priority: string | null
  frozen_service_tier: string | null
  sla_metric_version: string | null
  sla_version: number
  overdue: boolean | null
  response_clock: ActionableTicketClock
  recovery_clock: ActionableTicketClock
}
export interface ActionableTicketSnapshot {
  tenant_id: string
  actor_id: string
  permission_version: number
  observed_at: string
  scope: 'CURRENT_TENANT_INTERNAL' | 'REPORTER_ONLY'
  source: 'postgresql'
  metric_version: 'MOP_ACTIONABLE_TICKETS_V1'
  projection_version: 'MOP_ACTIONABLE_TICKETS_SLA_V1'
  excluded_states: ['CLOSED', 'CANCELLED', 'RESOLVED']
  registrations: number
  known_pending: number
  excluded: number
  unconfigured: number
  pending_tickets: number | null
  overdue_sla: number | null
  known_overdue: number
  unknown_sla: number
  evaluated_pending: number
  unevaluated_pending: 0
  evaluation_limit: null
  unavailable_reasons: string[]
  page: number
  page_size: number
  total_pages: number
  item_limit: number
  sort: 'REGISTRATION_CREATED_AT_DESC_ID_DESC'
  items: ActionableTicketItem[]
  writes_performed: false
}
export interface ActionableTicketState {
  current: ActionableTicketSnapshot | null
  busy: boolean
  cancelled: boolean
  canRetry: boolean
  error: unknown
  page: number
  pageSize: number
}
export const emptyActionableTickets = (pageSize = 5): ActionableTicketState => ({ current: null, busy: false, cancelled: false, canRetry: false, error: null, page: 1, pageSize })
const limit = 9_007_199_254_740_990
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const count = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= limit
const text = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0
const nullableText = (value: unknown) => value === null || text(value)
const exact = (value: unknown, keys: readonly string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const validContext = (value: ActionableTicketContext | null): value is ActionableTicketContext => Boolean(value && uuid(value.tenantId) && uuid(value.actorId) && count(value.permissionVersion) && value.permissionVersion > 0 && ['padmin', 'msp', 'tadmin', 'viewer'].includes(value.role))
const validPage = (page: unknown, pageSize: unknown) => count(page) && page > 0 && count(pageSize) && pageSize > 0 && pageSize <= 100 && BigInt(page) * BigInt(pageSize) <= BigInt(limit)
const bad = () => new ApiError(200, 'INVALID_ACTIONABLE_TICKET_SNAPSHOT', '待处理工单与SLA的授权范围、数量或状态未通过核对，请重新读取。', '—')
const snapshotKeys = ['tenant_id', 'actor_id', 'permission_version', 'observed_at', 'scope', 'source', 'metric_version', 'projection_version', 'excluded_states', 'registrations', 'known_pending', 'excluded', 'unconfigured', 'pending_tickets', 'overdue_sla', 'known_overdue', 'unknown_sla', 'evaluated_pending', 'unevaluated_pending', 'evaluation_limit', 'unavailable_reasons', 'page', 'page_size', 'total_pages', 'item_limit', 'sort', 'items', 'writes_performed']
const itemKeys = ['ticket_id', 'receipt_no', 'title', 'registered_at', 'workflow_model', 'state', 'version', 'priority', 'frozen_service_tier', 'sla_metric_version', 'sla_version', 'overdue', 'response_clock', 'recovery_clock']
const clockKeys = ['status', 'breached', 'missing_reason', 'deadline_at', 'completed_at', 'remaining_millis', 'remaining_basis']
const clockStates = ['RUNNING', 'PAUSED', 'COMPLETED', 'CONFIG_PENDING', 'PENDING_CONFIG', 'BREACHED', 'MET', 'PAUSED_FOR_CUSTOMER_CONFIRM', 'CANCELLED']
const excludedStates = ['CLOSED', 'CANCELLED', 'RESOLVED']

/** Check persisted clock evidence only. Browser time never changes the service's breach decision. */
function validClock(raw: unknown, observed: bigint): raw is ActionableTicketClock {
  if (!exact(raw, clockKeys) || !clockStates.includes(String(raw.status)) || ![true, false, null].includes(raw.breached as any)
      || !nullableText(raw.missing_reason) || !(raw.remaining_millis === null || count(raw.remaining_millis))) return false
  if (raw.deadline_at !== null && utcInstantNanos(raw.deadline_at) === null) return false
  if (raw.completed_at !== null) {
    const completed = utcInstantNanos(raw.completed_at)
    if (completed === null || completed > observed) return false
  }
  if (raw.remaining_millis === null ? raw.remaining_basis !== null : !['FROZEN_SERVICE_WINDOWS', 'EXPLICIT_BUDGET_MINUS_ELAPSED'].includes(String(raw.remaining_basis))) return false
  if (raw.breached === null && raw.status !== 'CANCELLED' && !text(raw.missing_reason)) return false
  if (raw.status === 'CANCELLED' && (raw.breached !== null || raw.remaining_millis !== null)) return false
  if (['CONFIG_PENDING', 'PENDING_CONFIG'].includes(String(raw.status)) && raw.breached !== null) return false
  if (raw.status === 'BREACHED' && raw.breached !== true || raw.status === 'MET' && raw.breached !== false) return false
  return true
}

/** The complete population and requested page must agree; do not filter five old rows into a new metric. */
export function readActionableTickets(raw: unknown, context: ActionableTicketContext, page = 1, pageSize = 5): ActionableTicketSnapshot {
  if (!validContext(context) || !validPage(page, pageSize) || !exact(raw, snapshotKeys)) throw bad()
  const expectedScope = ['viewer', 'tadmin'].includes(context.role) ? 'REPORTER_ONLY' : 'CURRENT_TENANT_INTERNAL'
  if (raw.tenant_id !== context.tenantId || raw.actor_id !== context.actorId || raw.permission_version !== context.permissionVersion
      || raw.scope !== expectedScope || raw.source !== 'postgresql' || raw.metric_version !== 'MOP_ACTIONABLE_TICKETS_V1'
      || raw.projection_version !== 'MOP_ACTIONABLE_TICKETS_SLA_V1' || JSON.stringify(raw.excluded_states) !== JSON.stringify(excludedStates)
      || raw.page !== page || raw.page_size !== pageSize || raw.item_limit !== pageSize
      || raw.sort !== 'REGISTRATION_CREATED_AT_DESC_ID_DESC' || raw.writes_performed !== false) throw bad()
  const observed = utcInstantNanos(raw.observed_at)
  if (observed === null || !count(raw.registrations) || !count(raw.known_pending) || !count(raw.excluded) || !count(raw.unconfigured)
      || BigInt(raw.known_pending) + BigInt(raw.excluded) + BigInt(raw.unconfigured) !== BigInt(raw.registrations)
      || raw.pending_tickets !== (raw.unconfigured === 0 ? raw.known_pending : null)) throw bad()
  if (!count(raw.total_pages) || BigInt(raw.total_pages) !== (BigInt(raw.known_pending) + BigInt(pageSize) - 1n) / BigInt(pageSize)) throw bad()
  const offset = (BigInt(page) - 1n) * BigInt(pageSize)
  const left = BigInt(raw.known_pending) - offset
  const expectedItems = left <= 0n ? 0 : left >= BigInt(pageSize) ? pageSize : Number(left)
  if (!Array.isArray(raw.items) || raw.items.length !== expectedItems
      || raw.evaluated_pending !== raw.known_pending || raw.unevaluated_pending !== 0 || raw.evaluation_limit !== null
      || !count(raw.known_overdue) || !count(raw.unknown_sla) || raw.unknown_sla < raw.unconfigured
      || BigInt(raw.known_overdue) + BigInt(raw.unknown_sla) > BigInt(raw.known_pending) + BigInt(raw.unconfigured)
      || raw.overdue_sla !== (raw.unknown_sla === 0 ? raw.known_overdue : null)) throw bad()
  const reasons = [ ...(raw.unconfigured ? ['REGISTRATIONS_WITHOUT_WORKFLOW'] : []), ...(raw.unknown_sla > raw.unconfigured ? ['SLA_INPUTS_OR_READER_UNAVAILABLE'] : []) ]
  if (JSON.stringify(raw.unavailable_reasons) !== JSON.stringify(reasons)) throw bad()
  const seen = new Set<string>()
  let previous: { time: bigint; id: string } | null = null
  let pageOverdue = 0, pageUnknown = 0
  for (const item of raw.items) {
    if (!exact(item, itemKeys) || !uuid(item.ticket_id) || seen.has(item.ticket_id) || !text(item.receipt_no) || !text(item.title)
        || !count(item.version) || item.version < 1 || item.sla_version !== item.version || !nullableText(item.priority)
        || !nullableText(item.frozen_service_tier) || !nullableText(item.sla_metric_version) || typeof item.workflow_model !== 'string'
        || !Object.hasOwn(pendingWorkflowLabels, item.workflow_model) || typeof item.state !== 'string' || excludedStates.includes(item.state)
        || !Object.hasOwn(pendingWorkflowLabels[item.workflow_model as keyof typeof pendingWorkflowLabels], item.state)
        || !validClock(item.response_clock, observed) || !validClock(item.recovery_clock, observed)) throw bad()
    const response = item.response_clock.breached, recovery = item.recovery_clock.breached
    const overdue = response === true || recovery === true ? true : response === null || recovery === null ? null : false
    if (item.overdue !== overdue) throw bad()
    if (overdue === true) pageOverdue++
    if (overdue === null) pageUnknown++
    const time = utcInstantNanos(item.registered_at)
    if (time === null || time > observed || previous && (time > previous.time || time === previous.time && item.ticket_id >= previous.id)) throw bad()
    seen.add(item.ticket_id)
    previous = { time, id: item.ticket_id }
  }
  if (pageOverdue > raw.known_overdue || pageUnknown > raw.unknown_sla - raw.unconfigured) throw bad()
  const outsidePage = raw.known_pending - raw.items.length
  if (raw.known_overdue - pageOverdue + raw.unknown_sla - raw.unconfigured - pageUnknown > outsidePage) throw bad()
  return structuredClone(raw) as unknown as ActionableTicketSnapshot
}

/** Display a server-issued budget; paused and completed clocks remain explicitly labelled. */
export function actionableClockLabel(clock: ActionableTicketClock): string {
  if (clock.status === 'CANCELLED') return '已取消 · SLA未知'
  const suffix = clock.status === 'PAUSED' || clock.status === 'PAUSED_FOR_CUSTOMER_CONFIRM' ? ' · 已暂停'
    : clock.completed_at !== null || clock.status === 'MET' || clock.status === 'COMPLETED' ? ' · 已停钟' : ''
  if (clock.breached === true) return '已超时' + suffix
  if (clock.breached === null) return '未知 · 待核对SLA配置'
  if (clock.remaining_millis === null) return '剩余未知' + suffix
  const seconds = Math.ceil(clock.remaining_millis / 1000)
  const hours = Math.floor(seconds / 3600), minutes = Math.floor(seconds % 3600 / 60), rest = seconds % 60
  const duration = hours > 0 ? `${hours}小时${minutes}分` : minutes > 0 ? `${minutes}分${rest ? rest + '秒' : ''}` : `${rest}秒`
  return '剩余 ' + duration + suffix
}

export function createActionableTickets(client: Pick<ApiClient, 'request'>, changed: (state: ActionableTicketState) => void, pageSize = 5) {
  if (!validPage(1, pageSize)) throw new Error('Invalid actionable ticket page size')
  let context: ActionableTicketContext | null = null
  let state = emptyActionableTickets(pageSize)
  let epoch = 0, disposed = false
  let controller: AbortController | null = null
  let lastObserved: bigint | null = null
  const emit = () => changed(detachedWorkspaceState(state))
  function reset(next: ActionableTicketContext | null) {
    controller?.abort()
    controller = null
    epoch++
    context = !disposed && validContext(next) ? { ...next } : null
    state = emptyActionableTickets(pageSize)
    lastObserved = null
    emit()
  }
  async function refresh(page = state.page) {
    if (!context || disposed || state.busy || !validPage(page, pageSize)) return false
    const owner = { ...context }, turn = ++epoch, current = new AbortController()
    controller = current
    state = { current: null, busy: true, cancelled: false, canRetry: false, error: null, page, pageSize }
    emit()
    try {
      if (disposed || turn !== epoch || current.signal.aborted) return false
      const raw = await client.request<unknown>(`/dashboard/actionable-tickets?page=${page}&page_size=${pageSize}`, { method: 'GET', signal: current.signal })
      if (disposed || turn !== epoch || current.signal.aborted) return false
      const snapshot = readActionableTickets(raw, owner, page, pageSize), observed = utcInstantNanos(snapshot.observed_at)!
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
      state = { ...state, current: null, busy: false, cancelled: true, canRetry: true, error: null }
      emit()
    },
    dispose() { disposed = true; reset(null) },
  }
}
