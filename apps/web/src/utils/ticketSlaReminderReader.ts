import { ApiError } from '../api/client'
import { utcInstantNanos } from './utcInstant'
export interface SlaReminder { intent_id:string; clock:'RESPONSE'|'RECOVERY'; progress:0.5|0.8|1; lifecycle_version:number; observed_at:string; elapsed_ms:number; budget_ms:number; configuration_reference:string; policy_reference:string|null; escalation_required:boolean; notification_status:'NOT_ROUTED'; delivered:false }
export interface SlaReminderPage { tenant_id:string; ticket_id:string; lifecycle_version:number; scan_state:'PENDING'|'LEASED'|'IDLE'|'BLOCKED_ATTEMPTS'; last_outcome:string|null; last_scan_at:string|null; items:SlaReminder[]; source:'postgresql'; production_approved:false }
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const integer=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=0&&Number(v)<=9007199254740990
const time=(v:unknown):v is string=>typeof v==='string'&&utcInstantNanos(v)!==null
const invalid=()=>new ApiError(200,'INVALID_SLA_REMINDER_RESPONSE','提醒记录的范围、版本或通知状态不一致，已清除显示','—')
export function readSlaReminders(value:unknown,scope:{tenantId:string;ticketId:string;expectedVersion:number}):SlaReminderPage{
  if(!exact(value,['tenant_id','ticket_id','lifecycle_version','scan_state','last_outcome','last_scan_at','items','source','production_approved'])||value.tenant_id!==scope.tenantId||value.ticket_id!==scope.ticketId||!integer(value.lifecycle_version)||value.lifecycle_version<1||value.lifecycle_version!==scope.expectedVersion||!['PENDING','LEASED','IDLE','BLOCKED_ATTEMPTS'].includes(value.scan_state as string)||value.source!=='postgresql'||value.production_approved!==false||!Array.isArray(value.items)||value.items.length>6)throw invalid()
  if(value.last_outcome!==null&&!['CONFIG_PENDING','INTENTS_RECORDED_NOT_DELIVERED','NO_DUE_NODE','ATTEMPT_LIMIT_REACHED'].includes(value.last_outcome as string)||value.last_scan_at!==null&&!time(value.last_scan_at)||(value.last_outcome===null)!==(value.last_scan_at===null)||value.scan_state==='BLOCKED_ATTEMPTS'&&value.last_outcome!=='ATTEMPT_LIMIT_REACHED')throw invalid()
  const ids=new Set<string>();let previous=-1
  for(const row of value.items){
    if(!exact(row,['intent_id','clock','progress','lifecycle_version','observed_at','elapsed_ms','budget_ms','configuration_reference','policy_reference','escalation_required','notification_status','delivered'])||typeof row.intent_id!=='string'||!uuid.test(row.intent_id)||ids.has(row.intent_id)||!['RESPONSE','RECOVERY'].includes(row.clock as string)||![0.5,0.8,1].includes(row.progress as number)||!integer(row.lifecycle_version)||row.lifecycle_version<1||row.lifecycle_version>scope.expectedVersion||!time(row.observed_at)||!integer(row.elapsed_ms)||!integer(row.budget_ms)||row.budget_ms<1||typeof row.configuration_reference!=='string'||!/^sha256:[0-9a-f]{64}$/.test(row.configuration_reference)||row.notification_status!=='NOT_ROUTED'||row.delivered!==false||row.escalation_required!==(row.progress===1))throw invalid()
    if(BigInt(row.elapsed_ms)*10n<BigInt(row.budget_ms)*(row.progress===0.5?5n:row.progress===0.8?8n:10n))throw invalid()
    if(row.policy_reference!==null){
      if(typeof row.policy_reference!=='string'||row.policy_reference.length>256)throw invalid()
      const opening='opening:'+row.configuration_reference, prefix='binding:'+scope.ticketId+':'
      if(row.policy_reference!==opening){if(!row.policy_reference.startsWith(prefix))throw invalid();const tail=row.policy_reference.slice(prefix.length).split(':');if(tail.length!==2||!uuid.test(tail[0]!)||!(/^[1-9][0-9]{0,15}$/).test(tail[1]!)||!integer(Number(tail[1])))throw invalid()}
    }
    const rank=(row.clock==='RESPONSE'?0:3)+[0.5,0.8,1].indexOf(row.progress as number);if(rank<=previous)throw invalid();previous=rank;ids.add(row.intent_id)
  }
  return structuredClone(value) as unknown as SlaReminderPage
}
