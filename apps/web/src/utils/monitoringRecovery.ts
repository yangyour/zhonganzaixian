import {ApiError, isCancelled, type RequestOptions} from '../api/client'
import {exportExact, exportInteger, exportTime, exportUuid} from './assetExportJobs'
import {createRequestId, digestSha256} from './browserCrypto'
import {utcInstantNanos} from './utcInstant'
import {writeFailureUncertain} from './writeFailure'

export interface RecoveryScope {tenantId:string;actorId:string;permissionVersion:number;eventId:string;canWrite:boolean}
export interface RecoveryRule {metric:string;comparison:string;threshold:string;unit:string;hold_seconds:string;max_gap_seconds:string;minimum_samples:number}
export interface RecoveryView {
 tenant_id:string;actor_id:string;permission_version:number;event_id:string;asset_id:string;event_version:number;monitor_state:'PROBLEM'|'RECOVERED';
 handling_state:string;recovery_evidence_status:'NOT_CONFIRMED'|'UNVERIFIED'|'CONFIRMED';
 configuration_status:'ACTIVE'|'CONFIG_PENDING';missing_reason:string|null;policy_release_id:string|null;policy_release_version:number|null;
 rule:RecoveryRule|null;observed_at:string;source:'postgresql';source_binding_available:boolean
}
export interface RecoveryReceipt {
 tenant_id:string;actor_id:string;permission_version:number;event_id:string;event_version:number;proof_id:string;status:string;applied:boolean;confirmed_at:string|null;
 policy_release_id:string;policy_release_version:number;observed_at:string;latest_sample_at:string|null;span_started_at:string|null;span_seconds:string|null;
 matching_samples:number;source_binding_sha256:string;source:'ZABBIX_SIMPLE_CHECK';production_approved:false
}
interface Attempt {key:string;hash:string;body:{expected_version:number;policy_release_id:string;policy_release_version:number};rule:RecoveryRule;uncertain:boolean}
export interface RecoveryState {view:RecoveryView|null;receipt:RecoveryReceipt|null;attempt:Attempt|null;busy:'read'|'prepare'|'write'|'receipt'|null;error:unknown;cancelled:boolean}
export const emptyRecoveryState=():RecoveryState=>({view:null,receipt:null,attempt:null,busy:null,error:null,cancelled:false})
const invalid=()=>new ApiError(0,'INVALID_RECOVERY_OBSERVATION','恢复观察响应未通过范围或完整性核对','—')
const decimal=(v:unknown):v is string=>typeof v==='string'&&/^-?(?:0|[1-9][0-9]{0,39})(?:\.[0-9]{1,40})?$/.test(v)&&!/-0(?:\.0+)?$/.test(v)
const comparePositive=(left:string,right:string)=>{const [a='',af='']=left.split('.'),[b='',bf='']=right.split('.'),scale=Math.max(af.length,bf.length);const x=BigInt(a+af.padEnd(scale,'0')),y=BigInt(b+bf.padEnd(scale,'0'));return x<y?-1:x>y?1:0}
const seconds=(nanos:bigint)=>`${nanos/1_000_000_000n}.${(nanos%1_000_000_000n).toString().padStart(9,'0')}`
const sameScope=(value:Record<string,unknown>,scope:RecoveryScope)=>value.tenant_id===scope.tenantId&&value.actor_id===scope.actorId&&value.permission_version===scope.permissionVersion&&value.event_id===scope.eventId
export function readRecoveryView(value:unknown,scope:RecoveryScope):RecoveryView {
 if(!exportExact(value,['tenant_id','actor_id','permission_version','event_id','asset_id','event_version','monitor_state','handling_state','recovery_evidence_status','configuration_status','missing_reason','policy_release_id','policy_release_version','rule','observed_at','source','source_binding_available'])||!sameScope(value,scope)||!exportUuid(value.asset_id)||!exportInteger(value.event_version,1)||!['PROBLEM','RECOVERED'].includes(value.monitor_state as string)||!['ACTIVE','CONFIG_PENDING'].includes(value.configuration_status as string)||typeof value.source_binding_available!=='boolean'||value.source!=='postgresql'||!['NEW','ACKNOWLEDGED','IN_PROGRESS','PENDING_REVIEW','RESOLVED','CLOSED'].includes(value.handling_state as string)||!['NOT_CONFIRMED','UNVERIFIED','CONFIRMED'].includes(value.recovery_evidence_status as string)||(value.monitor_state==='PROBLEM')!==(value.recovery_evidence_status==='NOT_CONFIRMED'))throw invalid()
 exportTime(value.observed_at)
 if(value.configuration_status==='ACTIVE'){
  const rule=value.rule
  if(!exportUuid(value.policy_release_id)||!exportInteger(value.policy_release_version,1)||value.missing_reason!==null||!exportExact(rule,['metric','comparison','threshold','unit','hold_seconds','max_gap_seconds','minimum_samples'])||!['icmp','loss','latency','tcp_22','tcp_80','tcp_443'].includes(rule.metric as string)||!['GT','GE','LT','LE'].includes(rule.comparison as string)||!decimal(rule.threshold)||typeof rule.unit!=='string'||rule.unit.length>32||!decimal(rule.hold_seconds)||rule.hold_seconds.startsWith('-')||!decimal(rule.max_gap_seconds)||Number(rule.max_gap_seconds)<=0||!exportInteger(rule.minimum_samples,1,2000))throw invalid()
 }else if(value.policy_release_id!==null||value.policy_release_version!==null||value.rule!==null||typeof value.missing_reason!=='string')throw invalid()
 return structuredClone(value) as unknown as RecoveryView
}
export function readRecoveryReceipt(value:unknown,scope:RecoveryScope,attempt:Attempt):RecoveryReceipt {
 if(!exportExact(value,['tenant_id','actor_id','permission_version','event_id','event_version','proof_id','status','applied','confirmed_at','policy_release_id','policy_release_version','observed_at','latest_sample_at','span_started_at','span_seconds','matching_samples','source_binding_sha256','source','production_approved'])||!sameScope(value,scope)||typeof value.applied!=='boolean'||!exportUuid(value.proof_id)||value.event_version!==attempt.body.expected_version+(value.applied?1:0)||value.policy_release_id!==attempt.body.policy_release_id||value.policy_release_version!==attempt.body.policy_release_version||!exportInteger(value.matching_samples,0,2000)||value.source!=='ZABBIX_SIMPLE_CHECK'||value.production_approved!==false||typeof value.source_binding_sha256!=='string'||!/^[0-9a-f]{64}$/.test(value.source_binding_sha256))throw invalid()
 const observed=exportTime(value.observed_at)
 if(!['RECOVERY_CONFIRMED','NO_SAMPLES','UNKNOWN_LATEST','STALE_LATEST','NOT_RECOVERED','OBSERVING'].includes(value.status as string)||(value.status==='RECOVERY_CONFIRMED')!==value.applied||(value.applied?value.confirmed_at!==value.observed_at:value.confirmed_at!==null))throw invalid()
 let latest:bigint|null=null,start:bigint|null=null
 if(value.latest_sample_at!==null){latest=utcInstantNanos(value.latest_sample_at);if(latest===null||latest>observed)throw invalid()}
 if(value.span_started_at!==null){start=utcInstantNanos(value.span_started_at);if(start===null||latest===null||start>latest)throw invalid()}
 if(value.span_seconds!==null&&(!decimal(value.span_seconds)||value.span_seconds.startsWith('-')))throw invalid()
 if(value.applied&&(start===null||latest===null||value.span_seconds===null||value.matching_samples===0))throw invalid()
 if(['RECOVERY_CONFIRMED','OBSERVING'].includes(value.status as string)){
  if(start===null||latest===null||typeof value.span_seconds!=='string'||comparePositive(value.span_seconds,seconds(latest-start))!==0||value.matching_samples===0||comparePositive(seconds(observed-latest),attempt.rule.max_gap_seconds)>0)throw invalid()
  const reached=comparePositive(value.span_seconds,attempt.rule.hold_seconds)>=0&&(value.matching_samples as number)>=attempt.rule.minimum_samples
  if(reached!==value.applied)throw invalid()
 }
 return structuredClone(value) as unknown as RecoveryReceipt
}

export function createMonitoringRecovery(api:{request<T>(path:string,options?:RequestOptions):Promise<T>},notify:(state:RecoveryState)=>void){
 let scope:RecoveryScope|null=null,state=emptyRecoveryState(),generation=0,read:AbortController|null=null,disposed=false
 const publish=()=>notify({...state,view:state.view?structuredClone(state.view):null,receipt:state.receipt?structuredClone(state.receipt):null,attempt:state.attempt?structuredClone(state.attempt):null})
 const path=(s:RecoveryScope)=>`/events/${s.eventId}/recovery-observations`
 const current=(s:RecoveryScope,turn:number)=>!disposed&&turn===generation&&scope!==null&&JSON.stringify(s)===JSON.stringify(scope)
 const unresolved=()=>state.attempt!==null||state.busy==='write'||state.busy==='prepare'
 function reset(next:RecoveryScope|null){generation++;read?.abort();read=null;scope=next&&exportUuid(next.tenantId)&&exportUuid(next.actorId)&&exportUuid(next.eventId)&&exportInteger(next.permissionVersion,1)&&typeof next.canWrite==='boolean'?{...next}:null;state=emptyRecoveryState();publish()}
 async function load(){
  if(!scope||state.busy==='write'||state.busy==='prepare'||disposed)return false
  read?.abort();const controller=new AbortController();read=controller;const s={...scope},turn=++generation
  state={...state,busy:'read',error:null,cancelled:false};publish()
  try{const result=readRecoveryView(await api.request(path(s),{signal:controller.signal}),s);if(!current(s,turn)||controller.signal.aborted)return false;state.view=result;return true}
  catch(error){if(current(s,turn)&&!isCancelled(error))state.error=error;return false}
  finally{if(current(s,turn)){state.busy=null;read=null;publish()}}
 }
 async function send(attempt:Attempt,s:RecoveryScope,turn:number){
  state={...state,busy:'write',error:null,cancelled:false};publish()
  try{if(!current(s,turn)||!scope?.canWrite)return false;const result=readRecoveryReceipt(await api.request(path(s),{method:'POST',body:attempt.body,idempotencyKey:attempt.key,timeoutMs:60000}),s,attempt);if(!current(s,turn))return false;state.receipt=result;state.attempt=null;return true}
  catch(error){if(current(s,turn)){state.error=error;attempt.uncertain=writeFailureUncertain(attempt.uncertain,error);if(!attempt.uncertain)state.attempt=null}return false}
  finally{if(current(s,turn)){state.busy=null;publish()}}
 }
 async function submit(){
  const view=state.view
  if(!scope?.canWrite||state.busy||state.attempt||state.error||!view||view.configuration_status!=='ACTIVE'||!view.source_binding_available||view.handling_state==='CLOSED'||view.recovery_evidence_status==='CONFIRMED'||view.policy_release_id===null||view.policy_release_version===null||state.receipt?.applied&&view.event_version<=state.receipt.event_version)return false
  const s={...scope},turn=++generation;state={...state,busy:'prepare',error:null,cancelled:false};publish()
  try{
   const key=createRequestId(),bytes=await digestSha256(new TextEncoder().encode(`${s.actorId}|${s.tenantId}|${s.eventId}|${key}`))
   if(!current(s,turn)||!scope?.canWrite)return false
   const attempt:Attempt={key,hash:Array.from(new Uint8Array(bytes),v=>v.toString(16).padStart(2,'0')).join(''),body:{expected_version:view.event_version,policy_release_id:view.policy_release_id,policy_release_version:view.policy_release_version},rule:structuredClone(view.rule!),uncertain:false}
   state.attempt=attempt;return await send(attempt,s,turn)
  }catch(error){if(current(s,turn)){state.error=error;state.busy=null;publish()}return false}
 }
 async function retry(){if(!scope?.canWrite||!state.attempt||state.busy)return false;return send(state.attempt,{...scope},++generation)}
 async function recover(){
  if(!scope||!state.attempt||state.busy)return false
  const s={...scope},attempt=state.attempt,turn=++generation,controller=new AbortController();read=controller
  state={...state,busy:'receipt',error:null,cancelled:false};publish()
  try{const result=readRecoveryReceipt(await api.request(`${path(s)}/receipts/${attempt.hash}`,{signal:controller.signal}),s,attempt);if(!current(s,turn)||controller.signal.aborted)return false;state.receipt=result;state.attempt=null;return true}
  catch(error){if(current(s,turn)&&!isCancelled(error))state.error=error;return false}
  finally{if(current(s,turn)){state.busy=null;read=null;publish()}}
 }
 function cancelRead(){if(!read)return;generation++;read.abort();read=null;state={...state,busy:null,error:null,cancelled:true};publish()}
 return {reset,load,submit,retry,recover,cancelRead,unresolved,dispose(){disposed=true;generation++;read?.abort();read=null;scope=null;state=emptyRecoveryState()}}
}
