import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {utcInstantNanos} from './utcInstant'
import type {EventMetricContext,EventMetricWindow} from './eventOperationalMetrics'
export const noiseLayers=['RAW_INTAKE','MERGE','FLAP','DEPENDENCY','MAINTENANCE','EFFECTIVE_ALERT'] as const
export type NoiseLayer=typeof noiseLayers[number]
export interface NoiseBucket{from:string;to:string;raw_count:number;after_fingerprint_merge_count:number;layers:Record<NoiseLayer,number>}
export interface NoiseMarker{record_id:string;event_id:string;rule_name:string;severity:string;recorded_at:string}
export interface NoiseTrend{tenant_id:string;actor_id:string;permission_version:number;observed_at:string;timezone:'Asia/Shanghai';window:EventMetricWindow;bucket_seconds:number;metric_version:'MOP_EVENT_NOISE_TREND_V1';period:{from:string;to:string};buckets:NoiseBucket[];totals:Record<NoiseLayer,number>;markers:NoiseMarker[];markers_truncated:boolean;marker_limit:8;event_metadata_basis:'CURRENT_EVENT_PROJECTION';final_notification_count:null;noise_reduction_percent:null;noise_reduction_status:'FINAL_NOTIFICATION_SOURCE_UNAVAILABLE';timestamp_basis:'NOISE_LEDGER_RECORDED_AT';source:'CURRENT_AUTHORIZED_NOISE_LEDGER';collection_freshness_verified:false;final_notifications_verified:false;writes_performed:false}
export interface NoiseRecordCursor{before:string;before_id:string}
export interface NoiseRecordQuery{from:string;to:string;cursor:NoiseRecordCursor|null;pageSize:number}
export interface NoiseRecordPage{tenant_id:string;actor_id:string;permission_version:number;from:string;to:string;observed_at:string;page_size:number;total_count:number;items:{record_id:string;event_id:string;recorded_at:string;detail:string}[];next:NoiseRecordCursor|null;layer:'RAW_INTAKE';timestamp_basis:'NOISE_LEDGER_RECORDED_AT';source:'CURRENT_AUTHORIZED_NOISE_LEDGER';snapshot_scope:'SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT';writes_performed:false}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const count=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0
const bad=()=>new ApiError(200,'INVALID_EVENT_NOISE_SOURCE','事件趋势或原始记录的范围、时间桶、计数未通过核对','—')
const time=(v:unknown)=>{if(typeof v!=='string')throw bad();const n=utcInstantNanos(v);if(n===null||n%1000000n!==0n)throw bad();return n}
const validScope=(s:EventMetricContext)=>s.canRead&&uuid(s.actorId)&&uuid(s.tenantId)&&count(s.permissionVersion)&&s.permissionVersion>0
const scopeMatches=(r:Record<string,unknown>,s:EventMetricContext)=>validScope(s)&&r.tenant_id===s.tenantId&&r.actor_id===s.actorId&&r.permission_version===s.permissionVersion
const layers=(raw:unknown):Record<NoiseLayer,number>=>{if(!exact(raw,[...noiseLayers])||!Object.values(raw).every(count))throw bad();return raw as Record<NoiseLayer,number>}
export function readNoiseTrend(raw:unknown,scope:EventMetricContext,window:EventMetricWindow):NoiseTrend{
 if(!exact(raw,['tenant_id','actor_id','permission_version','observed_at','timezone','window','bucket_seconds','metric_version','period','buckets','totals','markers','markers_truncated','marker_limit','event_metadata_basis','final_notification_count','noise_reduction_percent','noise_reduction_status','timestamp_basis','source','collection_freshness_verified','final_notifications_verified','writes_performed'])||!scopeMatches(raw,scope)||raw.window!==window||raw.timezone!=='Asia/Shanghai'||raw.metric_version!=='MOP_EVENT_NOISE_TREND_V1'||raw.final_notification_count!==null||raw.noise_reduction_percent!==null||raw.noise_reduction_status!=='FINAL_NOTIFICATION_SOURCE_UNAVAILABLE'||raw.timestamp_basis!=='NOISE_LEDGER_RECORDED_AT'||raw.source!=='CURRENT_AUTHORIZED_NOISE_LEDGER'||raw.collection_freshness_verified!==false||raw.final_notifications_verified!==false||raw.writes_performed!==false||raw.marker_limit!==8||raw.event_metadata_basis!=='CURRENT_EVENT_PROJECTION')throw bad()
 const [number,step]=window==='HOURS_24'?[24,3600]:window==='DAYS_7'?[28,21600]:[30,86400],observed=time(raw.observed_at),start=observed-BigInt(number!*step!)*1000000000n
 if(raw.bucket_seconds!==step||!exact(raw.period,['from','to'])||time(raw.period.to)!==observed||time(raw.period.from)!==start||!Array.isArray(raw.buckets)||raw.buckets.length!==number)throw bad()
 const totals=layers(raw.totals),sums=Object.fromEntries(noiseLayers.map(layer=>[layer,0n])) as Record<NoiseLayer,bigint>
 for(const [i,b] of raw.buckets.entries()){if(!exact(b,['from','to','raw_count','after_fingerprint_merge_count','layers'])||time(b.from)!==start+BigInt(i*step!)*1000000000n||time(b.to)!==start+BigInt((i+1)*step!)*1000000000n)throw bad();const values=layers(b.layers);if(values.MERGE>values.RAW_INTAKE||b.raw_count!==values.RAW_INTAKE||b.after_fingerprint_merge_count!==values.RAW_INTAKE-values.MERGE)throw bad();for(const layer of noiseLayers)sums[layer]+=BigInt(values[layer])}
 if(noiseLayers.some(layer=>sums[layer]!==BigInt(totals[layer])))throw bad()
 if(!Array.isArray(raw.markers)||raw.markers.length!==(window==='HOURS_24'?Math.min(8,totals.RAW_INTAKE):0)||raw.markers_truncated!==(window==='HOURS_24'&&totals.RAW_INTAKE>8))throw bad()
 const ids=new Set<string>();let prior:NoiseMarker|null=null
 for(const m of raw.markers){if(!exact(m,['record_id','event_id','rule_name','severity','recorded_at'])||!uuid(m.record_id)||!uuid(m.event_id)||ids.has(m.record_id)||typeof m.rule_name!=='string'||!m.rule_name.trim()||m.rule_name.length>128||!['DISASTER','HIGH','AVERAGE','WARNING','INFO'].includes(String(m.severity)))throw bad();const at=time(m.recorded_at);if(at<start||at>=observed||prior&&(at>time(prior.recorded_at)||at===time(prior.recorded_at)&&m.record_id>=prior.record_id))throw bad();ids.add(m.record_id);prior=m as unknown as NoiseMarker}
 return structuredClone(raw) as unknown as NoiseTrend
}
function validateQuery(q:NoiseRecordQuery){const from=time(q.from),to=time(q.to);if(from>=to||to-from>86400000000000n||!count(q.pageSize)||q.pageSize<1||q.pageSize>50||q.cursor&&(!uuid(q.cursor.before_id)||time(q.cursor.before)<from||time(q.cursor.before)>=to))throw bad()}
export function readNoiseRecords(raw:unknown,scope:EventMetricContext,q:NoiseRecordQuery):NoiseRecordPage{
 validateQuery(q);if(!exact(raw,['tenant_id','actor_id','permission_version','from','to','observed_at','page_size','total_count','items','next','layer','timestamp_basis','source','snapshot_scope','writes_performed'])||!scopeMatches(raw,scope)||time(raw.from)!==time(q.from)||time(raw.to)!==time(q.to)||time(raw.observed_at)<time(q.to)||raw.page_size!==q.pageSize||!count(raw.total_count)||raw.layer!=='RAW_INTAKE'||raw.timestamp_basis!=='NOISE_LEDGER_RECORDED_AT'||raw.source!=='CURRENT_AUTHORIZED_NOISE_LEDGER'||raw.snapshot_scope!=='SINGLE_READ_PAGE_NOT_GLOBAL_SNAPSHOT'||raw.writes_performed!==false||!Array.isArray(raw.items)||raw.items.length>q.pageSize||raw.items.length>raw.total_count)throw bad()
 if(!q.cursor&&(raw.items.length!==Math.min(q.pageSize,raw.total_count)||(raw.next!==null)!==(raw.total_count>q.pageSize)))throw bad()
 let previous=q.cursor;const ids=new Set<string>()
 for(const row of raw.items){if(!exact(row,['record_id','event_id','recorded_at','detail'])||!uuid(row.record_id)||!uuid(row.event_id)||ids.has(row.record_id)||typeof row.detail!=='string'||!row.detail.trim()||row.detail.length>500)throw bad();const at=time(row.recorded_at);if(at<time(q.from)||at>=time(q.to)||previous&&(at>time(previous.before)||at===time(previous.before)&&row.record_id>=previous.before_id))throw bad();ids.add(row.record_id);previous={before:row.recorded_at as string,before_id:row.record_id}}
 if(raw.next!==null&&(!exact(raw.next,['before','before_id'])||raw.items.length!==q.pageSize||raw.next.before!==previous?.before||raw.next.before_id!==previous?.before_id))throw bad()
 return structuredClone(raw) as unknown as NoiseRecordPage
}
export interface NoiseReadState<T,Q>{current:T|null;query:Q|null;busy:boolean;error:unknown;cancelled:boolean;stale:boolean}
export const emptyNoiseRead=<T,Q>():NoiseReadState<T,Q>=>({current:null,query:null,busy:false,error:null,cancelled:false,stale:false})
function resource<T,Q>(client:Pick<ApiClient,'request'>,parse:(raw:unknown,scope:EventMetricContext,q:Q)=>T,path:(q:Q)=>string,notify:(s:NoiseReadState<T,Q>)=>void,retainStale:boolean){
 let scope:EventMetricContext|null=null,state=emptyNoiseRead<T,Q>(),epoch=0,abort:AbortController|null=null,disposed=false
 const emit=()=>notify({...state}),clear=()=>{epoch++;abort?.abort();abort=null;state=emptyNoiseRead<T,Q>()}
 async function load(q:Q){if(!scope||disposed||state.busy)return false;const query=structuredClone(q),wasStale=state.stale;const prior=retainStale&&JSON.stringify(state.query)===JSON.stringify(q)?state.current:null;clear();const owner=epoch,identity={...scope},controller=new AbortController();abort=controller;state={current:prior,query,busy:true,error:null,cancelled:false,stale:prior!==null&&wasStale};emit()
  try{if(owner!==epoch||disposed||controller.signal.aborted)return false;const raw=await client.request(path(query),{method:'GET',signal:controller.signal});if(owner!==epoch||disposed||controller.signal.aborted)return false;state.current=parse(raw,identity,query);state.stale=false;return true}
  catch(error){if(owner===epoch&&!disposed&&!isCancelled(error)){state.error=error;const keep=prior!==null&&(error instanceof TypeError||error instanceof ApiError&&error.status>=500);state.current=keep?prior:null;state.stale=keep}return false}
  finally{if(owner===epoch&&!disposed){state.busy=false;abort=null;emit()}}
 }
 return{load,reset(next:EventMetricContext|null){clear();scope=!disposed&&next&&validScope(next)?{...next}:null;emit()},retry:()=>state.query?load(structuredClone(state.query)):Promise.resolve(false),cancel(){if(!state.busy)return;const q=state.query;clear();state.query=q;state.cancelled=true;emit()},dispose(){clear();scope=null;disposed=true;emit()}}
}
export const createNoiseTrendReader=(client:Pick<ApiClient,'request'>,notify:(s:NoiseReadState<NoiseTrend,EventMetricWindow>)=>void)=>resource(client,readNoiseTrend,window=>{if(!['HOURS_24','DAYS_7','DAYS_30'].includes(window))throw bad();return '/metrics/event-noise-trend?window='+window},notify,true)
export const createNoiseRecordsReader=(client:Pick<ApiClient,'request'>,notify:(s:NoiseReadState<NoiseRecordPage,NoiseRecordQuery>)=>void)=>resource(client,readNoiseRecords,q=>{validateQuery(q);return '/metrics/event-noise-trend/records?'+new URLSearchParams({from:q.from,to:q.to,page_size:String(q.pageSize),...q.cursor})},notify,false)
