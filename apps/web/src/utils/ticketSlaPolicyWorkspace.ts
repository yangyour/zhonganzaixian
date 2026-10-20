import { ApiError, isCancelled, type ApiClient } from '../api/client'
import { createRequestId } from './browserCrypto'
import { detachedWorkspaceState } from './detachedWorkspaceState'
import { utcInstantNanos } from './utcInstant'
export interface PolicyScope { tenantId:string; ticketId:string; actorId:string; permissionVersion:number; expectedVersion:number; canWrite:boolean }
export interface PolicyBinding { tenant_id:string; ticket_id:string; lifecycle_version:number; status:'OPENING_FROZEN'|'BOUND'|'AVAILABLE'|'CONFIG_PENDING'; release_id:string|null; release_version:number|null; clock_policy:'EXCLUDE_CONFIRMED_PAUSES'|'INCLUDE_ALL_ELAPSED_TIME'|null; bound_at:string|null; reference:string|null; missing:string|null; source:'postgresql'; production_approved:false }
interface Attempt { key:string; payload:string; policy:string; state:'uncertain'|'accepted'|'rejected' }
export interface PolicyState { view:PolicyBinding|null; attempt:Attempt|null; busy:'read'|'write'|null; error:unknown }
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const version=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=1&&Number(v)<=9007199254740990
const exact=(v:unknown,keys:string[]):v is Record<string,unknown>=>Boolean(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k)))
const invalid=()=>new ApiError(200,'INVALID_SLA_POLICY_RESPONSE','SLA口径版本或范围不一致，请重新读取','—')
export function readPolicyBinding(v:unknown,scope:PolicyScope):PolicyBinding{
  if(!exact(v,['tenant_id','ticket_id','lifecycle_version','status','release_id','release_version','clock_policy','bound_at','reference','missing','source','production_approved'])||v.tenant_id!==scope.tenantId||v.ticket_id!==scope.ticketId||v.lifecycle_version!==scope.expectedVersion||!version(v.lifecycle_version)||!['OPENING_FROZEN','BOUND','AVAILABLE','CONFIG_PENDING'].includes(v.status as string)||v.source!=='postgresql'||v.production_approved!==false)throw invalid()
  if(v.status==='CONFIG_PENDING'){
    if([v.release_id,v.release_version,v.clock_policy,v.bound_at,v.reference].some(x=>x!==null)||typeof v.missing!=='string'||!v.missing)throw invalid()
  }else{
    if(!['EXCLUDE_CONFIRMED_PAUSES','INCLUDE_ALL_ELAPSED_TIME'].includes(v.clock_policy as string)||v.missing!==null)throw invalid()
    if(v.status==='OPENING_FROZEN'){
      if(v.release_id!==null||v.release_version!==null||v.bound_at!==null||typeof v.reference!=='string'||!/^opening:sha256:[0-9a-f]{64}$/.test(v.reference))throw invalid()
    }else{
      if(typeof v.release_id!=='string'||!uuid.test(v.release_id)||!version(v.release_version))throw invalid()
      if(v.status==='AVAILABLE'?(v.bound_at!==null||v.reference!==null):(typeof v.bound_at!=='string'||utcInstantNanos(v.bound_at)===null||v.reference!==`binding:${scope.ticketId}:${v.release_id}:${v.release_version}`))throw invalid()
    }
  }
  return structuredClone(v) as unknown as PolicyBinding
}
export const emptyPolicyState=():PolicyState=>({view:null,attempt:null,busy:null,error:null})
export function createTicketSlaPolicyWorkspace(client:Pick<ApiClient,'request'>,changed:(state:PolicyState)=>void,newKey:()=>string=createRequestId){
  let scope:PolicyScope|null=null,state=emptyPolicyState(),epoch=0,controller:AbortController|undefined
  const emit=()=>changed(detachedWorkspaceState(state)),path=(c:PolicyScope)=>`/tickets/${c.ticketId}/sla/policy-binding`,unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
  function reset(next:PolicyScope|null){controller?.abort();++epoch;state=emptyPolicyState();scope=next&&[next.tenantId,next.ticketId,next.actorId].every(x=>uuid.test(x))&&version(next.permissionVersion)&&version(next.expectedVersion)&&typeof next.canWrite==='boolean'?{...next}:null;if(next&&!scope)state.error=invalid();emit()}
  async function refresh(){
    if(!scope||state.busy==='write')return false
    const c={...scope},turn=++epoch;controller?.abort();const abort=new AbortController();controller=abort;state={...state,view:null,busy:'read',error:null};emit()
    try{const view=readPolicyBinding(await client.request<unknown>(path(c),{signal:abort.signal}),c);if(turn!==epoch||abort.signal.aborted)return false;
      if(state.attempt?.state==='accepted'){const body=JSON.parse(state.attempt.payload);if(view.status!=='BOUND'||view.release_id!==body.release_id||view.release_version!==body.release_version||view.clock_policy!==state.attempt.policy)throw invalid()}
      state={...state,view};return true
    }catch(error){if(turn===epoch&&!isCancelled(error))state={...state,error};return false}
    finally{if(turn===epoch){state={...state,busy:null};emit()}}
  }
  async function send(attempt:Attempt,retry=false){
    if(!scope?.canWrite||state.busy)return false
    const c={...scope},turn=++epoch;controller?.abort();const abort=new AbortController();controller=abort;state={...state,busy:'write',error:null,attempt:{...attempt,state:'uncertain'}};emit()
    try{const body=JSON.parse(attempt.payload);const view=readPolicyBinding(await client.request<unknown>(path(c),{method:'POST',body,idempotencyKey:attempt.key,signal:abort.signal}),c);if(turn!==epoch||abort.signal.aborted)return false;
      if(view.status!=='BOUND'||view.release_id!==body.release_id||view.release_version!==body.release_version||view.clock_policy!==attempt.policy)throw invalid();state={...state,view,busy:null,attempt:{...attempt,state:'accepted'}};emit();await refresh();return true
    }catch(error){if(turn!==epoch)return false;const uncertain=retry||isCancelled(error)||!(error instanceof ApiError)||error.status===0||error.status>=500||error.code.startsWith('INVALID_');state={...state,view:null,error,attempt:{...attempt,state:uncertain?'uncertain':'rejected'}};return false}
    finally{if(turn===epoch){state={...state,busy:null};emit()}}
  }
  async function bind(){const v=state.view;if(!scope?.canWrite||state.busy||unresolved()||v?.status!=='AVAILABLE'||!v.clock_policy)return false;return send({key:newKey(),policy:v.clock_policy,payload:JSON.stringify({expected_lifecycle_version:v.lifecycle_version,release_id:v.release_id,release_version:v.release_version}),state:'uncertain'})}
  emit();return{reset,refresh,bind,retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),unresolved,dispose:()=>reset(null),state:()=>detachedWorkspaceState(state)}
}
