import { ApiError } from '../api/client'
import { changeNoticeFingerprint, changeNoticeMaxVersion, changeNoticeUuid, readChangeNoticeDefinition, type ChangeNoticeContext, type ChangeNoticeDefinition, type ChangeNoticeDetail } from './changeNoticeDrafts'
import { validInspectionText } from './inspectionTemplates'
import { utcInstantNanos, utcNotBefore } from './utcInstant'
export type ApprovalAction = 'SUBMIT'|'WITHDRAW'|'APPROVE'|'REJECT'
export type ApprovalStatus = 'DRAFT'|'IN_REVIEW'|'APPROVED'|'REJECTED'|'WITHDRAWN'
interface Safety { effective:false; device_execution_allowed:false; alert_suppression_allowed:false; automatic_p3_decision:null; source:'postgresql'; record_source:'REVISION_REVIEW_ONLY' }
export interface ApprovalSnapshot extends ChangeNoticeDefinition, Safety {
 notice_id:string; tenant_id:string; draft_revision:number; content_sha256:string; version:number; state:ApprovalStatus; applicant_id:string; timezone:'Asia/Shanghai'; created_at:string; submitted_at:string|null; reviewer_id:string|null; decided_at:string|null; review_policy_release_id:string|null; decision_note:string|null; updated_at:string; allowed_actions:ApprovalAction[]
}
export interface ApprovalEvent extends Safety { notice_id:string; draft_revision:number; content_sha256:string; version:number; from_state:ApprovalStatus; to_state:ApprovalStatus; action:ApprovalAction; actor_id:string; permission_version:number; review_policy_release_id:string|null; note:string|null; occurred_at:string }
export interface ApprovalHistory { tenant_id:string; notice_id:string; draft_revision:number; items:ApprovalEvent[]; has_more:boolean; next_before_version:number|null; page_size_limit:20; source:'postgresql' }
export interface ApprovalCommand { expected_version:number; action:ApprovalAction; note:string|null }
export const approvalBad=()=>new ApiError(0,'INVALID_CHANGE_NOTICE_APPROVAL','审批范围、明确修订、状态或收据未通过核对；未知结果请保留原请求重试','—')
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
export const approvalVersion=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=1&&Number(v)<=changeNoticeMaxVersion
const instant=(v:unknown):v is string=>{const n=utcInstantNanos(v);return n!==null&&n%1_000_000n===0n}
const safetyKeys=['effective','device_execution_allowed','alert_suppression_allowed','automatic_p3_decision','source','record_source']
const identityKeys=['notice_id','draft_revision','content_sha256','version']
const definitionKeys=['change_summary','asset_ids','ticket_id','planned_start_at','planned_end_at']
const actions:ApprovalAction[]=['SUBMIT','WITHDRAW','APPROVE','REJECT']
const statuses:ApprovalStatus[]=['DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN']
export const approvalNextState:Record<ApprovalAction,ApprovalStatus>={SUBMIT:'IN_REVIEW',WITHDRAW:'WITHDRAWN',APPROVE:'APPROVED',REJECT:'REJECTED'}
export const approvalTransition=(from:ApprovalStatus,action:ApprovalAction)=>action==='SUBMIT'?from==='DRAFT':action==='WITHDRAW'?from==='DRAFT'||from==='IN_REVIEW':from==='IN_REVIEW'
export function validApprovalContext(c:ChangeNoticeContext|null):c is ChangeNoticeContext{return Boolean(c&&changeNoticeUuid(c.tenantId)&&changeNoticeUuid(c.actorId)&&approvalVersion(c.permissionVersion)&&c.canRead&&typeof c.canWrite==='boolean')}
function safety(v:Record<string,unknown>){if(v.effective!==false||v.device_execution_allowed!==false||v.alert_suppression_allowed!==false||v.automatic_p3_decision!==null||v.source!=='postgresql'||v.record_source!=='REVISION_REVIEW_ONLY')throw approvalBad()}
function identity(v:Record<string,unknown>,target:ChangeNoticeDetail){if(v.notice_id!==target.id||v.draft_revision!==target.version||v.content_sha256!==target.content_sha256||!approvalVersion(v.version))throw approvalBad();safety(v)}
const validNote=(v:unknown)=>v===null||validInspectionText(v,2000,false,true)
export function readApprovalCommand(v:unknown):ApprovalCommand{
 if(!exact(v,['expected_version','action','note'])||!approvalVersion(v.expected_version)||v.expected_version>=changeNoticeMaxVersion||!actions.includes(v.action as ApprovalAction)||!validNote(v.note)||v.action==='REJECT'&&v.note===null)throw approvalBad()
 return structuredClone(v) as unknown as ApprovalCommand
}
export async function readApprovalSnapshot(v:unknown,c:ChangeNoticeContext,target:ChangeNoticeDetail):Promise<ApprovalSnapshot>{
 if(!validApprovalContext(c)||target.tenant_id!==c.tenantId||!exact(v,[...identityKeys,...definitionKeys,...safetyKeys,'tenant_id','state','applicant_id','timezone','created_at','submitted_at','reviewer_id','decided_at','review_policy_release_id','decision_note','updated_at','allowed_actions'])||v.tenant_id!==c.tenantId||v.applicant_id!==target.applicant_id||v.timezone!=='Asia/Shanghai'||!statuses.includes(v.state as ApprovalStatus)||!instant(v.created_at)||!instant(v.updated_at)||!utcNotBefore(v.updated_at,v.created_at)||v.submitted_at!==null&&(!instant(v.submitted_at)||!utcNotBefore(v.submitted_at,v.created_at)||!utcNotBefore(v.updated_at,v.submitted_at))||!validNote(v.decision_note))throw approvalBad()
 identity(v,target)
 const d=readChangeNoticeDefinition(Object.fromEntries(definitionKeys.map(k=>[k,v[k]])))
 if(await changeNoticeFingerprint(c.tenantId,target.applicant_id,d)!==target.content_sha256)throw approvalBad()
 const reviewed=v.state==='APPROVED'||v.state==='REJECTED'
 if(v.version!==(v.state==='DRAFT'?1:v.state==='IN_REVIEW'?2:reviewed?3:v.submitted_at===null?2:3))throw approvalBad()
 if(v.state==='DRAFT'&&(v.submitted_at!==null||v.version!==1)||v.state!=='DRAFT'&&v.version===1||(v.state==='IN_REVIEW'||reviewed)&&v.submitted_at===null)throw approvalBad()
 if(reviewed){if(!changeNoticeUuid(v.reviewer_id)||v.reviewer_id===v.applicant_id||!changeNoticeUuid(v.review_policy_release_id)||!instant(v.decided_at)||!utcNotBefore(v.decided_at,v.submitted_at)||!utcNotBefore(v.updated_at,v.decided_at)||v.state==='REJECTED'&&v.decision_note===null)throw approvalBad()}
 else if(v.reviewer_id!==null||v.decided_at!==null||v.review_policy_release_id!==null||v.decision_note!==null)throw approvalBad()
 if(!Array.isArray(v.allowed_actions)||new Set(v.allowed_actions).size!==v.allowed_actions.length||!v.allowed_actions.every(a=>actions.includes(a)&&approvalTransition(v.state as ApprovalStatus,a)))throw approvalBad()
 // Identity/state contradictions are rejected, but reviewer eligibility is exclusively server-derived Q09 authority.
 if(v.allowed_actions.some(a=>a==='SUBMIT'||a==='WITHDRAW'?c.actorId!==v.applicant_id:c.actorId===v.applicant_id))throw approvalBad()
 return structuredClone(v) as unknown as ApprovalSnapshot
}
export function readApprovalHistory(v:unknown,c:ChangeNoticeContext,target:ChangeNoticeDetail,before?:number):ApprovalHistory{
 if(!validApprovalContext(c)||target.tenant_id!==c.tenantId||before!==undefined&&(!approvalVersion(before)||before<2)||!exact(v,['tenant_id','notice_id','draft_revision','items','has_more','next_before_version','page_size_limit','source'])||v.tenant_id!==c.tenantId||v.notice_id!==target.id||v.draft_revision!==target.version||!Array.isArray(v.items)||v.items.length>15||typeof v.has_more!=='boolean'||v.page_size_limit!==20||v.source!=='postgresql')throw approvalBad()
 let previous=before,newer:ApprovalEvent|undefined
 for(const raw of v.items){
  if(!exact(raw,[...identityKeys,...safetyKeys,'from_state','to_state','action','actor_id','permission_version','review_policy_release_id','note','occurred_at'])||!actions.includes(raw.action as ApprovalAction)||!statuses.includes(raw.from_state as ApprovalStatus)||!approvalTransition(raw.from_state as ApprovalStatus,raw.action as ApprovalAction)||raw.to_state!==approvalNextState[raw.action as ApprovalAction]||!changeNoticeUuid(raw.actor_id)||!approvalVersion(raw.permission_version)||!instant(raw.occurred_at)||!validNote(raw.note)||raw.action==='REJECT'&&raw.note===null)throw approvalBad()
  identity(raw,target);const row=raw as unknown as ApprovalEvent,review=row.action==='APPROVE'||row.action==='REJECT'
  if(row.version!==(row.from_state==='DRAFT'?2:3)||previous!==undefined&&row.version!==previous-1||newer&&(newer.from_state!==row.to_state||!utcNotBefore(newer.occurred_at,row.occurred_at))||review&&(!changeNoticeUuid(row.review_policy_release_id)||row.actor_id===target.applicant_id)||!review&&(row.review_policy_release_id!==null||row.actor_id!==target.applicant_id))throw approvalBad()
  newer=row;previous=row.version
 }
 if(v.has_more?v.items.length!==15||previous===undefined||previous<=2||v.next_before_version!==previous:v.next_before_version!==null||v.items.length>0&&previous!==2||before!==undefined&&before>2&&v.items.length===0)throw approvalBad()
 return structuredClone(v) as unknown as ApprovalHistory
}
