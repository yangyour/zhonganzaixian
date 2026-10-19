import {type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportUuid} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'
import {isUtcInstant,utcInstantNanos} from './utcInstant'

/**
 * Monitoring Event Center, Deduplication (×N), Flap Silence, Dependency Suppression,
 * Maintenance Windows, Quarantine Decisions & Auto-Ticket Attempts (D15 / V37: M1-2, M4-7..M4-11, M4-14, M5-4).
 */
export type EventSeverity='DISASTER'|'HIGH'|'AVERAGE'|'WARNING'|'INFO'
export type MonitorState='PROBLEM'|'RECOVERED'
export type HandlingState='NEW'|'ACKNOWLEDGED'|'IN_PROGRESS'|'PENDING_REVIEW'|'RESOLVED'|'CLOSED'
export type SuppressionState='NONE'|'FLAP_SILENCED'|'DEPENDENCY_SUPPRESSED'|'MAINTENANCE_SILENCED'|'INFO_DEFAULT_SILENT'
export type NotificationEmptyState=
 |'NORMAL_DISPATCH_ELIGIBLE'|'DEPENDENCY_SUPPRESSED_NO_NOTIFY'|'MAINTENANCE_WINDOW_SUPPRESSED'
 |'FLAP_SILENCED_NO_NOTIFY'|'INFO_LEVEL_NO_NOTIFY'
export type NoiseLayer='RAW_INTAKE'|'MERGE'|'FLAP'|'DEPENDENCY'|'MAINTENANCE'|'EFFECTIVE_ALERT'
export type AutoTicketStatus='SUCCEEDED'|'RETRY_SCHEDULED'|'QUARANTINED_MANUAL_REQUIRED'|'MANUAL_CREATED'|'SUPPRESSED_SKIPPED'
export type EventTransition='ACKNOWLEDGE'|'START'|'MARK_PENDING_REVIEW'|'RESOLVE'|'CLOSE'
const notificationLabels:Record<NotificationEmptyState,string>={
 NORMAL_DISPATCH_ELIGIBLE:'符合派发条件（外部渠道待Q14/Q18配置）',
 DEPENDENCY_SUPPRESSED_NO_NOTIFY:'依赖抑制：未发送通知',
 MAINTENANCE_WINDOW_SUPPRESSED:'维护窗口：通知已抑制',
 FLAP_SILENCED_NO_NOTIFY:'抖动静默：5分钟内不再重复通知',
 INFO_LEVEL_NO_NOTIFY:'无通知记录（信息级默认不通知）',
}
export type FunnelWindow='24h'|'7d'|'30d'

export interface MonitoringEventSummary {
 id:string;tenant_id:string;asset_id:string;version:number;
 rule_code:string;rule_name:string;severity:EventSeverity;severity_rank:number;
 fingerprint:string;occurrence_count:number;flap_flip_count:number;flap_silenced_until:string|null;
 monitor_state:MonitorState;handling_state:HandlingState;
 suppression_state:SuppressionState;suppression_reason:string|null;
 notification_empty_state:NotificationEmptyState;notification_empty_state_label:string;
 sla_unknown_marked:boolean;review_policy_status:'CONFIG_PENDING_Q29'|'PUBLISHED_Q29';
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
 id:string;tenant_id:string;version:number;name:string;asset_id:string;
 starts_at:string;ends_at:string;
 silence_mode:'SUPPRESS_NOTIFY'|'PAUSE_COLLECTION';
 status:'SCHEDULED'|'ACTIVE'|'ENDED'|'CANCELLED';
 sla_exclusion_requested:boolean;sla_exclusion_confirmed:boolean;
 sla_exclusion_confirmed_by:string|null;sla_exclusion_confirmed_at:string|null;
 reason:string;created_by:string;updated_by:string;updated_at:string
}

export interface MonitoringQuarantineItem {
 quarantine_id:string;pool_id:string;source_event_id:string;source_phase:MonitorState;
 claimed_tenant_id:string|null;claimed_asset_id:string|null;quarantine_reason:'UNKNOWN_MAPPING'|'CROSS_TENANT_CLAIM'|'SOURCE_KEY_CONFLICT';
 occurred_at:string;received_at:string;quarantine_status:'HELD_UNVERIFIED';
 decision:'BIND_ASSET'|'DISCARD'|null;bound_asset_id:string|null;result_event_id:string|null;
 decision_reason:string|null;decided_by:string|null;decided_at:string|null
}

export interface AutoTicketAttemptEntry {
 id:string;tenant_id:string;event_id:string;version:number;
 attempt_count:number;max_retries:5;backoff_schedule_sec:'30,120,600,600,600';
 status:AutoTicketStatus;mapped_priority:'P1'|'P2'|'P3'|null;
 mapping_policy_status:'CONFIG_PENDING_Q14'|'PUBLISHED_Q14';
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
type WriteAttempt={path:string;method:'POST';key:string;body:Record<string,unknown>;kind:'transition'|'createWindow'|'windowAction'|'quarantine'|'manualTicket';targetId?:string}

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

const oneOf=(v:unknown,values:readonly unknown[])=>values.includes(v)
const text=(v:unknown,max=500):v is string=>typeof v==='string'&&v.trim().length>0&&Array.from(v).length<=max
const nullableText=(v:unknown,max=500)=>v===null||text(v,max)
const nullableUuid=(v:unknown)=>v===null||exportUuid(v)
const nullableTime=(v:unknown)=>v===null||isUtcInstant(v)
const version=(v:unknown)=>exportInteger(v,1)
/** V37 maintenance timestamptz(3) writes must round-trip without silently rounding human input. */
export const validMaintenanceTime=(v:unknown):v is string=>isUtcInstant(v)&&utcInstantNanos(v)!%1_000_000n===0n
const bad=()=>new Error('监控事件中心响应字段、状态或范围无效')
function bodyOf(raw:unknown):unknown{
 if(exportExact(raw,['data','trace_id'])){if(!text(raw.trace_id,256))throw bad();return raw.data}
 return raw
}
function exact(raw:unknown,keys:readonly string[]):Record<string,unknown>{if(!exportExact(raw,keys))throw bad();return raw}
function times(raw:Record<string,unknown>,required:string[],nullable:string[]=[]){
 if(required.some(k=>!isUtcInstant(raw[k]))||nullable.some(k=>!nullableTime(raw[k])))throw bad()
}
function unique<T>(items:T[],key:(item:T)=>string){if(new Set(items.map(key)).size!==items.length)throw bad();return items}

export function readEventSummary(raw:unknown,expectedTenant:string):MonitoringEventSummary{
 const r=exact(raw,EVENT_KEYS)
 if(!exportUuid(r.id)||r.tenant_id!==expectedTenant||!exportUuid(r.asset_id)||!exportUuid(r.updated_by)||!nullableUuid(r.ticket_id)||!version(r.version))throw bad()
 const severities=['INFO','WARNING','AVERAGE','HIGH','DISASTER']
 if(!oneOf(r.severity,severities)||r.severity_rank!==severities.indexOf(r.severity as string)+1||!exportInteger(r.occurrence_count,1)||!exportInteger(r.flap_flip_count,0))throw bad()
 if(!text(r.rule_code,64)||!text(r.rule_name,128)||!text(r.fingerprint,160)||!nullableText(r.suppression_reason,256)||!nullableText(r.root_cause_note)||!text(r.notification_empty_state_label,256)||typeof r.sla_unknown_marked!=='boolean')throw bad()
 if(!oneOf(r.monitor_state,['PROBLEM','RECOVERED'])||!oneOf(r.handling_state,['NEW','ACKNOWLEDGED','IN_PROGRESS','PENDING_REVIEW','RESOLVED','CLOSED'])||
  !oneOf(r.suppression_state,['NONE','FLAP_SILENCED','DEPENDENCY_SUPPRESSED','MAINTENANCE_SILENCED','INFO_DEFAULT_SILENT'])||
  !oneOf(r.notification_empty_state,['NORMAL_DISPATCH_ELIGIBLE','DEPENDENCY_SUPPRESSED_NO_NOTIFY','MAINTENANCE_WINDOW_SUPPRESSED','FLAP_SILENCED_NO_NOTIFY','INFO_LEVEL_NO_NOTIFY'])||
  !oneOf(r.review_policy_status,['CONFIG_PENDING_Q29','PUBLISHED_Q29'])||r.notification_empty_state_label!==notificationLabels[r.notification_empty_state as NotificationEmptyState])throw bad()
 times(r,['problem_at','last_occurred_at','updated_at'],['flap_silenced_until','recovered_at','first_ack_at','resolved_at','closed_at'])
 return structuredClone(r) as unknown as MonitoringEventSummary
}

export function readNoiseFunnel(raw:unknown,expectedTenant:string):MonitoringNoiseFunnel{
 const r=exact(raw,FUNNEL_KEYS)
 if(r.tenant_id!==expectedTenant||!oneOf(r.window,['24h','7d','30d'])||FUNNEL_KEYS.filter(k=>k.endsWith('_count')).some(k=>!exportInteger(r[k],0)))throw bad()
 times(r,['observed_at'])
 return structuredClone(r) as unknown as MonitoringNoiseFunnel
}

function readWindow(raw:unknown,tenant:string):MaintenanceWindowEntry{
 const r=exact(raw,['id','tenant_id','version','name','asset_id','starts_at','ends_at','silence_mode','status','sla_exclusion_requested','sla_exclusion_confirmed','sla_exclusion_confirmed_by','sla_exclusion_confirmed_at','reason','created_by','updated_by','updated_at'])
 if(!exportUuid(r.id)||r.tenant_id!==tenant||!version(r.version)||!exportUuid(r.asset_id)||!exportUuid(r.created_by)||!exportUuid(r.updated_by)||!nullableUuid(r.sla_exclusion_confirmed_by)||!text(r.name,128)||!text(r.reason))throw bad()
 if(!oneOf(r.silence_mode,['SUPPRESS_NOTIFY','PAUSE_COLLECTION'])||!oneOf(r.status,['SCHEDULED','ACTIVE','ENDED','CANCELLED'])||typeof r.sla_exclusion_requested!=='boolean'||typeof r.sla_exclusion_confirmed!=='boolean')throw bad()
 times(r,['starts_at','ends_at','updated_at'],['sla_exclusion_confirmed_at'])
 if(utcInstantNanos(r.ends_at)!<=utcInstantNanos(r.starts_at)!||r.sla_exclusion_confirmed&&(!r.sla_exclusion_requested||r.sla_exclusion_confirmed_by===null||r.sla_exclusion_confirmed_at===null))throw bad()
 return structuredClone(r) as unknown as MaintenanceWindowEntry
}
function readQuarantine(raw:unknown,tenant:string):MonitoringQuarantineItem{
 const r=exact(raw,['quarantine_id','pool_id','source_event_id','source_phase','claimed_tenant_id','claimed_asset_id','quarantine_reason','occurred_at','received_at','quarantine_status','decision','bound_asset_id','result_event_id','decision_reason','decided_by','decided_at'])
 if(!exportUuid(r.quarantine_id)||!exportUuid(r.pool_id)||!text(r.source_event_id,256)||!oneOf(r.source_phase,['PROBLEM','RECOVERED'])||!oneOf(r.claimed_tenant_id,[null,tenant])||!nullableUuid(r.claimed_asset_id)||
  !oneOf(r.quarantine_reason,['UNKNOWN_MAPPING','CROSS_TENANT_CLAIM','SOURCE_KEY_CONFLICT'])||r.quarantine_status!=='HELD_UNVERIFIED'||!oneOf(r.decision,[null,'BIND_ASSET','DISCARD'])||!nullableUuid(r.bound_asset_id)||!nullableUuid(r.result_event_id)||!nullableUuid(r.decided_by)||!nullableText(r.decision_reason))throw bad()
 times(r,['occurred_at','received_at'],['decided_at'])
 if(r.decision===null?[r.bound_asset_id,r.result_event_id,r.decision_reason,r.decided_by,r.decided_at].some(v=>v!==null):r.decision_reason===null||r.decided_by===null||r.decided_at===null||
  (r.decision==='DISCARD'&&(r.bound_asset_id!==null||r.result_event_id!==null))||(r.decision==='BIND_ASSET'&&r.bound_asset_id===null))throw bad()
 return structuredClone(r) as unknown as MonitoringQuarantineItem
}
function readAttempt(raw:unknown,tenant:string,eventId?:string):AutoTicketAttemptEntry{
 const r=exact(raw,['id','tenant_id','event_id','version','attempt_count','max_retries','backoff_schedule_sec','status','mapped_priority','mapping_policy_status','ticket_id','next_retry_at','last_error','updated_by','updated_at'])
 if(!exportUuid(r.id)||r.tenant_id!==tenant||!exportUuid(r.event_id)||eventId!==undefined&&r.event_id!==eventId||!version(r.version)||!exportInteger(r.attempt_count,1,6)||r.max_retries!==5||r.backoff_schedule_sec!=='30,120,600,600,600'||
  !oneOf(r.status,['SUCCEEDED','RETRY_SCHEDULED','QUARANTINED_MANUAL_REQUIRED','MANUAL_CREATED','SUPPRESSED_SKIPPED'])||!oneOf(r.mapped_priority,[null,'P1','P2','P3'])||!oneOf(r.mapping_policy_status,['CONFIG_PENDING_Q14','PUBLISHED_Q14'])||!nullableUuid(r.ticket_id)||!exportUuid(r.updated_by)||!nullableText(r.last_error))throw bad()
 times(r,['updated_at'],['next_retry_at'])
 return structuredClone(r) as unknown as AutoTicketAttemptEntry
}
export function readMonitoringWorkspace(raw:unknown,scope:MonitoringEventContext):MonitoringEventWorkspacePayload{
 if(!validContext(scope))throw bad()
 const r=exact(bodyOf(raw),['tenant_id','events','noise_funnel','maintenance_windows','quarantine_items','autoticket_attempts','observed_at','source'])
 if(r.tenant_id!==scope.tenantId||r.source!=='postgresql'||!Array.isArray(r.events)||!Array.isArray(r.maintenance_windows)||!Array.isArray(r.quarantine_items)||!Array.isArray(r.autoticket_attempts))throw bad()
 times(r,['observed_at'])
 if([r.events,r.maintenance_windows,r.quarantine_items,r.autoticket_attempts].some(rows=>rows.length>50))throw bad()
 return {...r,events:unique(r.events.map(e=>readEventSummary(e,scope.tenantId)),e=>e.id),noise_funnel:readNoiseFunnel(r.noise_funnel,scope.tenantId),
  maintenance_windows:unique(r.maintenance_windows.map(w=>readWindow(w,scope.tenantId)),w=>w.id),
  quarantine_items:unique(r.quarantine_items.map(q=>readQuarantine(q,scope.tenantId)),q=>q.quarantine_id),
  autoticket_attempts:unique(r.autoticket_attempts.map(a=>readAttempt(a,scope.tenantId)),a=>a.id)} as MonitoringEventWorkspacePayload
}
export function readMonitoringEventDetail(raw:unknown,scope:MonitoringEventContext,expectedEventId:string):MonitoringEventDetailPayload{
 if(!validContext(scope)||!exportUuid(expectedEventId))throw bad()
 const r=exact(bodyOf(raw),['event','members','noise_records','autoticket_attempt','observed_at','source'])
 if(r.source!=='postgresql'||!Array.isArray(r.members)||!Array.isArray(r.noise_records))throw bad()
 times(r,['observed_at'])
 const event=readEventSummary(r.event,scope.tenantId)
 if(event.id!==expectedEventId)throw bad()
 const members=unique(r.members.map(m=>{
  const x=exact(m,['id','tenant_id','event_id','raw_event_id','sequence_no','source_event_id','source_phase','occurred_at','merged_within_window','recorded_at'])
  if(!exportUuid(x.id)||x.tenant_id!==scope.tenantId||x.event_id!==expectedEventId||!nullableUuid(x.raw_event_id)||!exportInteger(x.sequence_no,1)||!text(x.source_event_id,256)||!oneOf(x.source_phase,['PROBLEM','RECOVERED'])||typeof x.merged_within_window!=='boolean')throw bad()
  times(x,['occurred_at','recorded_at']);return structuredClone(x) as unknown as MonitoringEventMember
 }),m=>m.id)
 const noise_records=unique(r.noise_records.map(n=>{
  const x=exact(n,['id','tenant_id','event_id','layer','detail','recorded_at'])
  if(!exportUuid(x.id)||x.tenant_id!==scope.tenantId||x.event_id!==expectedEventId||!oneOf(x.layer,['RAW_INTAKE','MERGE','FLAP','DEPENDENCY','MAINTENANCE','EFFECTIVE_ALERT'])||!text(x.detail))throw bad()
  times(x,['recorded_at']);return structuredClone(x) as unknown as MonitoringNoiseRecord
 }),n=>n.id)
 return {...r,event,members,noise_records,autoticket_attempt:r.autoticket_attempt===null?null:readAttempt(r.autoticket_attempt,scope.tenantId,expectedEventId)} as MonitoringEventDetailPayload
}

export function canTransition(event:MonitoringEventSummary,action:EventTransition,note:string|null=null):boolean{
 if(event.handling_state==='CLOSED')return false
 switch(action){
  case 'ACKNOWLEDGE':return event.handling_state==='NEW'
  case 'START':return oneOf(event.handling_state,['NEW','ACKNOWLEDGED'])
  case 'MARK_PENDING_REVIEW':return event.handling_state!=='RESOLVED'&&event.handling_state!=='PENDING_REVIEW'
  case 'RESOLVE':return event.handling_state==='PENDING_REVIEW'&&text(note)&&note.trim().length>=5
  case 'CLOSE':return event.handling_state==='RESOLVED'
  default:return false
 }
}

function readWriteReceipt(raw:unknown,scope:MonitoringEventContext,a:WriteAttempt){
 const r=bodyOf(raw),b=a.body
 if(a.kind==='transition'){
  const e=readEventSummary(r,scope.tenantId),target={ACKNOWLEDGE:'ACKNOWLEDGED',START:'IN_PROGRESS',MARK_PENDING_REVIEW:'PENDING_REVIEW',RESOLVE:'RESOLVED',CLOSE:'CLOSED'}
  if(e.id!==a.targetId||e.version!==Number(b.expected_version)+1||e.handling_state!==target[b.action as EventTransition]||b.action==='RESOLVE'&&e.root_cause_note!==b.root_cause_note)throw bad()
 }else if(a.kind==='createWindow'||a.kind==='windowAction'){
  const w=readWindow(r,scope.tenantId)
  if(a.kind==='createWindow'){
   if(w.version!==1||['name','asset_id','silence_mode','sla_exclusion_requested','reason'].some(k=>w[k as keyof MaintenanceWindowEntry]!==b[k])||utcInstantNanos(w.starts_at)!==utcInstantNanos(b.starts_at)||utcInstantNanos(w.ends_at)!==utcInstantNanos(b.ends_at))throw bad()
  }else if(w.id!==a.targetId||w.version!==Number(b.expected_version)+1||b.action==='END'&&w.status!=='ENDED'||b.action==='EXTEND'&&utcInstantNanos(w.ends_at)!==utcInstantNanos(b.new_ends_at)||b.action==='CONFIRM_SLA_EXCLUSION'&&!w.sla_exclusion_confirmed)throw bad()
 }else if(a.kind==='quarantine'){
  const q=readQuarantine(r,scope.tenantId)
  if(q.quarantine_id!==a.targetId||q.decision!==b.decision||q.bound_asset_id!==b.bound_asset_id||q.decision_reason!==b.reason)throw bad()
 }else{
  const t=readAttempt(r,scope.tenantId)
  if(t.id!==a.targetId||t.version!==Number(b.expected_version)+1||t.status!=='MANUAL_CREATED'||t.ticket_id===null)throw bad()
 }
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
  if(disposed||!context||state.busy||state.attempt||!oneOf(window,['24h','7d','30d']))return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,window,workspace:null,detail:null,busy:'read',error:null};emit()
  try{
   const raw=await client.request(`/events?window=${encodeURIComponent(window)}&page_size=25`,{signal:controller.signal})
   if(disposed||turn!==epoch)return false
   const workspace=readMonitoringWorkspace(raw,scope)
   if(workspace.noise_funnel.window!==window)throw bad()
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
  state={...state,detail:null,busy:'read',error:null};emit()
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
   const raw=await client.request(attempt.path,{method:attempt.method,idempotencyKey:attempt.key,body:structuredClone(attempt.body)})
   if(disposed||turn!==epoch||!scope)return false
   readWriteReceipt(raw,scope,attempt)
   state={...state,workspace:null,detail:null,busy:null,error:null,attempt:null};emit()
   return await load(state.window)
  }catch(error){
   if(disposed||turn!==epoch)return false
   const keep=writeFailureUncertain(retry,error)
   state={...state,busy:null,error,attempt:keep?attempt:null};emit()
   return false
  }
 }

 function currentEvent(id:string){return state.detail?.event.id===id?state.detail.event:state.workspace?.events.find(e=>e.id===id)}
 function writable(){return !disposed&&context?.canWriteInternal&&!state.busy&&!state.attempt}
 function prepare(kind:WriteAttempt['kind'],path:string,body:Record<string,unknown>,targetId?:string){return send({kind,path,method:'POST',key:newKey(),body:structuredClone(body),targetId})}
 async function submitTransition(eventId:string,input:{expected_version:number;action:EventTransition;root_cause_note:string|null}){
  const e=currentEvent(eventId)
  if(!writable()||!exportUuid(eventId)||!exportExact(input,['expected_version','action','root_cause_note'])||!e||e.version!==input.expected_version||!nullableText(input.root_cause_note)||!canTransition(e,input.action,input.root_cause_note))return false
  return prepare('transition',`/events/${eventId}/transitions`,{...input,root_cause_note:input.root_cause_note?.trim()??null},eventId)
 }
 async function submitMaintenanceWindow(input:{name:string;asset_id:string;starts_at:string;ends_at:string;silence_mode:'SUPPRESS_NOTIFY'|'PAUSE_COLLECTION';sla_exclusion_requested:boolean;reason:string}){
  if(!writable()||!exportExact(input,['name','asset_id','starts_at','ends_at','silence_mode','sla_exclusion_requested','reason'])||!text(input.name,128)||!exportUuid(input.asset_id)||!validMaintenanceTime(input.starts_at)||!validMaintenanceTime(input.ends_at)||utcInstantNanos(input.ends_at)!<=utcInstantNanos(input.starts_at)!||!oneOf(input.silence_mode,['SUPPRESS_NOTIFY','PAUSE_COLLECTION'])||typeof input.sla_exclusion_requested!=='boolean'||!text(input.reason))return false
  return prepare('createWindow','/maintenance-windows',{...input,name:input.name.trim(),reason:input.reason.trim()})
 }
 async function submitWindowAction(windowId:string,input:{expected_version:number;action:'EXTEND'|'END'|'CONFIRM_SLA_EXCLUSION';new_ends_at:string|null}){
  if(disposed||!context||state.busy||state.attempt||!exportUuid(windowId)||!exportExact(input,['expected_version','action','new_ends_at'])||!oneOf(input.action,['EXTEND','END','CONFIRM_SLA_EXCLUSION']))return false
  if(input.action==='CONFIRM_SLA_EXCLUSION'?!context.canAdminConfirm:!context.canWriteInternal)return false
  const w=state.workspace?.maintenance_windows.find(w=>w.id===windowId)
  if(!w||w.version!==input.expected_version||input.action==='CONFIRM_SLA_EXCLUSION'&&(!w.sla_exclusion_requested||w.sla_exclusion_confirmed))return false
  if(input.action==='EXTEND'? !validMaintenanceTime(input.new_ends_at)||utcInstantNanos(input.new_ends_at)!<=utcInstantNanos(w.ends_at)!:input.new_ends_at!==null)return false
  return prepare('windowAction',`/maintenance-windows/${windowId}/actions`,{...input},windowId)
 }
 async function submitQuarantineDecision(quarantineId:string,input:{decision:'BIND_ASSET'|'DISCARD';bound_asset_id:string|null;reason:string}){
  if(!writable()||!exportUuid(quarantineId)||!exportExact(input,['decision','bound_asset_id','reason'])||!text(input.reason)||!oneOf(input.decision,['BIND_ASSET','DISCARD']))return false
  const q=state.workspace?.quarantine_items.find(q=>q.quarantine_id===quarantineId)
  if(!q||q.decision!==null||(input.decision==='BIND_ASSET'?!exportUuid(input.bound_asset_id):input.bound_asset_id!==null))return false
  return prepare('quarantine',`/monitoring/quarantine/${quarantineId}/decisions`,{...input,reason:input.reason.trim()},quarantineId)
 }
 async function submitManualAutoTicket(attemptId:string,input:{expected_version:number;reason:string}){
  // Repository prefixes the trimmed reason with 8 code points inside V37 varchar(500).
  if(!writable()||!exportUuid(attemptId)||!exportExact(input,['expected_version','reason'])||!text(input.reason)||Array.from(input.reason.trim()).length>492)return false
  const a=state.workspace?.autoticket_attempts.find(a=>a.id===attemptId)
  if(!a||a.version!==input.expected_version||!oneOf(a.status,['QUARANTINED_MANUAL_REQUIRED','RETRY_SCHEDULED']))return false
  return prepare('manualTicket',`/autoticket-attempts/${attemptId}/manual-create`,{...input,reason:input.reason.trim()},attemptId)
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
