import {ApiError,isCancelled,type ApiClient} from '../api/client'
export interface AcceptanceScope {tenantId:string;actorId:string;permissionVersion:number;canRead:boolean}
export interface AcceptanceCounts {total:number;queued:number;processing:number;accepted:number;failed:number;unknown:number;cancelled:number}
export interface AcceptanceEntry {id:string;channel:'EMAIL'|'WECHAT_WORK';status:'QUEUED'|'PROCESSING'|'ACCEPTED'|'FAILED'|'UNKNOWN'|'CANCELLED';created_at:string;started_at:string|null;finished_at:string|null}
export interface AcceptanceSnapshot {tenant_id:string;actor_id:string;permission_version:number;observed_at:string;window_start:string;counts:AcceptanceCounts;by_channel:Record<'EMAIL'|'WECHAT_WORK',AcceptanceCounts>;items:AcceptanceEntry[];metric_version:'MOP_NOTIFICATION_ACCEPTANCE_V1';source:'POSTGRESQL_EMAIL_WECOM_SUBMISSION_LEDGER';population_basis:'BUSINESS_NOTIFICATIONS_CREATED_LAST_24H';verified_delivered:null;delivery_rate_pct:null;delivery_rate_below_95:null;delivery_verification_status:'AUTHENTICATED_DELIVERY_SOURCE_NOT_CONNECTED';unverified_declarations_counted_as_delivered:false;writes_performed:false}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const instant=(v:unknown):v is string=>typeof v==='string'&&/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(v)&&Number.isFinite(Date.parse(v))
const invalid=()=>new ApiError(200,'INVALID_NOTIFICATION_ACCEPTANCE','通知受理范围或统计未通过核对','—')
const statuses=['QUEUED','PROCESSING','ACCEPTED','FAILED','UNKNOWN','CANCELLED'] as const
export function acceptanceCounts(rows:AcceptanceEntry[]):AcceptanceCounts {const result={total:rows.length,queued:0,processing:0,accepted:0,failed:0,unknown:0,cancelled:0};for(const row of rows)result[row.status.toLowerCase() as Exclude<keyof AcceptanceCounts,'total'>]++;return result}
export function readAcceptance(raw:unknown,scope:AcceptanceScope):AcceptanceSnapshot {
 if(!exact(raw,['tenant_id','actor_id','permission_version','observed_at','window_start','counts','by_channel','items','metric_version','source','population_basis','verified_delivered','delivery_rate_pct','delivery_rate_below_95','delivery_verification_status','unverified_declarations_counted_as_delivered','writes_performed'])||raw.tenant_id!==scope.tenantId||raw.actor_id!==scope.actorId||raw.permission_version!==scope.permissionVersion||!instant(raw.observed_at)||!instant(raw.window_start)||Date.parse(raw.observed_at)-Date.parse(raw.window_start)!==86400000||raw.metric_version!=='MOP_NOTIFICATION_ACCEPTANCE_V1'||raw.source!=='POSTGRESQL_EMAIL_WECOM_SUBMISSION_LEDGER'||raw.population_basis!=='BUSINESS_NOTIFICATIONS_CREATED_LAST_24H'||raw.verified_delivered!==null||raw.delivery_rate_pct!==null||raw.delivery_rate_below_95!==null||raw.delivery_verification_status!=='AUTHENTICATED_DELIVERY_SOURCE_NOT_CONNECTED'||raw.unverified_declarations_counted_as_delivered!==false||raw.writes_performed!==false||!Array.isArray(raw.items)||raw.items.length>10000||!exact(raw.by_channel,['EMAIL','WECHAT_WORK']))throw invalid()
 const keys=new Set<string>(),from=Date.parse(raw.window_start),to=Date.parse(raw.observed_at)
 for(const row of raw.items){
  if(!exact(row,['id','channel','status','created_at','started_at','finished_at'])||!uuid(row.id)||!['EMAIL','WECHAT_WORK'].includes(String(row.channel))||!statuses.includes(row.status as typeof statuses[number])||!instant(row.created_at)||row.started_at!==null&&!instant(row.started_at)||row.finished_at!==null&&!instant(row.finished_at))throw invalid()
  const key=row.channel+':'+row.id;if(keys.has(key))throw invalid();keys.add(key)
  const created=Date.parse(row.created_at),start=row.started_at===null?null:Date.parse(row.started_at as string),finish=row.finished_at===null?null:Date.parse(row.finished_at as string)
  if(created<from||created>=to||start!==null&&(start<created||start>to)||finish!==null&&(start===null||finish<start||finish>to))throw invalid()
  if(row.status==='QUEUED'?start!==null||finish!==null:row.status==='PROCESSING'?start===null||finish!==null:start===null||finish===null)throw invalid()
 }
 const items=raw.items as unknown as AcceptanceEntry[]
 function check(actual:unknown,rows:AcceptanceEntry[]){const expected=acceptanceCounts(rows);if(!exact(actual,Object.keys(expected))||Object.entries(expected).some(([key,value])=>actual[key]!==value))throw invalid()}
 check(raw.counts,items);for(const channel of ['EMAIL','WECHAT_WORK'])check(raw.by_channel[channel],items.filter(row=>row.channel===channel))
 return structuredClone(raw) as unknown as AcceptanceSnapshot
}
export interface AcceptanceState {current:AcceptanceSnapshot|null;busy:boolean;error:unknown;cancelled:boolean;loadedAt:number}
export const emptyAcceptance=():AcceptanceState=>({current:null,busy:false,error:null,cancelled:false,loadedAt:0})
export function createAcceptanceReader(client:Pick<ApiClient,'request'>,changed:(value:AcceptanceState)=>void,clock=()=>performance.now()){
 let state=emptyAcceptance(),scope:AcceptanceScope|null=null,epoch=0,controller:AbortController|null=null,disposed=false
 const emit=()=>changed({...state}),clear=()=>{epoch++;controller?.abort();controller=null;state=emptyAcceptance()}
 return {
  reset(next:AcceptanceScope|null){clear();scope=!disposed&&next?.canRead&&uuid(next.tenantId)&&uuid(next.actorId)&&Number.isSafeInteger(next.permissionVersion)&&next.permissionVersion>0?{...next}:null;emit()},
  async load(){if(!scope||disposed||state.busy)return false;clear();const turn=epoch,expected={...scope},started=clock(),abort=new AbortController();controller=abort;state.busy=true;emit();try{const raw=await client.request('/dashboard/notification-acceptance',{method:'GET',signal:abort.signal});if(disposed||epoch!==turn||abort.signal.aborted)return false;state.current=readAcceptance(raw,expected);state.loadedAt=started;return true}catch(error){if(!disposed&&epoch===turn&&!isCancelled(error))state.error=error;return false}finally{if(!disposed&&epoch===turn){state.busy=false;controller=null;emit()}}},
  cancel(){if(!state.busy)return;clear();state.cancelled=true;emit()},dispose(){disposed=true;clear();scope=null;emit()},
 }
}
