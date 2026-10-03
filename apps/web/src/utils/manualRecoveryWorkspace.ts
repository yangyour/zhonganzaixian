import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readManualIncident, type ManualIncidentContext, type ManualIncidentEntry } from './manualIncidentReader'
import { internalNoteHash, validateInternalNoteText } from './internalNoteWorkspace'
import { worklogInstant } from './worklogWorkspace'
export interface HumanRecoveryConfirmation { id: string; tenant_id: string; incident_id: string; confirmed_by: string; note: string; note_sha256: string; reported_recovered_at: string | null; recorded_at: string; manual_recovery_status: 'HUMAN_CONFIRMED'; verification_status: 'SELF_REPORTED'; record_source: 'MANUAL_RECOVERY_CONFIRMATION'; monitor_state: null; disposition_state: null; monitoring_recovery_applicable: false; ticket_state_changed: false; sla_changed: false; immutable: true }
export interface ManualRecoveryCurrent { incident_id: string; tenant_id: string; version: 0 | 1; manual_recovery_status: 'NOT_CONFIRMED' | 'HUMAN_CONFIRMED'; confirmation: HumanRecoveryConfirmation | null; source: 'postgresql'; scope: 'CURRENT_TENANT_INTERNAL_HUMAN_CONFIRMATION' }
interface Attempt { parentId: string; payload: string; key: string; state: 'uncertain' | 'accepted' | 'rejected' }
export interface ManualRecoveryState { parent: ManualIncidentEntry | null; current: ManualRecoveryCurrent | null; receipt: ManualRecoveryCurrent | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const bad = () => new ApiError(200, 'INVALID_MANUAL_RECOVERY_RESPONSE', '人工恢复范围、版本、原文摘要或独立状态不一致，未确认结果', '—')
const date = (value: unknown) => { if (typeof value !== 'string' || !value.endsWith('Z')) throw bad(); try { return worklogInstant(value) } catch { throw bad() } }
export async function readManualRecovery(value: unknown, context: ManualIncidentContext, parentId: string): Promise<ManualRecoveryCurrent> {
  if (!uuid.test(parentId) || !uuid.test(context.tenantId)) throw bad()
  if (!exact(value, ['incident_id', 'tenant_id', 'version', 'manual_recovery_status', 'confirmation', 'source', 'scope']) || value.incident_id !== parentId || value.tenant_id !== context.tenantId || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_INTERNAL_HUMAN_CONFIRMATION') throw bad()
  if (value.version === 0) { if (value.manual_recovery_status !== 'NOT_CONFIRMED' || value.confirmation !== null) throw bad(); return copy(value) as unknown as ManualRecoveryCurrent }
  if (value.version !== 1 || value.manual_recovery_status !== 'HUMAN_CONFIRMED') throw bad()
  const evidence = value.confirmation
  if (!exact(evidence, ['id', 'tenant_id', 'incident_id', 'confirmed_by', 'note', 'note_sha256', 'reported_recovered_at', 'recorded_at', 'manual_recovery_status', 'verification_status', 'record_source', 'monitor_state', 'disposition_state', 'monitoring_recovery_applicable', 'ticket_state_changed', 'sla_changed', 'immutable']) || evidence.tenant_id !== context.tenantId || evidence.incident_id !== parentId || typeof evidence.id !== 'string' || !uuid.test(evidence.id) || typeof evidence.confirmed_by !== 'string' || !uuid.test(evidence.confirmed_by) || evidence.manual_recovery_status !== 'HUMAN_CONFIRMED' || evidence.verification_status !== 'SELF_REPORTED' || evidence.record_source !== 'MANUAL_RECOVERY_CONFIRMATION' || evidence.monitor_state !== null || evidence.disposition_state !== null || evidence.monitoring_recovery_applicable !== false || evidence.ticket_state_changed !== false || evidence.sla_changed !== false || evidence.immutable !== true || typeof evidence.note_sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(evidence.note_sha256)) throw bad()
  try { validateInternalNoteText(evidence.note); if (await internalNoteHash(evidence.note) !== evidence.note_sha256) throw bad() } catch { throw bad() }
  const recorded = date(evidence.recorded_at); if (evidence.reported_recovered_at !== null && date(evidence.reported_recovered_at) > recorded) throw bad()
  return copy(value) as unknown as ManualRecoveryCurrent
}
function sameCurrent(a: ManualRecoveryCurrent, b: ManualRecoveryCurrent) {
  if (a.version !== b.version || a.incident_id !== b.incident_id || a.tenant_id !== b.tenant_id) return false
  if (!a.confirmation || !b.confirmation) return a.confirmation === b.confirmation
  const first = a.confirmation, second = b.confirmation
  return Object.keys(first).every(key => key === 'recorded_at' ? date(first.recorded_at) === date(second.recorded_at) : key === 'reported_recovered_at' ? (first.reported_recovered_at === null ? second.reported_recovered_at === null : second.reported_recovered_at !== null && date(first.reported_recovered_at) === date(second.reported_recovered_at)) : first[key as keyof HumanRecoveryConfirmation] === second[key as keyof HumanRecoveryConfirmation])
}
export const emptyManualRecoveryState = (): ManualRecoveryState => ({ parent: null, current: null, receipt: null, attempt: null, busy: null, error: null })
export function createManualRecoveryWorkspace(client: Pick<ApiClient, 'request'>, changed: (value: ManualRecoveryState) => void, newKey: () => string = () => crypto.randomUUID()) {
  let context: ManualIncidentContext | null = null, state = emptyManualRecoveryState(), epoch = 0, abort: AbortController | undefined, lastKnown: ManualRecoveryCurrent | null = null
  const emit = () => changed(detachedWorkspaceState(state)), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: ManualIncidentContext | null) { abort?.abort(); ++epoch; const valid = next && uuid.test(next.actorId) && uuid.test(next.tenantId) && Number.isSafeInteger(next.permissionVersion) && next.permissionVersion > 0; context = valid ? { ...next! } : null; state = emptyManualRecoveryState(); lastKnown = null; if (next && !valid) state.error = bad(); emit() }
  function begin(parent: ManualIncidentEntry) { if (!context || state.busy || unresolved()) return false; const value = readManualIncident(parent, context); if (state.parent?.id !== value.id) lastKnown = null; state = { ...emptyManualRecoveryState(), parent: value }; emit(); return true }
  async function refresh() {
    if (!context || !state.parent || state.busy) return false
    const scope = { ...context }, parent = state.parent.id, known = state.receipt || lastKnown, turn = ++epoch, controller = new AbortController(); abort?.abort(); abort = controller; state = { ...state, busy: 'read', current: null, error: null }; emit()
    try { const value = await readManualRecovery(await client.request<unknown>('/manual-incidents/' + parent + '/recovery', { signal: controller.signal }), scope, parent); if (turn !== epoch || controller.signal.aborted) return false; if (known?.version === 1 && !sameCurrent(known, value)) throw bad(); lastKnown = value; state = { ...state, current: value }; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, current: null, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!context || state.busy || state.parent?.id !== attempt.parentId) return false
    const scope = { ...context }, turn = ++epoch, controller = new AbortController(); abort?.abort(); abort = controller; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const payload = JSON.parse(attempt.payload) as { expected_version: 0; note: string; reported_recovered_at: string | null }
      const raw = await client.request<unknown>('/manual-incidents/' + attempt.parentId + '/recovery', { method: 'POST', body: payload, idempotencyKey: attempt.key, signal: controller.signal }); if (turn !== epoch || controller.signal.aborted) return false
      const value = await readManualRecovery(raw, scope, attempt.parentId); if (turn !== epoch || controller.signal.aborted) return false
      const evidence = value.confirmation
      if (value.version !== 1 || !evidence || evidence.confirmed_by !== scope.actorId || evidence.note !== payload.note || (payload.reported_recovered_at === null ? evidence.reported_recovered_at !== null : evidence.reported_recovered_at === null || date(evidence.reported_recovered_at) !== worklogInstant(payload.reported_recovered_at))) throw bad()
      lastKnown = value; state = { ...state, receipt: value, current: value, attempt: { ...attempt, state: 'accepted' }, busy: null }; emit(); await refresh(); return true
    } catch (error) { if (turn !== epoch) return false; const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_'); state = { ...state, current: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  function submit(note: string, reportedRecoveredAt: string | null) {
    if (!context || !state.parent || !state.current || state.busy || state.attempt) return Promise.resolve(false)
    if (state.current.version !== 0) { state = { ...state, error: new ApiError(409, 'MANUAL_RECOVERY_ALREADY_CONFIRMED', '已有独立人工确认，不能创建第二份或覆盖原证据', '—') }; emit(); return Promise.resolve(false) }
    validateInternalNoteText(note); const time = reportedRecoveredAt === null ? null : new Date(worklogInstant(reportedRecoveredAt)).toISOString()
    return send({ parentId: state.parent.id, payload: JSON.stringify({ expected_version: 0, note, reported_recovered_at: time }), key: newKey(), state: 'uncertain' })
  }
  emit(); return { reset, begin, refresh, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, state: () => detachedWorkspaceState(state), dispose: () => reset(null) }
}
