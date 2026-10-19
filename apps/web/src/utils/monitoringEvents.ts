import {type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportTime,exportUuid} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'

/**
 * Monitoring Event Center, Deduplication (×N), Flap Silence, Dependency Suppression,
 * Maintenance Windows, Quarantine Decisions & Auto-Ticket Attempts (D15 / V37: M1-2, M4-7..M4-11, M4-14, M5-4).
 */
export type EventSeverity='DISASTER'|'HIGH'|'AVERAGE'|'WARNING'|'INFO'
export type MonitorState='PROBLEM'|'RECOVERED'
export type HandlingState='NEW'|'ACKNOWLEDGED'|'IN_PROGRESS'|'RESOLVED'|'CLOSED'
export type SuppressionState='NONE'|'FLAP_SILENCED'|'DEPENDENCY_SUPPRESSED'|'MAINTENANCE_SILENCED'
export type NotificationEmptyState=
 |'NORMAL_DISPATCH_ELIGIBLE'
 |'SILENCED_NO_NOTIFICATION'
 |'RECOVERED_within_2MIN_CANCELLED'
 |'BELOW_SUBSCRIPTION_LEVEL_UNSENT'
export type NoiseLayer=
 |'RAW_INTAKE'
 |'MERGED_DEDUPLICATED'
 |'FLAP_SILENCED'
 |'DEPENDENCY_SUPPRESSED'
 |'MAINTENANCE_SILENCED'
 |'EFFECTIVE_ALERT'
export type AutoTicketStatus='NOT_REQUIRED'|'RETRY_SCHEDULED'|'CREATED'|'QUARANTINED_MANUAL_REQUIRED'|'MANUAL_CREATED'
export type FunnelWindow='24h'|'7d'|'30d'

export interface MonitoringEventSummary {
 id:string;tenant_id:string;asset_id:string;version:number;
 rule_code:string;rule_name:string;severity:EventSeverity;severity_rank:number;
 fingerprint:string;occurrence_count:number;flap_flip_count:number;flap_silenced_until:string|null;
 monitor_state:MonitorState;handling_state:HandlingState;
 suppression_state:SuppressionState;suppression_reason:string|null;
 notification_empty_state:NotificationEmptyState;notification_empty_state_label:string;
 sla_unknown_marked:boolean;review_policy_status:'CONFIG_PENDING_Q29';
 problem_at:string;last_occurred_at:string;recovered_at:string|null;
 first_ack_at:string|null;resolved_at:string|null;closed_at:string|null;
 root_cause_note:string|null;ticket_id:string|null;updated_by:string;updated_at:string
}

export interface MonitoringEventMember {
 id:string;tenant_id:string;event_id:string;raw_event_id:string|null;
 sequence_no:number;source_event_id:string;source_phase:MonitorState;
 occurred_at:string;merged_within_window:boolean;recorded_at:string
}

export interface MonitoringNoiseRecord {
 id:string;tenant_id:string;event_id:string|null;
 layer:NoiseLayer;detail:string;recorded_at:string
}

export interface MonitoringNoiseFunnel {
 tenant_id:string;window:FunnelWindow;
 raw_intake_count:number;merged_deduplicated_count:number;flap_silenced_count:number;
 dependency_suppressed_count:number;maintenance_silenced_count:number;effective_alert_count:number;
 observed_at:string
}

export interface MaintenanceWindowEntry {
 id:string;tenant_id:string;version:number;name:string;asset_id:string|null;
 starts_at:string;ends_at:string;
 silence_mode:'SUPPRESS_NOTIFY'|'SILENCE_ALL_EXCEPT_DISASTER';
 status:'SCHEDULED'|'ACTIVE'|'ENDED'|'CANCELLED';
 sla_exclusion_requested:boolean;sla_exclusion_confirmed:boolean;
 sla_exclusion_confirmed_by:string|null;sla_exclusion_confirmed_at:string|null;
 reason:string;created_by:string;updated_by:string;updated_at:string
}

export interface MonitoringQuarantineItem {
 quarantine_id:string;pool_id:string;source_event_id:string;source_phase:'PROBLEM'|'RECOVERY';
 claimed_tenant_id:string|null;claimed_asset_id:string|null;quarantine_reason:string;
 occurred_at:string;received_at:string;quarantine_status:string;
 decision:'BOUND'|'DISCARDED'|null;bound_asset_id:string|null;result_event_id:string|null;
 decision_reason:string|null;decided_by:string|null;decided_at:string|null
}

export interface AutoTicketAttemptEntry {
 id:string;tenant_id:string;event_id:string;version:number;
 attempt_count:number;max_retries:5;backoff_schedule_sec:'30,120,600,600,600';
 status:AutoTicketStatus;mapped_priority:'P1'|'P2'|'P3'|'P4';
 mapping_policy_status:'CONFIG_PENDING_Q14';
 ticket_id:string|null;next_retry_at:string|null;last_error:string|null;
 updated_by:string;updated_at:string
}

export interface MonitoringEventWorkspacePayload {
 tenant_id:string;
 events:MonitoringEventSummary[];
 noise_funnel:MonitoringNoiseFunnel;
 maintenance_windows:MaintenanceWindowEntry[];
 quarantine_items:MonitoringQuarantineItem[];
 autoticket_attempts:AutoTicketAttemptEntry[];
 review_policy_status:'CONFIG_PENDING_Q29';
 mapping_policy_status:'CONFIG_PENDING_Q14';
 observed_at:string;
 source:'postgresql'
}

export interface MonitoringEventDetailPayload {
 event:MonitoringEventSummary;
 members:MonitoringEventMember[];
 noise_records:MonitoringNoiseRecord[];
 autoticket_attempt:AutoTicketAttemptEntry|null;
 observed_at:string;
 source:'postgresql'
}

export interface MonitoringEventContext {
 tenantId:string;actorId:string;permissionVersion:number;canWriteInternal:boolean;canAdminConfirm:boolean
}
type WriteAttempt={path:string;method:'POST';key:string;body:Record<string,unknown>}

export interface MonitoringEventCenterState {
 workspace:MonitoringEventWorkspacePayload|null;
 detail:MonitoringEventDetailPayload|null;
 window:FunnelWindow;
 busy:'read'|'write'|null;
 error:unknown;
 attempt:WriteAttempt|null
}

export const emptyMonitoringEventCenterState=():MonitoringEventCenterState=>({
 workspace:null,detail:null,window:'24h',busy:null,error:null,attempt:null
})

const EVENT_KEYS=[
 'id','tenant_id','asset_id','version','rule_code','rule_name','severity','severity_rank',
 'fingerprint','occurrence_count','flap_flip_count','flap_silenced_until',
 'monitor_state','handling_state','suppression_state','suppression_reason',
 'notification_empty_state','notification_empty_state_label','sla_unknown_marked','review_policy_status',
 'problem_at','last_occurred_at','recovered_at','first_ack_at','resolved_at','closed_at',
 'root_cause_note','ticket_id','updated_by','updated_at'
] as const

const FUNNEL_KEYS=[
 'tenant_id','window','raw_intake_count','merged_deduplicated_count','flap_silenced_count',
 'dependency_suppressed_count','maintenance_silenced_count','effective_alert_count','observed_at'
] as const

function validContext(c:MonitoringEventContext|null):c is MonitoringEventContext{
 return Boolean(c&&exportUuid(c.tenantId)&&exportUuid(c.actorId)&&exportInteger(c.permissionVersion,1)&&typeof c.canWriteInternal==='boolean'&&typeof c.canAdminConfirm==='boolean')
}

export function readEventSummary(raw:unknown,expectedTenant:string):MonitoringEventSummary{
 if(!exportExact(raw,EVENT_KEYS))throw new Error('监控事件条目字段不匹配')
 if(!exportUuid(raw.id)||raw.tenant_id!==expectedTenant||!exportUuid(raw.asset_id))throw new Error('监控事件条目与当前租户不一致')
 if(!exportInteger(raw.version,1,9007199254740990)||!exportInteger(raw.occurrence_count,1,1000000))throw new Error('监控事件版本或去重次数无效')
 if(raw.review_policy_status!=='CONFIG_PENDING_Q29')throw new Error('监控事件复盘策略状态未保持CONFIG_PENDING_Q29')
 exportTime(raw.problem_at);exportTime(raw.last_occurred_at);exportTime(raw.updated_at)
 return structuredClone(raw) as unknown as MonitoringEventSummary
}

export function readNoiseFunnel(raw:unknown,expectedTenant:string):MonitoringNoiseFunnel{
 if(!exportExact(raw,FUNNEL_KEYS))throw new Error('告警降噪漏斗载荷无效')
 if(raw.tenant_id!==expectedTenant||!['24h','7d','30d'].includes(String(raw.window)))throw new Error('告警降噪漏斗窗口无效')
 exportTime(raw.observed_at)
 if(
  !exportInteger(raw.raw_intake_count,0,9007199254740990)||
  !exportInteger(raw.merged_deduplicated_count,0,9007199254740990)||
  !exportInteger(raw.flap_silenced_count,0,9007199254740990)||
  !exportInteger(raw.dependency_suppressed_count,0,9007199254740990)||
  !exportInteger(raw.maintenance_silenced_count,0,9007199254740990)||
  !exportInteger(raw.effective_alert_count,0,9007199254740990)
 )throw new Error('告警降噪漏斗计数无效')
 return structuredClone(raw) as unknown as MonitoringNoiseFunnel
}

export function readMonitoringWorkspace(raw:unknown,scope:MonitoringEventContext):MonitoringEventWorkspacePayload{
 const body=exportExact(raw,['data','trace_id'] as const)?raw.data:raw
 if(!validContext(scope)||!exportExact(body,[
  'tenant_id','events','noise_funnel','maintenance_windows','quarantine_items',
  'autoticket_attempts','review_policy_status','mapping_policy_status','observed_at','source'
 ] as const))throw new Error('监控事件中心响应结构无效')
 if(body.tenant_id!==scope.tenantId||body.source!=='postgresql')throw new Error('监控事件中心租户或来源不匹配')
 exportTime(body.observed_at)
 if(body.review_policy_status!=='CONFIG_PENDING_Q29'||body.mapping_policy_status!=='CONFIG_PENDING_Q14')throw new Error('监控事件中心Q14/Q29状态未保持CONFIG_PENDING')
 if(!Array.isArray(body.events)||!Array.isArray(body.maintenance_windows)||!Array.isArray(body.quarantine_items)||!Array.isArray(body.autoticket_attempts))
  throw new Error('监控事件中心列表字段无效')
 const events=body.events.map(e=>readEventSummary(e,scope.tenantId))
 const funnel=readNoiseFunnel(body.noise_funnel,scope.tenantId)
 return {...(structuredClone(body) as unknown as MonitoringEventWorkspacePayload),events,noise_funnel:funnel}
}

export function readMonitoringEventDetail(raw:unknown,scope:MonitoringEventContext,expectedEventId:string):MonitoringEventDetailPayload{
 const body=exportExact(raw,['data','trace_id'] as const)?raw.data:raw
 if(!validContext(scope)||!exportExact(body,['event','members','noise_records','autoticket_attempt','observed_at','source'] as const))
  throw new Error('监控事件抽屉详情结构无效')
 if(body.source!=='postgresql'||!Array.isArray(body.members)||!Array.isArray(body.noise_records))
  throw new Error('监控事件抽屉详情载荷无效')
 exportTime(body.observed_at)
 const event=readEventSummary(body.event,scope.tenantId)
 if(event.id!==expectedEventId)throw new Error('监控事件抽屉ID与请求不一致')
 return {...(structuredClone(body) as unknown as MonitoringEventDetailPayload),event}
}

export function createMonitoringEventWorkspace(
 client:{request<T>(path:string,options?:RequestOptions):Promise<T>},
 changed:(s:MonitoringEventCenterState)=>void,
 newKey=createRequestId
){
 let state=emptyMonitoringEventCenterState(),context:MonitoringEventContext|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>({...structuredClone({...state,error:null}),error:state.error}),emit=()=>changed(snapshot())

 function reset(next:MonitoringEventContext|null){
  epoch++;abort?.abort();abort=null
  context=!disposed&&validContext(next)?{...next}:null
  state=emptyMonitoringEventCenterState();emit()
 }

 async function load(window:FunnelWindow=state.window){
  if(disposed||!context||state.busy||state.attempt)return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,window,busy:'read',error:null};emit()
  try{
   const raw=await client.request(`/events?window=${encodeURIComponent(window)}&page_size=25`,{signal:controller.signal})
   if(disposed||turn!==epoch)return false
   const workspace=readMonitoringWorkspace(raw,scope)
   abort=null;state={...state,workspace,busy:null,error:null};emit()
   return true
  }catch(error){
   if(disposed||turn!==epoch)return false
   abort=null;state={...state,busy:null,error};emit()
   return false
  }
 }

 async function loadEventDetail(eventId:string){
  if(disposed||!context||state.busy||state.attempt||!exportUuid(eventId))return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,busy:'read',error:null};emit()
  try{
   const raw=await client.request(`/events/${encodeURIComponent(eventId)}`,{signal:controller.signal})
   if(disposed||turn!==epoch)return false
   const detail=readMonitoringEventDetail(raw,scope,eventId)
   abort=null;state={...state,detail,busy:null,error:null};emit()
   return true
  }catch(error){
   if(disposed||turn!==epoch)return false
   abort=null;state={...state,busy:null,error};emit()
   return false
  }
 }

 async function send(attempt:WriteAttempt,retry=false){
  const turn=++epoch,scope=context?{...context}:null
  state={...state,busy:'write',error:null,attempt};emit()
  try{
   await client.request(attempt.path,{method:attempt.method,idempotencyKey:attempt.key,body:attempt.body})
   if(disposed||turn!==epoch||!scope)return false
   state={...state,busy:null,error:null,attempt:null};emit()
   return await load(state.window)
  }catch(error){
   if(disposed||turn!==epoch)return false
   const keep=writeFailureUncertain(retry,error)
   state={...state,busy:null,error,attempt:keep?attempt:null};emit()
   return false
  }
 }

 async function submitTransition(eventId:string,input:{expected_version:number;action:'ACKNOWLEDGE'|'START_PROGRESS'|'RESOLVE'|'CLOSE';root_cause_note:string|null}){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return send({path:`/events/${encodeURIComponent(eventId)}/actions`,method:'POST',key:newKey(),body:{...input}})
 }

 async function submitMaintenanceWindow(input:{
  name:string;asset_id:string|null;starts_at:string;ends_at:string;
  silence_mode:'SUPPRESS_NOTIFY'|'SILENCE_ALL_EXCEPT_DISASTER';sla_exclusion_requested:boolean;reason:string
 }){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return send({path:'/maintenance-windows',method:'POST',key:newKey(),body:{...input}})
 }

 async function submitWindowAction(windowId:string,input:{
  expected_version:number;action:'EXTEND'|'END_EARLY'|'CANCEL'|'CONFIRM_SLA_EXCLUSION';new_ends_at:string|null
 }){
  if(disposed||!context||state.busy||state.attempt)return false
  if(input.action==='CONFIRM_SLA_EXCLUSION'&&!context.canAdminConfirm)return false
  if(input.action!=='CONFIRM_SLA_EXCLUSION'&&!context.canWriteInternal)return false
  return send({path:`/maintenance-windows/${encodeURIComponent(windowId)}/actions`,method:'POST',key:newKey(),body:{...input}})
 }

 async function submitQuarantineDecision(quarantineId:string,input:{
  decision:'BOUND'|'DISCARDED';bound_asset_id:string|null;reason:string
 }){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return send({path:`/monitoring-quarantine/${encodeURIComponent(quarantineId)}/decisions`,method:'POST',key:newKey(),body:{...input}})
 }

 async function submitManualAutoTicket(attemptId:string,input:{expected_version:number;reason:string}){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return send({path:`/autoticket-attempts/${encodeURIComponent(attemptId)}/manual-create`,method:'POST',key:newKey(),body:{...input}})
 }

 async function retry(){
  if(disposed||!context||state.busy||!state.attempt)return false
  return send(state.attempt,true)
 }

 return{
  state:snapshot,reset,load,loadEventDetail,submitTransition,submitMaintenanceWindow,
  submitWindowAction,submitQuarantineDecision,submitManualAutoTicket,retry,
  unresolved:()=>Boolean(state.busy==='write'||state.attempt),
  dispose(){disposed=true;epoch++;abort?.abort();abort=null}
 }
}
