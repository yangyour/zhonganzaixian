import { isCancelled, type ApiClient } from '../api/client'
import { readSnapshot, readResources, readHistory, readResourceHistory, sampleText, type Device, type Metric, type HistoryMetric, type NetworkSnapshot, type ResourceSnapshot, type NetworkHistory, type ResourceHistory } from './networkMonitoring'

export interface MonitoringContext { tenantId: string; actorId: string; permissionVersion: number; assetId?: string }
export interface LiveMonitoringState {
  snapshot: NetworkSnapshot | null; resources: Record<string, ResourceSnapshot>; resourceErrors: Record<string, unknown>
  loadedAt: number; busy: boolean; error: unknown
  history: NetworkHistory | ResourceHistory | null; historyBusy: boolean; historyError: unknown
}
const empty = (): LiveMonitoringState => ({ snapshot: null, resources: {}, resourceErrors: {}, loadedAt: 0, busy: false, error: null, history: null, historyBusy: false, historyError: null })

/** Every response belongs to one authorization epoch, including transports that ignore abort. */
export function createLiveMonitoringWorkspace(client: Pick<ApiClient, 'request'>, changed: (s: LiveMonitoringState) => void, clock = () => performance.now()) {
  let state = empty(), context: MonitoringContext | null = null, epoch = 0, detailEpoch = 0, disposed = false
  let abort: AbortController | null = null, detailAbort: AbortController | null = null
  const emit = () => changed({ ...state, resources: { ...state.resources }, resourceErrors: { ...state.resourceErrors } })
  function clearHistory() { ++detailEpoch; detailAbort?.abort(); detailAbort = null; state.history = null; state.historyBusy = false; state.historyError = null }
  function reset(next: MonitoringContext | null) { ++epoch; abort?.abort(); abort = null; clearHistory(); context = disposed || !next ? null : { ...next }; state = empty(); emit() }
  async function load() {
    if (!context || disposed || state.busy) return false
    const scope = { ...context }, turn = ++epoch, controller = new AbortController(); abort = controller
    clearHistory(); state = { ...empty(), busy: true }; emit()
    const current = () => !disposed && turn === epoch && !controller.signal.aborted
    try {
      const result = readSnapshot(await client.request<NetworkSnapshot>('/network-monitoring', { method: 'GET', signal: controller.signal }), scope.tenantId)
      if (!current()) return false
      state.snapshot = { ...result, devices: result.devices.filter(d => !scope.assetId || d.asset_id === scope.assetId) }; state.loadedAt = clock(); emit()
      await Promise.all(state.snapshot.devices.filter(d => d.vendor_metrics_status === 'PARTIAL_API').map(async device => {
        try {
          const resource = readResources(await client.request<ResourceSnapshot>(`/network-monitoring/${encodeURIComponent(device.asset_id)}/resources`, { method: 'GET', signal: controller.signal }), device.asset_id)
          if (current()) { state.resources[device.asset_id] = resource; emit() }
        } catch (error) { if (current() && !isCancelled(error)) { state.resourceErrors[device.asset_id] = error; emit() } }
      }))
      return current()
    } catch (error) { if (current() && !isCancelled(error)) { state.snapshot = null; state.error = error }; return false }
    finally { if (current()) { state.busy = false; abort = null; emit() } }
  }
  async function history(assetId: string, metric: HistoryMetric, window: 'HOUR_1' | 'HOURS_24') {
    clearHistory()
    const device = state.snapshot?.devices.find(d => d.asset_id === assetId)
    if (!context || disposed || !device || !['icmp','loss','latency','tcp_22','tcp_80','tcp_443','cpu','memory'].includes(metric) || !['HOUR_1','HOURS_24'].includes(window) || (metric === 'cpu' || metric === 'memory') && device.vendor_metrics_status !== 'PARTIAL_API') { emit(); return false }
    const turn = detailEpoch, parent = epoch, controller = new AbortController(); detailAbort = controller; state.historyBusy = true; emit()
    const current = () => !disposed && turn === detailEpoch && parent === epoch && !controller.signal.aborted
    try {
      const resource = metric === 'cpu' || metric === 'memory'
      const path = `/network-monitoring/${encodeURIComponent(assetId)}/${resource ? 'resources/' : ''}history?` + new URLSearchParams({ metric, window })
      const raw = await client.request<any>(path, { method: 'GET', signal: controller.signal })
      if (!current()) return false
      state.history = resource ? readResourceHistory(raw, assetId, metric, window) : readHistory(raw, assetId, metric, window)
      return true
    } catch (error) { if (current() && !isCancelled(error)) state.historyError = error; return false }
    finally { if (current()) { state.historyBusy = false; detailAbort = null; emit() } }
  }
  emit()
  return { reset, load, history, clearHistory: () => { clearHistory(); emit() }, dispose: () => { disposed = true; reset(null) } }
}

export function liveMetricText(state: LiveMonitoringState, device: Device, metric: Metric, now: number) {
  const sample = device.metrics[metric], elapsed = Math.max(0, now - state.loadedAt)
  const age = sample.recorded_at && state.snapshot ? Date.parse(state.snapshot.generated_at) + elapsed - Date.parse(sample.recorded_at) : null
  if (sample.state === 'FRESH' && age !== null && age < 0) return '时钟异常'
  return sampleText(device, metric, elapsed > 90_000 || sample.state === 'FRESH' && age !== null && age > 180_000)
}
export function liveResourceValue(state: LiveMonitoringState, id: string, metric: 'cpu' | 'memory', now: number): number | null {
  const value = state.resources[id], elapsed = Math.max(0, now - state.loadedAt)
  if (!value || value.state !== 'FRESH' || !value.received_at || elapsed > 90_000) return null
  const age = Date.parse(value.generated_at) + elapsed - Date.parse(value.received_at)
  return age >= 0 && age <= 180_000 ? Number(value.metrics[metric].value) : null
}
