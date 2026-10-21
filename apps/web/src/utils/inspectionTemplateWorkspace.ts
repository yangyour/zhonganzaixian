import { createRequestId } from './browserCrypto'
import { isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { writeFailureUncertain } from './writeFailure'
import { inspectionBad, inspectionDefinition, inspectionUuid, readInspectionDefinition, readInspectionDetail, readInspectionDetailShape, readInspectionHistory, readInspectionPage, readInspectionPayload, validInspectionContext, validInspectionText, type InspectionAction, type InspectionContext, type InspectionDetail, type InspectionHistory, type InspectionPage, type InspectionView } from './inspectionTemplates'
export interface InspectionAttempt { path: string; method: 'POST' | 'PATCH'; action: InspectionAction; payload: string; key: string; baseline: InspectionDetail | null; state: 'uncertain' | 'accepted' | 'rejected' }
export interface InspectionState { page: InspectionPage | null; selected: InspectionDetail | null; history: InspectionHistory | null; receipt: InspectionDetail | null; attempt: InspectionAttempt | null; query: string; deviceType: string; view: InspectionView; paged: boolean; busy: 'read' | 'write' | null; error: unknown }
export const emptyInspectionState = (): InspectionState => ({ page: null, selected: null, history: null, receipt: null, attempt: null, query: '', deviceType: '', view: 'CURRENT', paged: false, busy: null, error: null })
const same = (a: unknown, b: unknown): boolean => {
  if (a === b) return true
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((value, index) => same(value, b[index]))
  if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) { const x = a as Record<string, unknown>, y = b as Record<string, unknown>; return Object.keys(x).length === Object.keys(y).length && Object.keys(x).every(key => Object.hasOwn(y, key) && same(x[key], y[key])) }
  return false
}
export function createInspectionWorkspace(client: Pick<ApiClient, 'request'>, changed: (value: InspectionState) => void, newKey: () => string = () => createRequestId()) {
  let context: InspectionContext | null = null, state = emptyInspectionState(), epoch = 0, abort: AbortController | null = null, disposed = false
  type ReadRequest={kind:'list'|'detail'|'history';query:string;deviceType:string;view:InspectionView;next:boolean;id?:string;before?:{time:string;id:string};historyBefore?:number}
  let lastRead:ReadRequest|null=null,readRetryable=false
  const snapshot = () => detachedWorkspaceState(state), emit = () => changed(snapshot()), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: InspectionContext | null) {lastRead=null;readRetryable=false;abort?.abort(); abort = null; ++epoch; context = !disposed && validInspectionContext(next) ? { ...next } : null; state = emptyInspectionState(); emit() }
  async function read(kind: 'list' | 'detail' | 'history', query: string, deviceType: string, view: InspectionView, next: boolean, id?: string) {
    if (!context || disposed || state.busy) return false
    const before = kind === 'list' && next && state.page?.has_more && query === state.query && deviceType === state.deviceType && view === state.view ? { time: state.page.next_before_created_at!, id: state.page.next_before_id! } : undefined
    const historyBefore = kind === 'history' && next && state.history !== null && state.history.template_id === id && state.history.has_more ? state.history.next_before_version! : undefined
    if (next && !(kind === 'list' ? before : historyBefore)) return false
    return readRequest({kind,query,deviceType,view,next,id,before,historyBefore})
  }
  async function readRequest(request:ReadRequest){
    if(!context||disposed||state.busy)return false
    const {kind,query,deviceType,view,next,id,before,historyBefore}=request;lastRead=null;readRetryable=false
    const cleared = kind === 'list' ? { page: null, selected: null, history: null } : kind === 'detail' ? { selected: null, history: null } : { history: null }
    if (!['CURRENT','ARCHIVED','ALL'].includes(view) || !validInspectionText(query, 200, false, false, false) || typeof deviceType !== 'string' || deviceType !== '' && !/^[a-z][a-z0-9_-]{0,63}$/.test(deviceType) || kind !== 'list' && !inspectionUuid(id)) { state = { ...state, ...cleared, error: inspectionBad() }; emit(); return false }
    lastRead=structuredClone(request)
    const turn = ++epoch, scope = { ...context }, controller = new AbortController(); abort = controller
    state = { ...state, ...cleared, busy: 'read', error: null, ...(kind === 'list' ? { query, deviceType, view, paged: next } : {}) }; emit()
    try {
      const params = new URLSearchParams({ page_size: '15' }); let path: string
      if (kind === 'list') { params.set('name_query', query); params.set('view', view); if (deviceType) params.set('device_type', deviceType); if (before) { params.set('before_created_at', before.time); params.set('before_id', before.id) } path = '/inspection-templates?' + params }
      else if (kind === 'history') { if (historyBefore) params.set('before_version', String(historyBefore)); path = '/inspection-templates/' + id + '/history?' + params }
      else path = '/inspection-templates/' + id
      if(turn!==epoch||disposed||controller.signal.aborted)return false
      const raw = await client.request<unknown>(path, { signal: controller.signal })
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      if (kind === 'list') {
        const page = readInspectionPage(raw, scope, view, deviceType, before), receipt = state.receipt, seen = receipt && page.items.find(row => row.id === receipt.id)
        if (seen && receipt) { const { items: _items, ...summary } = receipt; if (seen.version < receipt.version || seen.version === receipt.version && !same(seen, summary)) throw inspectionBad() }
        state.page = page
      }
      else if (kind === 'history') {
        const history = await readInspectionHistory(raw, scope, id!, historyBefore)
        if (turn !== epoch || disposed || controller.signal.aborted) return false
        if (!historyBefore && state.receipt !== null && state.receipt.id === id && history.items[0]!.version < state.receipt.version) throw inspectionBad()
        const receipt = state.receipt, known = receipt !== null && receipt.id === id ? history.items.find(row => row.version === receipt.version) : undefined
        if (known && receipt) { const { id: _id, tenant_id: _tenant, created_by: _creator, created_at: _created, updated_at: _updated, ...revision } = receipt; if (!same(known, { ...revision, previous_version: receipt.version === 1 ? null : receipt.version - 1 })) throw inspectionBad() }
        state.history = history
      } else {
        const row = await readInspectionDetail(raw, scope)
        if (turn !== epoch || disposed || controller.signal.aborted) return false
        if (row.id !== id || state.receipt !== null && state.receipt.id === id && (row.version < state.receipt.version || row.version === state.receipt.version && !same(row, state.receipt))) throw inspectionBad()
        state.selected = row
      }
      return true
    } catch (error) { if (turn === epoch && !disposed && !isCancelled(error)){state.error = error;readRetryable=true} return false }
    finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
  }
  async function send(input: InspectionAttempt, retry: boolean) {
    if (!context?.canWrite || disposed || state.busy) return false
    // Private copy before the first await; getter/callback/transport mutations cannot change this command.
    lastRead=null;readRetryable=false
    const attempt = structuredClone(input), turn = ++epoch, scope = { ...context }, controller = new AbortController(); abort = controller
    let dispatched = false
    state = { ...state, busy: 'write', attempt: { ...attempt, state: 'uncertain' }, page: null, selected: null, history: null, error: null }; emit()
    try {
      const payload = readInspectionPayload(JSON.parse(attempt.payload), attempt.action), base = attempt.baseline
      if (base) await readInspectionDetail(base, scope)
      // An authorization/context reset during asynchronous hashing must prevent even sending the old command.
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      dispatched = true
      const raw = await client.request<unknown>(attempt.path, { method: attempt.method, body: structuredClone(payload), idempotencyKey: attempt.key, signal: controller.signal })
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      const row = await readInspectionDetail(raw, scope)
      if (turn !== epoch || disposed || controller.signal.aborted) return false
      const expected = attempt.action === 'ARCHIVE' ? inspectionDefinition(base!) : readInspectionDefinition({ device_type: payload.device_type, name: payload.name, items: payload.items })
      if (row.version !== payload.expected_version + 1 || row.action !== attempt.action || row.recorded_by !== scope.actorId || !same(inspectionDefinition(row), expected) || (base ? row.id !== base.id || row.created_by !== base.created_by || row.created_at !== base.created_at : row.created_by !== scope.actorId)) throw inspectionBad()
      state = { ...state, receipt: row, attempt: { ...attempt, state: 'accepted' } }; return true
    } catch (error) {
      if (turn === epoch && !disposed) state = { ...state, error, attempt: { ...attempt, state: retry || dispatched && writeFailureUncertain(false, error) ? 'uncertain' : 'rejected' } }
      return false
    } finally { if (turn === epoch && !disposed) { state.busy = null; abort = null; emit() } }
  }
  async function submit(action: InspectionAction, input: unknown, baseline: unknown = null) {
    if (!context?.canWrite || disposed || state.busy || state.attempt) return false
    const generation = epoch
    try {
      const payload = readInspectionPayload(input, action), base = baseline === null ? null : readInspectionDetailShape(baseline, context)
      if (action === 'CREATE' ? base !== null : !base || payload.expected_version !== base.version || base.archived !== (action === 'RESTORE')) throw inspectionBad()
      const key = newKey(); if (generation !== epoch || disposed || !context?.canWrite || state.busy || state.attempt) return false; if (typeof key !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(key)) throw inspectionBad()
      const path = action === 'CREATE' ? '/inspection-templates' : '/inspection-templates/' + base!.id + (action === 'ARCHIVE' ? '/archive' : action === 'RESTORE' ? '/restore' : '')
      return send({ path, method: action === 'UPDATE' ? 'PATCH' : 'POST', action, key, payload: JSON.stringify(payload), baseline: base, state: 'uncertain' }, false)
    } catch (error) { if (generation === epoch && !disposed) { state.error = error; emit() } return false }
  }
  emit()
  function cancelRead(){if(disposed||state.busy!=='read')return false;epoch++;abort?.abort();abort=null;readRetryable=lastRead!==null;state={...state,busy:null,error:null};emit();return true}
  return { reset,cancelRead,canRetryRead:()=>!disposed&&readRetryable&&lastRead!==null&&!state.busy,retryRead:()=>readRetryable&&lastRead?readRequest(structuredClone(lastRead)):Promise.resolve(false), state: snapshot, unresolved, list: (query = '', deviceType = '', view: InspectionView = 'CURRENT', next = false) => read('list', query, deviceType, view, next), select: (id: string) => read('detail', state.query, state.deviceType, state.view, false, id), history: (id: string, next = false) => read('history', state.query, state.deviceType, state.view, next, id), submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), begin: () => { if (!context?.canWrite || disposed || state.busy || unresolved()) return false; lastRead=null;readRetryable=false;state = { ...state, receipt: null, attempt: null, error: null }; emit(); return true }, dispose: () => { disposed = true; reset(null) } }
}
