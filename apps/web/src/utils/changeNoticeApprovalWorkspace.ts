import { type ApiClient, isCancelled } from '../api/client'
import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { writeFailureUncertain } from './writeFailure'
import { readChangeNoticeDetail, type ChangeNoticeContext, type ChangeNoticeDetail } from './changeNoticeDrafts'
import { utcNotBefore } from './utcInstant'
import { approvalBad, approvalNextState, readApprovalCommand, readApprovalHistory, readApprovalSnapshot, validApprovalContext, type ApprovalAction, type ApprovalHistory, type ApprovalSnapshot } from './changeNoticeApproval'
export interface ApprovalAttempt { path:string; payload:string; key:string; baseline:ApprovalSnapshot; state:'uncertain'|'accepted'|'rejected' }
export interface ApprovalWorkspaceState { target:ChangeNoticeDetail|null; current:ApprovalSnapshot|null; baseline:ApprovalSnapshot|null; history:ApprovalHistory|null; receipt:ApprovalSnapshot|null; attempt:ApprovalAttempt|null; busy:'read'|'write'|null; error:unknown }
export const emptyApprovalState=():ApprovalWorkspaceState=>({target:null,current:null,baseline:null,history:null,receipt:null,attempt:null,busy:null,error:null})
const path=(t:ChangeNoticeDetail)=>`/change-notice-drafts/${t.id}/revisions/${t.version}/approval`
const sameSnapshot=(a:ApprovalSnapshot,b:ApprovalSnapshot)=>Object.keys(a).filter(k=>k!=='allowed_actions').every(k=>JSON.stringify(a[k as keyof ApprovalSnapshot])===JSON.stringify(b[k as keyof ApprovalSnapshot]))
export function createApprovalWorkspace(client:Pick<ApiClient,'request'>,changed:(s:ApprovalWorkspaceState)=>void,newKey=()=>createRequestId()){
 let context:ChangeNoticeContext|null=null,state=emptyApprovalState(),epoch=0,abort:AbortController|null=null,disposed=false,latest:ApprovalSnapshot|null=null
 type ReadRequest={history:boolean;before?:number;boundary?:ApprovalHistory['items'][number];target:ChangeNoticeDetail}
 let lastRead:ReadRequest|null=null,readRetryable=false
 const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot()),unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
 function reset(c:ChangeNoticeContext|null){lastRead=null;readRetryable=false;abort?.abort();abort=null;++epoch;context=!disposed&&validApprovalContext(c)?{...c}:null;state=emptyApprovalState();latest=null;emit()}
 async function pin(input:ChangeNoticeDetail){
  if(!context||disposed||state.busy||unresolved())return false
  lastRead=null;readRetryable=false
  const turn=++epoch,scope={...context};state=emptyApprovalState();latest=null;emit()
  try{const target=await readChangeNoticeDetail(input,scope);if(turn!==epoch||disposed)return false;state.target=target;emit();return read(false)}catch(error){if(turn===epoch&&!disposed){state.error=error;emit()}return false}
 }
 async function read(history:boolean,next=false){
  if(!context||!state.target||disposed||state.busy)return false
  const before=history&&next&&state.history?.has_more?state.history.next_before_version!:undefined
  if(next&&before===undefined)return false
  return readRequest({history,before,boundary:before?state.history!.items.at(-1):undefined,target:structuredClone(state.target)})
 }
 async function readRequest(request:ReadRequest){
  if(!context||!state.target||disposed||state.busy)return false
  const {history,before,boundary,target}=request
  if(target.id!==state.target.id||target.version!==state.target.version||target.content_sha256!==state.target.content_sha256)return false
  lastRead=structuredClone(request);readRetryable=false
  const scope={...context},turn=++epoch,controller=new AbortController();abort=controller
  state={...state,busy:'read',error:null,...(history?{history:null}:{current:null})};emit()
  try{
   const url=path(target)+(history?'/history?page_size=15'+(before?'&before_version='+before:''):'')
   if(turn!==epoch||disposed||controller.signal.aborted)return false
   const raw=await client.request<unknown>(url,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   if(history){
    const rows=readApprovalHistory(raw,scope,target,before),first=rows.items[0]
    if(boundary&&first&&(boundary.from_state!==first.to_state||!utcNotBefore(boundary.occurred_at,first.occurred_at))||!before&&latest&&latest.version>1&&(!first||first.version<latest.version))throw approvalBad()
    const known=latest&&rows.items.find(e=>e.version===latest!.version)
    if(known&&latest&&(known.to_state!==latest.state||known.occurred_at!==latest.updated_at||!utcNotBefore(known.occurred_at,latest.created_at)||(known.action==='APPROVE'||known.action==='REJECT')&&(known.actor_id!==latest.reviewer_id||known.note!==latest.decision_note||known.review_policy_release_id!==latest.review_policy_release_id)))throw approvalBad()
    state.history=rows
   }else{
    const row=await readApprovalSnapshot(raw,scope,target);if(turn!==epoch||disposed||controller.signal.aborted)return false
    if(latest&&(row.version<latest.version||row.created_at!==latest.created_at||!utcNotBefore(row.updated_at,latest.updated_at)||row.version===latest.version&&!sameSnapshot(row,latest)))throw approvalBad()
    state.current=row;latest=row
   }
   return true
  }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error)){state.error=error;readRetryable=true}return false}
  finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 function prepare(){
  if(!context?.canWrite||disposed||state.busy||unresolved()||!state.current||!state.current.allowed_actions.length)return false
  lastRead=null;readRetryable=false
  state={...state,baseline:structuredClone(state.current),attempt:null,receipt:null,error:null};emit();return true
 }
 async function send(input:ApprovalAttempt,retry:boolean){
  if(!context?.canWrite||!state.target||disposed||state.busy)return false
  lastRead=null;readRetryable=false
  const attempt=structuredClone(input),target=structuredClone(state.target),scope={...context},turn=++epoch,controller=new AbortController();abort=controller;let dispatched=false
  state={...state,attempt:{...attempt,state:'uncertain'},busy:'write',error:null};emit()
  try{
   const command=readApprovalCommand(JSON.parse(attempt.payload)),base=attempt.baseline
   await Promise.resolve();if(turn!==epoch||disposed||controller.signal.aborted)return false
   dispatched=true
   const raw=await client.request<unknown>(attempt.path,{method:'POST',body:command,idempotencyKey:attempt.key,signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   const row=await readApprovalSnapshot(raw,scope,target);if(turn!==epoch||disposed||controller.signal.aborted)return false
   const review=command.action==='APPROVE'||command.action==='REJECT'
   if(row.version!==base.version+1||row.state!==approvalNextState[command.action]||row.created_at!==base.created_at||!utcNotBefore(row.updated_at,base.updated_at)||row.submitted_at!==(command.action==='SUBMIT'?row.updated_at:base.submitted_at)||review&&(row.reviewer_id!==scope.actorId||row.decided_at!==row.updated_at||row.decision_note!==command.note))throw approvalBad()
   state={...state,receipt:row,attempt:{...attempt,state:'accepted'},current:null,baseline:null,history:null};if(!latest||latest.version<=row.version)latest=row;return true
  }catch(error){if(turn===epoch&&!disposed)state={...state,error,current:null,attempt:{...attempt,state:retry||dispatched&&writeFailureUncertain(false,error)?'uncertain':'rejected'}};return false}
  finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 function submit(action:ApprovalAction,note:string|null){
  if(!context?.canWrite||!state.target||!state.baseline||!state.current||disposed||state.busy||state.attempt)return Promise.resolve(false)
  try{
   // Refresh never advances a prepared CAS baseline. A newer read requires explicit preparation again.
   if(state.current.version!==state.baseline.version||!state.current.allowed_actions.includes(action)||!state.baseline.allowed_actions.includes(action))throw approvalBad()
   const command=readApprovalCommand({expected_version:state.baseline.version,action,note}),key=newKey()
   if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw approvalBad()
   return send({path:path(state.target)+'/actions',payload:JSON.stringify(command),key,baseline:structuredClone(state.baseline),state:'uncertain'},false)
  }catch(error){state.error=error;emit();return Promise.resolve(false)}
 }
 function cancelRead(){if(disposed||state.busy!=='read')return false;epoch++;abort?.abort();abort=null;readRetryable=lastRead!==null;state={...state,busy:null,error:null};emit();return true}
 emit();return{reset,pin,prepare,cancelRead,canRetryRead:()=>!disposed&&readRetryable&&lastRead!==null&&!state.busy,retryRead:()=>readRetryable&&lastRead?readRequest(structuredClone(lastRead)):Promise.resolve(false),submit,unresolved,state:snapshot,refresh:()=>read(false),history:(next=false)=>read(true,next),retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),dispose:()=>{disposed=true;reset(null)}}
}
