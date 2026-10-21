// Synthetic component/layout fixture only. Never evidence of real API, identity or policy integration.
import {createApp,h} from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '../src/styles.css'
import Panel from '../src/components/ChangeNoticeApprovalPanel.vue'
import {api} from '../src/api/client'
import type {ChangeNoticeDetail} from '../src/utils/changeNoticeDrafts'
import type {ApprovalSnapshot} from '../src/utils/changeNoticeApproval'
const id=(n:number)=>'7d000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
const target:ChangeNoticeDetail={id:id(3),tenant_id:id(1),version:1,archived:false,recorded_by:id(2),created_at:'2026-10-04T08:00:00.000Z',updated_at:'2026-10-04T08:00:00.000Z',recorded_at:'2026-10-04T08:00:00.000Z',change_summary:'合成变更😀\n只作草稿',asset_ids:[id(3),id(4)],planned_start_at:'2026-10-04T10:00:00Z',planned_end_at:null,ticket_id:null,applicant_id:id(2),asset_count:2,content_sha256:'36e3b2c1427636355944e0a1e256b0305a82946add4498e036246f4d0165f3be',timezone:'Asia/Shanghai',effective:false,lifecycle_status:'DRAFT',approval_status:null,approval_workflow_status:'CONFIG_PENDING',matching_policy_status:'CONFIG_PENDING',automatic_p3_decision:null,approval_evidence_verified:false,device_execution_allowed:false,alert_suppression_allowed:false,source:'postgresql',record_source:'UNREVIEWED_CHANGE_NOTICE_DRAFT'}
const snapshot:ApprovalSnapshot={notice_id:target.id,tenant_id:target.tenant_id,draft_revision:1,content_sha256:target.content_sha256,version:1,state:'DRAFT',applicant_id:target.applicant_id,change_summary:target.change_summary,asset_ids:target.asset_ids,ticket_id:null,planned_start_at:target.planned_start_at,planned_end_at:null,timezone:'Asia/Shanghai',created_at:target.created_at,submitted_at:null,reviewer_id:null,decided_at:null,review_policy_release_id:null,decision_note:null,updated_at:target.updated_at,effective:false,device_execution_allowed:false,alert_suppression_allowed:false,automatic_p3_decision:null,source:'postgresql',record_source:'REVISION_REVIEW_ONLY',allowed_actions:['SUBMIT','WITHDRAW']}
api.request=async <T>():Promise<T>=>structuredClone(snapshot) as T
createApp({render:()=>h('div',{style:'max-width:1100px;margin:0 auto;padding:12px;box-sizing:border-box'},[h('p',{role:'note'},'合成单元布局夹具：不代表真实API、Q09策略或审批验收'),h(Panel,{candidate:target,context:{tenantId:id(1),actorId:id(2),permissionVersion:7,role:'padmin',canRead:true,canWrite:true},contextKey:'synthetic-only'})])}).use(ElementPlus).mount('#fixture')
