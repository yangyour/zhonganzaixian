import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'
export interface SlaReference { id: string; version: number }
export interface SlaRow { scenario_ref: string; priority: 'P1' | 'P2' | 'P3' | 'P4' | null; exclusion: 'NONE' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'UNKNOWN' | null; exclusion_approval: SlaReference | null; sla_snapshot: SlaReference | null; response_due_at: string | null; recovery_due_at: string | null; response_actual_at: string | null; recovery_actual_at: string | null }
export interface SlaInput { population_complete: boolean | null; tickets: SlaRow[] }
export interface SlaItem { input: SlaRow; applicable: boolean | null; scenario_outcome: 'MET' | 'NOT_MET' | 'UNRESOLVED' | 'NOT_APPLICABLE'; response_check: 'MET' | 'NOT_MET' | 'UNKNOWN'; recovery_check: 'MET' | 'NOT_MET' | 'UNKNOWN'; exclusion_applied_in_scenario: boolean; unapproved_exclusion_ignored: boolean; reasons: string[] }
export interface SlaResult { tenant_id: string; scope: 'EXPLICIT_SLA_COMPARISON_SCENARIO'; metric_version: 'contract-m5-19-comparison-v1'; source: 'EXPLICIT_INPUT_NOT_PROJECT_DATA'; status: 'CALCULATED' | 'UNRESOLVED' | 'NO_APPLICABLE'; reason: string | null; population_complete_declared: boolean | null; approval_validation_status: 'UNVERIFIED'; runtime_snapshot_validation: 'NOT_CHECKED'; effective: false; effective_achievement: null; compensation_amount: null; compensation_status: 'CONFIG_PENDING'; counts: Record<'input_total' | 'known_applicable' | 'applicability_unknown' | 'met' | 'not_met' | 'timing_unresolved' | 'not_applicable', number>; scenario_achievement: { numerator: number; denominator: number } | null; items: SlaItem[]; scenario_limit: 200 }
export interface SlaContext { tenantId: string; actorId: string; permissionVersion: number }
export interface SlaState { input: SlaInput; result: SlaResult | null; busy: boolean; error: unknown }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k)))
const bad = () => new ApiError(0, 'INVALID_SLA_SCENARIO', '显式SLA场景或回读不一致；未保留旧结果，也未写入业务', '—')
const rowKeys = ['scenario_ref', 'priority', 'exclusion', 'exclusion_approval', 'sla_snapshot', 'response_due_at', 'recovery_due_at', 'response_actual_at', 'recovery_actual_at']
const label = (v: unknown): v is string => typeof v === 'string' && Boolean(v.trim()) && Array.from(v).length <= 160 && !/[\u0000-\u001f\u007f-\u009f]/.test(v) && !Array.from(v).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))
const reference = (v: unknown): SlaReference | null => { if (v === null) return null; if (!exact(v, ['id', 'version']) || !label(v.id) || !Number.isSafeInteger(v.version) || Number(v.version) < 1) throw bad(); return { id: v.id, version: Number(v.version) } }
const time = (v: unknown): string | null => { if (v === null) return null; if (typeof v !== 'string') throw bad(); try { return new Date(worklogInstant(v)).toISOString().replace('.000Z', 'Z') } catch { throw bad() } }
export function readSlaInput(value: unknown): SlaInput {
  if (!exact(value, ['population_complete', 'tickets']) || value.population_complete !== null && typeof value.population_complete !== 'boolean' || !Array.isArray(value.tickets) || value.tickets.length > 200) throw bad()
  const ids = new Set<string>(); const tickets = value.tickets.map(raw => {
    if (!exact(raw, rowKeys) || !label(raw.scenario_ref) || ids.has(raw.scenario_ref) || ![null, 'P1', 'P2', 'P3', 'P4'].includes(raw.priority as string | null) || ![null, 'NONE', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'UNKNOWN'].includes(raw.exclusion as string | null)) throw bad()
    ids.add(raw.scenario_ref); return { scenario_ref: raw.scenario_ref, priority: raw.priority, exclusion: raw.exclusion, exclusion_approval: reference(raw.exclusion_approval), sla_snapshot: reference(raw.sla_snapshot), response_due_at: time(raw.response_due_at), recovery_due_at: time(raw.recovery_due_at), response_actual_at: time(raw.response_actual_at), recovery_actual_at: time(raw.recovery_actual_at) } as SlaRow
  }); return { population_complete: value.population_complete as boolean | null, tickets }
}
/** Independent response invariants, not a substitute for a real API response. */
function expectedItem(row: SlaRow): SlaItem {
  const result: SlaItem = { input: row, applicable: null, scenario_outcome: 'UNRESOLVED', response_check: 'UNKNOWN', recovery_check: 'UNKNOWN', exclusion_applied_in_scenario: false, unapproved_exclusion_ignored: false, reasons: [] }
  if (row.priority === null) { result.reasons = ['PRIORITY_UNKNOWN']; return result }
  if (row.priority === 'P4') { result.applicable = false; result.scenario_outcome = 'NOT_APPLICABLE'; result.reasons = ['PRIORITY_OUTSIDE_SLA']; return result }
  if (row.exclusion === null || row.exclusion === 'UNKNOWN') { result.reasons = ['EXCLUSION_UNKNOWN']; return result }
  if (row.exclusion === 'APPROVED') { if (!row.exclusion_approval) { result.reasons = ['APPROVAL_EVIDENCE_MISSING']; return result } result.applicable = false; result.scenario_outcome = 'NOT_APPLICABLE'; result.exclusion_applied_in_scenario = true; result.reasons = ['APPROVED_EXCLUSION']; return result }
  result.applicable = true; result.unapproved_exclusion_ignored = row.exclusion === 'PENDING_APPROVAL' || row.exclusion === 'REJECTED'
  if (!row.sla_snapshot) { result.reasons = ['SLA_SNAPSHOT_MISSING']; return result }
  const check = (due: string | null, actual: string | null, prefix: string): SlaItem['response_check'] => { if (due === null) result.reasons.push(prefix + '_DUE_MISSING'); if (actual === null) result.reasons.push(prefix + '_ACTUAL_MISSING'); if (due === null || actual === null) return 'UNKNOWN'; if (Date.parse(actual) > Date.parse(due)) { result.reasons.push(prefix + '_LATE'); return 'NOT_MET' } return 'MET' }
  result.response_check = check(row.response_due_at, row.response_actual_at, 'RESPONSE'); result.recovery_check = check(row.recovery_due_at, row.recovery_actual_at, 'RECOVERY')
  result.scenario_outcome = result.response_check === 'NOT_MET' || result.recovery_check === 'NOT_MET' ? 'NOT_MET' : result.response_check === 'MET' && result.recovery_check === 'MET' ? 'MET' : 'UNRESOLVED'; return result
}
export function readSlaResult(value: unknown, scope: SlaContext, input: SlaInput): SlaResult {
  const keys = ['tenant_id', 'scope', 'metric_version', 'source', 'status', 'reason', 'population_complete_declared', 'approval_validation_status', 'runtime_snapshot_validation', 'effective', 'effective_achievement', 'compensation_amount', 'compensation_status', 'counts', 'scenario_achievement', 'items', 'scenario_limit']
  if (!exact(value, keys) || value.tenant_id !== scope.tenantId || value.scope !== 'EXPLICIT_SLA_COMPARISON_SCENARIO' || value.metric_version !== 'contract-m5-19-comparison-v1' || value.source !== 'EXPLICIT_INPUT_NOT_PROJECT_DATA' || value.population_complete_declared !== input.population_complete || value.approval_validation_status !== 'UNVERIFIED' || value.runtime_snapshot_validation !== 'NOT_CHECKED' || value.effective !== false || value.effective_achievement !== null || value.compensation_amount !== null || value.compensation_status !== 'CONFIG_PENDING' || value.scenario_limit !== 200 || !Array.isArray(value.items) || value.items.length !== input.tickets.length) throw bad()
  const counts = { input_total: input.tickets.length, known_applicable: 0, applicability_unknown: 0, met: 0, not_met: 0, timing_unresolved: 0, not_applicable: 0 }
  const items = value.items.map((raw, i) => {
    const expected = expectedItem(input.tickets[i]!)
    if (!exact(raw, Object.keys(expected))) throw bad()
    const normalized = readSlaInput({ population_complete: input.population_complete, tickets: [raw.input] }).tickets[0]!
    if (JSON.stringify(normalized) !== JSON.stringify(expected.input)) throw bad()
    for (const key of Object.keys(expected).filter(k => k !== 'input') as (keyof SlaItem)[]) if (JSON.stringify(raw[key]) !== JSON.stringify(expected[key])) throw bad()
    if (expected.applicable === null) counts.applicability_unknown++; else if (!expected.applicable) counts.not_applicable++; else { counts.known_applicable++; if (expected.scenario_outcome === 'MET') counts.met++; else if (expected.scenario_outcome === 'NOT_MET') counts.not_met++; else counts.timing_unresolved++ } return expected
  })
  if (!exact(value.counts, Object.keys(counts))) throw bad(); for (const key of Object.keys(counts) as (keyof typeof counts)[]) if (value.counts[key] !== counts[key]) throw bad()
  const reason = input.population_complete !== true ? 'POPULATION_NOT_COMPLETE' : counts.applicability_unknown ? 'APPLICABILITY_UNKNOWN' : counts.timing_unresolved ? 'TIMING_UNRESOLVED' : !counts.known_applicable ? 'NO_APPLICABLE_TICKETS' : null
  const status = reason === null ? 'CALCULATED' : reason === 'NO_APPLICABLE_TICKETS' ? 'NO_APPLICABLE' : 'UNRESOLVED'
  if (value.reason !== reason || value.status !== status) throw bad()
  if (status === 'CALCULATED') { if (!exact(value.scenario_achievement, ['numerator', 'denominator']) || value.scenario_achievement.numerator !== counts.met || value.scenario_achievement.denominator !== counts.known_applicable) throw bad() } else if (value.scenario_achievement !== null) throw bad()
  return { ...copy(value), items } as unknown as SlaResult
}
export function createSlaScenarioWorkspace(client: Pick<ApiClient, 'request'>, notify: (state: SlaState) => void) {
  let context: SlaContext | null = null, generation = 0, controller: AbortController | null = null, disposed = false
  let state: SlaState = { input: { population_complete: null, tickets: [] }, result: null, busy: false, error: null }
  const emit = () => notify({ ...state, input: copy(state.input), result: state.result ? copy(state.result) : null })
  const cancel = () => { generation++; controller?.abort(); controller = null; state.busy = false; state.result = null; state.error = null }
  return {
    state: () => ({ ...state, input: copy(state.input), result: state.result ? copy(state.result) : null }),
    reset(scope: SlaContext | null) { cancel(); context = !disposed && scope && Number.isSafeInteger(scope.permissionVersion) && scope.permissionVersion > 0 && scope.tenantId && scope.actorId ? { ...scope } : null; state.input = { population_complete: null, tickets: [] }; emit() },
    change(input: SlaInput) { if (disposed) return; cancel(); state.input = copy(input); emit() },
    async calculate() {
      if (disposed || !context || state.busy) return false
      cancel(); const current = generation, scope = { ...context }; controller = new AbortController(); state.busy = true; emit()
      try { const input = readSlaInput(state.input); const result = await client.request('/sla/compliance-scenarios', { method: 'POST', body: input, signal: controller.signal }); if (current !== generation || disposed) return false; state.result = readSlaResult(result, scope, input); return true }
      catch (error) { if (current === generation && !disposed && !isCancelled(error)) state.error = error; return false }
      finally { if (current === generation && !disposed) { state.busy = false; controller = null; emit() } }
    },
    dispose() { cancel(); disposed = true; context = null; state.input = { population_complete: null, tickets: [] }; emit() },
  }
}
