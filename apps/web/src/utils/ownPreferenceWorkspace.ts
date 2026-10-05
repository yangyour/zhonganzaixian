import {isCancelled,type ApiClient} from '../api/client'
import {detachedWorkspaceState} from './detachedWorkspaceState'
import {writeFailureUncertain} from './writeFailure'
import {ownPreferenceBad,ownPreferenceDefinition,ownPreferenceFingerprint,ownPreferenceSame,ownPreferenceTime,ownPreferenceUuid,readOwnPreferenceCurrent,readOwnPreferenceHistory,readOwnPreferencePayload,readOwnPreferenceSnapshot,readOwnPreferenceSnapshotShape,validOwnPreferenceContext,type OwnPreferenceContext,type OwnPreferenceCurrent,type OwnPreferenceHistory,type OwnPreferenceSnapshot,type OwnPreferenceRevision} from './ownNotificationPreferences'
export interface OwnPreferenceAttempt {path:string;method:'POST'|'PATCH';action:'CREATE'|'UPDATE';payload:string;key:string;baseline:OwnPreferenceSnapshot|null;state:'uncertain'|'accepted'|'rejected'}
export interface OwnPreferenceState {current:OwnPreferenceCurrent|null;history:OwnPreferenceHistory|null;receipt:OwnPreferenceSnapshot|null;attempt:OwnPreferenceAttempt|null;busy:'read'|'write'|null;error:unknown}
export const emptyOwnPreferenceState=():OwnPreferenceState=>({current:null,history:null,receipt:null,attempt:null,busy:null,error:null})
export function createOwnPreferenceWorkspace(client:Pick<ApiClient,'request'>,changed:(state:OwnPreferenceState)=>void,newKey:()=>string=()=>crypto.randomUUID()){
 let context:OwnPreferenceContext|null=null,state=emptyOwnPreferenceState(),epoch=0,abort:AbortController|null=null,disposed=false,known:OwnPreferenceSnapshot|null=null
 const revisions=new Map<number,OwnPreferenceRevision>(),snapshot=()=>detachedWorkspaceState(state),emit=()=>changed(snapshot()),unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
 function reset(next:OwnPreferenceContext|null){abort?.abort();abort=null;++epoch;context=!disposed&&validOwnPreferenceContext(next)?{...next}:null;state=emptyOwnPreferenceState();known=null;revisions.clear();emit()}
 function checkHead(row:OwnPreferenceSnapshot,receipt=false){if(known&&(row.id!==known.id||row.created_at!==known.created_at||!receipt&&(row.version<known.version||ownPreferenceTime(row.updated_at)<ownPreferenceTime(known.updated_at))||row.version===known.version&&!ownPreferenceSame(row,known)))throw ownPreferenceBad();const revision=revisions.get(row.version);if(revision&&!ownPreferenceSame(revision,toRevision(row)))throw ownPreferenceBad()}
 function remember(row:OwnPreferenceSnapshot){if(!known||row.version>known.version)known=structuredClone(row);revisions.set(row.version,toRevision(row));if(revisions.size>512)revisions.delete(revisions.keys().next().value!)}
 function toRevision(row:OwnPreferenceSnapshot):OwnPreferenceRevision{const {id,created_at:_created,updated_at,...rest}=row;return{...rest,preference_id:id,previous_version:row.version===1?null:row.version-1,action:row.version===1?'CREATE':'UPDATE',recorded_at:updated_at}}
 async function read(id?:string,next=false){
  if(!context||disposed||state.busy)return false
  const before=id&&next&&state.history?.preference_id===id&&state.history.has_more?state.history.next_before_version!:undefined,boundary=before?state.history!.items.at(-1):undefined
  if(next&&!before)return false
  if(id!==undefined&&!ownPreferenceUuid(id)){state.history=null;state.error=ownPreferenceBad();emit();return false}
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller;state={...state,...(id?{history:null}:{current:null,history:null}),busy:'read',error:null};emit()
  try{
   if(turn!==epoch||disposed||controller.signal.aborted)return false;const query=new URLSearchParams({page_size:'15'});if(before)query.set('before_version',String(before));const raw=await client.request<unknown>(id?'/my/notification-preferences/'+id+'/history?'+query:'/my/notification-preferences',{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   if(id){const page=await readOwnPreferenceHistory(raw,scope,id,before);if(turn!==epoch||disposed||controller.signal.aborted)return false
    const first=page.items[0];if(known&&(id!==known.id||before===undefined&&first.version<known.version)||boundary&&first&&ownPreferenceTime(first.recorded_at)>ownPreferenceTime(boundary.recorded_at))throw ownPreferenceBad()
    for(const row of page.items){const previous=revisions.get(row.version);if(previous&&!ownPreferenceSame(row,previous))throw ownPreferenceBad();if(known&&row.version===known.version&&!ownPreferenceSame(row,toRevision(known)))throw ownPreferenceBad()}
    for(const row of page.items){revisions.set(row.version,structuredClone(row));if(revisions.size>512)revisions.delete(revisions.keys().next().value!)}state.history=page
   }else{const result=await readOwnPreferenceCurrent(raw,scope);if(turn!==epoch||disposed||controller.signal.aborted)return false;if(result.current){checkHead(result.current);remember(result.current)}else if(known)throw ownPreferenceBad();state.current=result}
   return true
  }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error))state.error=error;return false}finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 async function send(input:OwnPreferenceAttempt,retry:boolean){
  if(!context?.canWrite||disposed||state.busy)return false
  const attempt=structuredClone(input),turn=++epoch,scope={...context},controller=new AbortController();abort=controller;let dispatched=false;state={...state,current:null,history:null,busy:'write',error:null,attempt:{...attempt,state:'uncertain'}};emit()
  try{
   const payload=readOwnPreferencePayload(JSON.parse(attempt.payload),attempt.action),base=attempt.baseline;if(base)await readOwnPreferenceSnapshot(base,scope)
   await Promise.resolve();if(turn!==epoch||disposed||controller.signal.aborted)return false
   dispatched=true;const raw=await client.request<unknown>(attempt.path,{method:attempt.method,body:structuredClone(payload),idempotencyKey:attempt.key,signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   const row=await readOwnPreferenceSnapshot(raw,scope);if(turn!==epoch||disposed||controller.signal.aborted)return false
   const expectedHash=await ownPreferenceFingerprint(scope.tenantId,scope.actorId,ownPreferenceDefinition(payload));if(turn!==epoch||disposed||controller.signal.aborted)return false
   if(row.content_sha256!==expectedHash||row.version!==payload.expected_version+1||row.recorded_permission_version!==scope.permissionVersion||base&&(row.id!==base.id||row.created_at!==base.created_at||ownPreferenceTime(row.updated_at)<ownPreferenceTime(base.updated_at)))throw ownPreferenceBad()
   checkHead(row,true);remember(row);state={...state,receipt:row,attempt:{...attempt,state:'accepted'}};return true
  }catch(error){if(turn===epoch&&!disposed)state={...state,error,attempt:{...attempt,state:retry||dispatched&&writeFailureUncertain(false,error)?'uncertain':'rejected'}};return false}finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 async function submit(action:'CREATE'|'UPDATE',input:unknown,baseline:unknown=null){
  if(!context?.canWrite||disposed||state.busy||state.attempt)return false
  try{const generation=epoch,payload=readOwnPreferencePayload(input,action),base=baseline===null?null:readOwnPreferenceSnapshotShape(baseline,context);if(action==='CREATE'?base!==null||known!==null:!base||payload.expected_version!==base.version)throw ownPreferenceBad();if(base)checkHead(base)
   const key=newKey();if(generation!==epoch||disposed||!context?.canWrite)return false;if(typeof key!=='string'||key.length<1||key.length>128||/[^A-Za-z0-9._:-]/.test(key))throw ownPreferenceBad()
   return send({path:action==='CREATE'?'/my/notification-preferences':'/my/notification-preferences/'+base!.id,method:action==='CREATE'?'POST':'PATCH',action,payload:JSON.stringify(payload),key,baseline:base,state:'uncertain'},false)
  }catch(error){state.error=error;emit();return false}
 }
 emit();return{reset,state:snapshot,unresolved,load:()=>read(),history:(id:string,next=false)=>read(id,next),submit,retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),begin:()=>{if(!context?.canWrite||disposed||state.busy||unresolved())return false;state={...state,receipt:null,attempt:null,error:null};emit();return true},dispose:()=>{disposed=true;reset(null)}}
}
