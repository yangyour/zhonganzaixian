import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { readAssetDateAssessment, readServerReferenceDate, type AssetDateAssessment } from './assetDateReader'
import { worklogInstant } from './worklogWorkspace'
export type DateField = 'warranty_expiry' | 'overdue_service_date'
export type DateWindow = 'UNKNOWN' | 'PAST_DATE' | 'WITHIN_30_DAYS' | 'WITHIN_90_DAYS' | 'REFERENCE_QUARTER'
export interface DateQueryInput { date_field: DateField | null; window: DateWindow | null; name: string; site_id: string }
export interface DateQueryFilters { date_field: DateField; window: DateWindow; name?: string; site_id?: string }
export interface DateQueryItem { asset_id: string; name: string; created_at: string; assessment: AssetDateAssessment }
export interface DateQueryPage { items: DateQueryItem[]; has_more: boolean; next_before_created_at: string | null; next_before_id: string | null; next_expected_reference_date: string | null; date_field: DateField; window: DateWindow; filters: { name?: string; site_id?: string }; reference_date: string; observed_at: string; timezone: 'Asia/Shanghai'; source: 'postgresql'; scope: 'CURRENT_TENANT_INTERNAL_MANUAL_DATE_FILTER'; page_size_limit: 100; pagination_consistency: 'CURRENT_REQUEST_SNAPSHOT_ONLY'; metadata_change_semantics: 'NOT_FROZEN_ACROSS_PAGES' }
export interface DateQueryContext { tenantId: string; actorId: string; permissionVersion: number }
export interface DateQueryReadTarget { filters: DateQueryFilters; cursor: Cursor | null }
export interface DateQueryState { readTarget: DateQueryReadTarget | null; cancelledRead: boolean; page: DateQueryPage | null; filters: DateQueryFilters | null; busy: boolean; error: unknown; paged: boolean }
interface Cursor { time: string; id: string; day: string }
const copy = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T
const exact = (v: unknown, keys: string[]): v is Record<string, unknown> => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v,k)))
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const text = (v: unknown,max: number): v is string => typeof v === 'string' && Boolean(v.trim()) && Array.from(v).length <= max && !/[\u0000-\u001f\u007f-\u009f]/.test(v) && !Array.from(v).some(c => c.length===1 && /[\ud800-\udfff]/.test(c))
const invalid = () => new ApiError(200,'INVALID_DATE_QUERY_RESPONSE','日期筛选回读范围、窗口或分页不一致，旧结果已清除','—')
export function normalizeDateQuery(value: unknown): DateQueryFilters {
  if (!exact(value,['date_field','window','name','site_id']) || typeof value.date_field !== 'string' || !['warranty_expiry','overdue_service_date'].includes(value.date_field) || typeof value.window !== 'string' || !['UNKNOWN','PAST_DATE','WITHIN_30_DAYS','WITHIN_90_DAYS','REFERENCE_QUARTER'].includes(value.window) || typeof value.name !== 'string' || typeof value.site_id !== 'string' || value.name !== '' && !text(value.name,100) || value.site_id !== '' && !text(value.site_id,64)) throw new Error('请明确选择日期字段和窗口；名称最多100字，站点最多64字，不接受全空白或控制字符')
  return { date_field: value.date_field as DateField, window: value.window as DateWindow, ...(value.name ? {name:value.name} : {}), ...(value.site_id ? {site_id:value.site_id} : {}) }
}
function readPage(raw: unknown,scope: DateQueryContext,filters: DateQueryFilters,cursor: Cursor | null): DateQueryPage {
  const keys=['items','has_more','next_before_created_at','next_before_id','next_expected_reference_date','date_field','window','filters','reference_date','observed_at','timezone','source','scope','page_size_limit','pagination_consistency','metadata_change_semantics']
  const echo={...(filters.name?{name:filters.name}:{}),...(filters.site_id?{site_id:filters.site_id}:{})}
  if (!exact(raw,keys) || !Array.isArray(raw.items) || raw.items.length>15 || typeof raw.has_more!=='boolean' || raw.date_field!==filters.date_field || raw.window!==filters.window || !exact(raw.filters,Object.keys(echo)) || Object.entries(echo).some(([k,v])=>(raw.filters as Record<string,unknown>)[k]!==v) || raw.timezone!=='Asia/Shanghai' || raw.source!=='postgresql' || raw.scope!=='CURRENT_TENANT_INTERNAL_MANUAL_DATE_FILTER' || raw.page_size_limit!==100 || raw.pagination_consistency!=='CURRENT_REQUEST_SNAPSHOT_ONLY' || raw.metadata_change_semantics!=='NOT_FROZEN_ACROSS_PAGES') throw invalid()
  const day=readServerReferenceDate(raw.observed_at,raw.reference_date);if(cursor && day!==cursor.day)throw invalid()
  let previous=cursor;const seen=new Set<string>();const items=raw.items.map(value=>{
    if(!exact(value,['asset_id','name','created_at','assessment']) || typeof value.asset_id!=='string' || !uuid.test(value.asset_id) || seen.has(value.asset_id) || !text(value.name,100) || typeof value.created_at!=='string')throw invalid()
    const created=worklogInstant(value.created_at);if(previous && (created>worklogInstant(previous.time) || created===worklogInstant(previous.time)&&value.asset_id>=previous.id))throw invalid();previous={time:value.created_at,id:value.asset_id,day};seen.add(value.asset_id)
    const assessment=readAssetDateAssessment(value.assessment,{...scope,assetId:value.asset_id,minimumAssetVersion:1});if(assessment.observed_at!==raw.observed_at || assessment.as_of_date!==day)throw invalid()
    const date=assessment[filters.date_field],matches=filters.window==='UNKNOWN'?date.position==='UNKNOWN':filters.window==='PAST_DATE'?date.position==='PAST_DATE':filters.window==='WITHIN_30_DAYS'?date.within_30_days===true:filters.window==='WITHIN_90_DAYS'?date.within_90_days===true:date.in_reference_quarter===true;if(!matches)throw invalid()
    return {asset_id:value.asset_id,name:value.name,created_at:value.created_at,assessment}
  })
  if(raw.has_more){const last=items.at(-1);if(items.length!==15 || raw.next_before_id!==last?.asset_id || raw.next_before_created_at!==last?.created_at || raw.next_expected_reference_date!==day)throw invalid()}else if(raw.next_before_id!==null || raw.next_before_created_at!==null || raw.next_expected_reference_date!==null)throw invalid()
  return {...copy(raw),items} as unknown as DateQueryPage
}
export const emptyDateQueryState=():DateQueryState=>({page:null,filters:null,busy:false,error:null,paged:false,readTarget:null,cancelledRead:false})
export function createAssetDateQuery(client: Pick<ApiClient,'request'>,notify: (state: DateQueryState)=>void){
  let scope:DateQueryContext|null=null,generation=0,controller:AbortController|null=null,disposed=false,referenceDayChanged=false
  let state=emptyDateQueryState()
  const snapshot=()=>({...state,page:state.page?copy(state.page):null,filters:state.filters?copy(state.filters):null,readTarget:state.readTarget?copy(state.readTarget):null});const emit=()=>notify(snapshot())
  const cancel=()=>{referenceDayChanged=false;generation++;controller?.abort();controller=null;state={...state,page:null,error:null,busy:false}}
  async function load(rawFilters:DateQueryFilters,rawCursor:Cursor|null){
    if(!scope||disposed||state.busy)return false
    const filters=copy(rawFilters),cursor=rawCursor?copy(rawCursor):null;cancel()
    const current=generation,context=scope,abort=new AbortController();controller=abort
    state={...state,filters,paged:Boolean(cursor),busy:true,readTarget:{filters:copy(filters),cursor},cancelledRead:false};emit()
    if(current!==generation||disposed||scope!==context||abort.signal.aborted)return false
    const query=new URLSearchParams({...filters,page_size:'15',...(cursor?{before_created_at:cursor.time,before_id:cursor.id,expected_reference_date:cursor.day}:{})})
    try{const raw=await client.request('/assets/date-assessments?'+query,{signal:abort.signal});if(current!==generation||disposed||scope!==context||abort.signal.aborted)return false;state.page=readPage(raw,context,filters,cursor);return true}
    catch(error){if(current===generation&&!disposed){if(isCancelled(error))state.cancelledRead=true;else {state.error=error;referenceDayChanged=error instanceof ApiError&&error.status===409&&error.code==='DATE_REFERENCE_CHANGED'}}return false}
    finally{if(current===generation&&!disposed){state.busy=false;controller=null;emit()}}
  }
  const canRetryRead=()=>!disposed&&!!scope&&!state.busy&&!!state.readTarget&&!!(state.error||state.cancelledRead)&&!referenceDayChanged
  function retryRead(){const target=state.readTarget;return canRetryRead()&&target?load(target.filters,target.cursor):Promise.resolve(false)}
  function cancelRead(){if(disposed||!state.busy)return false;cancel();state.cancelledRead=true;emit();return true}
  return {state:snapshot,cancelRead,retryRead,canRetryRead,
    reset(context:DateQueryContext|null){cancel();scope=!disposed&&context&&uuid.test(context.tenantId)&&uuid.test(context.actorId)&&Number.isSafeInteger(context.permissionVersion)&&context.permissionVersion>0?{...context}:null;state=emptyDateQueryState();emit()},
    invalidate(){cancel();state=emptyDateQueryState();emit()},
    async apply(input:DateQueryInput){if(state.busy||disposed)return false;try{return await load(normalizeDateQuery(input),null)}catch(error){cancel();state={...emptyDateQueryState(),error};emit();return false}},
    refresh:()=>state.filters?load(state.filters,null):Promise.resolve(false),
    next:()=>state.filters&&state.page?.has_more?load(state.filters,{time:state.page.next_before_created_at!,id:state.page.next_before_id!,day:state.page.next_expected_reference_date!}):Promise.resolve(false),
    dispose(){cancel();disposed=true;scope=null;state=emptyDateQueryState();emit()}}
}
