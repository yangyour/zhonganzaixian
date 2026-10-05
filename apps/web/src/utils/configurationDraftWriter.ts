import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import type { Configuration } from '../api/types'
export interface ConfigurationDraftContext { tenantId: string; actorId: string; permissionVersion: number }
export type RegistryDraft = Configuration & { mode: 'remote_test'; source_registry_status: 'design_registry_not_runtime_config'; [key: string]: unknown }
interface Attempt { path: string; key: string; payload: string; baseline: RegistryDraft; state: 'uncertain' | 'accepted' | 'rejected' }
export interface ConfigurationDraftState { baseline: RegistryDraft | null; lastRead: RegistryDraft | null; receipt: RegistryDraft | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const version = (v: unknown) => Number.isSafeInteger(v) && Number(v) >= 1
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const canonical = (v: unknown): string => JSON.stringify(v && typeof v === 'object' ? Array.isArray(v) ? v.map(x => JSON.parse(canonical(x))) : Object.fromEntries(Object.entries(v).sort(([a], [b]) => a.localeCompare(b)).map(([k, x]) => [k, JSON.parse(canonical(x))])) : v)
const bad = () => new ApiError(200, 'INVALID_CONFIGURATION_DRAFT_RESPONSE', '设计登记草稿回执、原配置键或版本不匹配，结果未确认', '—')
const invalid = () => new Error('旧设计登记草稿仅允许明确建议值及已知编辑基准，不能改固定约束或用缺值补默认')
export function readRegistryDraft(v: unknown): RegistryDraft {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw bad(); const value = v as Record<string, unknown>
  if (typeof value.key !== 'string' || !/^[A-Za-z0-9_.:-]{1,160}$/.test(value.key) || !version(value.version) || value.mode !== 'remote_test' || value.source_registry_status !== 'design_registry_not_runtime_config' || value.effective_value !== null || !Object.hasOwn(value, 'value') || !Object.hasOwn(value, 'registered_value') || !Object.hasOwn(value, 'draft_value') || canonical(value.value) !== canonical(value.registered_value) || typeof value.type !== 'string' || typeof value.lifecycle_status !== 'string' || !['pending', 'contract_fixed'].includes(String(value.decision_status)) || value.decision_status === 'pending' && value.value !== null || !['pending_customer_decision', 'configurable_default', 'fixed_constraint'].includes(String(value.contract_value_kind))) throw bad()
  return copy(value) as RegistryDraft
}
function unchangedMetadata(v: RegistryDraft) { return Object.fromEntries(Object.entries(v).filter(([key]) => !['version', 'draft_value', 'lifecycle_status'].includes(key))) }
export const emptyConfigurationDraftState = (): ConfigurationDraftState => ({ baseline: null, lastRead: null, receipt: null, attempt: null, busy: null, error: null })
export function createConfigurationDraftWriter(client: Pick<ApiClient, 'request'>, changed: (state: ConfigurationDraftState) => void, newKey: () => string = () => createRequestId()) {
  let scope: ConfigurationDraftContext | null = null, state = emptyConfigurationDraftState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: ConfigurationDraftContext | null) { controller?.abort(); ++epoch; const valid = next && uuid.test(next.actorId) && uuid.test(next.tenantId) && version(next.permissionVersion); scope = valid ? { ...next! } : null; state = emptyConfigurationDraftState(); if (next && !valid) state.error = invalid(); emit() }
  function begin(value: unknown) { if (!scope || state.busy || unresolved()) return false; const baseline = readRegistryDraft(value); if (baseline.contract_value_kind === 'fixed_constraint') throw invalid(); state = { ...emptyConfigurationDraftState(), baseline, lastRead: baseline }; emit(); return true }
  async function refresh() {
    const known = state.receipt || state.baseline; if (!scope || !known || state.busy === 'write') return false
    controller?.abort(); const turn = ++epoch, abort = new AbortController(); controller = abort; state = { ...state, busy: 'read', lastRead: null, error: null }; emit()
    try { const raw = await client.request<unknown>('/configurations', { signal: abort.signal }); if (turn !== epoch || abort.signal.aborted) return false; const page = raw as Record<string, unknown>; if (!page || !Array.isArray(page.items) || page.source !== 'postgresql' || page.registry_status !== 'design_registry_not_runtime_config' || page.runtime_activation !== false) throw bad(); const matches = page.items.filter(x => x && typeof x === 'object' && x.key === known.key); if (matches.length !== 1) throw bad(); const value = readRegistryDraft(matches[0]); if (value.version < known.version || canonical(unchangedMetadata(value)) !== canonical(unchangedMetadata(known)) || value.version === known.version && canonical(value) !== canonical(known)) throw bad(); state = { ...state, lastRead: value }; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error, lastRead: null }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!scope || state.busy) return false
    controller?.abort(); const turn = ++epoch, abort = new AbortController(); controller = abort; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try { const body = JSON.parse(attempt.payload) as { proposed_value: unknown; expected_version: number }; const raw = await client.request<unknown>(attempt.path, { method: 'POST', body, idempotencyKey: attempt.key, signal: abort.signal }); if (turn !== epoch || abort.signal.aborted) return false; const value = readRegistryDraft(raw); if (value.key !== attempt.baseline.key || value.version !== attempt.baseline.version + 1 || value.lifecycle_status !== 'DRAFT' || canonical(value.draft_value) !== canonical(body.proposed_value) || canonical(unchangedMetadata(value)) !== canonical(unchangedMetadata(attempt.baseline))) throw bad(); state = { ...state, receipt: value, attempt: { ...attempt, state: 'accepted' }, busy: null }; emit(); await refresh(); return true }
    catch (error) { if (turn !== epoch) return false; const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_'); state = { ...state, lastRead: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(proposed: unknown) { if (!scope || !state.baseline || state.busy || state.attempt) return false; if (proposed === null || proposed === undefined || state.baseline.version >= Number.MAX_SAFE_INTEGER) throw invalid(); if (state.lastRead && state.lastRead.version !== state.baseline.version) { state = { ...state, error: new ApiError(409, 'VERSION_CONFLICT', '已知版本已变化，请明确重新准备编辑', '—') }; emit(); return false } const payload = JSON.stringify({ proposed_value: proposed, expected_version: state.baseline.version }, (_key, value) => { if (value === undefined || typeof value === 'number' && !Number.isFinite(value) || ['bigint', 'function', 'symbol'].includes(typeof value)) throw invalid(); return value }); return send({ path: '/configurations/' + encodeURIComponent(state.baseline.key) + '/draft', key: newKey(), payload, baseline: copy(state.baseline), state: 'uncertain' }) }
  emit(); return { reset, begin, refresh, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, state: () => detachedWorkspaceState(state), dispose: () => reset(null) }
}
