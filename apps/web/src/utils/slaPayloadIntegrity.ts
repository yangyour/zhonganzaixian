import {ApiError} from '../api/client'
import {exportExact,exportInteger} from './assetExportJobs'
import {utcInstantNanos} from './utcInstant'
function bad():never{throw new ApiError(0,'INVALID_ONCALL_OR_SLA_PAYLOAD','SLA 数值、服务身份或区间明细未通过核对，未显示为有效结果','—')}
const text=(value:unknown):value is string=>typeof value==='string'&&value.trim().length>0&&!/[\u0000-\u001f\u007f-\u009f\uD800-\uDFFF]/u.test(value)
const pct=(value:unknown)=>value===null||typeof value==='number'&&Number.isFinite(value)&&value>=0&&value<=100
const optionalInteger=(value:unknown)=>value===null||exportInteger(value)
const tiers=['BASIC','STANDARD','ADVANCED']
export function validateSlaLatency(value:unknown){
 if(!exportExact(value,['sample_count','unrecovered_count','mean_sec','p50_sec','p95_sec'])||!exportInteger(value.sample_count)||!exportInteger(value.unrecovered_count))bad()
 for(const key of ['mean_sec','p50_sec','p95_sec'])if(!optionalInteger(value[key]))bad()
 if(value.sample_count===0?[value.mean_sec,value.p50_sec,value.p95_sec].some(v=>v!==null):[value.mean_sec,value.p50_sec,value.p95_sec].some(v=>v===null))bad()
 if(value.p50_sec!==null&&value.p95_sec!==null&&Number(value.p50_sec)>Number(value.p95_sec))bad()
}
export function validateSlaIntervals(value:unknown){
 if(!Array.isArray(value))bad()
 for(const row of value){
  if(!exportExact(row,['kind','start','end','duration_sec','explanation'])||!['KNOWN_AVAILABLE','APPROVED_EXCLUSION','UNAVAILABLE_UNION','UNKNOWN_DATA_GAP'].includes(row.kind as string)||!exportInteger(row.duration_sec,1)||typeof row.explanation!=='string'||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\uD800-\uDFFF]/u.test(row.explanation))bad()
  const start=utcInstantNanos(row.start),end=utcInstantNanos(row.end)
  if(start===null||end===null||end<=start||(end-start)/1_000_000_000n!==BigInt(Number(row.duration_sec)))bad()
 }
}
export function validateSlaService(value:unknown){
 if(!exportExact(value,['service_id','service_name','target_pct','agreed_service_sec','approved_exclusion_sec','effective_service_sec','unavailable_union_sec','unknown_interval_sec','known_available_sec','verified_availability_pct','upper_bound_availability_pct','incident_count','mtta','mttr','unrecovered_count','status','unknown_policy_status','interval_details']))bad()
 if(!text(value.service_id)||!text(value.service_name)||!['MET','NOT_MET','INSUFFICIENT_SAMPLES','UNKNOWN_INTERVAL_PENDING'].includes(value.status as string)||!['NO_UNKNOWN_GAPS','UNKNOWN_EXCLUDED_FROM_NUMERATOR_AND_DENOMINATOR'].includes(value.unknown_policy_status as string))bad()
 for(const key of ['target_pct','verified_availability_pct','upper_bound_availability_pct'])if(!pct(value[key]))bad()
 for(const key of ['agreed_service_sec','approved_exclusion_sec','effective_service_sec','unavailable_union_sec','unknown_interval_sec','known_available_sec','incident_count','unrecovered_count'])if(!exportInteger(value[key]))bad()
 if(Number(value.unknown_interval_sec)>0&&value.unknown_policy_status!=='UNKNOWN_EXCLUDED_FROM_NUMERATOR_AND_DENOMINATOR')bad()
 validateSlaLatency(value.mtta);validateSlaLatency(value.mttr);validateSlaIntervals(value.interval_details)
}
export function validateSlaKpis(value:unknown){
 if(!exportExact(value,['mean_verified_availability_pct','unavailable_union_total_sec','mtta','mttr','unrecovered_total_count','unknown_interval_total_sec'])||!pct(value.mean_verified_availability_pct))bad()
 for(const key of ['unavailable_union_total_sec','unrecovered_total_count','unknown_interval_total_sec'])if(!exportInteger(value[key]))bad()
 validateSlaLatency(value.mtta);validateSlaLatency(value.mttr)
}
export function validateSlaJudgments(value:unknown){
 if(!Array.isArray(value))bad();const seen=new Set<string>()
 for(const row of value){
  if(!exportExact(row,['ticket_id','priority','tier_snapshot','applicable','outcome','response_check','recovery_check','response_due_at','response_actual_at','recovery_due_at','recovery_actual_at','reasons'])||!text(row.ticket_id)||!['P1','P2','P3'].includes(row.priority as string)||!tiers.includes(row.tier_snapshot as string)||![null,true,false].includes(row.applicable as null|boolean)||!['MET','NOT_MET','UNRESOLVED','NOT_APPLICABLE'].includes(row.outcome as string)||!['MET','NOT_MET','UNKNOWN'].includes(row.response_check as string)||!['MET','NOT_MET','UNKNOWN'].includes(row.recovery_check as string)||!Array.isArray(row.reasons)||row.reasons.some(v=>!text(v)))bad()
  if(seen.has(row.ticket_id as string))bad();seen.add(row.ticket_id as string)
  for(const key of ['response_due_at','response_actual_at','recovery_due_at','recovery_actual_at'])if(row[key]!==null&&utcInstantNanos(row[key])===null)bad()
 }
}
export function validateSlaMonthlyNumbers(value:Record<string,unknown>){
 for(const key of ['total_ticket_count','applicable_count','met_count','not_met_count','unresolved_count','not_applicable_count'])if(!exportInteger(value[key]))bad()
 for(const key of ['monthly_fee_fen','candidate_fixed_ratio_fen','candidate_shortfall_weighted_fen','achievement_numerator','achievement_denominator'])if(!optionalInteger(value[key]))bad()
 if(!pct(value.achievement_rate_pct))bad();validateSlaJudgments(value.items)
}
export const validSlaServiceIdentity=text
export const validSlaPercentage=pct
