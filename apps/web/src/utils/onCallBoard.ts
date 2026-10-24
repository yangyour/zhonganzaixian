import {validateOnCallBoardDetails,validateOnCallRoster,validateOnCallHandoff,validateOnCallPolicy,validateOnCallStepRun} from './onCallPayloadIntegrity'
import {ApiError,type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportTime,exportUuid,exportEquals} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'
import type {ServiceTierCode} from './serviceTiers'
import {validateSlaService,validateSlaKpis,validateSlaIntervals,validateSlaJudgments,validateSlaMonthlyNumbers,validSlaServiceIdentity,validSlaPercentage} from './slaPayloadIntegrity'

/** On-Call Board, 18:00 Shift Handoff, Configurable Escalation Chain & SLA Monthly Reports (D16 / V36: M5-9, M5-10, M5-12, M5-13, M5-19). */
export type ShiftCode='DAY'|'NIGHT'|'FULL_DAY'
export type PolicyTierCode='BASIC'|'STANDARD'|'ADVANCED'|'FALLBACK'
export type TriggerCondition='IMMEDIATE'|'UNACKNOWLEDGED'|'UNRESOLVED'|'COUNTDOWN_ZERO'|'PHONE_UNANSWERED'
export type TargetRole='L1_ONCALL'|'L2_ONCALL'|'DUTY_LEAD'|'CUSTOMER_ADMIN'|'SERVICE_MANAGER'|'DECISION_MAKER'
export type SmsNoticeStatus='NOT_REQUIRED'|'INTENT_RECORDED_PENDING_GATEWAY'

export interface OnCallBoardKpis {
 unacknowledged_count:number;critical_count:number;in_progress_count:number;
 pending_review_count:number;recovered_today_count:number;mtta_p50_sec:number|null;mtta_target_sec:number
}
export interface OnCallQueueItem {
 ticket_id:string;priority:'P1'|'P2'|'P3';state:string;tier_snapshot:ServiceTierCode;
 assignee_user_id:string|null;created_at:string;response_due_at:string|null;recovery_due_at:string|null;
 countdown_remaining_sec:number|null;countdown_progress_pct:number|null;breached:boolean;
 current_escalation_step:number;next_escalation_step:number|null;next_escalation_target_role:TargetRole|null
}
export interface OnCallRosterEntry {
 id:string;tenant_id:string;version:number;shift_date:string;shift_code:ShiftCode;
 handoff_local_time:'18:00';primary_oncall_user_id:string;backup_oncall_user_id:string|null;
 note:string|null;roster_policy_status:'CONFIG_PENDING_Q15';updated_by:string;updated_at:string
}
export interface OnCallHandoffEntry {
 id:string;tenant_id:string;roster_id:string|null;outgoing_user_id:string;incoming_user_id:string;
 handoff_local_time:'18:00';unacknowledged_count:number;in_progress_count:number;pending_review_count:number;
 note:string;handed_off_at:string
}
export interface EscalationStep {
 step_order:number;trigger_condition:TriggerCondition;wait_minutes:number;target_role:TargetRole
}
export interface EscalationPolicy {
 tenant_id:string;tier:PolicyTierCode;version:number;unanswered_escalate_count:number;
 unanswered_retry_interval_sec:null;steps:EscalationStep[];policy_status:'CONFIG_PENDING_Q15';
 updated_by:string|null;updated_at:string;persisted:boolean
}
export interface EscalationStepRun {
 id:string;tenant_id:string;ticket_id:string;tier:PolicyTierCode;policy_version:number;
 step_order:number;trigger_condition:TriggerCondition;wait_minutes:number;target_role:TargetRole;
 unanswered_phone_count:number;escalated_to_step:number|null;escalated_target_role:TargetRole|null;
 sms_notice_status:SmsNoticeStatus;sms_notice_summary:string|null;recorded_by:string;recorded_at:string
}
export interface OnCallBoardPayload {
 tenant_id:string;kpis:OnCallBoardKpis;queue:OnCallQueueItem[];rosters:OnCallRosterEntry[];
 handoffs:OnCallHandoffEntry[];policies:EscalationPolicy[];step_runs:EscalationStepRun[];
 auto_refresh_sec:30;handoff_local_time:'18:00';roster_policy_status:'CONFIG_PENDING_Q15';
 observed_at:string;source:'postgresql'
}

export interface SlaLatencyStats {
 sample_count:number;unrecovered_count:number;mean_sec:number|null;p50_sec:number|null;p95_sec:number|null
 source_version?:'mop-event-acknowledgement-v1'|'mop-monitoring-recovery-t2-v1';unknown_count?:number
}
export interface SlaExplainedInterval {
 kind:'KNOWN_AVAILABLE'|'APPROVED_EXCLUSION'|'UNAVAILABLE_UNION'|'UNKNOWN_DATA_GAP';
 start:string;end:string;duration_sec:number;explanation:string
}
export interface SlaServiceRow {
 service_id:string;service_name:string;target_pct:number|null;agreed_service_sec:number;
 approved_exclusion_sec:number;effective_service_sec:number;unavailable_union_sec:number;
 unknown_interval_sec:number;known_available_sec:number;verified_availability_pct:number|null;
 upper_bound_availability_pct:number|null;incident_count:number;mtta:SlaLatencyStats;mttr:SlaLatencyStats;
 unrecovered_count:number;status:'MET'|'NOT_MET'|'INSUFFICIENT_SAMPLES'|'UNKNOWN_INTERVAL_PENDING';
 unknown_policy_status:'NO_UNKNOWN_GAPS'|'UNKNOWN_EXCLUDED_FROM_NUMERATOR_AND_DENOMINATOR';interval_details:SlaExplainedInterval[]
}
export interface SlaTicketJudgment {
 ticket_id:string;priority:'P1'|'P2'|'P3';tier_snapshot:ServiceTierCode;applicable:boolean|null;
 outcome:'MET'|'NOT_MET'|'UNRESOLVED'|'NOT_APPLICABLE';
 response_check:'MET'|'NOT_MET'|'UNKNOWN';
 recovery_check:'MET'|'NOT_MET'|'UNKNOWN';
 response_due_at:string|null;response_actual_at:string|null;
 recovery_due_at:string|null;recovery_actual_at:string|null;reasons:string[]
}
export interface SlaMonthlyEvaluation {
 tier_snapshot:ServiceTierCode|null;total_ticket_count:number;applicable_count:number;met_count:number;
 not_met_count:number;unresolved_count:number;not_applicable_count:number;
 achievement_numerator:number|null;achievement_denominator:number|null;achievement_rate_pct:number|null;
 compensation_ratio:0.1|0.2|0.3|null;monthly_fee_fen:number|null;compensation_amount_fen:null;
 compensation_status:'CONFIG_PENDING_Q26';candidate_fixed_ratio_fen:number|null;
 candidate_shortfall_weighted_fen:number|null;
 formula_version:'customer-20261020-availability-v2'|'contract-m5-9-m5-19-v1';items:SlaTicketJudgment[]
}
export interface SlaMonthlyResultRecord extends SlaMonthlyEvaluation {
 id:string;tenant_id:string;report_month:string;version:number;
 frozen:boolean;frozen_at:string|null;evaluated_by:string;evaluated_at:string
}
export interface SlaReportPayload {
 tenant_id:string;period:string;current_tier:ServiceTierCode|null;target_availability_pct:number|null;
 kpis:{
  mean_verified_availability_pct:number|null;unavailable_union_total_sec:number;
  mtta:SlaLatencyStats;mttr:SlaLatencyStats;unrecovered_total_count:number;unknown_interval_total_sec:number
 };
 services:SlaServiceRow[];monthly_result:SlaMonthlyResultRecord|null;
 live_monthly_evaluation:SlaMonthlyEvaluation;
 formula_version:'customer-20261020-availability-v2';timezone:'Asia/Shanghai';
 observed_at:string;source:'postgresql'
}
export interface SlaServiceDetailPayload {
 tenant_id:string;period:string;service:SlaServiceRow;
 interval_details:SlaExplainedInterval[];ticket_judgments:SlaTicketJudgment[];
 formula_version:'customer-20261020-availability-v2';timezone:'Asia/Shanghai';
 observed_at:string;source:'postgresql'
}

export interface OnCallContext {tenantId:string;actorId:string;permissionVersion:number;canWriteInternal:boolean;canWriteConfig:boolean}
type WriteAttempt={path:string;method:'POST'|'PATCH';key:string;body:Record<string,unknown>}

export interface OnCallReceipt {path:string;identity:string;version:number|null;recordedAt:string}
export interface OnCallBoardState {
 board:OnCallBoardPayload|null;receipt:OnCallReceipt|null;readFailed:boolean;busy:'read'|'write'|null;error:unknown;attempt:WriteAttempt|null
}
export interface SlaReportState {
 report:SlaReportPayload|null;detail:SlaServiceDetailPayload|null;receipt:SlaMonthlyResultRecord|null;
 busy:'read'|'write'|null;error:unknown;attempt:WriteAttempt|null
}

export const emptyOnCallBoardState=():OnCallBoardState=>({board:null,receipt:null,readFailed:false,busy:null,error:null,attempt:null})
export const emptySlaReportState=():SlaReportState=>({report:null,detail:null,receipt:null,busy:null,error:null,attempt:null})

export const shiftCodeLabels:Record<ShiftCode,string>={DAY:'白班（09:00-18:00）',NIGHT:'夜班（18:00-次日09:00）',FULL_DAY:'全天值班（24h）'}
export const policyTierLabels:Record<PolicyTierCode,string>={BASIC:'基础版升级链',STANDARD:'标准版升级链',ADVANCED:'高级版升级链',FALLBACK:'未配置档位兜底链'}
export const triggerConditionLabels:Record<TriggerCondition,string>={
 IMMEDIATE:'立即通知',UNACKNOWLEDGED:'超时未确认',UNRESOLVED:'超时未恢复',
 COUNTDOWN_ZERO:'SLA倒计时归零',PHONE_UNANSWERED:'电话连续未接听'
}
export const targetRoleLabels:Record<TargetRole,string>={
 L1_ONCALL:'一线值班工程师',L2_ONCALL:'二线值班专家',DUTY_LEAD:'值班主管',
 CUSTOMER_ADMIN:'客户管理员',SERVICE_MANAGER:'服务经理',DECISION_MAKER:'决策人'
}
export const serviceStatusLabels:Record<SlaServiceRow['status'],string>={
 MET:'达标',NOT_MET:'未达标',INSUFFICIENT_SAMPLES:'样本不足或目标未配置',UNKNOWN_INTERVAL_PENDING:'含未知区间'
}

export function formatCountdown(sec:number|null):string{
 if(sec===null)return '待配置'
 const abs=Math.abs(Math.trunc(sec))
 const h=Math.floor(abs/3600),m=Math.floor((abs%3600)/60),s=abs%60
 const text=`${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`
 return sec<0?`已超时 ${text}`:`剩余 ${text}`
}
export function formatDurationSec(sec:number|null):string{
 if(sec===null)return '暂无样本'
 if(sec<60)return `${sec}s`
 const m=Math.floor(sec/60),s=sec%60
 if(m<60)return s>0?`${m}m ${s}s`:`${m}m`
 const h=Math.floor(m/60),rm=m%60
 return rm>0?`${h}h ${rm}m`:`${h}h`
}
export function formatFenToYuan(fen:number|null):string{
 if(fen===null)return '待确认 / 未登记'
 return `¥${(fen/100).toFixed(2)}`
}

const invalid=()=>new ApiError(0,'INVALID_ONCALL_OR_SLA_PAYLOAD','值班看板或SLA月报数据未通过完整性校验，请刷新核对','—')
const validContext=(c:OnCallContext|null):c is OnCallContext=>
 Boolean(c&&exportUuid(c.tenantId)&&exportUuid(c.actorId)&&exportInteger(c.permissionVersion,1)&&typeof c.canWriteInternal==='boolean'&&typeof c.canWriteConfig==='boolean')
const isTier=(v:unknown):v is ServiceTierCode=>v==='BASIC'||v==='STANDARD'||v==='ADVANCED'
const isTargetRole=(v:unknown):v is TargetRole=>['L1_ONCALL','L2_ONCALL','DUTY_LEAD','CUSTOMER_ADMIN','SERVICE_MANAGER','DECISION_MAKER'].includes(v as string)

export function readOnCallBoard(raw:unknown,scope:OnCallContext,maxRows=100):OnCallBoardPayload{
 if(!validContext(scope)||!exportExact(raw,['tenant_id','kpis','queue','rosters','handoffs','policies','step_runs','auto_refresh_sec','handoff_local_time','roster_policy_status','observed_at','source']))throw invalid()
 if(raw.tenant_id!==scope.tenantId||raw.auto_refresh_sec!==30||raw.handoff_local_time!=='18:00'||raw.roster_policy_status!=='CONFIG_PENDING_Q15'||raw.source!=='postgresql')throw invalid()
 exportTime(raw.observed_at)
 validateOnCallBoardDetails(raw,scope.tenantId,maxRows)
 return structuredClone(raw) as unknown as OnCallBoardPayload
}

function validateMonthlyEvaluation(raw:Record<string,unknown>){
 validateSlaMonthlyNumbers(raw)
 if((raw.tier_snapshot!==null&&!isTier(raw.tier_snapshot))||raw.compensation_amount_fen!==null||raw.compensation_status!=='CONFIG_PENDING_Q26'||!['customer-20261020-availability-v2','contract-m5-9-m5-19-v1'].includes(raw.formula_version as string)||!Array.isArray(raw.items))throw invalid()
 if(raw.tier_snapshot===null?raw.compensation_ratio!==null:![0.1,0.2,0.3].includes(raw.compensation_ratio as number))throw invalid()
}

export const validSlaMonth=(value:unknown):value is string=>typeof value==='string'&&/^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(value)
export function readSlaMonthlyRecord(raw:unknown,scope:OnCallContext,period:string):SlaMonthlyResultRecord {
 if(!validContext(scope)||!validSlaMonth(period)||!exportExact(raw,['id','tenant_id','report_month','version','tier_snapshot','total_ticket_count','applicable_count','met_count','not_met_count','unresolved_count','not_applicable_count','achievement_numerator','achievement_denominator','achievement_rate_pct','compensation_ratio','monthly_fee_fen','compensation_amount_fen','compensation_status','candidate_fixed_ratio_fen','candidate_shortfall_weighted_fen','formula_version','items','frozen','frozen_at','evaluated_by','evaluated_at']))throw invalid()
 if(!exportUuid(raw.id)||raw.tenant_id!==scope.tenantId||raw.report_month!==period||!exportInteger(raw.version,1)||!exportUuid(raw.evaluated_by)||typeof raw.frozen!=='boolean'||raw.frozen!==(raw.frozen_at!==null))throw invalid()
 exportTime(raw.evaluated_at);if(raw.frozen_at!==null)exportTime(raw.frozen_at)
 for(const key of ['total_ticket_count','applicable_count','met_count','not_met_count','unresolved_count','not_applicable_count'])if(!exportInteger(raw[key]))throw invalid()
 for(const key of ['monthly_fee_fen','candidate_fixed_ratio_fen','candidate_shortfall_weighted_fen','achievement_numerator','achievement_denominator'])if(raw[key]!==null&&!exportInteger(raw[key]))throw invalid()
 if(raw.achievement_rate_pct!==null&&(typeof raw.achievement_rate_pct!=='number'||!Number.isFinite(raw.achievement_rate_pct)||raw.achievement_rate_pct<0||raw.achievement_rate_pct>100))throw invalid()
 validateMonthlyEvaluation(raw);return structuredClone(raw) as unknown as SlaMonthlyResultRecord
}

export function readSlaReport(raw:unknown,scope:OnCallContext,expectedPeriod:string):SlaReportPayload{
 if(!validSlaMonth(expectedPeriod)||!validContext(scope)||!exportExact(raw,['tenant_id','period','current_tier','target_availability_pct','kpis','services','monthly_result','live_monthly_evaluation','formula_version','timezone','observed_at','source']))throw invalid()
 if(raw.tenant_id!==scope.tenantId||raw.period!==expectedPeriod||(raw.current_tier!==null&&!isTier(raw.current_tier))||raw.formula_version!=='customer-20261020-availability-v2'||raw.timezone!=='Asia/Shanghai'||raw.source!=='postgresql')throw invalid()
 exportTime(raw.observed_at)
 if(!Array.isArray(raw.services)||raw.services.length>1000)throw invalid()
 validateSlaKpis(raw.kpis);if(!validSlaPercentage(raw.target_availability_pct))throw invalid()
 const services=new Set<string>();for(const service of raw.services){validateSlaService(service);const id=(service as Record<string,unknown>).service_id as string;if(services.has(id))throw invalid();services.add(id)}
 if(!exportExact(raw.live_monthly_evaluation,['tier_snapshot','total_ticket_count','applicable_count','met_count','not_met_count','unresolved_count','not_applicable_count','achievement_numerator','achievement_denominator','achievement_rate_pct','compensation_ratio','monthly_fee_fen','compensation_amount_fen','compensation_status','candidate_fixed_ratio_fen','candidate_shortfall_weighted_fen','formula_version','items']))throw invalid()
 validateMonthlyEvaluation(raw.live_monthly_evaluation)
 if(raw.monthly_result!==null)readSlaMonthlyRecord(raw.monthly_result,scope,expectedPeriod)
 return structuredClone(raw) as unknown as SlaReportPayload
}

export function readSlaServiceDetail(raw:unknown,scope:OnCallContext,expectedPeriod:string,expectedServiceId:string):SlaServiceDetailPayload{
 if(!validSlaMonth(expectedPeriod)||!validSlaServiceIdentity(expectedServiceId)||!validContext(scope)||!exportExact(raw,['tenant_id','period','service','interval_details','ticket_judgments','formula_version','timezone','observed_at','source']))throw invalid()
 if(raw.tenant_id!==scope.tenantId||raw.period!==expectedPeriod||raw.formula_version!=='customer-20261020-availability-v2'||raw.timezone!=='Asia/Shanghai'||raw.source!=='postgresql')throw invalid()
 exportTime(raw.observed_at)
 const svc=raw.service as Record<string,unknown>
 validateSlaService(svc);validateSlaIntervals(raw.interval_details);validateSlaJudgments(raw.ticket_judgments)
 if(svc.service_id!==expectedServiceId||!exportEquals(svc.interval_details,raw.interval_details))throw invalid()
 return structuredClone(raw) as unknown as SlaServiceDetailPayload
}

function readOnCallReceipt(raw:unknown,scope:OnCallContext,attempt:WriteAttempt):OnCallReceipt {
 const b=attempt.body,r=raw as Record<string,unknown>
 const check=(keys:string[])=>{if(!exportExact(raw,keys)||r.tenant_id!==scope.tenantId)throw invalid()}
 const same=(keys:string[])=>{if(keys.some(k=>r[k]!==b[k]))throw invalid()}
 const stamp=(key:string)=>{exportTime(r[key]);return r[key] as string}
 const identity=()=>{if(!exportUuid(r.id))throw invalid();return r.id as string}
 if(attempt.path==='/oncall/rosters'){
  validateOnCallRoster(raw,scope.tenantId)
  check(['id','tenant_id','version','shift_date','shift_code','handoff_local_time','primary_oncall_user_id','backup_oncall_user_id','note','roster_policy_status','updated_by','updated_at'])
  same(['shift_date','shift_code','primary_oncall_user_id','backup_oncall_user_id','note'])
  if(!exportInteger(r.version,1)||r.version!==Number(b.expected_version)+1||r.updated_by!==scope.actorId||r.handoff_local_time!=='18:00'||r.roster_policy_status!=='CONFIG_PENDING_Q15')throw invalid()
  return {path:attempt.path,identity:identity(),version:r.version as number,recordedAt:stamp('updated_at')}
 }
 if(attempt.path==='/oncall/handoffs'){
  validateOnCallHandoff(raw,scope.tenantId)
  check(['id','tenant_id','roster_id','outgoing_user_id','incoming_user_id','handoff_local_time','unacknowledged_count','in_progress_count','pending_review_count','note','handed_off_at'])
  same(['roster_id','incoming_user_id','note'])
  if(r.outgoing_user_id!==scope.actorId||r.outgoing_user_id===r.incoming_user_id||r.handoff_local_time!=='18:00'||!['unacknowledged_count','in_progress_count','pending_review_count'].every(k=>exportInteger(r[k])))throw invalid()
  return {path:attempt.path,identity:identity(),version:null,recordedAt:stamp('handed_off_at')}
 }
 if(attempt.path==='/escalation-policies'){
  validateOnCallPolicy(raw,scope.tenantId)
  check(['tenant_id','tier','version','unanswered_escalate_count','unanswered_retry_interval_sec','steps','policy_status','updated_by','updated_at','persisted'])
  same(['tier','unanswered_escalate_count'])
  const requested=b.steps as EscalationStep[]
  if(!exportInteger(r.version,1)||r.version!==Number(b.expected_version)+1||r.updated_by!==scope.actorId||r.persisted!==true||r.unanswered_retry_interval_sec!==null||r.policy_status!=='CONFIG_PENDING_Q15'||!Array.isArray(r.steps)||r.steps.length!==requested.length||r.steps.some((step,i)=>!exportExact(step,['step_order','trigger_condition','wait_minutes','target_role'])||Object.keys(step).some(k=>step[k]!==requested[i]![k as keyof EscalationStep])))throw invalid()
  return {path:attempt.path,identity:String(r.tier),version:r.version as number,recordedAt:stamp('updated_at')}
 }
 if(attempt.path==='/oncall/escalations'){
  validateOnCallStepRun(raw,scope.tenantId)
  check(['id','tenant_id','ticket_id','tier','policy_version','step_order','trigger_condition','wait_minutes','target_role','unanswered_phone_count','escalated_to_step','escalated_target_role','sms_notice_status','sms_notice_summary','recorded_by','recorded_at'])
  same(['ticket_id','tier','step_order','trigger_condition','unanswered_phone_count'])
  if(r.recorded_by!==scope.actorId||!exportInteger(r.policy_version)||!exportInteger(r.wait_minutes,0,10080)||!isTargetRole(r.target_role)||r.escalated_to_step!==null&&!exportInteger(r.escalated_to_step,1,8)||r.escalated_target_role!==null&&!isTargetRole(r.escalated_target_role)||!['NOT_REQUIRED','INTENT_RECORDED_PENDING_GATEWAY'].includes(r.sms_notice_status as string)||r.sms_notice_summary!==null&&typeof r.sms_notice_summary!=='string')throw invalid()
  return {path:attempt.path,identity:identity(),version:null,recordedAt:stamp('recorded_at')}
 }
 throw invalid()
}

export function createOnCallBoardWorkspace(client:{request<T>(path:string,options?:RequestOptions):Promise<T>},changed:(s:OnCallBoardState)=>void,newKey=createRequestId){
 let state=emptyOnCallBoardState(),context:OnCallContext|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>({...structuredClone({...state,error:null}),error:state.error}),emit=()=>changed(snapshot())
 function reset(next:OnCallContext|null){epoch++;abort?.abort();abort=null;context=!disposed&&validContext(next)?{...next}:null;state=emptyOnCallBoardState();emit()}
 async function load(){
  if(disposed||!context||state.busy||state.attempt)return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,board:null,busy:'read',error:null,readFailed:false};emit()
  try{
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const raw=await client.request('/oncall/board?page_size=25',{signal:controller.signal})
   if(disposed||turn!==epoch)return false
   const board=readOnCallBoard(raw,scope,25)
   abort=null;state={...state,board,busy:null,error:null,readFailed:false};emit()
   return !disposed&&turn===epoch
  }catch(error){
   if(disposed||turn!==epoch)return false
   abort=null;state={...state,busy:null,error,readFailed:true};emit()
   return false
  }
 }
 async function send(original:WriteAttempt,retry=false){
  if(disposed||!context||state.busy||(original.path==='/escalation-policies'?!context.canWriteConfig:!context.canWriteInternal))return false
  const activeContext=context,attempt=structuredClone(original)
  const turn=++epoch,scope=context?{...context}:null
  state={...state,busy:'write',error:null,readFailed:false,receipt:null,attempt};emit()
  try{
   if(disposed||turn!==epoch||context!==activeContext||(attempt.path==='/escalation-policies'?!context?.canWriteConfig:!context?.canWriteInternal))return false
   const raw=await client.request(attempt.path,{method:attempt.method,idempotencyKey:attempt.key,body:structuredClone(attempt.body)})
   if(disposed||turn!==epoch||!scope)return false
   const receipt=readOnCallReceipt(raw,scope,attempt)
   state={...state,board:null,receipt,busy:null,error:null,attempt:null};emit()
   if(disposed||turn!==epoch||context!==activeContext)return false
   await load();return !disposed&&context===activeContext&&state.receipt?.path===receipt.path&&state.receipt?.identity===receipt.identity
  }catch(error){
   if(disposed||turn!==epoch)return false
   const keep=writeFailureUncertain(retry,error)
   state={...state,busy:null,error,attempt:keep?attempt:null};emit()
   return false
  }
 }
 function commandKey(){const generation=epoch,key=newKey();if(generation!==epoch||disposed||!context)throw new Error('ONCALL_CONTEXT_CHANGED');if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw invalid();return key}
 async function prepareWrite(path:string,method:'POST'|'PATCH',body:Record<string,unknown>){
  const generation=epoch
  try{const key=commandKey();if(generation!==epoch||disposed||!context)return false;return send({path,method,key,body})}catch(error){if(generation===epoch&&!disposed){state.error=error;state.readFailed=false;emit()}return false}
 }
 async function submitRoster(input:{expected_version:number;shift_date:string;shift_code:ShiftCode;primary_oncall_user_id:string;backup_oncall_user_id:string|null;note:string|null}){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return prepareWrite('/oncall/rosters','POST',structuredClone(input))
 }
 async function submitHandoff(input:{roster_id:string|null;incoming_user_id:string;note:string}){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return prepareWrite('/oncall/handoffs','POST',structuredClone(input))
 }
 async function submitEscalation(input:{ticket_id:string;tier:PolicyTierCode;step_order:number;trigger_condition:TriggerCondition;unanswered_phone_count:number}){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  return prepareWrite('/oncall/escalations','POST',structuredClone(input))
 }
 async function submitPolicyUpdate(input:{tier:PolicyTierCode;expected_version:number;unanswered_escalate_count:number;steps:EscalationStep[]}){
  if(disposed||!context||!context.canWriteConfig||state.busy||state.attempt)return false
  return prepareWrite('/escalation-policies','PATCH',structuredClone(input))
 }
 async function retry(){
  if(disposed||!context||state.busy||!state.attempt)return false
  return send(state.attempt,true)
 }
 function cancelRead(){if(disposed||state.busy!=='read')return false;epoch++;abort?.abort();abort=null;state={...state,busy:null,error:null,readFailed:false};emit();return true}
 return{
  state:snapshot,reset,load,submitRoster,submitHandoff,submitEscalation,submitPolicyUpdate,retry,cancelRead,
  unresolved:()=>Boolean(state.busy==='write'||state.attempt),
  dispose(){disposed=true;epoch++;abort?.abort();abort=null}
 }
}

export function createSlaReportWorkspace(client:{request<T>(path:string,options?:RequestOptions):Promise<T>},changed:(s:SlaReportState)=>void,newKey=createRequestId){
 let state=emptySlaReportState(),context:OnCallContext|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>({...structuredClone({...state,error:null}),error:state.error}),emit=()=>changed(snapshot())
 function reset(next:OnCallContext|null){epoch++;abort?.abort();abort=null;context=!disposed&&validContext(next)?{...next}:null;state=emptySlaReportState();emit()}
 async function load(period:string){
  if(disposed||!context||state.busy||state.attempt||!validSlaMonth(period))return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,report:null,detail:null,busy:'read',error:null};emit()
  try{
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const raw=await client.request(`/sla/reports?period=${encodeURIComponent(period)}`,{signal:controller.signal})
   if(disposed||turn!==epoch)return false
   const report=readSlaReport(raw,scope,period)
   abort=null;state={...state,report,detail:null,busy:null,error:null};emit()
   return true
  }catch(error){
   if(disposed||turn!==epoch)return false
   abort=null;state={...state,busy:null,error};emit()
   return false
  }
 }
 async function loadServiceDetail(period:string,serviceId:string){
  if(disposed||!context||state.busy||state.attempt||!validSlaMonth(period)||!validSlaServiceIdentity(serviceId))return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller
  state={...state,detail:null,busy:'read',error:null};emit()
  try{
   if(disposed||turn!==epoch||controller.signal.aborted)return false
   const raw=await client.request(`/sla/reports/${encodeURIComponent(serviceId)}/details?period=${encodeURIComponent(period)}`,{signal:controller.signal})
   if(disposed||turn!==epoch)return false
   const detail=readSlaServiceDetail(raw,scope,period,serviceId)
   abort=null;state={...state,detail,busy:null,error:null};emit()
   return true
  }catch(error){
   if(disposed||turn!==epoch)return false
   abort=null;state={...state,busy:null,error};emit()
   return false
  }
 }
 async function send(original:WriteAttempt,reloadPeriod:string,retry=false){
  if(disposed||!context?.canWriteInternal||state.busy)return false
  const attempt=structuredClone(original)
  const turn=++epoch,scope=context?{...context}:null
  state={...state,busy:'write',error:null,receipt:null,attempt};emit()
  try{
   if(disposed||turn!==epoch||!scope||!context?.canWriteInternal)return false
   const raw=await client.request(attempt.path,{method:attempt.method,idempotencyKey:attempt.key,body:structuredClone(attempt.body)})
   if(disposed||turn!==epoch||!scope)return false
   const receipt=readSlaMonthlyRecord(raw,scope,reloadPeriod)
   if(receipt.evaluated_by!==scope.actorId||receipt.version!==Number(attempt.body.expected_version)+1||receipt.frozen!==attempt.body.freeze)throw invalid()
   state={...state,busy:null,error:null,report:null,detail:null,receipt,attempt:null};emit()
   const reloadTurn=epoch+1;await load(reloadPeriod);return !disposed&&epoch===reloadTurn
  }catch(error){
   if(disposed||turn!==epoch)return false
   const keep=writeFailureUncertain(retry,error)
   state={...state,busy:null,error,attempt:keep?attempt:null};emit()
   return false
  }
 }
 async function submitMonthlyResult(input:{report_month:string;expected_version:number;freeze:boolean}){
  if(disposed||!context||!context.canWriteInternal||state.busy||state.attempt)return false
  if(!state.report||!validSlaMonth(input.report_month)||input.report_month!==state.report.period||state.report.monthly_result?.frozen||input.expected_version!==(state.report.monthly_result?.version??0)||!exportInteger(input.expected_version,0,Number.MAX_SAFE_INTEGER-1)||typeof input.freeze!=='boolean'){state.error=invalid();emit();return false}
  try{const generation=epoch,key=newKey();if(generation!==epoch||disposed||!context?.canWriteInternal)return false;if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw invalid();return send({path:'/sla/monthly-results',method:'POST',key,body:{...input}},input.report_month)}catch(error){state.error=error;emit();return false}
 }
 async function retry(){
  if(disposed||!context||state.busy||!state.attempt)return false
  const period=String(state.attempt.body.report_month)
  return send(state.attempt,period,true)
 }
 function cancelRead(){
  if(disposed||state.busy!=='read')return false
  epoch++;abort?.abort();abort=null;state={...state,busy:null,error:null};emit();return true
 }
 return{
  state:snapshot,reset,load,loadServiceDetail,submitMonthlyResult,retry,cancelRead,
  unresolved:()=>Boolean(state.busy==='write'||state.attempt),
  dispose(){disposed=true;epoch++;abort?.abort();abort=null}
 }
}
