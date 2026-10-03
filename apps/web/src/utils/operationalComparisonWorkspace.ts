import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { roundedDecimal128 } from './capacityEstimateWorkspace'
export interface OperationalInput { elapsed_millis: (string | null)[] | null; staffing: { active_tenants: string | null; on_call_fte: string | null; second_line_fte: string | null } | null }
export interface OperationalContext { tenantId: string; actorId: string; permissionVersion: number; role: string; canCompare: boolean }
type Status = 'CALCULATED' | 'UNRESOLVED' | 'UNDEFINED'
export interface OperationalResult { tenant_id: string; scope: 'EXPLICIT_OPERATIONAL_COMPARISON'; source: 'EXPLICIT_INPUT_NOT_PROJECT_METRICS'; input: OperationalInput; decimal_encoding: 'BASE10_STRINGS'; sample_median: { status: Status; reason: string; formula_version: 'explicit-elapsed-median-v1'; unit: 'milliseconds'; sample_count: number | null; unknown_count: number | null; median_millis: string | null; duration_basis_verification: 'UNVERIFIED_Q27'; population_verification: 'UNVERIFIED'; effective_processing_duration: null }; staffing: { status: Status; reason: string; formula_version: 'explicit-tenants-per-fte-v1'; explicit_total_fte: string | null; tenants_per_fte: string | null; missing_inputs: string[]; decimal_precision: 'DECIMAL128'; population_verification: 'UNVERIFIED_Q30'; staffing_basis_verification: 'UNVERIFIED_Q23'; effective_tenants_per_fte: null; target_met: null; billing_applicable: false }; comparison_saved: false; formal_metrics_available: false; business_data_queried: false }
export interface OperationalState { input: unknown; result: OperationalResult | null; busy: boolean; error: unknown }
const copy = <T>(v: T): T => structuredClone(v)
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const integer = (v: unknown) => v === null || typeof v === 'string' && /^(0|[1-9][0-9]{0,39})$/.test(v)
const decimal = (v: unknown) => v === null || typeof v === 'string' && /^(0|[1-9][0-9]{0,39})(\.[0-9]{1,40})?$/.test(v)
const bad = () => new ApiError(0, 'INVALID_OPERATIONAL_COMPARISON', '输入或返回对照不一致，旧结果已清除；每行须为整数或?，不忽略空行，不采用本地计算替代服务端结果', '—')
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const emptyOperationalInput = (): OperationalInput => ({ elapsed_millis: null, staffing: null })
export function readOperationalInput(value: unknown): OperationalInput {
  if (!exact(value, ['elapsed_millis', 'staffing'])) throw bad()
  if (value.elapsed_millis !== null && (!Array.isArray(value.elapsed_millis) || value.elapsed_millis.length > 10000 || !value.elapsed_millis.every(integer))) throw bad()
  if (value.staffing !== null && (!exact(value.staffing, ['active_tenants', 'on_call_fte', 'second_line_fte']) || !integer(value.staffing.active_tenants) || !decimal(value.staffing.on_call_fte) || !decimal(value.staffing.second_line_fte))) throw bad()
  return copy(value) as unknown as OperationalInput
}
export function operationalSampleInput(mode: string, text: string): unknown { if (mode === 'unknown') return null; if (mode === 'empty') return []; if (mode !== 'provided' || text.length > 430000) return false; return text.split(/\r?\n/).map(v => v === '?' ? null : v) }
function same(a: unknown, b: unknown): boolean { if (a === b) return true; if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => same(v, b[i])); if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const aa = a as Record<string, unknown>, bb = b as Record<string, unknown>; return Object.keys(aa).length === Object.keys(bb).length && Object.keys(aa).every(k => Object.hasOwn(bb, k) && same(aa[k], bb[k])) } return false }
function exactDecimal(n: bigint, scale: number): string { if (scale === 0) return n.toString(); const value = n.toString().padStart(scale + 1, '0'); return (value.slice(0, -scale) + '.' + value.slice(-scale)).replace(/0+$/, '').replace(/\.$/, '') }
/** Only verifies an actual API response. No local fallback or formal metric projection. */
function expected(input: OperationalInput, tenant: string): OperationalResult {
  const values = input.elapsed_millis, count = values?.length ?? null, unknown = values?.filter(v => v === null).length ?? null
  let median: string | null = null
  if (values && values.length && unknown === 0) { const sorted = values.map(v => BigInt(v!)).sort((a, b) => a < b ? -1 : a > b ? 1 : 0), middle = Math.floor(sorted.length / 2); const twice = sorted.length % 2 ? sorted[middle]! * 2n : sorted[middle - 1]! + sorted[middle]!; median = (twice / 2n).toString() + (twice % 2n ? '.5' : '') }
  const s = input.staffing, missing = s ? ['active_tenants', 'on_call_fte', 'second_line_fte'].filter(k => s[k as keyof typeof s] === null) : ['input']
  let fte: string | null = null, ratio: string | null = null
  if (s?.on_call_fte != null && s.second_line_fte != null) { const [aw, af = ''] = s.on_call_fte.split('.'), [bw, bf = ''] = s.second_line_fte.split('.'), scale = Math.max(af.length, bf.length); const sum = BigInt(aw! + af.padEnd(scale, '0')) + BigInt(bw! + bf.padEnd(scale, '0')); fte = exactDecimal(sum, scale); if (!missing.length && sum > 0n) ratio = roundedDecimal128(BigInt(s.active_tenants!) * 10n ** BigInt(scale), sum) }
  return { tenant_id: tenant, scope: 'EXPLICIT_OPERATIONAL_COMPARISON', source: 'EXPLICIT_INPUT_NOT_PROJECT_METRICS', input, decimal_encoding: 'BASE10_STRINGS', sample_median: { status: median !== null ? 'CALCULATED' : values?.length === 0 ? 'UNDEFINED' : 'UNRESOLVED', reason: values === null ? 'INPUT_UNAVAILABLE' : values.length === 0 ? 'EMPTY_SAMPLE' : unknown ? 'UNKNOWN_SAMPLE' : 'ALL_EXPLICIT', formula_version: 'explicit-elapsed-median-v1', unit: 'milliseconds', sample_count: count, unknown_count: unknown, median_millis: median, duration_basis_verification: 'UNVERIFIED_Q27', population_verification: 'UNVERIFIED', effective_processing_duration: null }, staffing: { status: missing.length ? 'UNRESOLVED' : fte === '0' ? 'UNDEFINED' : 'CALCULATED', reason: !s ? 'INPUT_UNAVAILABLE' : missing.length ? 'MISSING_INPUT' : fte === '0' ? 'ZERO_FTE' : 'ALL_EXPLICIT', formula_version: 'explicit-tenants-per-fte-v1', explicit_total_fte: fte, tenants_per_fte: ratio, missing_inputs: missing, decimal_precision: 'DECIMAL128', population_verification: 'UNVERIFIED_Q30', staffing_basis_verification: 'UNVERIFIED_Q23', effective_tenants_per_fte: null, target_met: null, billing_applicable: false }, comparison_saved: false, formal_metrics_available: false, business_data_queried: false }
}
export function readOperationalResult(value: unknown, scope: OperationalContext, input: OperationalInput): OperationalResult { const correct = expected(readOperationalInput(input), scope.tenantId); if (!same(value, correct)) throw bad(); return copy(value) as OperationalResult }
export function createOperationalWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: OperationalState) => void) {
  let context: OperationalContext | null = null, generation = 0, controller: AbortController | null = null, disposed = false, state: OperationalState = { input: emptyOperationalInput(), result: null, busy: false, error: null }
  const snapshot = () => ({ ...state, input: copy(state.input), result: state.result ? copy(state.result) : null }), emit = () => notify(snapshot())
  function clear() { ++generation; controller?.abort(); controller = null; state = { ...state, result: null, busy: false, error: null } }
  return {
    state: snapshot,
    reset(scope: OperationalContext | null) { clear(); context = !disposed && scope?.role === 'padmin' && scope.canCompare === true && uuid.test(scope.tenantId) && uuid.test(scope.actorId) && Number.isSafeInteger(scope.permissionVersion) && scope.permissionVersion > 0 ? { ...scope } : null; state.input = emptyOperationalInput(); emit() },
    change(input: unknown) { if (disposed) return; clear(); state.input = copy(input); emit() },
    async calculate() { if (disposed || !context || state.busy) return false; clear(); const turn = generation, scope = { ...context }; controller = new AbortController(); state.busy = true; emit(); try { const input = readOperationalInput(state.input); const value = await client.request('/operations/explicit-comparisons', { method: 'POST', body: input, signal: controller.signal }); if (turn !== generation || disposed) return false; state.result = readOperationalResult(value, scope, input); return true } catch (error) { if (turn === generation && !disposed && !isCancelled(error)) state.error = error; return false } finally { if (turn === generation && !disposed) { state.busy = false; controller = null; emit() } } },
    dispose() { clear(); disposed = true; context = null; state.input = emptyOperationalInput(); emit() },
  }
}
