import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readManualIncident, validManualTitle, manualSeverityLabels, type ManualSeverity, type ManualIncidentContext, type ManualIncidentEntry } from './manualIncidentReader'
import { worklogInstant } from './worklogWorkspace'
export interface RegistrationAsset { id: string; tenant_id: string; name: string; version: number }
export interface ManualIncidentInput { title: string; severity: ManualSeverity }
interface Attempt { payload: string; key: string; asset: RegistrationAsset; state: 'uncertain' | 'accepted' | 'rejected' }
export interface ManualIncidentWriteState { editing: boolean; asset: RegistrationAsset | null; lastAssetRead: RegistrationAsset | null; choices: RegistrationAsset[] | null; nextAssetCursor: string | null; assetSearch: string; assetPaged: boolean; receipt: ManualIncidentEntry | null; verifiedReceipt: ManualIncidentEntry | null; attempt: Attempt | null; busy: 'read' | 'write' | null; error: unknown }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const bad = () => new ApiError(200, 'INVALID_MANUAL_INCIDENT_RECEIPT', '关联资产或登记回执与原请求不一致，尚未确认结果', '—')
function asset(value: unknown, context: ManualIncidentContext, expectedId?: string): RegistrationAsset {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw bad()
  const raw = value as Record<string, unknown>
  if (typeof raw.id !== 'string' || !uuid.test(raw.id) || expectedId !== undefined && raw.id !== expectedId || raw.tenant_id !== context.tenantId || typeof raw.name !== 'string' || !raw.name.length || Array.from(raw.name).length > 100 || !Number.isSafeInteger(raw.version) || Number(raw.version) < 1) throw bad()
  return { id: raw.id, tenant_id: raw.tenant_id as string, name: raw.name, version: Number(raw.version) }
}
function sameEntry(a: ManualIncidentEntry, b: ManualIncidentEntry) { return Object.keys(a).every(key => key === 'registered_at' ? worklogInstant(a.registered_at) === worklogInstant(b.registered_at) : a[key as keyof ManualIncidentEntry] === b[key as keyof ManualIncidentEntry]) }
export const emptyManualIncidentWriteState = (): ManualIncidentWriteState => ({ editing: false, asset: null, lastAssetRead: null, choices: null, nextAssetCursor: null, assetSearch: '', assetPaged: false, receipt: null, verifiedReceipt: null, attempt: null, busy: null, error: null })
type ReadTarget={kind:'SEARCH';query:string;next:boolean;cursor:string|null}|{kind:'CHOOSE';baseline:RegistrationAsset}|{kind:'ASSET';baseline:RegistrationAsset}|{kind:'RECEIPT';baseline:ManualIncidentEntry}
export function createManualIncidentWriter(client: Pick<ApiClient, 'request'>, changed: (value: ManualIncidentWriteState) => void, newKey: () => string = () => createRequestId()) {
  let context: ManualIncidentContext | null = null, state = emptyManualIncidentWriteState(), epoch = 0, disposed=false, lastRead:ReadTarget|null=null, assetFloor:RegistrationAsset|null=null, abort: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state)), unresolved = () => state.busy === 'write' || state.attempt?.state === 'uncertain'
  function reset(next: ManualIncidentContext | null) { abort?.abort();abort=undefined;++epoch;lastRead=null;assetFloor=null; const valid = !disposed&&next && uuid.test(next.actorId) && uuid.test(next.tenantId) && Number.isSafeInteger(next.permissionVersion) && next.permissionVersion > 0; context = valid ? { ...next! } : null; state = emptyManualIncidentWriteState(); if (next && !valid && !disposed) state.error = bad(); emit() }
  function begin() { if (!context || disposed || state.busy || unresolved()) return false;++epoch;lastRead=null;assetFloor=null; state = { ...emptyManualIncidentWriteState(), editing: true }; emit(); return true }
  async function read(action: (signal: AbortSignal, scope: ManualIncidentContext) => Promise<Partial<ManualIncidentWriteState>>, clear: Partial<ManualIncidentWriteState>, target:ReadTarget) {
    if (!context || disposed || state.busy) return false
    const scope = { ...context }, turn = ++epoch, controller = new AbortController(); abort?.abort(); abort = controller; lastRead=copy(target);state = { ...state, ...clear, busy: 'read', error: null }; emit()
    try { if(turn!==epoch||disposed||controller.signal.aborted)return false;const result = await action(controller.signal, scope); if (turn !== epoch || controller.signal.aborted) return false; state = { ...state, ...result };if(result.asset||result.lastAssetRead)assetFloor=copy((result.asset||result.lastAssetRead)!);lastRead=null; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, ...clear, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  function search(query: string, next = false, recovery?:Extract<ReadTarget,{kind:'SEARCH'}>) {
    if (!state.editing || state.attempt || !context || state.busy || typeof query !== 'string' || query.length > 100 || /[\u0000-\u001f\u007f]/.test(query) || next && !recovery && (!state.nextAssetCursor || query !== state.assetSearch)) return Promise.resolve(false)
    const cursor = recovery?recovery.cursor:next ? state.nextAssetCursor : null
    return read(async (signal, scope) => {
      const raw = await client.request<unknown>('/assets?page_size=15' + (query ? '&q=' + encodeURIComponent(query) : '') + (cursor ? '&cursor=' + encodeURIComponent(cursor) : ''), { signal })
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw bad(); const value = raw as Record<string, unknown>
      if (Object.keys(value).length !== 6 || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.page_size_limit !== 100 || value.page_size_limit_kind !== 'test_technical_limit' || (value.has_more ? value.items.length !== 15 || typeof value.next_cursor !== 'string' || !value.next_cursor || value.next_cursor.length > 2048 : value.next_cursor !== null)) throw bad()
      const choices = value.items.map(v => asset(v, scope)); if (new Set(choices.map(v => v.id)).size !== choices.length) throw bad()
      return { choices, nextAssetCursor: value.next_cursor as string | null }
    }, { choices: null, nextAssetCursor: null, assetSearch: query, assetPaged: next },{kind:'SEARCH',query,next,cursor})
  }
  function floor(known:RegistrationAsset){return assetFloor?.id===known.id&&assetFloor.version>=known.version?copy(assetFloor):copy(known)}
  function checkAsset(current:RegistrationAsset,known:RegistrationAsset){if(current.version<known.version||current.version===known.version&&current.name!==known.name)throw bad();return current}
  function choose(id:string,recovery?:RegistrationAsset){
    if(!context||disposed||!state.editing||state.attempt||state.busy||!uuid.test(id))return Promise.resolve(false)
    const found=recovery||state.choices?.find(v=>v.id===id);if(!found||found.id!==id)return Promise.resolve(false);const original=copy(found),known=floor(found)
    return read(async(signal,scope)=>{const current=checkAsset(checkAsset(asset(await client.request<unknown>('/assets/'+id,{signal}),scope,id),original),known);return{asset:current,lastAssetRead:current}},{asset:null,lastAssetRead:null},{kind:'CHOOSE',baseline:original})
  }
  function refreshAsset(recovery?:RegistrationAsset){const found=recovery||state.asset;if(!found)return Promise.resolve(false);const known=floor(found);return read(async(signal,scope)=>({lastAssetRead:checkAsset(asset(await client.request<unknown>('/assets/'+known.id,{signal}),scope,known.id),known)}),{lastAssetRead:null},{kind:'ASSET',baseline:known})}
  function adoptLatestAsset(expected: RegistrationAsset | null = state.lastAssetRead) { if (!context || disposed || state.busy || unresolved() || !state.editing || !state.lastAssetRead || !state.asset || state.asset.id !== state.lastAssetRead.id || !expected || expected.id !== state.lastAssetRead.id || expected.tenant_id !== state.lastAssetRead.tenant_id || expected.version !== state.lastAssetRead.version || expected.name !== state.lastAssetRead.name) return false; lastRead=null;state = { ...state, asset: copy(state.lastAssetRead), attempt: null, error: null }; emit(); return true }
  function refreshReceipt(recovery?:ManualIncidentEntry) { const known = recovery||state.receipt; if (!known) return Promise.resolve(false); return read(async (signal, scope) => { const current = readManualIncident(await client.request<unknown>('/manual-incidents/' + known.id, { signal }), scope); if (!sameEntry(known, current)) throw bad(); return { verifiedReceipt: current } }, { verifiedReceipt: null },{kind:'RECEIPT',baseline:copy(known)}) }
  async function send(attempt: Attempt, retry = false) {
    if (!context || disposed || state.busy) return false
    lastRead=null;const scope = { ...context }, turn = ++epoch, controller = new AbortController(); abort?.abort(); abort = controller; state = { ...state, busy: 'write', error: null, attempt: { ...attempt, state: 'uncertain' } }; emit()
    try {
      const body = JSON.parse(attempt.payload) as ManualIncidentInput & { asset_id: string; expected_asset_version: number }
      if(turn!==epoch||disposed||controller.signal.aborted)return false
      const raw = await client.request<unknown>('/manual-incidents', { method: 'POST', body, idempotencyKey: attempt.key, signal: controller.signal }); if (turn !== epoch || controller.signal.aborted) return false
      const value = readManualIncident(raw, scope)
      if (value.registered_by !== scope.actorId || value.asset_id !== attempt.asset.id || value.asset_name_at_registration !== attempt.asset.name || value.observed_asset_version !== attempt.asset.version || value.title !== body.title || value.severity !== body.severity) throw bad()
      state = { ...state, receipt: value, verifiedReceipt: null, attempt: { ...attempt, state: 'accepted' }, editing: false, busy: null }; emit(); if(turn===epoch&&!disposed&&!controller.signal.aborted)await refreshReceipt(); return true
    } catch (error) {
      if (turn !== epoch) return false
      const uncertain = retry || isCancelled(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500 || error.code.startsWith('INVALID_')
      state = { ...state, error, attempt: { ...attempt, state: uncertain ? 'uncertain' : 'rejected' } }; return false
    } finally { if (turn === epoch) { state = { ...state, busy: null }; emit() } }
  }
  function submit(input: ManualIncidentInput) {
    if (!context || disposed || !state.editing || state.busy || state.attempt || !state.asset || !state.lastAssetRead) return Promise.resolve(false)
    if (!input || typeof input !== 'object' || Object.keys(input).length !== 2 || !validManualTitle(input.title) || !Object.hasOwn(manualSeverityLabels, input.severity)) throw new Error('请提供1至200字符的原始标题并明确选择合同严重级别，不会补默认值')
    if (state.lastAssetRead.version !== state.asset.version) { state = { ...state, error: new ApiError(409, 'VERSION_CONFLICT', '已发现关联资产新版本，请核对后明确采用新基准，当前输入保留', '—') }; emit(); return Promise.resolve(false) }
    const current = copy(state.asset)
    const turn=epoch,payload=JSON.stringify({title:input.title,severity:input.severity,asset_id:current.id,expected_asset_version:current.version})
    try{const key=newKey();if(turn!==epoch||disposed||!context||!state.editing||state.busy||state.attempt)return Promise.resolve(false);if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw Error('INVALID_KEY');return send({payload,key,asset:current,state:'uncertain'})}
    catch{if(turn===epoch&&!disposed){state={...state,error:new ApiError(0,'REQUEST_ID_UNAVAILABLE','无法创建有效请求标识；没有发送手工登记请求，请稍后重试','—')};emit()}return Promise.resolve(false)}
  }
  function canRetryRead(){return !!context&&!disposed&&!state.busy&&lastRead!==null}
  function cancelRead(){if(disposed||state.busy!=='read')return false;++epoch;abort?.abort();abort=undefined;state={...state,busy:null,error:null};emit();return true}
  async function retryRead(){if(!canRetryRead()||!lastRead)return false;const target=copy(lastRead);return target.kind==='SEARCH'?search(target.query,target.next,target):target.kind==='CHOOSE'?choose(target.baseline.id,target.baseline):target.kind==='ASSET'?refreshAsset(target.baseline):refreshReceipt(target.baseline)}
  emit(); return { reset, begin, search:(query:string,next=false)=>search(query,next), choose:(id:string)=>choose(id), refreshAsset:()=>refreshAsset(), adoptLatestAsset, refreshReceipt:()=>refreshReceipt(), cancelRead, retryRead, canRetryRead, submit, retry: () => state.attempt?.state === 'uncertain' ? send(state.attempt, true) : Promise.resolve(false), unresolved, state: () => detachedWorkspaceState(state), dispose: () => {disposed=true;reset(null)} }
}
