import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readCalendar, readCalendarDraft, type CalendarDraft, type CalendarSnapshot, type CalendarReadContext } from './workingCalendarReader'
import { worklogInstant } from './worklogWorkspace'
export interface CalendarInput { name: string; coverage_start: string; coverage_end: string; working_windows: { start: string; end: string }[]; coverage_complete: boolean; note: string | null }
interface Attempt { method: 'POST' | 'PATCH'; path: string; payload: string; key: string; baseline: CalendarSnapshot | null; state: 'uncertain' | 'accepted' | 'rejected' }
export interface CalendarWriteState { editing: boolean; baseline: CalendarSnapshot | null; lastRead: CalendarSnapshot | null; receipt: CalendarSnapshot | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const bad = () => new ApiError(200, 'INVALID_CALENDAR_RECEIPT', '保存回执与原请求范围、版本或草稿不一致，结果尚未确认', '—')
function normalized(value: CalendarDraft): CalendarDraft { return { ...value, coverage_start: new Date(worklogInstant(value.coverage_start)).toISOString(), coverage_end: new Date(worklogInstant(value.coverage_end)).toISOString(), working_windows: value.working_windows.map(w => ({ start: new Date(worklogInstant(w.start)).toISOString(), end: new Date(worklogInstant(w.end)).toISOString() })) } }
export function validateCalendarInput(value: unknown): CalendarInput {
  try {
    if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== 6 || !['name', 'coverage_start', 'coverage_end', 'working_windows', 'coverage_complete', 'note'].every(k => Object.hasOwn(value, k))) throw bad()
    const v = value as CalendarInput
    if (!Array.isArray(v.working_windows) || v.working_windows.length > 1000 || v.working_windows.some(w => !w || typeof w !== 'object' || Object.keys(w).length !== 2 || !Object.hasOwn(w, 'start') || !Object.hasOwn(w, 'end'))) throw bad()
    const time = (text: unknown) => new Date(worklogInstant(text)).toISOString()
    const canonical = { ...v, coverage_start: time(v.coverage_start), coverage_end: time(v.coverage_end), working_windows: v.working_windows.map(w => ({ start: time(w.start), end: time(w.end) })), timezone: 'Asia/Shanghai' as const }
    const draft = readCalendarDraft(canonical); const { timezone: _timezone, ...input } = draft; return input
  } catch { throw new Error('请明确名称、含时区及秒的覆盖范围、全部已知窗口和完整性声明；不默认时段，备注最多2000字符') }
}
export const emptyCalendarWriteState = (): CalendarWriteState => ({ editing: false, baseline: null, lastRead: null, receipt: null, attempt: null, busy: null, error: null })
export function createWorkingCalendarWriter(client: Pick<ApiClient, 'request'>, changed: (value: CalendarWriteState) => void, newKey: () => string = () => createRequestId()) {
  let scope: CalendarReadContext | null = null, state = emptyCalendarWriteState(), epoch = 0, controller: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: CalendarReadContext | null) { controller?.abort(); ++epoch; const valid = next && uuid.test(next.tenantId) && uuid.test(next.actorId) && Number.isSafeInteger(next.permissionVersion) && next.permissionVersion > 0; scope = valid ? { ...next! } : null; state = emptyCalendarWriteState(); if (next && !valid) state.error = bad(); emit() }
  function begin(baseline: CalendarSnapshot | null) { if (!scope || state.busy || unresolved()) return false; const value = baseline === null ? null : readCalendar(baseline, scope); state = { ...emptyCalendarWriteState(), editing: true, baseline: value, lastRead: value }; emit(); return true }
  async function refresh() {
    if (!scope || state.busy === 'write') return false
    const known = state.receipt || state.baseline; if (!known) return false
    const context = { ...scope }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: 'read', lastRead: null, error: null }; emit()
    try { const value = readCalendar(await client.request<unknown>('/working-calendars/' + known.id, { signal: abort.signal }), context); if (turn !== epoch || abort.signal.aborted) return false; if (value.id !== known.id || value.version < known.version || worklogInstant(value.updated_at) < worklogInstant(known.updated_at) || worklogInstant(value.created_at) !== worklogInstant(known.created_at) || value.version === known.version && (JSON.stringify(normalized(value.draft)) !== JSON.stringify(normalized(known.draft)) || value.updated_by !== known.updated_by || worklogInstant(value.updated_at) !== worklogInstant(known.updated_at))) throw bad(); state = { ...state, lastRead: value }; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, error, lastRead: null }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function send(attempt: Attempt, retry = false) {
    if (!scope || state.busy) return false
    const context = { ...scope }, turn = ++epoch; controller?.abort(); const abort = new AbortController(); controller = abort; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const payload = JSON.parse(attempt.payload) as CalendarInput & { expected_version?: number }
      const raw = await client.request<unknown>(attempt.path, { method: attempt.method, body: payload, idempotencyKey: attempt.key, signal: abort.signal }); if (turn !== epoch || abort.signal.aborted) return false
      const value = readCalendar(raw, context), { expected_version: _version, ...input } = payload
      const expected = readCalendarDraft({ ...input, timezone: 'Asia/Shanghai' })
      if (value.updated_by !== context.actorId || value.version !== (attempt.baseline ? attempt.baseline.version + 1 : 1) || !attempt.baseline && worklogInstant(value.created_at) !== worklogInstant(value.updated_at) || attempt.baseline && (value.id !== attempt.baseline.id || worklogInstant(value.created_at) !== worklogInstant(attempt.baseline.created_at)) || JSON.stringify(normalized(value.draft)) !== JSON.stringify(normalized(expected))) throw bad()
      state = { ...state, receipt: value, attempt: { ...attempt, state: 'accepted' }, busy: null, editing: false }; emit(); await refresh(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, lastRead: null, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  async function submit(input: CalendarInput) { if (!scope || !state.editing || state.busy || state.attempt) return false; const value = validateCalendarInput(input), baseline = state.baseline ? copy(state.baseline) : null; if (baseline && state.lastRead && state.lastRead.version !== baseline.version) { state = { ...state, error: new ApiError(409, 'VERSION_CONFLICT', '已发现新版本，请明确重新准备编辑，不会自动重定基准', '—') }; emit(); return false } if (baseline && baseline.version >= Number.MAX_SAFE_INTEGER) throw new Error('版本已到技术边界，不能继续递增'); return send({ path: baseline ? '/working-calendars/' + baseline.id : '/working-calendars', method: baseline ? 'PATCH' : 'POST', key: newKey(), payload: JSON.stringify(baseline ? { ...value, expected_version: baseline.version } : value), baseline, state: 'uncertain' }) }
  emit(); return { reset, begin, submit, refresh, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, state: () => detachedWorkspaceState(state), dispose: () => reset(null) }
}
