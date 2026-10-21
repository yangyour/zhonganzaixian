import { ApiError, isCancelled, type ApiClient } from '../api/client'
export interface CapacityInputs { hosts: string | null; items_per_host: string | null; interval_seconds: string | null; history_days: number | null; bytes_per_value: string | null; trend_ratio: string | null; trend_days: number | null; headroom_ratio: string | null }
export interface CapacityModel { version: string | null; bytes_per_trend_row: string | null; pool_nvps_limit: string | null }
export interface BufferInputs { site_nvps: string | null; outage_seconds: string | null; bytes_per_value: string | null; margin_factor: string | null }
export interface CapacityInput { inputs: CapacityInputs | null; model: CapacityModel | null; proxy_buffer: BufferInputs | null }
export interface CapacityOutputs { nvps: string; daily_bytes: string; history_bytes: string; trend_bytes: string; single_node_bytes: string; two_node_bytes: string; peak_nvps: string; total_items: string; pools: string }
export interface CapacityResult { tenant_id: string; scope: 'EXPLICIT_CAPACITY_ESTIMATE'; source: 'EXPLICIT_INPUT_NOT_MEASURED_DATA'; formula_version: 'contract-m10-7-capacity-v1'; input: CapacityInput; status: 'ESTIMATED' | 'UNRESOLVED'; model_version: string | null; missing_inputs: string[]; outputs: CapacityOutputs | null; decimal_precision: 'DECIMAL128'; decimal_encoding: 'BASE10_STRINGS'; estimate_only: true; scenario_saved: false; model_approval_status: 'UNVERIFIED'; measurement_verification: 'UNVERIFIED'; approved_pool_nvps_limit: null; measured_capacity: null; production_capacity_verified: false; poc_status: 'NOT_RUN'; proxy_buffer: { status: 'ESTIMATED' | 'UNRESOLVED'; formula_version: 'contract-m4-15-buffer-v1'; missing_inputs: string[]; estimated_bytes: string | null; margin_factor_below_one: boolean | null; measurement_verified: false; failover_verified: false }; units: { nvps: 'values_per_second'; storage: 'bytes'; counts: 'integers' } }
export interface CapacityContext { tenantId: string; actorId: string; permissionVersion: number; role: string; canEstimate: boolean }
export interface CapacityState { input: unknown; result: CapacityResult | null; busy: boolean; error: unknown }
const inputKeys = ['hosts', 'items_per_host', 'interval_seconds', 'history_days', 'bytes_per_value', 'trend_ratio', 'trend_days', 'headroom_ratio'] as const
const modelKeys = ['version', 'bytes_per_trend_row', 'pool_nvps_limit'] as const
const bufferKeys = ['site_nvps', 'outage_seconds', 'bytes_per_value', 'margin_factor'] as const
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy = <T>(v: T): T => structuredClone(v)
const exact = (v: unknown, keys: readonly string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const bad = () => new ApiError(0, 'INVALID_CAPACITY_ESTIMATE', '显式容量输入或返回算术不一致，旧结果已清除；未保存或写入业务', '—')
const integer = (v: unknown): v is string | null => v === null || typeof v === 'string' && /^(0|[1-9][0-9]{0,39})$/.test(v)
const decimal = (v: unknown): v is string | null => v === null || typeof v === 'string' && /^(0|[1-9][0-9]{0,39})(\.[0-9]{1,40})?$/.test(v)
const day = (v: unknown) => v === null || Number.isInteger(v) && Number(v) >= 0 && Number(v) <= 2147483647
const label = (v: unknown) => v === null || typeof v === 'string' && Boolean(v.replace(/[\s\u001c-\u001f\u0085]+/gu, '')) && Array.from(v).length <= 100 && !/[\u0000-\u001f\u007f-\u009f]/.test(v) && !Array.from(v).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))
type Rational = { n: bigint; d: bigint }
const power = (n: number) => { if (!Number.isInteger(n) || n < 0 || n > 1000) throw bad(); return 10n ** BigInt(n) }
const rational = (v: string | number): Rational => { const [whole, fraction = ''] = String(v).split('.'); return { n: BigInt(whole! + fraction), d: power(fraction.length) } }
const multiply = (a: Rational, b: Rational): Rational => ({ n: a.n * b.n, d: a.d * b.d })
const add = (a: Rational, b: Rational): Rational => ({ n: a.n * b.d + b.n * a.d, d: a.d * b.d })
const divide = (a: Rational, b: Rational): Rational => { if (b.n <= 0n) throw bad(); return { n: a.n * b.d, d: a.d * b.n } }
/** Independent 34-significant-digit HALF_EVEN verification; no floating-point conversion. */
export function roundedDecimal128(n: bigint, d: bigint): string {
  if (n < 0n || d <= 0n) throw bad(); if (n === 0n) return '0'
  let exponent = n.toString().length - d.toString().length
  if (exponent >= 0 ? n < d * power(exponent) : n * power(-exponent) < d) exponent--
  const scale = 33 - exponent, numerator = scale >= 0 ? n * power(scale) : n, denominator = scale >= 0 ? d : d * power(-scale)
  let q = numerator / denominator; const remainder = numerator % denominator
  if (remainder * 2n > denominator || remainder * 2n === denominator && q % 2n !== 0n) q++
  const digits = q.toString(); if (scale <= 0) return digits + '0'.repeat(-scale)
  const padded = digits.padStart(scale + 1, '0'), formatted = padded.slice(0, -scale) + '.' + padded.slice(-scale)
  return formatted.replace(/0+$/, '').replace(/\.$/, '')
}
const output = (v: Rational) => roundedDecimal128(v.n, v.d)
const positive = (v: string | null) => v === null || rational(v).n > 0n
const ratio = (v: string | null) => v === null || rational(v).n <= rational(v).d
export function readCapacityInput(v: unknown): CapacityInput {
  if (!exact(v, ['inputs', 'model', 'proxy_buffer'])) throw bad()
  if (v.inputs !== null) { if (!exact(v.inputs, inputKeys)) throw bad(); const i = v.inputs; if (!integer(i.hosts) || !integer(i.items_per_host) || !day(i.history_days) || !day(i.trend_days)) throw bad(); for (const k of ['interval_seconds', 'bytes_per_value', 'trend_ratio', 'headroom_ratio']) if (!decimal(i[k])) throw bad(); if (!positive(i.interval_seconds as string | null) || !positive(i.bytes_per_value as string | null) || !ratio(i.trend_ratio as string | null) || !ratio(i.headroom_ratio as string | null)) throw bad() }
  if (v.model !== null) { if (!exact(v.model, modelKeys) || !label(v.model.version) || !decimal(v.model.bytes_per_trend_row) || !decimal(v.model.pool_nvps_limit) || !positive(v.model.bytes_per_trend_row) || !positive(v.model.pool_nvps_limit)) throw bad() }
  if (v.proxy_buffer !== null) { if (!exact(v.proxy_buffer, bufferKeys)) throw bad(); for (const k of bufferKeys) if (!decimal(v.proxy_buffer[k])) throw bad(); if (!positive(v.proxy_buffer.bytes_per_value as string | null) || !positive(v.proxy_buffer.margin_factor as string | null)) throw bad() }
  return copy(v) as unknown as CapacityInput
}
function same(a: unknown, b: unknown): boolean { if (a === b) return true; if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => same(v, b[i])); if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const aa = a as Record<string, unknown>, bb = b as Record<string, unknown>; return Object.keys(aa).length === Object.keys(bb).length && Object.keys(aa).every(k => Object.hasOwn(bb, k) && same(aa[k], bb[k])) } return false }
/** Arithmetic expectations only validate a fetched response; they are never displayed as an API result. */
function expected(input: CapacityInput, tenant: string): CapacityResult {
  const missing: string[] = []; if (!input.inputs) missing.push('inputs'); else for (const k of inputKeys) if (input.inputs[k] === null) missing.push(k)
  if (!input.model) missing.push('model'); else for (const k of modelKeys) if (input.model[k] === null) missing.push('model.' + k)
  let outputs: CapacityOutputs | null = null
  if (!missing.length) {
    const i = input.inputs!, m = input.model!, itemCount = BigInt(i.hosts!) * BigInt(i.items_per_host!), items = rational(itemCount.toString()), interval = rational(i.interval_seconds!), nvps = divide(items, interval)
    const daily = divide(multiply(multiply(items, rational(86400)), rational(i.bytes_per_value!)), interval), history = multiply(daily, rational(i.history_days!))
    const trend = multiply(multiply(multiply(multiply(items, rational(i.trend_ratio!)), rational(24)), rational(i.trend_days!)), rational(m.bytes_per_trend_row!))
    const single = multiply(add(history, trend), add(rational(1), rational(i.headroom_ratio!))), pools = divide(items, multiply(interval, rational(m.pool_nvps_limit!)))
    outputs = { nvps: output(nvps), daily_bytes: output(daily), history_bytes: output(history), trend_bytes: output(trend), single_node_bytes: output(single), two_node_bytes: output(multiply(single, rational(2))), peak_nvps: output(multiply(nvps, rational(2))), total_items: itemCount.toString(), pools: ((pools.n + pools.d - 1n) / pools.d).toString() }
  }
  const buffer = input.proxy_buffer, bufferMissing: string[] = []; if (!buffer) bufferMissing.push('proxy_buffer'); else for (const k of bufferKeys) if (buffer[k] === null) bufferMissing.push(k)
  const bufferBytes = bufferMissing.length ? null : output(bufferKeys.reduce((a, k) => multiply(a, rational(buffer![k]!)), rational(1)))
  return { tenant_id: tenant, scope: 'EXPLICIT_CAPACITY_ESTIMATE', source: 'EXPLICIT_INPUT_NOT_MEASURED_DATA', formula_version: 'contract-m10-7-capacity-v1', input, status: missing.length ? 'UNRESOLVED' : 'ESTIMATED', model_version: input.model?.version ?? null, missing_inputs: missing, outputs, decimal_precision: 'DECIMAL128', decimal_encoding: 'BASE10_STRINGS', estimate_only: true, scenario_saved: false, model_approval_status: 'UNVERIFIED', measurement_verification: 'UNVERIFIED', approved_pool_nvps_limit: null, measured_capacity: null, production_capacity_verified: false, poc_status: 'NOT_RUN', proxy_buffer: { status: bufferMissing.length ? 'UNRESOLVED' : 'ESTIMATED', formula_version: 'contract-m4-15-buffer-v1', missing_inputs: bufferMissing, estimated_bytes: bufferBytes, margin_factor_below_one: buffer?.margin_factor == null ? null : rational(buffer.margin_factor).n < rational(buffer.margin_factor).d, measurement_verified: false, failover_verified: false }, units: { nvps: 'values_per_second', storage: 'bytes', counts: 'integers' } }
}
export function readCapacityResult(value: unknown, scope: CapacityContext, input: CapacityInput): CapacityResult { const normalized = readCapacityInput(input), correct = expected(normalized, scope.tenantId); if (!same(value, correct)) throw bad(); return copy(value) as CapacityResult }
export const emptyCapacityInput = (): CapacityInput => ({ inputs: null, model: null, proxy_buffer: null })
export function createCapacityWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: CapacityState) => void) {
  let context: CapacityContext | null = null, generation = 0, controller: AbortController | null = null, disposed = false, state: CapacityState = { input: emptyCapacityInput(), result: null, busy: false, error: null }
  const snapshot = () => ({ ...state, input: copy(state.input), result: state.result ? copy(state.result) : null }), emit = () => notify(snapshot())
  function clear() { ++generation; controller?.abort(); controller = null; state = { ...state, result: null, busy: false, error: null } }
  return {
    state: snapshot,
    reset(scope: CapacityContext | null) { clear(); context = !disposed && scope?.role === 'padmin' && scope.canEstimate === true && uuid.test(scope.tenantId) && uuid.test(scope.actorId) && Number.isSafeInteger(scope.permissionVersion) && scope.permissionVersion > 0 ? { ...scope } : null; state.input = emptyCapacityInput(); emit() },
    change(input: unknown) { if (disposed) return; clear(); state.input = copy(input); emit() },
    cancel() { if (disposed) return; clear(); emit() },
    async calculate() {
      if (disposed || !context || state.busy) return false
      clear(); const turn = generation, scope = { ...context }, abort = new AbortController(); controller = abort
      const current = () => turn === generation && !disposed && !abort.signal.aborted
      try {
        const input = readCapacityInput(state.input)
        state.busy = true; emit()
        if (!current()) return false
        const value = await client.request('/capacity/estimates', { method: 'POST', body: input, signal: abort.signal })
        if (!current()) return false
        state.result = readCapacityResult(value, scope, input); return true
      } catch (error) { if (current() && !isCancelled(error)) state.error = error; return false }
      finally { if (current()) { state.busy = false; controller = null; emit() } }
    },
    dispose() { clear(); disposed = true; context = null; state.input = emptyCapacityInput(); emit() },
  }
}
