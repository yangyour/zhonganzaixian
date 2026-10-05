import { createRequestId } from './browserCrypto'
import { isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { writeFailureUncertain } from './writeFailure'
import { inspectionUuid, validInspectionContext } from './inspectionTemplates'
import { worksheetBad, worksheetBinding, worksheetDefinition, worksheetSameTime, worksheetTime, readWorksheetDetail, readWorksheetDetailShape, readWorksheetHistory, readWorksheetPage, readWorksheetPayload, readWorksheetSource, readWorksheetSourceShape, type WorksheetAction, type WorksheetContext, type WorksheetDetail, type WorksheetHistory, type WorksheetPage, type WorksheetSource, type WorksheetView } from './inspectionWorksheets'
export interface WorksheetAttempt { path: string; method: 'POST' | 'PATCH'; action: WorksheetAction; payload: string; key: string; baseline: WorksheetDetail | null; source: WorksheetSource | null; state: 'uncertain' | 'accepted' | 'rejected' }
export interface WorksheetState { page: WorksheetPage | null; selected: WorksheetDetail | null; history: WorksheetHistory | null; receipt: WorksheetDetail | null; attempt: WorksheetAttempt | null; assetId: string; view: WorksheetView; paged: boolean; busy: 'read' | 'write' | null; error: unknown }
export const emptyWorksheetState = (): WorksheetState => ({ page: null, selected: null, history: null, receipt: null, attempt: null, assetId: '', view: 'CURRENT', paged: false, busy: null, error: null })
const same = (a: unknown, b: unknown): boolean => {
  if (a === b) return true
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((value, index) => same(value, b[index]))
  if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const x = a as Record<string, unknown>, y = b as Record<string, unknown>; return Object.keys(x).length === Object.keys(y).length && Object.keys(x).every(key => Object.hasOwn(y, key) && same(x[key], y[key])) }
  return false
}
export function createWorksheetWorkspace(client: Pick<ApiClient, 'request'>, changed: (value: WorksheetState) => void, newKey: () => string = () => createRequestId()) {
  let context: WorksheetContext | null = null, state = emptyWorksheetState(), epoch = 0, abort: AbortController | null = null, disposed = false
  const snapshot = () => detachedWorkspaceState(state), emit = () => changed(snapshot()), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: WorksheetContext | null) { abort?.abort(); abort = null; ++epoch; context = !disposed && validInspectionContext(next) ? { ...next } : null; state = emptyWorksheetState(); emit() }
  async function read(kind: 'list' | 'detail' | 'history', assetId: string, view: WorksheetView, next: boolean, id?: string) {
    if (!context || disposed || state.busy) return false
    const before = kind === 'list' && next && state.page?.has_more && assetId === state.assetId && view === state.view ? { time: state.page.next_before_created_at!, id: state.page.next_before_id! } : undefined
    const historyBefore = kind === 'history' && next && state.history !== null && state.history.worksheet_id === id && state.history.has_more ? state.history.next_before_version! : undefined
    if (next && !(kind === 'list' ? before : historyBefore)) return false
    const cleared = kind === 'list' ? { page: null, selected: null, history: null } : kind === 'detail' ? { selected: null, history: null } : { history: null }
    if (!['CURRENT','ARCHIVED','ALL'].includes(view) || typeof assetId !== 'string' || assetId !== '' && !inspectionUuid(assetId) || kind !== 'list' && !inspectionUuid(id)) { state = { ...state, ...cleared, error: worksheetBad() }; emit(); return false }
    const turn = ++epoch, scope = { ...context }, controller = new AbortController(); abort = controller
    state = { ...state, ...cleared, busy: 'read', error: null, ...(kind === 'list' ? { assetId, view, paged: next } : {}) }; emit()
    try {
      const params = new URLSearchParams({ page_size: '15' }); let path: string
      if (kind === 'list') { params.set('view', view); if (assetId) params.set('asset_id', assetId); if (before) { params.set('before_created_at', before.time); params.set('before_id', before.id) } path = '/inspection-worksheets?' + params }
      else if (kind === 'history') { if (historyBefore) params.set('before_version', String(historyBefore)); path = '/inspection-worksheets/' + id + '/history?' + params }
      else path = '/inspection-worksheets/' + id
      const raw = await client.request<unknown>(path, { signal: controller.signal })
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      if (kind === 'list') {
        const page = readWorksheetPage(raw, scope, view, assetId, before), receipt = state.receipt, seen = receipt && page.items.find(row => row.id === receipt.id)
        if (seen && receipt) { const { template_items: _items, observations: _observations, ...summary } = receipt; if (seen.version < receipt.version || seen.version === receipt.version && !same(seen, summary)) throw worksheetBad() }
        state.page = page
      }
      else if (kind === 'history') {
        const history = await readWorksheetHistory(raw, scope, id!, historyBefore)
        if (turn !== epoch || disposed || controller.signal.aborted) return false
        if (!historyBefore && state.receipt !== null && state.receipt.id === id && history.items[0]!.version < state.receipt.version) throw worksheetBad()
        const receipt = state.receipt, known = receipt !== null && receipt.id === id ? history.items.find(row => row.version === receipt.version) : undefined
        if (known && receipt) { const { id: _id, tenant_id: _tenant, created_by: _creator, created_at: _created, updated_at: _updated, ...revision } = receipt; if (!same(known, { ...revision, previous_version: receipt.version === 1 ? null : receipt.version - 1 })) throw worksheetBad() }
        state.history = history
      } else {
        const row = await readWorksheetDetail(raw, scope)
        if (turn !== epoch || disposed || controller.signal.aborted) return false
        if (row.id !== id || state.receipt !== null && state.receipt.id === id && (row.version < state.receipt.version || row.version === state.receipt.version && !same(row, state.receipt))) throw worksheetBad()
        state.selected = row
      }
      return true
    } catch (error) { if (turn === epoch && !disposed && !isCancelled(error)) state.error = error; return false }
    finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
  }
  async function send(input: WorksheetAttempt, retry: boolean) {
    if (!context?.canWrite || disposed || state.busy) return false
    // Private copy before the first await; getter/callback/transport mutations cannot change this command.
    const attempt = structuredClone(input), turn = ++epoch, scope = { ...context }, controller = new AbortController(); abort = controller
    let dispatched = false
    state = { ...state, busy: 'write', attempt: { ...attempt, state: 'uncertain' }, page: null, selected: null, history: null, error: null }; emit()
    try {
      const payload = readWorksheetPayload(JSON.parse(attempt.payload), attempt.action), base = attempt.baseline
      if (base) await readWorksheetDetail(base, scope)
      if (attempt.source) await readWorksheetSource(attempt.source, scope)
      // An authorization/context reset during asynchronous hashing must prevent even sending the old command.
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      dispatched = true
      const raw = await client.request<unknown>(attempt.path, { method: attempt.method, body: structuredClone(payload), idempotencyKey: attempt.key, signal: controller.signal })
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      const row = await readWorksheetDetail(raw, scope)
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      if (row.version !== payload.expected_version + 1 || row.action !== attempt.action || row.recorded_by !== scope.actorId || (base ? row.id !== base.id || row.created_by !== base.created_by || row.created_at !== base.created_at || worksheetTime(row.updated_at)! < worksheetTime(base.updated_at)! || worksheetTime(row.recorded_at)! < worksheetTime(base.recorded_at)! : row.created_by !== scope.actorId)) throw worksheetBad()
      if (base && !same(worksheetBinding(row), worksheetBinding(base))) throw worksheetBad()
      if (attempt.source) {
        const source = attempt.source
        // Asset name is captured by the server at lock time; an intervening legitimate rename is not a source-id change.
        if (row.asset_id !== source.asset.id || row.template_id !== source.template.id || row.template_version !== source.template.version || row.template_content_sha256 !== source.template.content_sha256 || !same(worksheetDefinition(row), { device_type: source.template.device_type, name: source.template.name, items: source.template.items })) throw worksheetBad()
      }
      const expectedObservations = attempt.action === 'ARCHIVE' ? base!.observations : payload.observations!
      const expectedTime = attempt.action === 'ARCHIVE' ? base!.reported_performed_at : payload.reported_performed_at!
      if (!worksheetSameTime(row.reported_performed_at, expectedTime) || row.observations.length !== expectedObservations.length || row.observations.some(answer => !same(answer, expectedObservations.find(expected => expected.key === answer.key)))) throw worksheetBad()
      if (attempt.action === 'ARCHIVE' && row.content_sha256 !== base!.content_sha256) throw worksheetBad()
      state = { ...state, receipt: row, attempt: { ...attempt, state: 'accepted' } }; return true
    } catch (error) {
      if (turn === epoch && !disposed) state = { ...state, error, attempt: { ...attempt, state: retry || dispatched && writeFailureUncertain(false, error) ? 'uncertain' : 'rejected' } }
      return false
    } finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
  }
  async function submit(action: WorksheetAction, input: unknown, baseline: unknown = null, creationSource: unknown = null) {
    if (!context?.canWrite || disposed || state.busy || state.attempt) return false
    try {
      const payload = readWorksheetPayload(input, action), base = baseline === null ? null : readWorksheetDetailShape(baseline, context), source = creationSource === null ? null : readWorksheetSourceShape(creationSource, context)
      if (action === 'CREATE' ? base !== null || !source || payload.asset_id !== source.asset.id || payload.template_id !== source.template.id || payload.template_version !== source.template.version : source !== null || !base || payload.expected_version !== base.version || base.archived !== (action === 'RESTORE')) throw worksheetBad()
      if (action !== 'ARCHIVE') { const keys = new Set((source?.template.items ?? base!.template_items).map(item => item.key)); if (payload.observations!.some(row => !keys.has(row.key))) throw worksheetBad() }
      const key = newKey(); if (typeof key !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(key)) throw worksheetBad()
      const path = action === 'CREATE' ? '/inspection-worksheets' : '/inspection-worksheets/' + base!.id + (action === 'ARCHIVE' ? '/archive' : action === 'RESTORE' ? '/restore' : '')
      return send({ path, method: action === 'UPDATE' ? 'PATCH' : 'POST', action, key, payload: JSON.stringify(payload), baseline: base, source, state: 'uncertain' }, false)
    } catch (error) { state.error = error; emit(); return false }
  }
  emit()
  return { reset, state: snapshot, unresolved, list: (assetId = '', view: WorksheetView = 'CURRENT', next = false) => read('list', assetId, view, next), select: (id: string) => read('detail', state.assetId, state.view, false, id), history: (id: string, next = false) => read('history', state.assetId, state.view, next, id), submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), begin: () => { if (!context?.canWrite || disposed || state.busy || unresolved()) return false; state = { ...state, receipt: null, attempt: null, error: null }; emit(); return true }, dispose: () => { disposed = true; reset(null) } }
}
