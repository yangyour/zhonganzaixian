import {row,scope,id} from './changeNotice.fixture'
import type {ApprovalSnapshot,ApprovalEvent,ApprovalHistory} from '../src/utils/changeNoticeApproval'
export {row,scope,id}
export const time='2026-10-19T00:00:00Z',later='2026-10-19T01:00:00Z',decided='2026-10-19T02:00:00Z'
export const safety={effective:false,device_execution_allowed:false,alert_suppression_allowed:false,automatic_p3_decision:null,source:'postgresql',record_source:'REVISION_REVIEW_ONLY'} as const
export function approval(patch:Partial<ApprovalSnapshot>={}):ApprovalSnapshot{const r=row();return{...safety,notice_id:r.id,tenant_id:r.tenant_id,draft_revision:r.version,content_sha256:r.content_sha256,version:1,state:'DRAFT',applicant_id:r.applicant_id,change_summary:r.change_summary,asset_ids:r.asset_ids,ticket_id:r.ticket_id,planned_start_at:r.planned_start_at,planned_end_at:r.planned_end_at,timezone:'Asia/Shanghai',created_at:time,submitted_at:null,reviewer_id:null,decided_at:null,review_policy_release_id:null,decision_note:null,updated_at:time,allowed_actions:['SUBMIT','WITHDRAW'],...patch}}
export const submitted=(patch:Partial<ApprovalSnapshot>={})=>approval({version:2,state:'IN_REVIEW',submitted_at:later,updated_at:later,allowed_actions:[],...patch})
export const approved=(patch:Partial<ApprovalSnapshot>={})=>submitted({version:3,state:'APPROVED',reviewer_id:id(9),decided_at:decided,updated_at:decided,review_policy_release_id:id(10),...patch})
export const event=(patch:Partial<ApprovalEvent>={}):ApprovalEvent=>({...safety,notice_id:row().id,draft_revision:1,content_sha256:row().content_sha256,version:2,from_state:'DRAFT',to_state:'IN_REVIEW',action:'SUBMIT',actor_id:scope.actorId,permission_version:7,review_policy_release_id:null,note:null,occurred_at:later,...patch})
export const history=(items:ApprovalEvent[]=[]):ApprovalHistory=>({tenant_id:scope.tenantId,notice_id:row().id,draft_revision:1,items,has_more:false,next_before_version:null,page_size_limit:20,source:'postgresql'})
