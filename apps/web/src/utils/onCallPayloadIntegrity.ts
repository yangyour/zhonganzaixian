import {ApiError} from '../api/client'
import {exportExact,exportInteger,exportTime,exportUuid} from './assetExportJobs'

const policyTiers=['BASIC','STANDARD','ADVANCED','FALLBACK']
const roles=['L1_ONCALL','L2_ONCALL','DUTY_LEAD','CUSTOMER_ADMIN','SERVICE_MANAGER','DECISION_MAKER']
const triggers=['IMMEDIATE','UNACKNOWLEDGED','UNRESOLVED','COUNTDOWN_ZERO','PHONE_UNANSWERED']
const versionMax=Number.MAX_SAFE_INTEGER-1
function bad():never{throw new ApiError(0,'INVALID_ONCALL_OR_SLA_PAYLOAD','值班快照数值、身份或升级记录未通过完整性核对，未显示为有效结果','—')}
const member=(v:unknown,values:string[])=>typeof v==='string'&&values.includes(v)
const nullableUuid=(v:unknown)=>v===null||exportUuid(v)
const text=(v:unknown,max:number)=>typeof v==='string'&&v.trim().length>0&&v.length<=max
function time(v:unknown){try{return exportTime(v)}catch{bad()}}
function date(v:unknown){if(typeof v!=='string'||!/^(?!0000)\d{4}-\d{2}-\d{2}$/.test(v))return false;const at=Date.parse(v+'T00:00:00Z');return Number.isFinite(at)&&new Date(at).toISOString().slice(0,10)===v}
function rows(v:unknown,max:number):unknown[]{if(!Array.isArray(v)||v.length>max)bad();return v}
function unique(seen:Set<string>,key:unknown){if(typeof key!=='string'||seen.has(key))bad();seen.add(key)}
export function validateOnCallRoster(v:unknown,tenant:string){
 if(!exportExact(v,['id','tenant_id','version','shift_date','shift_code','handoff_local_time','primary_oncall_user_id','backup_oncall_user_id','note','roster_policy_status','updated_by','updated_at']))bad()
 if(v.tenant_id!==tenant||!exportUuid(v.id)||!exportInteger(v.version,1,versionMax)||!date(v.shift_date)||!member(v.shift_code,['DAY','NIGHT','FULL_DAY'])||v.handoff_local_time!=='18:00'||!exportUuid(v.primary_oncall_user_id)||!nullableUuid(v.backup_oncall_user_id)||v.backup_oncall_user_id===v.primary_oncall_user_id||v.note!==null&&!text(v.note,500)||v.roster_policy_status!=='CONFIG_PENDING_Q15'||!exportUuid(v.updated_by))bad()
 time(v.updated_at)
}
export function validateOnCallHandoff(v:unknown,tenant:string){
 if(!exportExact(v,['id','tenant_id','roster_id','outgoing_user_id','incoming_user_id','handoff_local_time','unacknowledged_count','in_progress_count','pending_review_count','note','handed_off_at']))bad()
 if(v.tenant_id!==tenant||!exportUuid(v.id)||!nullableUuid(v.roster_id)||!exportUuid(v.outgoing_user_id)||!exportUuid(v.incoming_user_id)||v.outgoing_user_id===v.incoming_user_id||v.handoff_local_time!=='18:00'||!text(v.note,1000))bad()
 for(const k of ['unacknowledged_count','in_progress_count','pending_review_count'])if(!exportInteger(v[k],0,1000000))bad()
 time(v.handed_off_at)
}
export function validateOnCallPolicy(v:unknown,tenant:string){
 if(!exportExact(v,['tenant_id','tier','version','unanswered_escalate_count','unanswered_retry_interval_sec','steps','policy_status','updated_by','updated_at','persisted']))bad()
 if(v.tenant_id!==tenant||!member(v.tier,policyTiers)||!exportInteger(v.unanswered_escalate_count,1,10)||v.unanswered_retry_interval_sec!==null||v.policy_status!=='CONFIG_PENDING_Q15'||typeof v.persisted!=='boolean')bad()
 if(v.persisted?(!exportInteger(v.version,1,versionMax)||!exportUuid(v.updated_by)):(v.version!==0||v.updated_by!==null))bad()
 time(v.updated_at);const steps=rows(v.steps,8);if(!steps.length)bad()
 steps.forEach((s,index)=>{if(!exportExact(s,['step_order','trigger_condition','wait_minutes','target_role'])||s.step_order!==index+1||!member(s.trigger_condition,triggers)||!exportInteger(s.wait_minutes,0,10080)||!member(s.target_role,roles))bad()})
}
export function validateOnCallStepRun(v:unknown,tenant:string){
 if(!exportExact(v,['id','tenant_id','ticket_id','tier','policy_version','step_order','trigger_condition','wait_minutes','target_role','unanswered_phone_count','escalated_to_step','escalated_target_role','sms_notice_status','sms_notice_summary','recorded_by','recorded_at']))bad()
 if(v.tenant_id!==tenant||!exportUuid(v.id)||!exportUuid(v.ticket_id)||!member(v.tier,policyTiers)||!exportInteger(v.policy_version,1,versionMax)||!exportInteger(v.step_order,1,8)||!member(v.trigger_condition,triggers)||!exportInteger(v.wait_minutes,0,10080)||!member(v.target_role,roles)||!exportInteger(v.unanswered_phone_count,0,10)||!exportUuid(v.recorded_by))bad()
 if(v.escalated_to_step===null?v.escalated_target_role!==null:(!exportInteger(v.escalated_to_step,2,8)||v.escalated_to_step!==Number(v.step_order)+1||!member(v.escalated_target_role,roles)))bad()
 if(v.sms_notice_status==='NOT_REQUIRED'?v.sms_notice_summary!==null:v.sms_notice_status!=='INTENT_RECORDED_PENDING_GATEWAY'||!text(v.sms_notice_summary,500))bad()
 time(v.recorded_at)
}
function validateQueue(v:unknown){
 if(!exportExact(v,['ticket_id','priority','state','tier_snapshot','assignee_user_id','created_at','response_due_at','recovery_due_at','countdown_remaining_sec','countdown_progress_pct','breached','current_escalation_step','next_escalation_step','next_escalation_target_role']))bad()
 if(!exportUuid(v.ticket_id)||!member(v.priority,['P1','P2','P3'])||!member(v.state,['OPEN','ACCEPTED','IN_PROGRESS','RESOLVED'])||!member(v.tier_snapshot,['BASIC','STANDARD','ADVANCED'])||!nullableUuid(v.assignee_user_id)||typeof v.breached!=='boolean'||!exportInteger(v.current_escalation_step,1,8))bad()
 time(v.created_at);for(const k of ['response_due_at','recovery_due_at'])if(v[k]!==null)time(v[k])
 if(v.countdown_remaining_sec===null?v.countdown_progress_pct!==null:(!exportInteger(v.countdown_remaining_sec,-Number.MAX_SAFE_INTEGER)||typeof v.countdown_progress_pct!=='number'||!Number.isFinite(v.countdown_progress_pct)||v.countdown_progress_pct<0||v.countdown_progress_pct>1))bad()
 // Breach can refer to another SLA clock. Never infer it solely from this countdown.
 if(v.next_escalation_step===null?v.next_escalation_target_role!==null:(!exportInteger(v.next_escalation_step,2,8)||v.next_escalation_step!==Number(v.current_escalation_step)+1||!member(v.next_escalation_target_role,roles)))bad()
}
/** Validate the finite response without inventing totals from the limited lists. */
export function validateOnCallBoardDetails(v:Record<string,unknown>,tenant:string,maxRows=100){
 if(!exportInteger(maxRows,1,100))bad()
 const k=v.kpis;if(!exportExact(k,['unacknowledged_count','critical_count','in_progress_count','pending_review_count','recovered_today_count','mtta_p50_sec','mtta_target_sec']))bad()
 for(const key of ['unacknowledged_count','critical_count','in_progress_count','pending_review_count','recovered_today_count'])if(!exportInteger(k[key]))bad()
 if(k.mtta_p50_sec!==null&&!exportInteger(k.mtta_p50_sec)||!exportInteger(k.mtta_target_sec,1))bad()
 for(const [key,validate] of [['queue',validateQueue],['rosters',validateOnCallRoster],['handoffs',validateOnCallHandoff],['step_runs',validateOnCallStepRun]] as const){
  const seen=new Set<string>(),rosterKeys=new Set<string>();for(const row of rows(v[key],maxRows)){validate(row,tenant);const item=row as Record<string,unknown>;unique(seen,item[key==='queue'?'ticket_id':'id']);if(key==='rosters')unique(rosterKeys,`${item.shift_date}:${item.shift_code}`)}
 }
 const seen=new Set<string>();for(const row of rows(v.policies,4)){validateOnCallPolicy(row,tenant);unique(seen,(row as Record<string,unknown>).tier)}
}
