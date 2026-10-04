import { isCancelled, type ApiClient } from '../api/client'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { writeFailureUncertain } from './writeFailure'
import { utcNotBefore } from './utcInstant'
import { backupPlanBad, backupPlanDefinition, backupPlanUuid, readBackupPlanDefinition, readBackupPlanDetail, readBackupPlanDetailShape, readBackupPlanHistory, readBackupPlanPage, readBackupPlanPayload, validBackupPlanContext, type BackupPlanAction, type BackupPlanContext, type BackupPlanDetail, type BackupPlanHistory, type BackupPlanPage } from './backupPlanDrafts'
export interface BackupPlanAttempt { path:string; method:'POST'|'PATCH'; action:BackupPlanAction; payload:string; key:string; baseline:BackupPlanDetail|null; state:'uncertain'|'accepted'|'rejected' }
export interface BackupPlanState { page:BackupPlanPage|null; selected:BackupPlanDetail|null; history:BackupPlanHistory|null; receipt:BackupPlanDetail|null; attempt:BackupPlanAttempt|null; paged:boolean; busy:'read'|'write'|null; error:unknown }
export const emptyBackupPlanState=():BackupPlanState=>({page:null,selected:null,history:null,receipt:null,attempt:null,paged:false,busy:null,error:null})
const same=(a:unknown,b:unknown):boolean=>{if(a===b)return true;if(Array.isArray(a)&&Array.isArray(b))return a.length===b.length&&a.every((v,i)=>same(v,b[i]));if(a&&b&&typeof a==='object'&&typeof b==='object'&&!Array.isArray(a)&&!Array.isArray(b)){const x=a as Record<string,unknown>,y=b as Record<string,unknown>;return Object.keys(x).length===Object.keys(y).length&&Object.keys(x).every(k=>Object.hasOwn(y,k)&&same(x[k],y[k]))}return false}
export function createBackupPlanWorkspace(client:Pick<ApiClient,'request'>,changed:(state:BackupPlanState)=>void,newKey:()=>string=()=>crypto.randomUUID()){
  let context:BackupPlanContext|null=null,state=emptyBackupPlanState(),epoch=0,abort:AbortController|null=null,disposed=false
  const snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot()),unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
  function reset(next:BackupPlanContext|null){abort?.abort();abort=null;++epoch;context=!disposed&&validBackupPlanContext(next)?{...next}:null;state=emptyBackupPlanState();emit()}
  async function read(kind:'list'|'detail'|'history',next:boolean,id?:string){
    if(!context||disposed||state.busy)return false
    const before=kind==='list'&&next&&state.page?.has_more?{time:state.page.next_before_created_at!,id:state.page.next_before_id!}:undefined
    const historyBefore=kind==='history'&&next&&state.history!==null&&state.history.plan_id===id&&state.history.has_more?state.history.next_before_version!:undefined
    if(next&&!(kind==='list'?before:historyBefore))return false
    const cleared=kind==='list'?{page:null,selected:null,history:null}:kind==='detail'?{selected:null,history:null}:{history:null}
    if(kind!=='list'&&!backupPlanUuid(id)){state={...state,...cleared,error:backupPlanBad()};emit();return false}
    const turn=++epoch,scope={...context},controller=new AbortController();abort=controller;state={...state,...cleared,busy:'read',error:null,...(kind==='list'?{paged:next}:{})};emit()
    try{
      const params=new URLSearchParams({page_size:'15'});let path:string
      if(kind==='list'){if(before){params.set('before_created_at',before.time);params.set('before_id',before.id)}path='/backup-plan-drafts?'+params}
      else if(kind==='history'){if(historyBefore)params.set('before_version',String(historyBefore));path='/backup-plan-drafts/'+id+'/history?'+params}
      else path='/backup-plan-drafts/'+id
      if(turn!==epoch||disposed||controller.signal.aborted)return false
      const raw=await client.request<unknown>(path,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
      if(kind==='list'){
        const page=readBackupPlanPage(raw,scope,before),receipt=state.receipt,seen=receipt&&page.items.find(x=>x.id===receipt.id)
        if(seen&&receipt){const {target_asset_ids:_ids,note:_note,...summary}=receipt;if(seen.created_at!==receipt.created_at||seen.version<receipt.version||!utcNotBefore(seen.updated_at,receipt.updated_at)||!utcNotBefore(seen.recorded_at,receipt.recorded_at)||seen.version===receipt.version&&!same(seen,summary))throw backupPlanBad()}
        state.page=page
      }else if(kind==='detail'){
        const row=await readBackupPlanDetail(raw,scope);if(turn!==epoch||disposed||controller.signal.aborted)return false
        const receipt=state.receipt;if(row.id!==id||receipt!==null&&receipt.id===id&&(row.created_at!==receipt.created_at||row.version<receipt.version||!utcNotBefore(row.updated_at,receipt.updated_at)||!utcNotBefore(row.recorded_at,receipt.recorded_at)||row.version===receipt.version&&!same(row,receipt)))throw backupPlanBad();state.selected=row
      }else{
        const history=await readBackupPlanHistory(raw,scope,id!,historyBefore);if(turn!==epoch||disposed||controller.signal.aborted)return false
        const receipt=state.receipt;if(!historyBefore&&receipt!==null&&receipt.id===id&&history.items[0]!.version<receipt.version)throw backupPlanBad()
        const known=receipt!==null&&receipt.id===id?history.items.find(x=>x.version===receipt.version):undefined
        if(known&&receipt){const {id:plan_id,created_at:_created,updated_at:_updated,...fields}=receipt;if(!same(known,{...fields,plan_id,previous_version:receipt.version===1?null:receipt.version-1,action:state.attempt?.action}))throw backupPlanBad()}
        state.history=history
      }
      return true
    }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}
    finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
  }
  async function send(input:BackupPlanAttempt,retry:boolean){
    if(!context?.canWrite||disposed||state.busy)return false
    const attempt=structuredClone(input),turn=++epoch,scope={...context},controller=new AbortController();abort=controller;let dispatched=false
    state={...state,busy:'write',attempt:{...attempt,state:'uncertain'},page:null,selected:null,history:null,error:null};emit()
    try{
      const payload=readBackupPlanPayload(JSON.parse(attempt.payload),attempt.action),base=attempt.baseline;if(base)await readBackupPlanDetail(base,scope)
      // Let immediate context changes/unmounts cancel even a new create before dispatch.
      await Promise.resolve();if(turn!==epoch||disposed||controller.signal.aborted)return false
      dispatched=true;const raw=await client.request<unknown>(attempt.path,{method:attempt.method,body:structuredClone(payload),idempotencyKey:attempt.key,signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
      const row=await readBackupPlanDetail(raw,scope);if(turn!==epoch||disposed||controller.signal.aborted)return false
      const expected=['ARCHIVE','RESTORE'].includes(attempt.action)?backupPlanDefinition(base!):readBackupPlanDefinition({name:payload.name,target_asset_ids:payload.target_asset_ids,daily_local_time:payload.daily_local_time,credential_ref:payload.credential_ref,requested_enabled:payload.requested_enabled,note:payload.note})
      if(row.version!==payload.expected_version+1||row.archived!==(attempt.action==='ARCHIVE')||row.recorded_by!==scope.actorId||!same(backupPlanDefinition(row),expected)||base&&(row.id!==base.id||row.created_at!==base.created_at||!utcNotBefore(row.updated_at,base.updated_at)||!utcNotBefore(row.recorded_at,base.recorded_at)))throw backupPlanBad()
      state={...state,receipt:row,attempt:{...attempt,state:'accepted'}};return true
    }catch(error){if(turn===epoch&&!disposed)state={...state,error,attempt:{...attempt,state:retry||dispatched&&writeFailureUncertain(false,error)?'uncertain':'rejected'}};return false}
    finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
  }
  async function submit(action:BackupPlanAction,input:unknown,baseline:unknown=null){
    if(!context?.canWrite||disposed||state.busy||state.attempt)return false
    try{
      const generation=epoch,payload=readBackupPlanPayload(input,action),base=baseline===null?null:readBackupPlanDetailShape(baseline,context)
      if(action==='CREATE'?base!==null:!base||payload.expected_version!==base.version||base.archived!==(action==='RESTORE'))throw backupPlanBad()
      const key=newKey();if(generation!==epoch||disposed||!context?.canWrite)return false;if(typeof key!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(key))throw backupPlanBad()
      const path=action==='CREATE'?'/backup-plan-drafts':'/backup-plan-drafts/'+base!.id+(action==='ARCHIVE'?'/archive':action==='RESTORE'?'/restore':'')
      return send({path,method:action==='UPDATE'?'PATCH':'POST',action,payload:JSON.stringify(payload),key,baseline:base,state:'uncertain'},false)
    }catch(error){state.error=error;emit();return false}
  }
  emit();return{reset,state:snapshot,unresolved,list:(next=false)=>read('list',next),select:(id:string)=>read('detail',false,id),history:(id:string,next=false)=>read('history',next,id),submit,retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),begin:()=>{if(!context?.canWrite||disposed||state.busy||unresolved())return false;state={...state,receipt:null,attempt:null,error:null};emit();return true},dispose:()=>{disposed=true;reset(null)}}
}
