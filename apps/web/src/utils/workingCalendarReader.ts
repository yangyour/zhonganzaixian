import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'
export interface CalendarWindow { start: string; end: string }
export interface CalendarDraft { name: string; coverage_start: string; coverage_end: string; working_windows: CalendarWindow[]; coverage_complete: boolean; note: string | null; timezone: 'Asia/Shanghai' }
export interface CalendarSnapshot { id: string; tenant_id: string; version: number; draft: CalendarDraft; updated_by: string; created_at: string; updated_at: string; source: 'postgresql'; lifecycle_status: 'DRAFT'; customer_confirmation: 'UNVERIFIED'; effective: false; record_source: 'MANUAL_CALENDAR_DRAFT' }
export interface CalendarRevision { calendar_id: string; tenant_id: string; version: number; before: CalendarDraft | null; after: CalendarDraft; actor_id: string; recorded_at: string }
interface CalendarPage { items: CalendarSnapshot[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; source: 'postgresql'; scope: 'CURRENT_TENANT_CONFIGURATION_DRAFTS'; page_size_limit: 100 }
interface HistoryPage { items: CalendarRevision[]; has_more: boolean; next_before_version: number | null; source: 'postgresql'; page_size_limit: 100 }
export interface CalendarPreview { calendar_id: string; tenant_id: string; calendar_version: number; status: 'CALCULATED' | 'UNRESOLVED'; reason: string | null; scenario_deadline: string | null; effective_deadline: null; scope: 'UNPUBLISHED_DRAFT_SCENARIO'; effective: false; customer_confirmation: 'UNVERIFIED'; source: 'postgresql'; timezone: 'Asia/Shanghai'; start: string | null; required_seconds: number | null; remaining_duration: string | null; consumed: CalendarWindow[] }
export interface CalendarReadContext { tenantId: string; actorId: string; permissionVersion: number }
export interface CalendarReadState { page: CalendarPage | null; selected: CalendarSnapshot | null; history: HistoryPage | null; preview: CalendarPreview | null; busy: boolean; error: unknown; paged: boolean; historyPaged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const version = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 1
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const bad = () => new ApiError(200, 'INVALID_CALENDAR_RESPONSE', '日历范围、版本或未生效事实回读异常，相关显示已清除', '—')
const date = (v: unknown) => { try { if (typeof v !== 'string' || !v.endsWith('Z')) throw bad(); return worklogInstant(v) } catch { throw bad() } }
const text = (v: unknown, max: number, multiline = false) => typeof v === 'string' && Boolean(v.trim()) && Array.from(v).length <= max && !(multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(v) && !Array.from(v).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c))
function window(v: unknown): CalendarWindow { if (!exact(v, ['start', 'end']) || date(v.start) >= date(v.end)) throw bad(); return { start: v.start as string, end: v.end as string } }
export function readCalendarDraft(v: unknown): CalendarDraft {
  if (!exact(v, ['name', 'coverage_start', 'coverage_end', 'working_windows', 'coverage_complete', 'note', 'timezone']) || !text(v.name, 100) || v.note !== null && !text(v.note, 2000, true) || v.timezone !== 'Asia/Shanghai' || typeof v.coverage_complete !== 'boolean' || !Array.isArray(v.working_windows) || v.working_windows.length > 1000) throw bad()
  const start = date(v.coverage_start), end = date(v.coverage_end); if (start >= end) throw bad()
  for (const raw of v.working_windows) { const w = window(raw); if (date(w.start) < start || date(w.end) > end) throw bad() }
  return { name: v.name as string, coverage_start: v.coverage_start as string, coverage_end: v.coverage_end as string, working_windows: v.working_windows.map(window), coverage_complete: v.coverage_complete, note: v.note as string | null, timezone: 'Asia/Shanghai' }
}
export function readCalendar(v: unknown, scope: CalendarReadContext): CalendarSnapshot {
  if (!exact(v, ['id', 'tenant_id', 'version', 'draft', 'updated_by', 'created_at', 'updated_at', 'source', 'lifecycle_status', 'customer_confirmation', 'effective', 'record_source']) || typeof v.id !== 'string' || !uuid.test(v.id) || v.tenant_id !== scope.tenantId || !version(v.version) || typeof v.updated_by !== 'string' || !uuid.test(v.updated_by) || v.source !== 'postgresql' || v.lifecycle_status !== 'DRAFT' || v.customer_confirmation !== 'UNVERIFIED' || v.effective !== false || v.record_source !== 'MANUAL_CALENDAR_DRAFT' || date(v.updated_at) < date(v.created_at)) throw bad()
  return { ...copy(v), draft: readCalendarDraft(v.draft) } as unknown as CalendarSnapshot
}
export function validateCalendarScenario(start: string | null, seconds: number | null) { if (start !== null) worklogInstant(start); if (seconds !== null && (!Number.isSafeInteger(seconds) || seconds < 0)) throw new Error('时长必须为明确的非负整数秒，或留空保持未知') }
function scenarioFacts(current: CalendarSnapshot, start: string | null, seconds: number | null) {
  let remaining = seconds === null ? null : BigInt(seconds) * 1000n
  const consumed: [number, number][] = []
  const unresolved = (reason: string) => ({ reason, deadline: null, remaining, consumed })
  if (seconds === null) return unresolved('DURATION_MISSING')
  if (start === null) return unresolved('START_MISSING')
  if (!current.draft.coverage_complete) return unresolved('COVERAGE_INCOMPLETE')
  const begin = worklogInstant(start)
  if (begin < date(current.draft.coverage_start) || begin >= date(current.draft.coverage_end)) return unresolved('START_OUTSIDE_COVERAGE')
  if (seconds === 0) return { reason: null, deadline: begin, remaining: 0n, consumed }
  const union: [number, number][] = []
  for (const w of current.draft.working_windows.map(w => [date(w.start), date(w.end)] as [number, number]).sort((a, b) => a[0] - b[0])) {
    const tail = union.at(-1); if (tail && w[0] <= tail[1]) tail[1] = Math.max(tail[1], w[1]); else union.push([...w])
  }
  for (const [from, to] of union) {
    if (to <= begin) continue
    const cursor = Math.max(from, begin), available = BigInt(to - cursor)
    if (remaining! <= available) { const deadline = cursor + Number(remaining); consumed.push([cursor, deadline]); return { reason: null, deadline, remaining: 0n, consumed } }
    consumed.push([cursor, to]); remaining = remaining! - available
  }
  return unresolved('INSUFFICIENT_WORKING_TIME')
}
function durationMillis(v: unknown): bigint | null {
  if (v === null) return null
  if (typeof v !== 'string') throw bad()
  const m = /^PT(?=.)(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,3}))?S)?$/.exec(v)
  if (!m) throw bad()
  return BigInt(m[1] || '0') * 3600000n + BigInt(m[2] || '0') * 60000n + BigInt(m[3] || '0') * 1000n + BigInt((m[4] || '').padEnd(3, '0'))
}
function readPreview(v: unknown, current: CalendarSnapshot, start: string | null, seconds: number | null): CalendarPreview {
  if (!exact(v, ['calendar_id', 'tenant_id', 'calendar_version', 'status', 'reason', 'scenario_deadline', 'effective_deadline', 'scope', 'effective', 'customer_confirmation', 'source', 'timezone', 'start', 'required_seconds', 'remaining_duration', 'consumed']) || v.calendar_id !== current.id || v.tenant_id !== current.tenant_id || v.calendar_version !== current.version || v.effective_deadline !== null || v.effective !== false || v.customer_confirmation !== 'UNVERIFIED' || v.source !== 'postgresql' || v.scope !== 'UNPUBLISHED_DRAFT_SCENARIO' || v.timezone !== 'Asia/Shanghai' || v.required_seconds !== seconds || (start === null ? v.start !== null : date(v.start) !== worklogInstant(start)) || !Array.isArray(v.consumed) || v.consumed.length > 1000) throw bad()
  if (v.status === 'CALCULATED') { if (v.reason !== null || v.scenario_deadline === null || v.remaining_duration !== 'PT0S' || start === null || seconds === null || !current.draft.coverage_complete) throw bad(); const deadline = date(v.scenario_deadline); if (deadline < worklogInstant(start) || deadline > date(current.draft.coverage_end)) throw bad() }
  else if (v.status !== 'UNRESOLVED' || !['DURATION_MISSING', 'START_MISSING', 'COVERAGE_INCOMPLETE', 'START_OUTSIDE_COVERAGE', 'INSUFFICIENT_WORKING_TIME'].includes(String(v.reason)) || v.scenario_deadline !== null) throw bad()
  if (seconds === null ? v.reason !== 'DURATION_MISSING' || v.remaining_duration !== null : typeof v.remaining_duration !== 'string' || !/^PT(?=.+)(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d{1,9})?S)?$/.test(v.remaining_duration)) throw bad()
  let last: number | null = null
  for (const raw of v.consumed) { const w = window(raw), from = date(w.start), to = date(w.end); if (start === null || from < worklogInstant(start) || from < date(current.draft.coverage_start) || to > date(current.draft.coverage_end) || last !== null && from < last) throw bad(); last = to }
  if (v.status === 'CALCULATED' && (seconds === 0 ? v.consumed.length !== 0 || date(v.scenario_deadline) !== worklogInstant(start!) : last !== date(v.scenario_deadline))) throw bad()
  const expected = scenarioFacts(current, start, seconds)
  if (v.reason !== expected.reason || (v.scenario_deadline === null ? null : date(v.scenario_deadline)) !== expected.deadline || durationMillis(v.remaining_duration) !== expected.remaining || JSON.stringify(v.consumed.map(raw => { const w = window(raw); return [date(w.start), date(w.end)] })) !== JSON.stringify(expected.consumed)) throw bad()
  return copy(v) as unknown as CalendarPreview
}
export const emptyCalendarReadState = (): CalendarReadState => ({ page: null, selected: null, history: null, preview: null, busy: false, error: null, paged: false, historyPaged: false })
export function createWorkingCalendarReader(client: Pick<ApiClient, 'request'>, changed: (value: CalendarReadState) => void) {
  let scope: CalendarReadContext | null = null, state = emptyCalendarReadState(), epoch = 0, abort: AbortController | undefined
  const emit = () => changed({ ...state })
  function reset(next: CalendarReadContext | null) { abort?.abort(); ++epoch; const valid = next && uuid.test(next.tenantId) && uuid.test(next.actorId) && version(next.permissionVersion); scope = valid ? { ...next! } : null; state = emptyCalendarReadState(); if (next && !valid) state.error = bad(); emit() }
  async function run(action: (signal: AbortSignal, context: CalendarReadContext) => Promise<Partial<CalendarReadState>>, clear: Partial<CalendarReadState>) {
    if (!scope) return false
    abort?.abort(); const turn = ++epoch, controller = new AbortController(), context = { ...scope }; abort = controller; state = { ...state, ...clear, busy: true, error: null }; emit()
    try { const result = await action(controller.signal, context); if (turn !== epoch || controller.signal.aborted) return false; state = { ...state, ...result }; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, ...clear, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: false }; emit() } }
  }
  function list(next = false) {
    if (next && (!state.page?.has_more || state.busy)) return Promise.resolve(false)
    const boundary = next ? { time: state.page!.next_before_created_at!, id: state.page!.next_before_id! } : null
    return run(async (signal, context) => {
      const v = await client.request<unknown>('/working-calendars?page_size=15' + (boundary ? '&before_created_at=' + encodeURIComponent(boundary.time) + '&before_id=' + boundary.id : ''), { signal })
      if (!exact(v, ['items', 'has_more', 'next_before_created_at', 'next_before_id', 'source', 'scope', 'page_size_limit']) || !Array.isArray(v.items) || v.items.length > 15 || typeof v.has_more !== 'boolean' || v.source !== 'postgresql' || v.scope !== 'CURRENT_TENANT_CONFIGURATION_DRAFTS' || v.page_size_limit !== 100) throw bad()
      const items = v.items.map(x => readCalendar(x, context)); let previous = boundary
      for (const item of items) { if (previous && (date(item.created_at) > date(previous.time) || date(item.created_at) === date(previous.time) && item.id >= previous.id)) throw bad(); previous = { time: item.created_at, id: item.id } }
      const tail = items.at(-1); if (v.has_more ? items.length !== 15 || v.next_before_created_at !== tail?.created_at || v.next_before_id !== tail?.id : v.next_before_created_at !== null || v.next_before_id !== null) throw bad()
      return { page: copy({ ...v, items }) as unknown as CalendarPage }
    }, { page: null, selected: null, history: null, preview: null, paged: next, historyPaged: false })
  }
  async function select(id: string, beforeVersion: number | null = null) {
    if (!uuid.test(id) || beforeVersion !== null && (!version(beforeVersion) || state.selected?.id !== id || state.history?.next_before_version !== beforeVersion || !state.history.has_more)) return false
    return run(async (signal, context) => {
      const current = readCalendar(await client.request<unknown>('/working-calendars/' + id, { signal }), context); if (signal.aborted) throw new DOMException('Context changed', 'AbortError'); if (current.id !== id) throw bad()
      const v = await client.request<unknown>('/working-calendars/' + id + '/history?page_size=15' + (beforeVersion === null ? '' : '&before_version=' + beforeVersion), { signal })
      if (!exact(v, ['items', 'has_more', 'next_before_version', 'source', 'page_size_limit']) || !Array.isArray(v.items) || v.items.length > 15 || typeof v.has_more !== 'boolean' || v.source !== 'postgresql' || v.page_size_limit !== 100 || !v.items.length) throw bad()
      let expected = beforeVersion === null ? current.version : beforeVersion - 1
      const items = v.items.map(raw => { if (!exact(raw, ['calendar_id', 'tenant_id', 'version', 'before', 'after', 'actor_id', 'recorded_at']) || raw.calendar_id !== id || raw.tenant_id !== context.tenantId || raw.version !== expected-- || !version(raw.version) || typeof raw.actor_id !== 'string' || !uuid.test(raw.actor_id) || raw.version === 1 && raw.before !== null || raw.version !== 1 && raw.before === null) throw bad(); date(raw.recorded_at); return { ...copy(raw), before: raw.before === null ? null : readCalendarDraft(raw.before), after: readCalendarDraft(raw.after) } as unknown as CalendarRevision })
      for (let i = 0; i < items.length - 1; i++) if (JSON.stringify(items[i]!.before) !== JSON.stringify(items[i + 1]!.after)) throw bad()
      if (beforeVersion === null && JSON.stringify(items[0]!.after) !== JSON.stringify(current.draft)) throw bad()
      const tail = items.at(-1)!; if (v.has_more ? items.length !== 15 || tail.version <= 1 || v.next_before_version !== tail.version : tail.version !== 1 || v.next_before_version !== null) throw bad()
      return { selected: current, history: copy({ ...v, items }) as unknown as HistoryPage }
    }, { selected: null, history: null, preview: null, historyPaged: beforeVersion !== null })
  }
  function preview(start: string | null, seconds: number | null) {
    if (!state.selected || state.busy) return Promise.resolve(false); validateCalendarScenario(start, seconds); const current = copy(state.selected)
    return run(async (signal) => ({ preview: readPreview(await client.request<unknown>('/working-calendars/' + current.id + '/preview', { method: 'POST', body: { expected_version: current.version, start, required_seconds: seconds }, signal }), current, start, seconds) }), { preview: null })
  }
  emit(); return { reset, list, select, preview, clearPreview: () => { state = { ...state, preview: null }; emit() }, state: () => state, dispose: () => reset(null) }
}
