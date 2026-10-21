import { ApiError, type ApiClient } from '../api/client'
import {isUtcInstant} from './utcInstant'
import { createRequestId } from './browserCrypto'

export const workflowLabels = { OPEN: '待接单', ACCEPTED: '已接单', IN_PROGRESS: '处理中', RESOLVED: '待客户确认', CLOSED: '已关闭', CANCELLED: '已撤销' } as const
export type WorkflowState = keyof typeof workflowLabels
export interface WorkflowContext { tenantId: string; actorId: string; permissionVersion: number; ticketId: string; customer: boolean }
export interface TicketWorkflow {
  ticket_id: string; tenant_id: string; title: string; receipt_no: string; registration_created_at: string
  version: number; state: WorkflowState | null; priority: 'P1' | 'P2' | 'P3' | null; service_tier: 'BASIC' | 'STANDARD' | 'ADVANCED' | null
  assignee_id: string | null; collaborator_ids: string[]; accepted_at: string | null; resolved_at: string | null
  closed_at: string | null; confirm_due_at: string | null; close_mode: 'customer_confirm' | 'timeout_auto' | null
  reopen_count: number; confirmation_cycle: number; resolution_note: string | null; response_due_at: string | null; recovery_due_at: string | null
  sla_missing: Record<string, string>; sla_references: unknown[]; snapshot_hash: string | null; updated_at: string | null
  observed_at: string; authority_source: 'MOP'; source: 'postgresql'; scope: 'REPORTER_ONLY' | 'CURRENT_TENANT_INTERNAL'
  customer_confirmation_status: 'UNVERIFIED'; production_approved: false; external_notification_delivery: 'NOT_CONFIRMED'
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const keys = ['ticket_id','tenant_id','title','receipt_no','registration_created_at','version','state','priority','service_tier','assignee_id','collaborator_ids','accepted_at','resolved_at','closed_at','confirm_due_at','close_mode','reopen_count','confirmation_cycle','resolution_note','response_due_at','recovery_due_at','sla_missing','sla_references','snapshot_hash','updated_at','observed_at','authority_source','source','scope','customer_confirmation_status','production_approved','external_notification_delivery']
function invalid(): never { throw new Error('工单处理结果未通过核对，请重新读取') }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const instant = (v: unknown): v is string => isUtcInstant(v) && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(v)
const validContext=(c:WorkflowContext|null):c is WorkflowContext=>!!c&&[c.tenantId,c.actorId,c.ticketId].every(v=>typeof v==='string'&&v.length===36&&uuid.test(v))&&Number.isSafeInteger(c.permissionVersion)&&c.permissionVersion>0&&typeof c.customer==='boolean'
export function readWorkflow(value: unknown, context: WorkflowContext): TicketWorkflow {
  if (!validContext(context)||!object(value) || Object.keys(value).length !== keys.length || keys.some(k => !Object.hasOwn(value,k))
    || value.ticket_id !== context.ticketId || value.tenant_id !== context.tenantId || !uuid.test(context.ticketId)
    || value.source !== 'postgresql' || value.authority_source !== 'MOP' || value.production_approved !== false
    || value.customer_confirmation_status !== 'UNVERIFIED' || value.external_notification_delivery !== 'NOT_CONFIRMED'
    || value.scope !== (context.customer ? 'REPORTER_ONLY' : 'CURRENT_TENANT_INTERNAL')) invalid()
  const v = value as unknown as TicketWorkflow
  if (!Number.isSafeInteger(v.version) || v.version < 0 || !Number.isSafeInteger(v.reopen_count) || v.reopen_count < 0
    || !Number.isSafeInteger(v.confirmation_cycle) || v.confirmation_cycle < 0 || typeof v.title !== 'string' || typeof v.receipt_no !== 'string'
    || !instant(v.registration_created_at) || !instant(v.observed_at) || !Array.isArray(v.sla_references)
    || !object(v.sla_missing) || Object.values(v.sla_missing).some(x => typeof x !== 'string') || !Array.isArray(v.collaborator_ids)
    || v.collaborator_ids.some(x => typeof x !== 'string' || x.length!==36 || !uuid.test(x)) || new Set(v.collaborator_ids).size !== v.collaborator_ids.length) invalid()
  for (const time of [v.accepted_at,v.resolved_at,v.closed_at,v.confirm_due_at,v.response_due_at,v.recovery_due_at,v.updated_at]) if (time !== null && !instant(time)) invalid()
  if (v.version === 0) {
    if ([v.state,v.priority,v.service_tier,v.snapshot_hash,v.updated_at,v.assignee_id,v.accepted_at,v.resolved_at,v.closed_at,v.confirm_due_at,v.close_mode,v.resolution_note,v.response_due_at,v.recovery_due_at].some(x => x !== null) || v.collaborator_ids.length || v.reopen_count || v.confirmation_cycle) invalid()
  } else if (v.state === null || !Object.hasOwn(workflowLabels,v.state) || !['P1','P2','P3'].includes(v.priority ?? '')
    || !['BASIC','STANDARD','ADVANCED'].includes(v.service_tier ?? '') || !v.updated_at || !v.snapshot_hash || v.snapshot_hash.length!==64 || !/^[0-9a-f]{64}$/.test(v.snapshot_hash)) invalid()
  if (context.customer && (v.assignee_id !== null || v.collaborator_ids.length || v.sla_references.length)) invalid()
  if (v.assignee_id !== null && (typeof v.assignee_id !== 'string' || v.assignee_id.length!==36 || !uuid.test(v.assignee_id))) invalid()
  if (v.state === 'CLOSED' && (!v.closed_at || !['customer_confirm','timeout_auto'].includes(v.close_mode ?? '')) || v.state !== 'CLOSED' && (v.closed_at !== null || v.close_mode !== null)) invalid()
  if (v.resolution_note !== null && typeof v.resolution_note !== 'string') invalid()
  return JSON.parse(JSON.stringify(v)) as TicketWorkflow
}
export interface WorkflowWorkspaceState { current: TicketWorkflow | null; busy: boolean; error: unknown; uncertain: boolean; receiptVersion: number | null; receiptAction:string|null; readbackFailed:boolean }
export const emptyWorkflowState = (): WorkflowWorkspaceState => ({ current: null, busy: false, error: null, uncertain: false, receiptVersion: null,receiptAction:null,readbackFailed:false })
const actionStates:Record<string,WorkflowState>={activate:'OPEN',accept:'ACCEPTED',reassign:'ACCEPTED',start:'IN_PROGRESS',resolve:'RESOLVED',close:'CLOSED',reopen:'IN_PROGRESS',cancel:'CANCELLED'}
/** Receipt authority is bound to the original command, not just to a ticket identifier. */
function commandReceipt(raw:unknown,context:WorkflowContext,action:string,body:Record<string,unknown>){
 const receipt=readWorkflow(raw,context)
 if(!Number.isSafeInteger(body.expected_version)||receipt.version!==Number(body.expected_version)+1||receipt.state!==actionStates[action]
  ||action==='activate'&&(receipt.priority!==body.priority||body.service_tier!=null&&receipt.service_tier!==body.service_tier)
  ||action==='accept'&&receipt.assignee_id!==context.actorId
  ||action==='reassign'&&receipt.assignee_id!==body.assignee_id
  ||action==='resolve'&&receipt.resolution_note!==body.note
  ||action==='close'&&receipt.close_mode!=='customer_confirm')throw new ApiError(200,'INVALID_WORKFLOW_RECEIPT','工单回执与原操作或版本不一致，结果未确认，请保留原请求重试。','—')
 return receipt
}
export function createWorkflowWorkspace(client: Pick<ApiClient,'request'>, publish: (state: WorkflowWorkspaceState) => void) {
 let context:WorkflowContext|null=null,generation=0,disposed=false,state=emptyWorkflowState(),readAbort:AbortController|null=null,writeAbort:AbortController|null=null
 let pending:{path:string;action:string;payload:string;key:string}|null=null
 const emit=()=>publish({...state,current:state.current?structuredClone(state.current):null})
 const unresolved=()=>!!pending
 async function refresh(){
  if(disposed||!context||state.busy)return false
  const expected={...context},token=generation;readAbort?.abort();readAbort=new AbortController();const signal=readAbort.signal
  state={...state,busy:true,current:null,error:null};emit()
  try{const raw=await client.request(`/tickets/${expected.ticketId}/workflow`,{signal});if(disposed||token!==generation)return false;const current=readWorkflow(raw,expected);if(state.receiptVersion!==null&&current.version<state.receiptVersion)throw new ApiError(200,'INVALID_WORKFLOW_READBACK','回读状态早于已确认回执，未沿用旧状态，请重新读取。','—');state.current=current;state.readbackFailed=false;return true}
  catch(error){if(!disposed&&token===generation){state.error=error;state.readbackFailed=state.receiptVersion!==null}return false}
  finally{if(!disposed&&token===generation){readAbort=null;state.busy=false;emit()}}
 }
 async function sendOriginal(recovering=false){
  if(disposed||!context||!pending||state.busy)return false
  const expected={...context},token=generation,command={...pending};writeAbort=new AbortController();const signal=writeAbort.signal
  state={...state,busy:true,error:null,current:null};emit();let accepted=false
  try{const body=JSON.parse(command.payload),raw=await client.request(command.path,{method:'POST',body,idempotencyKey:command.key,signal});if(disposed||token!==generation)return false
   const receipt=commandReceipt(raw,expected,command.action,body);state.receiptVersion=receipt.version;state.receiptAction=command.action;pending=null;state.uncertain=false;state.readbackFailed=false;accepted=true
  }catch(error){if(disposed||token!==generation)return false;state.error=error;const definitive=!recovering&&error instanceof ApiError&&[400,401,403,404,409,413,415,422].includes(error.status);if(definitive){pending=null;state.uncertain=false}else state.uncertain=true}
  finally{if(!disposed&&token===generation){writeAbort=null;state.busy=false;emit()}}
  // Preserve an accepted receipt even when this independent current-state read fails.
  if(accepted&&!disposed&&token===generation)await refresh()
  return accepted&&!disposed&&token===generation
 }
 function reset(next:WorkflowContext|null){generation++;readAbort?.abort();writeAbort?.abort();readAbort=null;writeAbort=null;context=!disposed&&validContext(next)?{...next}:null;pending=null;state=emptyWorkflowState();if(next&&!context&&!disposed)state.error=new ApiError(400,'INVALID_WORKFLOW_CONTEXT','工单标识或授权上下文无效，未发送读取请求。','—');emit()}
 return {reset,refresh,unresolved,
  async submit(action:string,fields:Record<string,unknown>){
   if(disposed||!context||state.busy||pending||!state.current||!Object.hasOwn(actionStates,action))return false
   pending={path:`/tickets/${context.ticketId}/${action}`,action,payload:JSON.stringify({...fields,expected_version:state.current.version}),key:createRequestId()};state.receiptVersion=null;state.receiptAction=null;state.readbackFailed=false;return sendOriginal()
  },retry:()=>sendOriginal(true),dispose(){disposed=true;reset(null)}
 }
}
