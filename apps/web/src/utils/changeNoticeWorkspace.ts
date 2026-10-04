import { isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { writeFailureUncertain } from './writeFailure'
import { utcNotBefore } from './utcInstant'
import { changeNoticeBad, changeNoticeFingerprint, changeNoticeDefinition, changeNoticeUuid, readChangeNoticeDefinition, readChangeNoticeDetail, readChangeNoticeDetailShape, readChangeNoticeHistory, readChangeNoticePage, readChangeNoticePayload, validChangeNoticeContext, type ChangeNoticeAction, type ChangeNoticeContext, type ChangeNoticeDetail, type ChangeNoticeHistory, type ChangeNoticePage } from './changeNoticeDrafts'
export interface ChangeNoticeAttempt { path:string; method:'POST'|'PATCH'; action:ChangeNoticeAction; payload:string; key:string; baseline:ChangeNoticeDetail|null; state:'uncertain'|'accepted'|'rejected' }
export interface ChangeNoticeState { page:ChangeNoticePage|null; selected:ChangeNoticeDetail|null; history:ChangeNoticeHistory|null; receipt:ChangeNoticeDetail|null; attempt:ChangeNoticeAttempt|null; paged:boolean; busy:'read'|'write'|null; error:unknown }
export const emptyChangeNoticeState=():ChangeNoticeState=>({page:null,selected:null,history:null,receipt:null,attempt:null,paged:false,busy:null,error:null})
const same=(a:unknown,b:unknown):boolean=>{if(a===b)return true;if(Array.isArray(a)&&Array.isArray(b))return a.length===b.length&&a.every((v,i)=>same(v,b[i]));if(a&&b&&typeof a==='object'&&typeof b==='object'&&!Array.isArray(a)&&!Array.isArray(b)){const x=a as Record<string,unknown>,y=b as Record<string,unknown>;return Object.keys(x).length===Object.keys(y).length&&Object.keys(x).every(k=>Object.hasOwn(y,k)&&same(x[k],y[k]))}return false}
export function createChangeNoticeWorkspace(client:Pick<ApiClient,'request'>,changed:(state:ChangeNoticeState)=>void,newKey:()=>string=()=>crypto.randomUUID()){
  let context:ChangeNoticeContext|null=null,state=emptyChangeNoticeState(),epoch=0,abort:AbortController|null=null,disposed=false
  const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot()),unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
  function reset(next:ChangeNoticeContext|null){abort?.abort();abort=null;++epoch;context=!disposed&&validChangeNoticeContext(next)?{...next}:null;state=emptyChangeNoticeState();emit()}
  async function read(kind:'list'|'detail'|'history',next:boolean,id?:string){
    if(!context||disposed||state.busy)return false
    const before=kind==='list'&&next&&state.page?.has_more?{time:state.page.next_before_created_at!,id:state.page.next_before_id!}:undefined
    const historyBefore=kind==='history'&&next&&state.history!==null&&state.history.notice_id===id&&state.history.has_more?state.history.next_before_version!:undefined
    const boundary=historyBefore?state.history!.items.at(-1):undefined
    if(next&&!(kind==='list'?before:historyBefore))return false
    const cleared=kind==='list'?{page:null,selected:null,history:null}:kind==='detail'?{selected:null,history:null}:{history:null}
    if(kind!=='list'&&!changeNoticeUuid(id)){state={...state,...cleared,error:changeNoticeBad()};emit();return false}
    const turn=++epoch,scope={...context},controller=new AbortController();abort=controller;state={...state,...cleared,busy:'read',error:null,...(kind==='list'?{paged:next}:{})};emit()
    try{
      const params=new URLSearchParams({page_size:'15'});let path:string
      if(kind==='list'){if(before){params.set('before_created_at',before.time);params.set('before_id',before.id)}path='/change-notice-drafts?'+params}
      else if(kind==='history'){if(historyBefore)params.set('before_version',String(historyBefore));path='/change-notice-drafts/'+id+'/history?'+params}
      else path='/change-notice-drafts/'+id
      if(turn!==epoch||disposed||controller.signal.aborted)return false
      const raw=await client.request<unknown>(path,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
      if(kind==='list'){
        const page=readChangeNoticePage(raw,scope,before),receipt=state.receipt,seen=receipt&&page.items.find(x=>x.id===receipt.id)
        if(seen&&receipt){const {asset_ids:_ids,change_summary:_summary,...summary}=receipt;if(seen.applicant_id!==receipt.applicant_id||seen.created_at!==receipt.created_at||seen.version<receipt.version||!utcNotBefore(seen.updated_at,receipt.updated_at)||!utcNotBefore(seen.recorded_at,receipt.recorded_at)||seen.version===receipt.version&&!same(seen,summary))throw changeNoticeBad()}
        state.page=page
      }else if(kind==='detail'){
        const row=await readChangeNoticeDetail(raw,scope);if(turn!==epoch||disposed||controller.signal.aborted)return false
        const receipt=state.receipt;if(row.id!==id||receipt!==null&&receipt.id===id&&(row.applicant_id!==receipt.applicant_id||row.created_at!==receipt.created_at||row.version<receipt.version||!utcNotBefore(row.updated_at,receipt.updated_at)||!utcNotBefore(row.recorded_at,receipt.recorded_at)||row.version===receipt.version&&!same(row,receipt)))throw changeNoticeBad();state.selected=row
      }else{
        const history=await readChangeNoticeHistory(raw,scope,id!,historyBefore);if(turn!==epoch||disposed||controller.signal.aborted)return false
        const first=history.items[0];if(boundary&&first&&(boundary.applicant_id!==first.applicant_id||!utcNotBefore(boundary.recorded_at,first.recorded_at)||(boundary.action==='RESTORE')!==first.archived||['ARCHIVE','RESTORE'].includes(boundary.action)&&boundary.content_sha256!==first.content_sha256))throw changeNoticeBad()
        const receipt=state.receipt;if(!historyBefore&&receipt!==null&&receipt.id===id&&history.items[0]!.version<receipt.version)throw changeNoticeBad()
        const known=receipt!==null&&receipt.id===id?history.items.find(x=>x.version===receipt.version):undefined
        if(known&&receipt){const {id:notice_id,created_at:_created,updated_at:_updated,...fields}=receipt;if(!same(known,{...fields,notice_id,previous_version:receipt.version===1?null:receipt.version-1,action:state.attempt?.action}))throw changeNoticeBad()}
        state.history=history
      }
      return true
    }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}
    finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
  }
  async function send(input:ChangeNoticeAttempt,retry:boolean){
    if(!context?.canWrite||disposed||state.busy)return false
    const attempt=structuredClone(input),turn=++epoch,scope={...context},controller=new AbortController();abort=controller;let dispatched=false
    state={...state,busy:'write',attempt:{...attempt,state:'uncertain'},page:null,selected:null,history:null,error:null};emit()
    try{
      const payload=readChangeNoticePayload(JSON.parse(attempt.payload),attempt.action),base=attempt.baseline;if(base)await readChangeNoticeDetail(base,scope)
      // Let immediate context changes/unmounts cancel even a new create before dispatch.
      await Promise.resolve();if(turn!==epoch||disposed||controller.signal.aborted)return false
      dispatched=true;const raw=await client.request<unknown>(attempt.path,{method:attempt.method,body:structuredClone(payload),idempotencyKey:attempt.key,signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
      const row=await readChangeNoticeDetail(raw,scope);if(turn!==epoch||disposed||controller.signal.aborted)return false
      const expected=['ARCHIVE','RESTORE'].includes(attempt.action)?changeNoticeDefinition(base!):readChangeNoticeDefinition({change_summary:payload.change_summary,asset_ids:payload.asset_ids,planned_start_at:payload.planned_start_at,planned_end_at:payload.planned_end_at,ticket_id:payload.ticket_id})
      const applicant=base?.applicant_id??scope.actorId,expectedHash=await changeNoticeFingerprint(scope.tenantId,applicant,expected);if(turn!==epoch||disposed||controller.signal.aborted)return false
      if(row.applicant_id!==applicant||row.content_sha256!==expectedHash||row.version!==payload.expected_version+1||row.archived!==(attempt.action==='ARCHIVE')||row.recorded_by!==scope.actorId||base&&(row.id!==base.id||row.created_at!==base.created_at||!utcNotBefore(row.updated_at,base.updated_at)||!utcNotBefore(row.recorded_at,base.recorded_at)))throw changeNoticeBad()
      state={...state,receipt:row,attempt:{...attempt,state:'accepted'}};return true
    }catch(error){if(turn===epoch&&!disposed)state={...state,error,attempt:{...attempt,state:retry||dispatched&&writeFailureUncertain(false,error)?'uncertain':'rejected'}};return false}
    finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
  }
  async function submit(action:ChangeNoticeAction,input:unknown,baseline:unknown=null){
    if(!context?.canWrite||disposed||state.busy||state.attempt)return false
    try{
      const generation=epoch,payload=readChangeNoticePayload(input,action),base=baseline===null?null:readChangeNoticeDetailShape(baseline,context)
      if(action==='CREATE'?base!==null:!base||payload.expected_version!==base.version||base.archived!==(action==='RESTORE'))throw changeNoticeBad()
      const key=newKey();if(generation!==epoch||disposed||!context?.canWrite)return false;if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw changeNoticeBad()
      const path=action==='CREATE'?'/change-notice-drafts':'/change-notice-drafts/'+base!.id+(action==='ARCHIVE'?'/archive':action==='RESTORE'?'/restore':'')
      return send({path,method:action==='UPDATE'?'PATCH':'POST',action,payload:JSON.stringify(payload),key,baseline:base,state:'uncertain'},false)
    }catch(error){state.error=error;emit();return false}
  }
  emit();return{reset,state:snapshot,unresolved,list:(next=false)=>read('list',next),select:(id:string)=>read('detail',false,id),history:(id:string,next=false)=>read('history',next,id),submit,retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),begin:()=>{if(!context?.canWrite||disposed||state.busy||unresolved())return false;state={...state,receipt:null,attempt:null,error:null};emit();return true},dispose:()=>{disposed=true;reset(null)}}
}
