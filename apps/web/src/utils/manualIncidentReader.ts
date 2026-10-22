import { detachedWorkspaceState } from './detachedWorkspaceState'
import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { worklogInstant } from './worklogWorkspace'
export const manualSeverityLabels = { disaster: '灾难', high: '严重', average: '一般', warning: '警告', info: '信息' } as const
export type ManualSeverity = keyof typeof manualSeverityLabels
export interface ManualIncidentContext { tenantId: string; actorId: string; permissionVersion: number }
export interface ManualIncidentEntry { id: string; tenant_id: string; asset_id: string; asset_name_at_registration: string; observed_asset_version: number; title: string; severity: ManualSeverity; severity_label: string; registered_by: string; registered_at: string; source_state: 'MANUAL'; registration_status: 'REGISTERED_ONLY'; verification_status: 'SELF_REPORTED'; monitor_state: null; disposition_state: null; ticket_priority: null; observed_at: null; recovered_at: null; manual_recovery_status: 'NOT_CONFIRMED'; monitoring_recovery_applicable: false; ticket_automatically_created: false; source: 'postgresql'; record_source: 'MANUAL_INCIDENT_REGISTRATION'; immutable: true }
export interface ManualIncidentPage { items: ManualIncidentEntry[]; has_more: boolean; next_before_registered_at: string | null; next_before_id: string | null; source: 'postgresql'; scope: 'CURRENT_TENANT_INTERNAL_MANUAL_REGISTRATIONS'; page_size_limit: 100; pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY' }
export interface ManualIncidentReadState { page: ManualIncidentPage | null; selected: ManualIncidentEntry | null; busy: boolean; error: unknown; paged: boolean }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const version = (value: unknown) => Number.isSafeInteger(value) && Number(value) > 0
const exact = (value: unknown, keys: string[]): value is Record<string, unknown> => Boolean(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const bad = () => new ApiError(200, 'INVALID_MANUAL_INCIDENT_RESPONSE', '手工登记范围、来源或未知状态回读异常，相关显示已清除', '—')
const date = (value: unknown) => { try { if (typeof value !== 'string' || !value.endsWith('Z')) throw bad(); return worklogInstant(value) } catch { throw bad() } }
export function validManualTitle(value: unknown): value is string { return typeof value === 'string' && Boolean(value.trim()) && Array.from(value).length <= 200 && !/[\u0000-\u001f\u007f-\u009f]/.test(value) && !Array.from(value).some(c => c.length === 1 && /[\ud800-\udfff]/.test(c)) }
export function readManualIncident(value: unknown, context: ManualIncidentContext): ManualIncidentEntry {
  const keys = ['id', 'tenant_id', 'asset_id', 'asset_name_at_registration', 'observed_asset_version', 'title', 'severity', 'severity_label', 'registered_by', 'registered_at', 'source_state', 'registration_status', 'verification_status', 'monitor_state', 'disposition_state', 'ticket_priority', 'observed_at', 'recovered_at', 'manual_recovery_status', 'monitoring_recovery_applicable', 'ticket_automatically_created', 'source', 'record_source', 'immutable']
  if (!exact(value, keys) || value.tenant_id !== context.tenantId || !['id', 'asset_id', 'registered_by'].every(key => typeof value[key] === 'string' && uuid.test(value[key])) || !version(value.observed_asset_version) || typeof value.asset_name_at_registration !== 'string' || !value.asset_name_at_registration.length || Array.from(value.asset_name_at_registration).length > 100 || !validManualTitle(value.title) || typeof value.severity !== 'string' || !Object.hasOwn(manualSeverityLabels, value.severity) || value.severity_label !== manualSeverityLabels[value.severity as ManualSeverity] || value.source_state !== 'MANUAL' || value.registration_status !== 'REGISTERED_ONLY' || value.verification_status !== 'SELF_REPORTED' || value.manual_recovery_status !== 'NOT_CONFIRMED' || value.monitoring_recovery_applicable !== false || value.ticket_automatically_created !== false || value.source !== 'postgresql' || value.record_source !== 'MANUAL_INCIDENT_REGISTRATION' || value.immutable !== true || !['monitor_state', 'disposition_state', 'ticket_priority', 'observed_at', 'recovered_at'].every(key => value[key] === null)) throw bad()
  date(value.registered_at); return copy(value) as unknown as ManualIncidentEntry
}
export const emptyManualIncidentReadState = (): ManualIncidentReadState => ({ page: null, selected: null, busy: false, error: null, paged: false })
type ReadTarget={kind:'LIST';next:boolean;boundary:{time:string;id:string}|null;known:ManualIncidentEntry|null}|{kind:'DETAIL';id:string;known:ManualIncidentEntry[]}
function sameEntry(a:ManualIncidentEntry,b:ManualIncidentEntry){return Object.keys(a).every(key=>key==='registered_at'?date(a.registered_at)===date(b.registered_at):a[key as keyof ManualIncidentEntry]===b[key as keyof ManualIncidentEntry])}
export function createManualIncidentReader(client: Pick<ApiClient, 'request'>, changed: (value: ManualIncidentReadState) => void) {
  let scope: ManualIncidentContext | null = null, state = emptyManualIncidentReadState(), epoch = 0, disposed=false,lastRead:ReadTarget|null=null,knownDetail:ManualIncidentEntry|null=null, abort: AbortController | undefined
  const emit = () => changed(detachedWorkspaceState(state))
  function reset(next: ManualIncidentContext | null) { abort?.abort();abort=undefined;++epoch;lastRead=null;knownDetail=null; const valid = !disposed&&next && uuid.test(next.tenantId) && uuid.test(next.actorId) && version(next.permissionVersion); scope = valid ? { ...next! } : null; state = emptyManualIncidentReadState(); if (next && !valid && !disposed) state.error = bad(); emit() }
  async function run(action: (signal: AbortSignal, context: ManualIncidentContext) => Promise<Partial<ManualIncidentReadState>>, clear: Partial<ManualIncidentReadState>,target:ReadTarget) {
    if (!scope || disposed) return false
    abort?.abort(); const turn = ++epoch, controller = new AbortController(), context = { ...scope }; abort = controller;lastRead=copy(target); state = { ...state, ...clear, busy: true, error: null }; emit()
    try { if(turn!==epoch||disposed||controller.signal.aborted)return false;const result = await action(controller.signal, context); if (turn !== epoch || controller.signal.aborted) return false; state = { ...state, ...result };if(result.selected)knownDetail=copy(result.selected);lastRead=null; return true }
    catch (error) { if (turn === epoch && !isCancelled(error)) state = { ...state, ...clear, error }; return false }
    finally { if (turn === epoch) { state = { ...state, busy: false }; emit() } }
  }
  function list(next = false,recovery?:Extract<ReadTarget,{kind:'LIST'}>) {
    if (next && !recovery && (!state.page?.has_more || state.busy)) return Promise.resolve(false)
    const known=recovery?recovery.known:knownDetail, boundary = recovery?recovery.boundary:next ? { time: state.page!.next_before_registered_at!, id: state.page!.next_before_id! } : null
    return run(async (signal, context) => {
      const value = await client.request<unknown>('/manual-incidents?page_size=15' + (boundary ? '&before_registered_at=' + encodeURIComponent(boundary.time) + '&before_id=' + boundary.id : ''), { signal })
      if (!exact(value, ['items', 'has_more', 'next_before_registered_at', 'next_before_id', 'source', 'scope', 'page_size_limit', 'pagination_consistency']) || !Array.isArray(value.items) || value.items.length > 15 || typeof value.has_more !== 'boolean' || value.source !== 'postgresql' || value.scope !== 'CURRENT_TENANT_INTERNAL_MANUAL_REGISTRATIONS' || value.page_size_limit !== 100 || value.pagination_consistency !== 'CURRENT_REQUEST_SNAPSHOT_ONLY') throw bad()
      const items = value.items.map(item => readManualIncident(item, context)); if (new Set(items.map(item => item.id)).size !== items.length) throw bad(); let previous = boundary
      for (const item of items) { if (previous && (date(item.registered_at) > date(previous.time) || date(item.registered_at) === date(previous.time) && item.id >= previous.id)) throw bad(); previous = { time: item.registered_at, id: item.id } }
      if(known){const matched=items.find(item=>item.id===known.id);if(matched&&!sameEntry(known,matched))throw bad()}
      const tail = items.at(-1); if (value.has_more ? items.length !== 15 || value.next_before_registered_at !== tail?.registered_at || value.next_before_id !== tail?.id : value.next_before_registered_at !== null || value.next_before_id !== null) throw bad()
      return { page: copy({ ...value, items }) as unknown as ManualIncidentPage }
    }, { page: null, selected: null, paged: next },{kind:'LIST',next,boundary,known:known?copy(known):null})
  }
  function select(id:string,expected?:ManualIncidentEntry,recovery?:Extract<ReadTarget,{kind:'DETAIL'}>){
    if(!uuid.test(id))return Promise.resolve(false)
    const known=recovery?copy(recovery.known):[expected,state.selected?.id===id?state.selected:null,knownDetail?.id===id?knownDetail:null,state.page?.items.find(item=>item.id===id)].filter((item):item is ManualIncidentEntry=>Boolean(item)).map(copy)
    return run(async(signal,context)=>{
      for(const prior of known){if(readManualIncident(prior,context).id!==id)throw bad()}
      const value=readManualIncident(await client.request<unknown>('/manual-incidents/'+id,{signal}),context)
      if(value.id!==id||known.some(prior=>!sameEntry(prior,value)))throw bad();return{selected:value}
    },{selected:null},{kind:'DETAIL',id,known})
  }
  function canRetryRead(){return !!scope&&!disposed&&!state.busy&&lastRead!==null}
  function cancelRead(){if(disposed||!state.busy)return false;++epoch;abort?.abort();abort=undefined;state={...state,busy:false,error:null};emit();return true}
  async function retryRead(){if(!canRetryRead()||!lastRead)return false;const target=copy(lastRead);return target.kind==='LIST'?list(target.next,target):select(target.id,undefined,target)}
  function closeDetails(){if(disposed||state.busy)return false;if(lastRead?.kind==='DETAIL')lastRead=null;state={...state,selected:null,error:null};emit();return true}
  emit(); return { reset, list:(next=false)=>list(next), select:(id:string,expected?:ManualIncidentEntry)=>select(id,expected), cancelRead,retryRead,canRetryRead,closeDetails,state: () => detachedWorkspaceState(state), dispose: () => {disposed=true;reset(null)} }
}
