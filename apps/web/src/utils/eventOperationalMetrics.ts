import {ApiError,isCancelled,type ApiClient} from '../api/client'
import {utcInstantNanos} from './utcInstant'
export const eventMetricWindows=['HOURS_24','DAYS_7','DAYS_30'] as const
export type EventMetricWindow=typeof eventMetricWindows[number]
export type EventMetricView='CURRENT_PROBLEMS'|'RECENT_PROBLEMS'
export const eventSeverities=['DISASTER','HIGH','AVERAGE','WARNING','INFO'] as const
export interface EventMetricContext{tenantId:string;actorId:string;permissionVersion:number;canRead:boolean}
export interface EventMetricCursor{before:string;before_id:string}
export interface EventMetricItem{event_id:string;asset_id:string;version:number;rule_name:string;severity:typeof eventSeverities[number];monitor_state:'PROBLEM'|'RECOVERED';handling_state:string;suppression_state:string;problem_at:string;first_ack_at:string|null;updated_at:string;acknowledgement_state:string;elapsed_millis:string|null}
export interface EventMetricSnapshot{
 tenant_id:string;actor_id:string;permission_version:number;observed_at:string;timezone:'Asia/Shanghai';window:EventMetricWindow;view:EventMetricView;period:{from:string;to:string};metric_version:'mop-event-acknowledgement-v1';statistics_version:'mop-elapsed-statistics-v1'
 current_problems:{total:number;by_severity:Record<typeof eventSeverities[number],number>;population_basis:'PROBLEM_AND_SUPPRESSION_NONE'}
 mtta:{status:'CALCULATED'|'NO_ACKNOWLEDGED_SAMPLES'|'INVALID_SOURCE_TIMESTAMPS'|'SAMPLE_BUDGET_EXCEEDED';population_count:number;sample_count:number|null;unacknowledged_count:number|null;invalid_count:number|null;mean_millis:string|null;median_millis:string|null;p95_millis:string|null;target_millis:300000;target_breached:boolean|null;source_manifest_sha256:string|null;population_basis:'PROBLEM_AT_IN_HALF_OPEN_WINDOW_ALL_SUPPRESSION_STATES';timestamp_source:'RECORDED_FIRST_ACK_AT_NOT_TICKET_ACCEPTANCE';maximum_samples:10000}
 items:EventMetricItem[];next:EventMetricCursor|null;source:'CURRENT_AUTHORIZED_POSTGRESQL_EVENT_PROJECTION';collection_freshness_verified:false;clock_accuracy_verified:false;frozen_report:false;writes_performed:false
}
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const count=(v:unknown):v is number=>Number.isSafeInteger(v)&&(v as number)>=0
const time=(v:unknown)=>{if(typeof v!=='string')throw bad();const n=utcInstantNanos(v);if(n===null||n%1000000n!==0n)throw bad();return n}
const decimal=(v:unknown):v is string=>typeof v==='string'&&/^(0|[1-9][0-9]{0,15})(\.[0-9]{1,40})?$/.test(v)&&Number.isFinite(Number(v))&&Number(v)<=Number.MAX_SAFE_INTEGER
const exceedsTarget=(v:string)=>{const [whole,fraction='']=v.split('.');return BigInt(whole!)>300000n||BigInt(whole!)===300000n&&/[1-9]/.test(fraction)}
const bad=()=>new ApiError(200,'INVALID_EVENT_METRICS','事件指标的范围、同源计数或时间字段未通过核对','—')
export function readEventMetrics(raw:unknown,scope:EventMetricContext,window:EventMetricWindow,view:EventMetricView):EventMetricSnapshot{
 if(!exact(raw,['tenant_id','actor_id','permission_version','observed_at','timezone','window','period','metric_version','statistics_version','current_problems','mtta','view','items','next','source','collection_freshness_verified','clock_accuracy_verified','frozen_report','writes_performed'])||raw.tenant_id!==scope.tenantId||raw.actor_id!==scope.actorId||raw.permission_version!==scope.permissionVersion||raw.window!==window||raw.view!==view||raw.timezone!=='Asia/Shanghai'||raw.metric_version!=='mop-event-acknowledgement-v1'||raw.statistics_version!=='mop-elapsed-statistics-v1'||raw.source!=='CURRENT_AUTHORIZED_POSTGRESQL_EVENT_PROJECTION'||raw.collection_freshness_verified!==false||raw.clock_accuracy_verified!==false||raw.frozen_report!==false||raw.writes_performed!==false)throw bad()
 const observed=time(raw.observed_at),hours=window==='HOURS_24'?24:window==='DAYS_7'?168:720
 if(!exact(raw.period,['from','to'])||time(raw.period.to)!==observed||time(raw.period.from)!==observed-BigInt(hours)*3600000000000n)throw bad()
 const c=raw.current_problems;if(!exact(c,['total','by_severity','population_basis'])||c.population_basis!=='PROBLEM_AND_SUPPRESSION_NONE'||!count(c.total)||!exact(c.by_severity,[...eventSeverities])||!Object.values(c.by_severity).every(count)||Object.values(c.by_severity).reduce((a,b)=>Number(a)+Number(b),0)!==c.total)throw bad()
 const m=raw.mtta;if(!exact(m,['status','population_count','sample_count','unacknowledged_count','invalid_count','mean_millis','median_millis','p95_millis','target_millis','target_breached','source_manifest_sha256','population_basis','timestamp_source','maximum_samples'])||!count(m.population_count)||!['CALCULATED','NO_ACKNOWLEDGED_SAMPLES','INVALID_SOURCE_TIMESTAMPS','SAMPLE_BUDGET_EXCEEDED'].includes(String(m.status))||m.target_millis!==300000||m.maximum_samples!==10000||m.population_basis!=='PROBLEM_AT_IN_HALF_OPEN_WINDOW_ALL_SUPPRESSION_STATES'||m.timestamp_source!=='RECORDED_FIRST_ACK_AT_NOT_TICKET_ACCEPTANCE')throw bad()
 if(m.status==='SAMPLE_BUDGET_EXCEEDED'){if(m.population_count<=10000||[m.sample_count,m.unacknowledged_count,m.invalid_count,m.mean_millis,m.median_millis,m.p95_millis,m.target_breached,m.source_manifest_sha256].some(x=>x!==null))throw bad()}
 else {if(m.population_count>10000||!count(m.sample_count)||!count(m.unacknowledged_count)||!count(m.invalid_count)||m.sample_count+m.unacknowledged_count+m.invalid_count!==m.population_count||typeof m.source_manifest_sha256!=='string'||!/^[a-f0-9]{64}$/.test(m.source_manifest_sha256))throw bad()
  if(m.status==='CALCULATED'){if(m.sample_count<1||m.invalid_count!==0||!decimal(m.mean_millis)||!decimal(m.median_millis)||!decimal(m.p95_millis)||m.p95_millis.includes('.')||m.target_breached!==exceedsTarget(m.mean_millis))throw bad()}
  else if([m.mean_millis,m.median_millis,m.p95_millis,m.target_breached].some(v=>v!==null)||m.status==='NO_ACKNOWLEDGED_SAMPLES'&&(m.sample_count!==0||m.invalid_count!==0)||m.status==='INVALID_SOURCE_TIMESTAMPS'&&m.invalid_count<1)throw bad()
 }
 if(!Array.isArray(raw.items)||raw.items.length>50||raw.items.length>(view==='CURRENT_PROBLEMS'?c.total:m.population_count))throw bad()
 const ids=new Set<string>();let previous:EventMetricItem|null=null
 for(const item of raw.items){if(!exact(item,['event_id','asset_id','version','rule_name','severity','monitor_state','handling_state','suppression_state','problem_at','first_ack_at','updated_at','acknowledgement_state','elapsed_millis'])||!uuid(item.event_id)||!uuid(item.asset_id)||ids.has(item.event_id)||!count(item.version)||item.version<1||typeof item.rule_name!=='string'||!item.rule_name.trim()||!eventSeverities.includes(item.severity as typeof eventSeverities[number])||!['PROBLEM','RECOVERED'].includes(String(item.monitor_state))||!['NEW','ACKNOWLEDGED','IN_PROGRESS','PENDING_REVIEW','RESOLVED','CLOSED'].includes(String(item.handling_state))||!['NONE','FLAP_SILENCED','DEPENDENCY_SUPPRESSED','MAINTENANCE_SILENCED','INFO_DEFAULT_SILENT'].includes(String(item.suppression_state)))throw bad()
  const problem=time(item.problem_at),ack=item.first_ack_at===null?null:time(item.first_ack_at);time(item.updated_at)
  if(view==='CURRENT_PROBLEMS'&&(item.monitor_state!=='PROBLEM'||item.suppression_state!=='NONE')||view==='RECENT_PROBLEMS'&&(problem<time(raw.period.from)||problem>=observed))throw bad()
  const state=ack===null?item.handling_state==='NEW'?'UNACKNOWLEDGED':'MISSING_ACK_TIME':ack<problem||ack>observed?'INVALID_ACK_TIME':'RECORDED_ACK_TIME'
  if(item.acknowledgement_state!==state||item.elapsed_millis!==(state==='RECORDED_ACK_TIME'?((ack!-problem)/1000000n).toString():null)||previous&&(problem>time(previous.problem_at)||problem===time(previous.problem_at)&&item.event_id>=previous.event_id))throw bad()
  ids.add(item.event_id);previous=item as unknown as EventMetricItem
 }
 if(raw.next!==null&&(!exact(raw.next,['before','before_id'])||raw.items.length!==50||raw.next.before_id!==previous?.event_id||raw.next.before!==previous?.problem_at))throw bad()
 return structuredClone(raw) as unknown as EventMetricSnapshot
}
export interface EventMetricState{current:EventMetricSnapshot|null;busy:boolean;error:unknown;cancelled:boolean}
export const emptyEventMetrics=():EventMetricState=>({current:null,busy:false,error:null,cancelled:false})
export function createEventMetricReader(client:Pick<ApiClient,'request'>,changed:(s:EventMetricState)=>void){
 let context:EventMetricContext|null=null,epoch=0,controller:AbortController|null=null,disposed=false;let state=emptyEventMetrics()
 const emit=()=>changed({...state}),clear=()=>{epoch++;controller?.abort();controller=null;state=emptyEventMetrics()}
 function reset(next:EventMetricContext|null){clear();context=!disposed&&next?.canRead&&uuid(next.tenantId)&&uuid(next.actorId)&&count(next.permissionVersion)&&next.permissionVersion>0?{...next}:null;emit()}
 async function load(window:EventMetricWindow='HOURS_24',view:EventMetricView='RECENT_PROBLEMS',before:EventMetricCursor|null=null){
  if(!context||disposed||state.busy)return false
  clear();const turn=epoch,scope={...context},abort=new AbortController();controller=abort;state.busy=true;emit()
  try{if(turn!==epoch||disposed||abort.signal.aborted)return false
   if(!eventMetricWindows.includes(window)||!['CURRENT_PROBLEMS','RECENT_PROBLEMS'].includes(view)||before&&(!uuid(before.before_id)||time(before.before)===null))throw bad()
   const query=new URLSearchParams({window,view,...before});const raw=await client.request('/metrics/monitoring-events?'+query,{method:'GET',signal:abort.signal})
   if(turn!==epoch||disposed||abort.signal.aborted)return false
   state.current=readEventMetrics(raw,scope,window,view);return true
  }catch(e){if(turn===epoch&&!disposed&&!isCancelled(e))state.error=e;return false}finally{if(turn===epoch&&!disposed){state.busy=false;controller=null;emit()}}
 }
 return{reset,load,cancel(){if(!state.busy)return;clear();state.cancelled=true;emit()},dispose(){clear();disposed=true;context=null;emit()}}
}
export function elapsedMetricLabel(value:string|null){if(value===null)return '—';const seconds=Number(value)/1000;return seconds<60?seconds.toLocaleString('zh-CN',{maximumFractionDigits:2})+' 秒':(seconds/60).toLocaleString('zh-CN',{maximumFractionDigits:2})+' 分钟'}
