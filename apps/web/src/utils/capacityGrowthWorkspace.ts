import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readCapacityInput, readCapacityResult, type CapacityInput, type CapacityContext, type CapacityResult } from './capacityEstimateWorkspace'
export interface GrowthPoint { hosts: string; result: CapacityResult }
export interface GrowthState { points: GrowthPoint[] | null; busy: boolean; completed: number; total: number; error: unknown }
export const emptyGrowthState = (): GrowthState => ({ points: null, busy: false, completed: 0, total: 0, error: null })
const bad = () => new ApiError(0, 'INVALID_CAPACITY_GROWTH', '请明确提供2至20个严格递增、不重复的非负整数主机数；没有运行或保存场景', '—')
export function readGrowthHosts(raw: string): string[] { if (typeof raw !== 'string' || raw.length > 1000) throw bad(); const values = raw.trim().split(/[\s,，]+/u); if (values.length < 2 || values.length > 20) throw bad(); let previous = -1n; for (const value of values) { if (!/^(0|[1-9][0-9]{0,39})$/.test(value) || BigInt(value) <= previous) throw bad(); previous = BigInt(value) } return values }
/** Same visual scale, rounded down to basis points only for rendering. Original result strings are never rounded or replaced. */
export function growthWidths(values: string[]): string[] { if (!values.length) return []; if (values.length > 60 || values.some(v => typeof v !== 'string' || v.length > 256 || !/^(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(v))) throw bad(); const scale = Math.max(...values.map(v => v.split('.')[1]?.length ?? 0)); const integers = values.map(v => { const [whole, fraction = ''] = v.split('.'); return BigInt(whole! + fraction.padEnd(scale, '0')) }); const max = integers.reduce((a, b) => a > b ? a : b, 0n); return integers.map(v => { const bp = max === 0n ? 0n : v * 10000n / max; return `${bp / 100n}.${(bp % 100n).toString().padStart(2, '0')}%` }) }
export function createGrowthWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: GrowthState) => void) {
 let context: CapacityContext | null = null, state = emptyGrowthState(), epoch = 0, controller: AbortController | undefined
 const emit = () => changed({ ...state, points: state.points ? structuredClone(state.points) : null })
 function invalidate() { controller?.abort(); epoch++; state = emptyGrowthState(); emit() }
 function reset(next: CapacityContext | null) { invalidate(); context = next && next.role === 'padmin' && next.canEstimate && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(next.tenantId) && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(next.actorId) && Number.isSafeInteger(next.permissionVersion) && next.permissionVersion > 0 ? { ...next } : null }
 async function calculate(input: unknown, rawHosts: string) {
  if (!context || state.busy) return false
  const turn = ++epoch, scope = { ...context }, abort = new AbortController(); controller = abort; state = emptyGrowthState()
  try { const base = readCapacityInput(input), hosts = readGrowthHosts(rawHosts); if (base.inputs === null) throw bad(); state = { ...state, busy: true, total: hosts.length }; emit(); const points: GrowthPoint[] = []
   for (const count of hosts) { const value: CapacityInput = { inputs: { ...base.inputs, hosts: count }, model: base.model, proxy_buffer: null }; const raw = await client.request<unknown>('/capacity/estimates', { method: 'POST', body: value, signal: abort.signal }); if (turn !== epoch || abort.signal.aborted) return false; const result = readCapacityResult(raw, scope, value); points.push({ hosts: count, result }); state.completed = points.length; emit() }
   state.points = points; return true
  } catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, points: null, error }; return false } finally { if (turn === epoch) { state.busy = false; emit() } }
 }
 emit(); return { reset, invalidate, calculate, dispose: () => { invalidate(); context = null }, state: () => ({ ...state, points: state.points ? structuredClone(state.points) : null }) }
}
