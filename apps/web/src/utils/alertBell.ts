import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { utcInstantNanos } from './utcInstant'

export interface AlertBellContext { tenantId: string; actorId: string; permissionVersion: number; role: 'padmin' | 'msp' }
export interface AlertBellItem {
  event_id: string; asset_id: string; rule_name: string; severity: 'INFO' | 'WARNING' | 'AVERAGE' | 'HIGH' | 'DISASTER'
  monitor_state: 'PROBLEM'; handling_state: 'NEW'; suppression_state: 'NONE' | 'FLAP_SILENCED' | 'DEPENDENCY_SUPPRESSED' | 'MAINTENANCE_SILENCED' | 'INFO_DEFAULT_SILENT'
  occurrence_count: number; last_occurred_at: string; version: number
}
export interface AlertBellSnapshot {
  tenant_id: string; actor_id: string; permission_version: number; observed_at: string
  scope: 'CURRENT_TENANT_INTERNAL'; source: 'postgresql'; metric_version: 'MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1'
  filter: { monitor_state: 'PROBLEM'; handling_state: 'NEW' }; total: number; item_limit: 5
  sort: 'LAST_OCCURRED_AT_DESC_ID_DESC'; items: AlertBellItem[]; writes_performed: false
}
export interface AlertBellState { current: AlertBellSnapshot | null; busy: boolean; cancelled: boolean; canRetry: boolean; error: unknown }
export const emptyAlertBell = (): AlertBellState => ({ current: null, busy: false, cancelled: false, canRetry: false, error: null })
const maximum = 9_007_199_254_740_990
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= maximum
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && keys.length === Object.keys(value).length && keys.every(key => Object.hasOwn(value, key)))
const valid = (value: AlertBellContext | null): value is AlertBellContext => Boolean(value && uuid(value.tenantId) && uuid(value.actorId) && integer(value.permissionVersion) && value.permissionVersion > 0 && ['padmin', 'msp'].includes(value.role))
const bad = () => new ApiError(200, 'INVALID_ALERT_BELL', '未确认告警的范围、数量或状态未通过核对，未显示替代数量。', '—')
const severity = ['INFO', 'WARNING', 'AVERAGE', 'HIGH', 'DISASTER']
const suppression = ['NONE', 'FLAP_SILENCED', 'DEPENDENCY_SUPPRESSED', 'MAINTENANCE_SILENCED', 'INFO_DEFAULT_SILENT']

/** Only the dedicated same-snapshot bell response can supply its count; search/list page counts are not substitutes. */
export function readAlertBell(raw: unknown, context: AlertBellContext): AlertBellSnapshot {
  if (!valid(context) || !exact(raw, ['tenant_id', 'actor_id', 'permission_version', 'observed_at', 'scope', 'source', 'metric_version', 'filter', 'total', 'item_limit', 'sort', 'items', 'writes_performed'])) throw bad()
  if (raw.tenant_id !== context.tenantId || raw.actor_id !== context.actorId || raw.permission_version !== context.permissionVersion
      || raw.scope !== 'CURRENT_TENANT_INTERNAL' || raw.source !== 'postgresql' || raw.metric_version !== 'MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1'
      || !exact(raw.filter, ['monitor_state', 'handling_state']) || raw.filter.monitor_state !== 'PROBLEM' || raw.filter.handling_state !== 'NEW'
      || raw.item_limit !== 5 || raw.sort !== 'LAST_OCCURRED_AT_DESC_ID_DESC' || raw.writes_performed !== false || !integer(raw.total)
      || !Array.isArray(raw.items) || raw.items.length !== Math.min(5, raw.total)) throw bad()
  const observed = utcInstantNanos(raw.observed_at)
  if (observed === null) throw bad()
  let previous: { time: bigint; id: string } | null = null
  const seen = new Set<string>()
  for (const item of raw.items) {
    if (!exact(item, ['event_id', 'asset_id', 'rule_name', 'severity', 'monitor_state', 'handling_state', 'suppression_state', 'occurrence_count', 'last_occurred_at', 'version'])
        || !uuid(item.event_id) || !uuid(item.asset_id) || seen.has(item.event_id)
        || typeof item.rule_name !== 'string' || !item.rule_name.trim() || Array.from(item.rule_name).length > 128
        || typeof item.severity !== 'string' || !severity.includes(item.severity)
        || item.monitor_state !== 'PROBLEM' || item.handling_state !== 'NEW'
        || typeof item.suppression_state !== 'string' || !suppression.includes(item.suppression_state)
        || !integer(item.occurrence_count) || item.occurrence_count < 1 || item.occurrence_count > 2147483647
        || !integer(item.version) || item.version < 1) throw bad()
    const time = utcInstantNanos(item.last_occurred_at)
    if (time === null || time > observed || previous && (time > previous.time || time === previous.time && item.event_id >= previous.id)) throw bad()
    previous = { time, id: item.event_id }; seen.add(item.event_id)
  }
  return structuredClone(raw) as unknown as AlertBellSnapshot
}

export function createAlertBell(client: Pick<ApiClient, 'request'>, changed: (state: AlertBellState) => void) {
  let context: AlertBellContext | null = null, state = emptyAlertBell(), epoch = 0, disposed = false
  let abort: AbortController | null = null, lastObserved: bigint | null = null
  const emit = () => changed(detachedWorkspaceState(state))
  function reset(next: AlertBellContext | null) {
    epoch++; abort?.abort(); abort = null
    context = !disposed && valid(next) ? { ...next } : null
    state = emptyAlertBell(); lastObserved = null; emit()
  }
  async function refresh() {
    if (!context || disposed || state.busy) return false
    const owner = { ...context }, turn = ++epoch, request = new AbortController()
    abort = request; state = { current: null, busy: true, cancelled: false, canRetry: false, error: null }; emit()
    try {
      if (disposed || turn !== epoch || request.signal.aborted) return false
      const raw = await client.request<unknown>('/dashboard/alert-bell', { method: 'GET', signal: request.signal })
      if (disposed || turn !== epoch || request.signal.aborted) return false
      const result = readAlertBell(raw, owner), observed = utcInstantNanos(result.observed_at)!
      if (lastObserved !== null && observed < lastObserved) throw bad()
      lastObserved = observed; state.current = result; return true
    } catch (error) {
      if (!disposed && turn === epoch && !isCancelled(error)) state = { ...state, error, current: null, canRetry: true }
      return false
    } finally {
      if (!disposed && turn === epoch) { abort = null; state.busy = false; emit() }
    }
  }
  emit()
  return {
    reset, refresh, retry: () => state.canRetry ? refresh() : Promise.resolve(false),
    cancel() {
      if (!state.busy) return
      epoch++; abort?.abort(); abort = null
      state = { current: null, busy: false, cancelled: true, canRetry: true, error: null }; emit()
    },
    dispose() { disposed = true; reset(null) },
  }
}
