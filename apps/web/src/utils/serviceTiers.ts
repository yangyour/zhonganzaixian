import {ApiError,isCancelled,type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportTime,exportUuid} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'

/** Tenant service tier snapshot, append-only change ledger, and ticket SLA clock inspection (D16 / V35). */
export type ServiceTierCode='BASIC'|'STANDARD'|'ADVANCED'
export type EffectiveMode='IMMEDIATE'|'NEXT_MONTH_FIRST_DAY'
export type BillingCycle='MONTHLY'|'QUARTERLY'|'YEARLY'
export interface ServiceTierSnapshot {
 tenant_id:string;version:number;tier:ServiceTierCode;availability_target:number|null;
 contract_device_count:number|null;billing_cycle:BillingCycle|null;monthly_fee_fen:number|null;
 effective_mode:EffectiveMode;effective_from:string;updated_by:string;updated_at:string;
 customer_approval_status:'UNVERIFIED'
}
export interface ServiceTierChange {
 id:string;tenant_id:string;version:number;previous_version:number|null;
 from_tier:ServiceTierCode|null;to_tier:ServiceTierCode;availability_target:number|null;
 contract_device_count:number|null;billing_cycle:BillingCycle|null;monthly_fee_fen:number|null;
 effective_mode:EffectiveMode;effective_from:string;reason:string;approved_by:string;changed_at:string;
 customer_approval_status:'UNVERIFIED'
}
export interface ServiceTierPage {
 tenant_id:string;current:ServiceTierSnapshot|null;items:ServiceTierChange[];
 has_more:boolean;next_before_version:number|null;observed_at:string;
 customer_approval_status:'UNVERIFIED';in_flight_ticket_retroactive:false;source:'postgresql'
}
export interface ServiceTierChangeInput {
 to_tier:ServiceTierCode;effective_mode:EffectiveMode;
 contract_device_count:number|null;billing_cycle:BillingCycle|null;monthly_fee_fen:number|null;reason:string
}
export interface TicketSlaClockStatus {
 name:'RESPONSE'|'RECOVERY';due_at:string|null;completed_at:string|null;
 paused:boolean;breached:boolean|null;
 status:'PENDING_CONFIG'|'RUNNING'|'PAUSED_FOR_CUSTOMER_CONFIRM'|'MET'|'BREACHED'|'CANCELLED';
 missing_reason:string|null;
 thresholds:{p50_at:string|null;p80_at:string|null;p100_at:string|null};
 working_windows_count:number
}
export const SLA_DIAGNOSTIC_LIMITS={clockTasks:64,policyReferences:20} as const
export interface SlaPolicyReference {key:string;release_id:string|null;version:number;definition_hash:string;source:'CONTRACT_DEFAULT'|'RUNTIME_RELEASE'}
export interface TicketSlaInspection {
 ticket_id:string;tenant_id:string;receipt_no:string;registration_created_at:string;
 workflow_version:number;state:string|null;priority:'P1'|'P2'|'P3'|null;
 frozen_service_tier:ServiceTierCode|null;current_tenant_service_tier:ServiceTierCode|null;
 tier_changed_since_open:boolean;
 response_clock:TicketSlaClockStatus|null;recovery_clock:TicketSlaClockStatus|null;
 clock_tasks:{clock_key:string;kind:string;due_at:string;outcome:string|null;completed_at:string|null}[];
 sla_missing:Record<string,string>;sla_references:SlaPolicyReference[];
 reopen_count:number;reopen_policy_status:'NOT_REOPENED'|'PENDING_Q27';
 metric_version:'SLA_CLOCK_V2';observed_at:string;source:'postgresql'
}
export interface ServiceTierContext {tenantId:string;actorId:string;permissionVersion:number;canWrite:boolean}
type Attempt={key:string;body:ServiceTierChangeInput&{expected_version:number}}
export interface ServiceTierState {
 page:ServiceTierPage|null;receipt:ServiceTierSnapshot|null;busy:'read'|'write'|null;error:unknown;attempt:Attempt|null;
 readTarget:{before:number|null}|null;slaReadTarget:string|null;
 slaInspection:TicketSlaInspection|null;slaBusy:boolean;slaError:unknown
}
export const emptyServiceTierState=():ServiceTierState=>({
 page:null,receipt:null,busy:null,error:null,attempt:null,readTarget:null,slaReadTarget:null,slaInspection:null,slaBusy:false,slaError:null
})
export const TIER_PAGE_SIZE=25
export const tierLabels:Record<ServiceTierCode,string>={BASIC:'基础版（5×8 工作日历）',STANDARD:'标准版（7×24 全天）',ADVANCED:'高级版（7×24 高优保障）'}
export const effectiveModeLabels:Record<EffectiveMode,string>={IMMEDIATE:'立即生效',NEXT_MONTH_FIRST_DAY:'次月 1 日 00:00 生效'}
export const billingCycleLabels:Record<BillingCycle,string>={MONTHLY:'按月',QUARTERLY:'按季',YEARLY:'按年'}
export const clockStatusLabels:Record<TicketSlaClockStatus['status'],string>={
 PENDING_CONFIG:'待配置（规则未发布）',RUNNING:'计时中',PAUSED_FOR_CUSTOMER_CONFIRM:'已暂停（待客户确认）',
 MET:'已达标',BREACHED:'已超时违规',CANCELLED:'已撤销'
}

const invalid=()=>new ApiError(0,'INVALID_SERVICE_TIER_PAYLOAD','服务档位或SLA时钟数据未通过完整性校验，请刷新核对','—')
const tierTime=(value:unknown):bigint=>{try{return exportTime(value)}catch{throw invalid()}}
const isTier=(v:unknown):v is ServiceTierCode=>v==='BASIC'||v==='STANDARD'||v==='ADVANCED'
const isMode=(v:unknown):v is EffectiveMode=>v==='IMMEDIATE'||v==='NEXT_MONTH_FIRST_DAY'
const isCycle=(v:unknown):v is BillingCycle=>v==='MONTHLY'||v==='QUARTERLY'||v==='YEARLY'
const validTarget=(v:unknown)=>v===null||typeof v==='number'&&Number.isFinite(v)&&v>0&&v<=100
const validContext=(c:ServiceTierContext|null):c is ServiceTierContext=>
 Boolean(c&&exportUuid(c.tenantId)&&exportUuid(c.actorId)&&exportInteger(c.permissionVersion,1)&&typeof c.canWrite==='boolean')

export function readServiceTierSnapshot(raw:unknown,tenantId:string):ServiceTierSnapshot{
 if(!exportExact(raw,['tenant_id','version','tier','availability_target','contract_device_count','billing_cycle','monthly_fee_fen','effective_mode','effective_from','updated_by','updated_at','customer_approval_status']))throw invalid()
 if(raw.tenant_id!==tenantId||!exportInteger(raw.version,1)||!isTier(raw.tier)||!validTarget(raw.availability_target))throw invalid()
 if(!(raw.contract_device_count===null||exportInteger(raw.contract_device_count,0,1000000)))throw invalid()
 if(!(raw.billing_cycle===null||isCycle(raw.billing_cycle))||!(raw.monthly_fee_fen===null||exportInteger(raw.monthly_fee_fen,0)))throw invalid()
 if(!isMode(raw.effective_mode)||!exportUuid(raw.updated_by)||raw.customer_approval_status!=='UNVERIFIED')throw invalid()
 tierTime(raw.effective_from);tierTime(raw.updated_at)
 return structuredClone(raw) as unknown as ServiceTierSnapshot
}

export function readServiceTierPage(value:unknown,scope:ServiceTierContext,beforeVersion?:number):ServiceTierPage{
 if(!validContext(scope)||!exportExact(value,['tenant_id','current','items','has_more','next_before_version','observed_at','customer_approval_status','in_flight_ticket_retroactive','source']))throw invalid()
 if(value.tenant_id!==scope.tenantId||!Array.isArray(value.items)||value.items.length>TIER_PAGE_SIZE||typeof value.has_more!=='boolean'||value.customer_approval_status!=='UNVERIFIED'||value.in_flight_ticket_retroactive!==false||value.source!=='postgresql')throw invalid()
 tierTime(value.observed_at)
 const current=value.current===null?null:readServiceTierSnapshot(value.current,scope.tenantId)
 let prevVersion=beforeVersion
 const seen=new Set<string>()
 for(const item of value.items){
  if(!exportExact(item,['id','tenant_id','version','previous_version','from_tier','to_tier','availability_target','contract_device_count','billing_cycle','monthly_fee_fen','effective_mode','effective_from','reason','approved_by','changed_at','customer_approval_status']))throw invalid()
  if(!exportUuid(item.id)||seen.has(item.id)||item.tenant_id!==scope.tenantId||!exportInteger(item.version,1))throw invalid()
  if(item.version===1&&(item.previous_version!==null||item.from_tier!==null))throw invalid()
  if(item.version>1&&(item.previous_version!==item.version-1||!isTier(item.from_tier)))throw invalid()
  if(!isTier(item.to_tier)||!validTarget(item.availability_target)||!(item.contract_device_count===null||exportInteger(item.contract_device_count,0,1000000)))throw invalid()
  if(!(item.billing_cycle===null||isCycle(item.billing_cycle))||!(item.monthly_fee_fen===null||exportInteger(item.monthly_fee_fen,0)))throw invalid()
  if(!isMode(item.effective_mode)||typeof item.reason!=='string'||!item.reason.trim()||[...item.reason].length>1000||!exportUuid(item.approved_by)||item.customer_approval_status!=='UNVERIFIED')throw invalid()
  tierTime(item.effective_from);tierTime(item.changed_at)
  if(prevVersion!==undefined&&item.version>=prevVersion)throw invalid()
  prevVersion=item.version;seen.add(item.id)
 }
 if(value.has_more){
  const last=value.items.at(-1)
  if(value.items.length!==TIER_PAGE_SIZE||!last||value.next_before_version!==last.version)throw invalid()
 }else if(value.next_before_version!==null)throw invalid()
 if(current===null&&value.items.length>0)throw invalid()
 return {
  ...(structuredClone(value) as unknown as ServiceTierPage),
  current
 }
}

function readClock(raw:unknown,expectedName:'RESPONSE'|'RECOVERY',registered:bigint,observed:bigint):TicketSlaClockStatus{
 if(!exportExact(raw,['name','due_at','completed_at','paused','breached','status','missing_reason','thresholds','working_windows_count']))throw invalid()
 if(raw.name!==expectedName||typeof raw.paused!=='boolean'||(raw.breached!==null&&typeof raw.breached!=='boolean')||!['PENDING_CONFIG','RUNNING','PAUSED_FOR_CUSTOMER_CONFIRM','MET','BREACHED','CANCELLED'].includes(raw.status as string))throw invalid()
 if(!(raw.due_at===null||tierTime(raw.due_at)>=0)||!(raw.completed_at===null||tierTime(raw.completed_at)>=0)||!(raw.missing_reason===null||typeof raw.missing_reason==='string'))throw invalid()
 if(!exportExact(raw.thresholds,['p50_at','p80_at','p100_at'])||!exportInteger(raw.working_windows_count,0,1000))throw invalid()
 for(const k of ['p50_at','p80_at','p100_at'] as const){const v=raw.thresholds[k];if(!(v===null||tierTime(v)>=0))throw invalid()}
 const due=raw.due_at===null?null:tierTime(raw.due_at),completed=raw.completed_at===null?null:tierTime(raw.completed_at)
 if(due!==null&&due<registered||completed!==null&&(completed<registered||completed>observed)||typeof raw.missing_reason==='string'&&!raw.missing_reason.trim())throw invalid()
 const rawThresholds=raw.thresholds
 const thresholds=['p50_at','p80_at','p100_at'].map(k=>rawThresholds[k]===null?null:tierTime(rawThresholds[k]))
 if(due===null){if(raw.working_windows_count!==0||thresholds.some(t=>t!==null)||!['PENDING_CONFIG','CANCELLED'].includes(raw.status as string))throw invalid()}
 else if(raw.working_windows_count===0||thresholds.some(t=>t===null)||thresholds[0]!<registered||thresholds[0]!>thresholds[1]!||thresholds[1]!>thresholds[2]!||thresholds[2]!==due)throw invalid()
 if(expectedName==='RESPONSE'&&raw.paused)throw invalid()
 switch(raw.status){
  case 'PENDING_CONFIG':if(due!==null||raw.breached!==null||typeof raw.missing_reason!=='string'||!raw.missing_reason.trim())throw invalid();break
  case 'CANCELLED':if(raw.breached!==null||raw.paused)throw invalid();break
  case 'BREACHED':if(due===null||raw.breached!==true||(completed??observed)<=due)throw invalid();break
  case 'RUNNING':if(due===null||raw.breached!==false||raw.paused||completed!==null||observed>due)throw invalid();break
  case 'MET':if(due===null||raw.breached!==false||completed!==null&&completed>due)throw invalid();break
  case 'PAUSED_FOR_CUSTOMER_CONFIRM':if(expectedName!=='RECOVERY'||due===null||raw.breached!==false||!raw.paused||completed!==null&&completed>due)throw invalid();break
 }
 return structuredClone(raw) as unknown as TicketSlaClockStatus
}

export function readTicketSlaInspection(value:unknown,scope:ServiceTierContext,expectedTicketId:string):TicketSlaInspection{
 if(!validContext(scope)||!exportExact(value,['ticket_id','tenant_id','receipt_no','registration_created_at','workflow_version','state','priority','frozen_service_tier','current_tenant_service_tier','tier_changed_since_open','response_clock','recovery_clock','clock_tasks','sla_missing','sla_references','reopen_count','reopen_policy_status','metric_version','observed_at','source']))throw invalid()
 if(value.ticket_id!==expectedTicketId||value.tenant_id!==scope.tenantId||typeof value.receipt_no!=='string'||!value.receipt_no.trim()||!exportInteger(value.workflow_version,0)||typeof value.tier_changed_since_open!=='boolean'||!exportInteger(value.reopen_count,0)||!['NOT_REOPENED','PENDING_Q27'].includes(value.reopen_policy_status as string)||value.metric_version!=='SLA_CLOCK_V2'||value.source!=='postgresql')throw invalid()
 const registered=tierTime(value.registration_created_at),observed=tierTime(value.observed_at)
 if(observed<registered||value.current_tenant_service_tier!==null&&!isTier(value.current_tenant_service_tier)||!Array.isArray(value.clock_tasks)||!Array.isArray(value.sla_references)||!value.sla_missing||typeof value.sla_missing!=='object'||Array.isArray(value.sla_missing))throw invalid()
 if(value.clock_tasks.length>SLA_DIAGNOSTIC_LIMITS.clockTasks||value.sla_references.length>SLA_DIAGNOSTIC_LIMITS.policyReferences)throw new ApiError(413,'SLA_INSPECTION_LIMIT','SLA 核验明细超过单次接口范围，未截断展示；请联系管理员核对历史规模','—')
 if(Object.entries(value.sla_missing).some(([k,v])=>!k.trim()||typeof v!=='string'||!v.trim()))throw invalid()
 const seenTasks=new Set<string>()
 let previousTask:{due:bigint;key:string}|null=null
 for(const task of value.clock_tasks){
  if(!exportExact(task,['clock_key','kind','due_at','outcome','completed_at'])||typeof task.clock_key!=='string'||!task.clock_key||task.clock_key.length>80||seenTasks.has(task.clock_key)||!['SLA','CONFIRMATION_REMINDER','AUTO_CLOSE'].includes(task.kind as string)||task.outcome!==null&&!['EMITTED','OBSOLETE','AUTO_CLOSED'].includes(task.outcome as string)||(task.outcome===null)!==(task.completed_at===null))throw invalid()
  const key=task.clock_key,due=tierTime(task.due_at),completed=task.completed_at===null?null:tierTime(task.completed_at)
  if(due<registered||completed!==null&&(completed<due||completed>observed)||previousTask&&(due<previousTask.due||due===previousTask.due&&key<=previousTask.key))throw invalid()
  if(task.kind==='SLA'&&!/^(response|recovery):(50|80|100)$/.test(key)||task.kind==='CONFIRMATION_REMINDER'&&!/^confirmation:[0-9]+$/.test(key)||task.kind==='AUTO_CLOSE'&&!/^auto_close:[0-9]+$/.test(key)||task.outcome==='AUTO_CLOSED'&&task.kind!=='AUTO_CLOSE'||task.outcome==='EMITTED'&&task.kind==='AUTO_CLOSE')throw invalid()
  seenTasks.add(key);previousTask={due,key}
 }
 for(const source of value.sla_references){
  if(!exportExact(source,['key','release_id','version','definition_hash','source'])||typeof source.key!=='string'||!source.key.trim()||typeof source.definition_hash!=='string'||!/^[0-9a-f]{64}$/.test(source.definition_hash))throw invalid()
  if(source.source==='CONTRACT_DEFAULT'){if(source.release_id!==null||source.version!==0)throw invalid()}
  else if(source.source==='RUNTIME_RELEASE'){if(typeof source.release_id!=='string'||!source.release_id.trim()||!exportInteger(source.version,1))throw invalid()}
  else throw invalid()
 }
 if(value.workflow_version===0){
  if(value.state!==null||value.priority!==null||value.frozen_service_tier!==null||value.response_clock!==null||value.recovery_clock!==null||value.tier_changed_since_open!==false||value.reopen_count!==0||value.clock_tasks.length||value.sla_references.length||Object.keys(value.sla_missing).length)throw invalid()
 }else{
  if(!['OPEN','ACCEPTED','IN_PROGRESS','RESOLVED','CLOSED','CANCELLED'].includes(value.state as string)||!['P1','P2','P3'].includes(value.priority as string)||!isTier(value.frozen_service_tier)||value.response_clock===null||value.recovery_clock===null)throw invalid()
  const response=readClock(value.response_clock,'RESPONSE',registered,observed),recovery=readClock(value.recovery_clock,'RECOVERY',registered,observed)
  if(value.state==='CANCELLED'&&recovery.status!=='CANCELLED'||value.state!=='CANCELLED'&&(response.status==='CANCELLED'||recovery.status==='CANCELLED'))throw invalid()
  const expectedChanged=value.current_tenant_service_tier!==null&&value.current_tenant_service_tier!==value.frozen_service_tier
  if(value.tier_changed_since_open!==expectedChanged)throw invalid()
 }
 if((value.reopen_count>0)!==(value.reopen_policy_status==='PENDING_Q27'))throw invalid()
 return structuredClone(value) as unknown as TicketSlaInspection
}

/** Explicit blank is unknown; malformed numeric text never becomes null or a truncated integer. */
export function parseOptionalTierInteger(raw:unknown,max:number):number|null {
 if(typeof raw!=='string')throw new Error('请输入十进制非负整数或留空。')
 const value=raw.trim();if(value==='')return null
 if(!/^[0-9]+$/.test(value))throw new Error('请输入十进制非负整数；不接受小数、负数或科学计数法。')
 const parsed=Number(value);if(!Number.isSafeInteger(parsed)||parsed>max)throw new Error('数值超出允许范围，未提交。')
 return parsed
}

export function createServiceTierWorkspace(client:{request<T>(path:string,options?:RequestOptions):Promise<T>},changed:(s:ServiceTierState)=>void,newKey=createRequestId){
 let state=emptyServiceTierState(),context:ServiceTierContext|null=null,epoch=0,slaEpoch=0,abort:AbortController|null=null,slaAbort:AbortController|null=null,disposed=false,knownTierVersion=0
 let knownSla:{ticketId:string;version:number}|null=null
 const snapshot=()=>({...structuredClone({...state,error:null,slaError:null}),error:state.error,slaError:state.slaError}),emit=()=>changed(snapshot())
 function reset(next:ServiceTierContext|null){knownTierVersion=0;knownSla=null;epoch++;slaEpoch++;abort?.abort();abort=null;slaAbort?.abort();slaAbort=null;context=!disposed&&validContext(next)?{...next}:null;state=emptyServiceTierState();emit()}
 async function load(next=false){
  if(disposed||!context||state.busy||state.attempt)return false
  const before=next&&state.page?.has_more?state.page.next_before_version??undefined:undefined
  if(next&&before===undefined)return false
  return readLedger(before??null)
 }
 async function readLedger(original:number|null){
  if(disposed||!context||state.busy||state.attempt)return false
  const before=original??undefined
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,page:null,busy:'read',error:null,readTarget:{before:original}};emit()
  try{
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const q=new URLSearchParams({page_size:String(TIER_PAGE_SIZE)});if(before!==undefined)q.set('before_version',String(before))
   const raw=await client.request<unknown>('/service-tiers?'+q,{signal:controller.signal})
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const result=readServiceTierPage(raw,scope,before),version=result.current?.version??0
   if(version<knownTierVersion)throw invalid()
   knownTierVersion=version;state.page=result;state.readTarget=null;return true
  }catch(error){if(!disposed&&turn===epoch&&!isCancelled(error))state.error=error;return false}finally{if(!disposed&&turn===epoch){state.busy=null;abort=null;emit()}}
 }
 async function send(attempt:Attempt,retry=false){
  if(disposed||!context?.canWrite||state.busy)return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,busy:'write',error:null,receipt:null,attempt:structuredClone(attempt)};emit()
  try{
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const raw=await client.request<unknown>('/service-tiers/changes',{method:'POST',body:structuredClone(attempt.body),idempotencyKey:attempt.key,signal:controller.signal})
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const updated=readServiceTierSnapshot(raw,scope.tenantId)
   if(updated.version!==attempt.body.expected_version+1||updated.updated_by!==scope.actorId||updated.tier!==attempt.body.to_tier||updated.effective_mode!==attempt.body.effective_mode||updated.contract_device_count!==attempt.body.contract_device_count||updated.billing_cycle!==attempt.body.billing_cycle||updated.monthly_fee_fen!==attempt.body.monthly_fee_fen)throw invalid()
   knownTierVersion=Math.max(knownTierVersion,updated.version);state.readTarget=null;state.attempt=null;state.busy=null;state.page=null;state.receipt=updated;abort=null;emit()
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const reloadTurn=epoch+1;await load(false)
   return !disposed&&epoch===reloadTurn
  }catch(error){
   if(!disposed&&turn===epoch){state.error=error;if(!writeFailureUncertain(retry,error))state.attempt=null}
   return false
  }finally{if(!disposed&&turn===epoch){state.busy=null;abort=null;emit()}}
 }
 function submitChange(input:ServiceTierChangeInput,expectedVersion:number){
  if(disposed||!context?.canWrite||state.busy||state.attempt||!state.page)return Promise.resolve(false)
  const expected=state.page.current?.version??0
  if(expectedVersion!==expected||!exportInteger(expectedVersion,0,Number.MAX_SAFE_INTEGER-1)){state.error=invalid();emit();return Promise.resolve(false)}
  if(typeof input.reason!=='string')return Promise.resolve(false)
  const reason=input.reason.trim()
  if(!isTier(input.to_tier)||!isMode(input.effective_mode)||!reason||[...reason].length>1000||input.contract_device_count!==null&&!exportInteger(input.contract_device_count,0,1000000)||input.monthly_fee_fen!==null&&!exportInteger(input.monthly_fee_fen,0,Number.MAX_SAFE_INTEGER-1)||input.billing_cycle!==null&&!isCycle(input.billing_cycle))return Promise.resolve(false)
  const turn=epoch
  try{
   const original={...structuredClone(input),reason,expected_version:expected}
   const key=newKey();
   if(disposed||turn!==epoch||state.busy||state.attempt||!context?.canWrite)return Promise.resolve(false)
   if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw invalid()
   return send({key,body:original})
  }catch(error){if(!disposed&&turn===epoch){state.error=error;emit()}return Promise.resolve(false)}
 }
 async function inspectTicketSla(ticketId:string){
  const trimmed=ticketId.trim()
  if(disposed||!context||state.slaBusy||!exportUuid(trimmed))return false
  const turn=++slaEpoch,scope={...context},controller=new AbortController();slaAbort=controller
  state={...state,slaBusy:true,slaError:null,slaInspection:null,slaReadTarget:trimmed};emit()
  try{
   if(disposed||turn!==slaEpoch||controller.signal.aborted)return false
   const raw=await client.request<unknown>(`/tickets/${trimmed}/workflow/sla`,{signal:controller.signal})
   if(disposed||turn!==slaEpoch||controller.signal.aborted)return false
   const result=readTicketSlaInspection(raw,scope,trimmed)
   if(knownSla?.ticketId===trimmed&&result.workflow_version<knownSla.version)throw invalid()
   knownSla={ticketId:trimmed,version:result.workflow_version};state.slaInspection=result;state.slaReadTarget=null;return true
  }catch(error){if(!disposed&&turn===slaEpoch&&!isCancelled(error))state.slaError=error;return false}finally{if(!disposed&&turn===slaEpoch){state.slaBusy=false;slaAbort=null;emit()}}
 }
 function cancelRead(){if(disposed||state.busy!=='read')return false;epoch++;abort?.abort();abort=null;state.busy=null;state.error=null;emit();return true}
 function cancelSlaRead(){if(disposed||!state.slaBusy)return false;slaEpoch++;slaAbort?.abort();slaAbort=null;state.slaBusy=false;state.slaError=null;emit();return true}
 const canRetryRead=()=>!disposed&&!!context&&!state.busy&&!state.attempt&&state.readTarget!==null
 const canRetrySlaRead=()=>!disposed&&!!context&&!state.slaBusy&&state.slaReadTarget!==null
 emit()
 return{
  reset,load,submitChange,inspectTicketSla,cancelRead,cancelSlaRead,canRetryRead,canRetrySlaRead,
  retryRead:()=>canRetryRead()?readLedger(state.readTarget!.before):Promise.resolve(false),
  retrySlaRead:()=>canRetrySlaRead()?inspectTicketSla(state.slaReadTarget!):Promise.resolve(false),
  retry:()=>state.attempt?send(structuredClone(state.attempt),true):Promise.resolve(false),
  unresolved:()=>state.attempt!==null,
  state:snapshot,
  dispose:()=>{disposed=true;reset(null)}
 }
}
