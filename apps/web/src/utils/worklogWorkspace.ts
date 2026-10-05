import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'

export type WorklogKind = 'REMOTE' | 'ONSITE' | 'INSPECTION' | 'COMMUTE'
export interface WorklogInput { kind: WorklogKind; started_at: string; ended_at: string; note: string | null }
interface WorklogFacts extends WorklogInput { elapsed_millis: number; record_source: 'MANUAL_INTERVAL'; source: 'postgresql'; verification_status: 'SELF_REPORTED'; financial_status: 'CONFIG_PENDING'; default_minutes: null; labor_cost: null; transport_cost: null; gross_margin: null }
export interface WorklogEntry extends WorklogFacts { id: string; tenant_id: string; ticket_id: string; owner_id: string; version: number; created_at: string; updated_at: string }
export interface WorklogRevision extends WorklogFacts { version: number; action: 'CREATE' | 'UPDATE'; actor_id: string; recorded_at: string }
export interface WorklogContext { tenantId: string; ticketId: string; actorId: string; permissionVersion: number; canWrite: boolean }
interface WorklogPage { items: WorklogEntry[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; source: 'postgresql'; scope: 'OWN'; page_size_limit: 100 }
interface HistoryPage { items: WorklogRevision[]; has_more: boolean; next_before_version: number | null; source: 'postgresql'; scope: 'OWN'; page_size_limit: 100 }
interface Attempt { path: string; method: 'POST' | 'PATCH'; key: string; payload: string; baseline: WorklogEntry | null; state: 'uncertain' | 'accepted' | 'rejected' }
export interface WorklogState { page: WorklogPage | null; parentVersion: number | null; selected: WorklogEntry | null; history: HistoryPage | null; receipt: WorklogEntry | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown; paged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const kinds = ['REMOTE', 'ONSITE', 'INSPECTION', 'COMMUTE']
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const object = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => object(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
const invalid = () => new ApiError(200, 'INVALID_WORKLOG_RESPONSE', '工时范围、版本或实填事实回读异常，当前显示已清除', '—')
const inputInvalid = () => new Error('请填写明确类型、含时区及秒的有效起止时间（最多毫秒），结束不能早于开始；备注为空则保存null，最多2000个字符')
const safeVersion = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 1
export function worklogInstant(value: unknown): number {
  if (typeof value !== 'string') throw inputInvalid()
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?(Z|[+-]\d{2}:\d{2})$/.exec(value)
  if (!match) throw inputInvalid()
  const [, year, month, day, hour, minute, second, fraction, zone] = match
  const wall = `${year}-${month}-${day}T${hour}:${minute}:${second}.${(fraction || '').padEnd(3, '0')}Z`
  const wallMillis = Date.parse(wall)
  if (!Number.isFinite(wallMillis) || new Date(wallMillis).toISOString() !== wall || Number(hour) > 23 || Number(minute) > 59 || Number(second) > 59) throw inputInvalid()
  if (zone !== 'Z') { const h = Number(zone!.slice(1, 3)), m = Number(zone!.slice(4)); if (h > 18 || m > 59 || h === 18 && m !== 0) throw inputInvalid() }
  const millis = Date.parse(value)
  if (!Number.isFinite(millis) || millis < -62135596800000 || millis > 253402300799999) throw inputInvalid()
  return millis
}
function noteValid(value: unknown) { return value === null || typeof value === 'string' && Boolean(value.trim()) && Array.from(value).length <= 2000 && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(value) && !Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c)) }
export function validateWorklogInput(value: unknown): asserts value is WorklogInput {
  if (!exact(value, ['kind', 'started_at', 'ended_at', 'note']) || typeof value.kind !== 'string' || !kinds.includes(value.kind) || !noteValid(value.note) || worklogInstant(value.ended_at) < worklogInstant(value.started_at)) throw inputInvalid()
}
const factsKeys = ['kind', 'started_at', 'ended_at', 'note', 'elapsed_millis', 'record_source', 'source', 'verification_status', 'financial_status', 'default_minutes', 'labor_cost', 'transport_cost', 'gross_margin']
function facts(value: Record<string, unknown>) {
  validateWorklogInput({ kind: value.kind, started_at: value.started_at, ended_at: value.ended_at, note: value.note })
  if (!String(value.started_at).endsWith('Z') || !String(value.ended_at).endsWith('Z') || value.elapsed_millis !== worklogInstant(value.ended_at) - worklogInstant(value.started_at) || value.record_source !== 'MANUAL_INTERVAL' || value.source !== 'postgresql' || value.verification_status !== 'SELF_REPORTED' || value.financial_status !== 'CONFIG_PENDING' || ['default_minutes', 'labor_cost', 'transport_cost', 'gross_margin'].some(key => value[key] !== null)) throw invalid()
}
function timestamp(value: unknown) { return typeof value === 'string' && value.endsWith('Z') && Number.isFinite(worklogInstant(value)) }
export function readWorklog(value: unknown, scope: WorklogContext): WorklogEntry {
  try {
    if (!exact(value, ['id', 'tenant_id', 'ticket_id', 'owner_id', 'version', 'created_at', 'updated_at', ...factsKeys]) || typeof value.id !== 'string' || !uuid.test(value.id) || value.tenant_id !== scope.tenantId || value.ticket_id !== scope.ticketId || value.owner_id !== scope.actorId || !safeVersion(value.version) || !timestamp(value.created_at) || !timestamp(value.updated_at)) throw invalid()
    facts(value); return copy(value) as unknown as WorklogEntry
  } catch { throw invalid() }
}
export function readWorklogPage(value: unknown, scope: WorklogContext): WorklogPage {
  if (!exact(value, ['items', 'has_more', 'next_before_created_at', 'next_before_id', 'source', 'scope', 'page_size_limit']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'OWN' || value.page_size_limit !== 100) throw invalid()
  const items = value.items.map(item => readWorklog(item, scope))
  if (new Set(items.map(item => item.id)).size !== items.length) throw invalid()
  for (let i = 1; i < items.length; i++) { const prev = items[i - 1]!, next = items[i]!; if (worklogInstant(prev.created_at) < worklogInstant(next.created_at) || worklogInstant(prev.created_at) === worklogInstant(next.created_at) && prev.id <= next.id) throw invalid() }
  const tail = items.at(-1)
  if (value.has_more ? items.length !== 15 || value.next_before_created_at !== tail?.created_at || value.next_before_id !== tail?.id : value.next_before_created_at !== null || value.next_before_id !== null) throw invalid()
  return copy({ ...value, items }) as unknown as WorklogPage
}
export function readWorklogHistory(value: unknown, scope: WorklogContext, selected: WorklogEntry, before: number | null): HistoryPage {
  if (!exact(value, ['items', 'has_more', 'next_before_version', 'source', 'scope', 'page_size_limit']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'OWN' || value.page_size_limit !== 100) throw invalid()
  let prior = before ?? selected.version + 1
  for (const item of value.items) {
    if (!exact(item, ['version', 'action', 'actor_id', 'recorded_at', ...factsKeys]) || !safeVersion(item.version) || item.version !== prior - 1 || item.actor_id !== scope.actorId || item.action !== (item.version === 1 ? 'CREATE' : 'UPDATE') || !timestamp(item.recorded_at)) throw invalid()
    facts(item); prior = item.version
    if (item.version === selected.version && ['kind', 'started_at', 'ended_at', 'note', 'elapsed_millis'].some(key => item[key] !== selected[key as keyof WorklogEntry])) throw invalid()
  }
  if (!value.items.length || (value.has_more ? value.items.length !== 15 || prior <= 1 || value.next_before_version !== prior : prior !== 1 || value.next_before_version !== null)) throw invalid()
  return copy(value) as unknown as HistoryPage
}
export const emptyWorklogState = (): WorklogState => ({ page: null, parentVersion: null, selected: null, history: null, receipt: null, attempt: null, busy: null, error: null, paged: false })
export function createWorklogWorkspace(client: Pick<ApiClient, 'request'>, changed: (state: WorklogState) => void, newKey: () => string = () => createRequestId()) {
  let scope: WorklogContext | null = null, state = emptyWorklogState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), path = () => `/tickets/${scope!.ticketId}/worklogs`
  const unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: WorklogContext | null) { controller?.abort(); ++epoch; const valid = next && [next.tenantId, next.ticketId, next.actorId].every(id => uuid.test(id)) && safeVersion(next.permissionVersion) && typeof next.canWrite === 'boolean'; scope = valid ? { ...next! } : null; state = emptyWorklogState(); if (next && !valid) state.error = invalid(); emit() }
  async function refresh(next = false) {
    if (!scope || state.busy === 'write' || next && (!state.page?.has_more || state.busy)) return false
    const page = state.page, current = ++epoch, context = { ...scope }; controller?.abort(); const abort = new AbortController(); controller = abort
    let query = '?page_size=15'; if (next) query += `&before_created_at=${encodeURIComponent(page!.next_before_created_at!)}&before_id=${page!.next_before_id}`
    state = { ...state, page: null, selected: null, history: null, parentVersion: null, busy: 'read', error: null, paged: next }; emit()
    try {
      const [parent, raw] = await Promise.all([client.request<unknown>(`/tickets/${context.ticketId}`, { signal: abort.signal }), client.request<unknown>(path() + query, { signal: abort.signal })])
      if (current !== epoch || abort.signal.aborted) return false
      if (!object(parent) || parent.id !== context.ticketId || parent.tenant_id !== context.tenantId || !safeVersion(parent.version)) throw invalid()
      const result = readWorklogPage(raw, context)
      if (next && result.items.some(item => worklogInstant(item.created_at) > worklogInstant(page!.next_before_created_at) || worklogInstant(item.created_at) === worklogInstant(page!.next_before_created_at) && item.id >= page!.next_before_id!)) throw invalid()
      if (state.receipt) {
        const known = result.items.find(item => item.id === state.receipt!.id)
        if (!next && !result.has_more && !known || known && (known.version < state.receipt.version || known.version === state.receipt.version && Object.keys(known).some(key => known[key as keyof WorklogEntry] !== state.receipt![key as keyof WorklogEntry]))) throw invalid()
      }
      state = { ...state, parentVersion: parent.version, page: result }; return true
    } catch (error) { if (current === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (current === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function show(id: string, moreHistory = false) {
    if (!scope || state.busy || !uuid.test(id) || moreHistory && (!state.history?.has_more || state.selected?.id !== id)) return false
    const context = { ...scope }, current = ++epoch, previous = state.selected, before = moreHistory ? state.history!.next_before_version : null; controller?.abort(); const abort = new AbortController(); controller = abort
    state = { ...state, selected: null, history: null, busy: 'read', error: null }; emit()
    try {
      const selected = readWorklog(await client.request<unknown>(path() + '/' + id, { signal: abort.signal }), context)
      if (current !== epoch || abort.signal.aborted) return false
      if (selected.id !== id || previous?.id === id && selected.version < previous.version || state.receipt?.id === id && selected.version < state.receipt.version) throw invalid()
      // If another editor changed the latest record, restart history rather than mixing old boundaries.
      const boundary = moreHistory && previous?.version === selected.version ? before : null
      const history = readWorklogHistory(await client.request<unknown>(path() + '/' + id + '/history?page_size=15' + (boundary ? '&before_version=' + boundary : ''), { signal: abort.signal }), context, selected, boundary)
      if (current !== epoch || abort.signal.aborted) return false
      state = { ...state, selected, history }; return true
    } catch (error) { if (current === epoch && !isCancelled(error)) state = { ...state, error }; return false }
    finally { if (current === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!scope?.canWrite || state.busy) return false
    const context = { ...scope }, current = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort
    state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as WorklogInput & { expected_version?: number; expected_ticket_version?: number }
      const raw = await client.request<unknown>(attempt.path, { method: attempt.method, body, idempotencyKey: attempt.key, signal: abort.signal })
      if (current !== epoch || abort.signal.aborted) return false
      const receipt = readWorklog(raw, context)
      if (receipt.version !== (attempt.baseline ? attempt.baseline.version + 1 : 1) || attempt.baseline && (receipt.id !== attempt.baseline.id || receipt.created_at !== attempt.baseline.created_at) || receipt.kind !== body.kind || receipt.note !== body.note || worklogInstant(receipt.started_at) !== worklogInstant(body.started_at) || worklogInstant(receipt.ended_at) !== worklogInstant(body.ended_at)) throw invalid()
      state = { ...state, receipt, selected: receipt, history: null, busy: null, attempt: { ...attempt, state: 'accepted' } }; emit(); await refresh(); return true
    } catch (error) {
      if (current !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, selected: null, history: null, parentVersion: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (current === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(input: WorklogInput, edit: { id: string; version: number } | null = null) {
    if (!scope?.canWrite || state.busy || unresolved() || !state.parentVersion || edit && (!state.selected || state.selected.id !== edit.id || state.selected.version !== edit.version)) return false
    validateWorklogInput(input)
    const baseline = edit ? copy(state.selected!) : null, version = baseline?.version ?? state.parentVersion
    if (version >= Number.MAX_SAFE_INTEGER) throw inputInvalid()
    const payload = JSON.stringify({ [edit ? 'expected_version' : 'expected_ticket_version']: version, ...copy(input) })
    return send({ path: path() + (baseline ? '/' + baseline.id : ''), method: edit ? 'PATCH' : 'POST', key: newKey(), payload, baseline, state: 'uncertain' })
  }
  emit()
  return { reset, refresh, show, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, dispose: () => reset(null), state: () => detachedWorkspaceState(state) }
}
