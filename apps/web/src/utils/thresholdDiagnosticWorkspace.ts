import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { parseStrictJsonValue } from './strictJsonValue'
export type ThresholdComparison = 'GT' | 'GE' | 'LT' | 'LE'
export interface ThresholdCondition { comparison: ThresholdComparison | null; threshold: string | null; unit: string | null }
export interface ThresholdInput { direction: 'HIGH_IS_BAD' | 'LOW_IS_BAD' | null; warning: ThresholdCondition | null; critical: ThresholdCondition | null; recovery: ThresholdCondition | null; trigger_hold_seconds: string | null; recovery_hold_seconds: string | null }
export interface ThresholdContext { tenantId: string; actorId: string; permissionVersion: number; role: string; canRead: boolean }
export type ThresholdStatus = 'CONSISTENT' | 'UNRESOLVED' | 'CONFLICT'
export interface ThresholdIssue { code: string; kind: 'UNRESOLVED' | 'CONFLICT' }
export interface ThresholdResult { tenant_id: string; source: 'EXPLICIT_INPUT'; scope: 'EXPLICIT_PREDICATE_DIAGNOSTIC_ONLY'; analysis_version: 'threshold-half-lines-v1'; inputs: ThresholdInput; status: ThresholdStatus; direction_consistent: boolean | null; critical_strict_subset_of_warning: boolean | null; recovery_overlaps_warning: boolean | null; recovery_overlaps_critical: boolean | null; durations_nonnegative: boolean | null; issues: ThresholdIssue[]; publishable: false; effective_rule: null; customer_confirmation: 'UNVERIFIED'; physical_range_check: 'NOT_RUN'; units_converted: false; monitoring_applied: false; time_series_evaluated: false; rule_saved: false; writes_performed: false; tenant_authorization: 'CURRENT_MEMBERSHIP_VERIFIED' }
export interface ThresholdState { text: string; result: ThresholdResult | null; busy: boolean; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy = <T>(value: T): T => structuredClone(value)
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const bad = () => new ApiError(0, 'INVALID_THRESHOLD_DIAGNOSTIC', '显式阈值输入或服务端诊断未通过核对；不补默认、不自动纠正或发布', '—')
function decimal(value: unknown): string | null { if (value === null) return null; if (typeof value !== 'string' || !/^-?(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,40})?$/.test(value) || /^-0(?:\.0+)?$/.test(value)) throw bad(); return value }
function condition(value: unknown): ThresholdCondition | null {
 if (value === null) return null
 if (!exact(value, ['comparison', 'threshold', 'unit']) || !(value.comparison === null || typeof value.comparison === 'string' && ['GT', 'GE', 'LT', 'LE'].includes(value.comparison))) throw bad()
 const unit = value.unit; if (!(unit === null || typeof unit === 'string' && Array.from(unit).length <= 32 && Boolean(unit.replace(/[\s\u001c-\u001f\u0085]+/gu, '')) && !/[\u0000-\u001f\u007f-\u009f]/.test(unit) && !Array.from(unit).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c)))) throw bad()
 return { comparison: value.comparison as ThresholdComparison | null, threshold: decimal(value.threshold), unit }
}
export function readThresholdInput(value: unknown): ThresholdInput { if (!exact(value, ['direction', 'warning', 'critical', 'recovery', 'trigger_hold_seconds', 'recovery_hold_seconds']) || !(value.direction === null || value.direction === 'HIGH_IS_BAD' || value.direction === 'LOW_IS_BAD')) throw bad(); return { direction: value.direction, warning: condition(value.warning), critical: condition(value.critical), recovery: condition(value.recovery), trigger_hold_seconds: decimal(value.trigger_hold_seconds), recovery_hold_seconds: decimal(value.recovery_hold_seconds) } }
export function parseThresholdText(text: string): ThresholdInput { try { return readThresholdInput(parseStrictJsonValue(text, { bytes: 16384, depth: 3, tokens: 256, stringLength: 128 })) } catch { throw bad() } }
interface Decimal { integer: bigint; scale: number }
function parts(value: string): Decimal { const [integer, fraction = ''] = value.split('.'); return { integer: BigInt(integer! + fraction), scale: fraction.length } }
function compare(a: string, b: string): number { const aa = parts(a), bb = parts(b), scale = Math.max(aa.scale, bb.scale), left = aa.integer * 10n ** BigInt(scale - aa.scale), right = bb.integer * 10n ** BigInt(scale - bb.scale); return left < right ? -1 : left > right ? 1 : 0 }
const upper = (c: ThresholdComparison) => c === 'GT' || c === 'GE'
const inclusive = (c: ThresholdComparison) => c === 'GE' || c === 'LE'
type Complete = { comparison: ThresholdComparison; threshold: string; unit: string }
const complete = (c: ThresholdCondition | null): c is Complete => Boolean(c && c.comparison !== null && c.threshold !== null && c.unit !== null)
function directions(c: ThresholdCondition | null, direction: ThresholdInput['direction'], recovery: boolean): boolean | null { if (!c?.comparison || direction === null) return null; const expected = direction === 'HIGH_IS_BAD'; return upper(c.comparison) === (recovery ? !expected : expected) }
function subset(inner: Complete, outer: Complete): boolean { if (upper(inner.comparison) !== upper(outer.comparison)) return false; const order = compare(inner.threshold, outer.threshold); return order === 0 ? !inclusive(inner.comparison) || inclusive(outer.comparison) : upper(inner.comparison) ? order > 0 : order < 0 }
function overlap(a: Complete, b: Complete): boolean { if (upper(a.comparison) === upper(b.comparison)) return true; const high = upper(a.comparison) ? a : b, low = upper(a.comparison) ? b : a, order = compare(high.threshold, low.threshold); return order < 0 || order === 0 && inclusive(high.comparison) && inclusive(low.comparison) }
function equal(a: unknown, b: unknown): boolean { if (a === b) return true; if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => equal(v, b[i])); if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const aa = a as Record<string, unknown>, bb = b as Record<string, unknown>; return Object.keys(aa).length === Object.keys(bb).length && Object.keys(aa).every(k => Object.hasOwn(bb, k) && equal(aa[k], bb[k])) } return false }
/** Build expected facts only to verify an actual server response; never use this as a local success fallback. */
export function readThresholdResult(raw: unknown, tenant: string, submitted: ThresholdInput): ThresholdResult {
 if (!uuid.test(tenant)) throw bad(); const input = readThresholdInput(submitted), issues: ThresholdIssue[] = [], issue = (code: string, kind: ThresholdIssue['kind']) => issues.push({ code, kind })
 const { warning: w, critical: c, recovery: r } = input
 if (input.direction === null) issue('DIRECTION_MISSING', 'UNRESOLVED')
 if (!complete(w)) issue('WARNING_CONDITION_INCOMPLETE', 'UNRESOLVED')
 if (!complete(c)) issue('CRITICAL_CONDITION_INCOMPLETE', 'UNRESOLVED')
 if (!complete(r)) issue('RECOVERY_CONDITION_INCOMPLETE', 'UNRESOLVED')
 if (new Set([w, c, r].flatMap(v => v?.unit !== null && v?.unit !== undefined ? [v.unit] : [])).size > 1) issue('UNIT_MISMATCH', 'CONFLICT')
 const wd = directions(w, input.direction, false), cd = directions(c, input.direction, false), rd = directions(r, input.direction, true)
 if (wd === false) issue('WARNING_DIRECTION_CONFLICT', 'CONFLICT')
 if (cd === false) issue('CRITICAL_DIRECTION_CONFLICT', 'CONFLICT')
 if (rd === false) issue('RECOVERY_DIRECTION_CONFLICT', 'CONFLICT')
 const direction = wd === null || cd === null || rd === null ? null : wd && cd && rd
 const strict = complete(c) && complete(w) && c.unit === w.unit ? subset(c, w) && !(c.comparison === w.comparison && compare(c.threshold, w.threshold) === 0) : null
 if (strict === false) issue('CRITICAL_NOT_STRICTER_THAN_WARNING', 'CONFLICT')
 const ow = complete(r) && complete(w) && r.unit === w.unit ? overlap(r, w) : null, oc = complete(r) && complete(c) && r.unit === c.unit ? overlap(r, c) : null
 if (ow === true) issue('RECOVERY_OVERLAPS_WARNING', 'CONFLICT')
 if (oc === true) issue('RECOVERY_OVERLAPS_CRITICAL', 'CONFLICT')
 if (input.trigger_hold_seconds === null) issue('TRIGGER_DURATION_MISSING', 'UNRESOLVED'); else if (compare(input.trigger_hold_seconds, '0') < 0) issue('NEGATIVE_TRIGGER_DURATION', 'CONFLICT')
 if (input.recovery_hold_seconds === null) issue('RECOVERY_DURATION_MISSING', 'UNRESOLVED'); else if (compare(input.recovery_hold_seconds, '0') < 0) issue('NEGATIVE_RECOVERY_DURATION', 'CONFLICT')
 const duration = input.trigger_hold_seconds === null || input.recovery_hold_seconds === null ? null : compare(input.trigger_hold_seconds, '0') >= 0 && compare(input.recovery_hold_seconds, '0') >= 0
 const expected: ThresholdResult = { tenant_id: tenant, source: 'EXPLICIT_INPUT', scope: 'EXPLICIT_PREDICATE_DIAGNOSTIC_ONLY', analysis_version: 'threshold-half-lines-v1', inputs: input, status: issues.some(i => i.kind === 'CONFLICT') ? 'CONFLICT' : issues.length ? 'UNRESOLVED' : 'CONSISTENT', direction_consistent: direction, critical_strict_subset_of_warning: strict, recovery_overlaps_warning: ow, recovery_overlaps_critical: oc, durations_nonnegative: duration, issues, publishable: false, effective_rule: null, customer_confirmation: 'UNVERIFIED', physical_range_check: 'NOT_RUN', units_converted: false, monitoring_applied: false, time_series_evaluated: false, rule_saved: false, writes_performed: false, tenant_authorization: 'CURRENT_MEMBERSHIP_VERIFIED' }
 if (!equal(raw, expected)) throw bad(); return copy(raw) as ThresholdResult
}
export const emptyThresholdState = (): ThresholdState => ({ text: '', result: null, busy: false, error: null })
export function createThresholdWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: ThresholdState) => void) {
 let scope: ThresholdContext | null = null, state = emptyThresholdState(), generation = 0, abort: AbortController | null = null, disposed = false
 const snapshot = () => ({ ...state, result: state.result ? copy(state.result) : null }), emit = () => notify(snapshot())
 function clear() { ++generation; abort?.abort(); abort = null; state = { ...state, result: null, busy: false, error: null } }
 function reset(context: ThresholdContext | null) { clear(); scope = !disposed && context && context.role === 'padmin' && context.canRead === true && uuid.test(context.tenantId) && uuid.test(context.actorId) && Number.isSafeInteger(context.permissionVersion) && context.permissionVersion > 0 ? { ...context } : null; state.text = ''; emit() }
 return { state: snapshot, reset, change(text: string) { if (disposed) return; clear(); state.text = text; emit() }, async analyze() { if (!scope || disposed || state.busy) return false; clear(); const turn = generation, context = { ...scope }, controller = new AbortController(); abort = controller; state.busy = true; emit(); try { const input = parseThresholdText(state.text), raw = await client.request<unknown>('/monitoring/threshold-diagnostics', { method: 'POST', body: input, signal: controller.signal }); if (turn !== generation || disposed || controller.signal.aborted) return false; state.result = readThresholdResult(raw, context.tenantId, input); return true } catch (error) { if (turn === generation && !disposed && !isCancelled(error)) state.error = error; return false } finally { if (turn === generation && !disposed) { state.busy = false; abort = null; emit() } } }, dispose() { disposed = true; reset(null) } }
}
