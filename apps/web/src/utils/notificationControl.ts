import {ApiError, type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {writeFailureUncertain} from './writeFailure'

export type ControlKind='POLICY'|'PREFERENCE'|'BINDING'|'REQUEST'|'ROUTE'|'RECEIPT'
export interface ControlEntry {kind:ControlKind;id:string;version:number;command_id:string;state:Record<string,unknown>;recorded_at:string}
export interface ControlPage {items:ControlEntry[];next_id:string|null;next_time:string|null;source:'postgresql'}
export interface ControlCommand {id:string;path:string;body:Record<string,unknown>;kind:ControlKind;objectId:string|null}
export interface ControlCommandState {pending:ControlCommand|null;busy:boolean;error:unknown;receipt:ControlEntry|null;restorationBlocked:boolean}
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const object=(value:unknown):value is Record<string,unknown>=>value!==null&&typeof value==='object'&&!Array.isArray(value)
const kinds:ControlKind[]=['POLICY','PREFERENCE','BINDING','REQUEST','ROUTE','RECEIPT']
const invalid=()=>new ApiError(0,'INVALID_NOTIFICATION_CONTROL','通知记录未通过范围或完整性校验，请重新核对','—')

export function readControlEntry(value:unknown):ControlEntry {
 if(!object(value)||Object.keys(value).sort().join(',')!=='command_id,id,kind,recorded_at,state,version'||!kinds.includes(value.kind as ControlKind)
  ||!uuid(value.id)||!uuid(value.command_id)||!Number.isSafeInteger(value.version)||(value.version as number)<0||(value.version as number)>100000
  ||!object(value.state)||typeof value.recorded_at!=='string'||!Number.isFinite(Date.parse(value.recorded_at)))throw invalid()
 return structuredClone(value) as unknown as ControlEntry
}
export function readControlPage(value:unknown,kind:ControlKind):ControlPage {
 if(!object(value)||!Array.isArray(value.items)||value.items.length>100||value.source!=='postgresql'
  ||(value.next_id===null)!==(value.next_time===null)||value.next_id!==null&&(!uuid(value.next_id)||typeof value.next_time!=='string'||!Number.isFinite(Date.parse(value.next_time))))throw invalid()
 const entries=value.items.map(readControlEntry)
 if(entries.some(entry=>entry.kind!==kind)||new Set(entries.map(entry=>entry.id)).size!==entries.length)throw invalid()
 if(value.next_id!==null&&entries.at(-1)?.id!==value.next_id)throw invalid()
 return {items:entries,next_id:value.next_id as string|null,next_time:value.next_time as string|null,source:'postgresql'}
}

/** Retains the exact command across reloads; a current-object GET is never treated as a write acknowledgment. */
export function createControlCommands(client:{request(path:string,options?:RequestOptions):Promise<unknown>},
 storage:Pick<Storage,'getItem'|'setItem'|'removeItem'>,changed:(state:ControlCommandState)=>void,newId=createRequestId){
 let scope:string|null=null,epoch=0,abort:AbortController|null=null
 let state:ControlCommandState={pending:null,busy:false,error:null,receipt:null,restorationBlocked:false}
 const key=()=>scope===null?null:'mop.notification-control.pending:'+scope
 const snapshot=()=>({...state,pending:state.pending?structuredClone(state.pending):null,receipt:state.receipt?structuredClone(state.receipt):null})
 const emit=()=>changed(snapshot())
 const valid=(value:unknown):value is ControlCommand=>object(value)&&uuid(value.id)&&typeof value.path==='string'
  &&/^\/(notification-routing\/(policy|bindings|requests\/[0-9a-f-]{36}\/(plan|cancel)|routes\/[0-9a-f-]{36}\/[a-z-]+)|my\/notification-preferences\/publication)$/.test(value.path)
  &&object(value.body)&&Number.isSafeInteger(value.body.expected_version)&&(value.body.expected_version as number)>=-1
  &&(value.body.expected_version as number)<100000&&kinds.includes(value.kind as ControlKind)&&(value.objectId===null||uuid(value.objectId))
  &&(!value.path.endsWith('/cancel')||value.kind==='REQUEST'&&value.objectId===value.path.split('/')[3]
   &&(value.body.expected_version as number)>=0&&Object.keys(value.body).sort().join(',')==='expected_version,reason'
   &&typeof value.body.reason==='string'&&value.body.reason.trim().length>0&&value.body.reason.length<=1000)
 function reset(next:string|null){
  const priorKey=key();epoch++;abort?.abort();abort=null
  let cleanupFailure:unknown=null
  if(scope!==null&&scope!==next&&priorKey){try{storage.removeItem(priorKey)}catch(error){cleanupFailure=error}}
  scope=next;state={pending:null,busy:false,error:null,receipt:null,restorationBlocked:false}
  if(cleanupFailure){state.error=cleanupFailure;state.restorationBlocked=true}
  else if(next!==null){try{const raw=storage.getItem(key()!);if(raw){const value:unknown=JSON.parse(raw);if(raw.length>65536||!valid(value))throw invalid();state.pending=value}}catch(error){state.error=error;state.restorationBlocked=true}}
  emit()
 }
 function cancelWait(){epoch++;abort?.abort();abort=null;state.busy=false;emit()}
 function acknowledge(raw:unknown,command:ControlCommand){
  const entry=readControlEntry(raw)
  if(entry.command_id!==command.id||entry.kind!==command.kind||command.objectId!==null&&entry.id!==command.objectId
   ||entry.version!==(command.body.expected_version as number)+1)throw invalid()
  if(command.path.endsWith('/cancel')&&(entry.state.status!=='CANCELLED'||entry.state.cancellation_reason!==command.body.reason
   ||entry.state.external_dispatch_enabled!==false))throw invalid()
  storage.removeItem(key()!);state.pending=null;state.receipt=entry
 }
 async function perform(recover:boolean,retry:boolean){
  if(scope===null||state.busy||!state.pending)return false
  const turn=++epoch,command=structuredClone(state.pending),controller=new AbortController();abort=controller
  state.busy=true;state.error=null;state.receipt=null;emit()
  try{
   const raw=await client.request(recover?`/notification-routing/commands/${command.id}`:command.path,
    recover?{signal:controller.signal}:{method:'POST',body:command.body,idempotencyKey:command.id,signal:controller.signal})
   if(turn!==epoch||controller.signal.aborted)return false
   acknowledge(raw,command);return true
  }catch(error){if(turn===epoch){state.error=error;if(!recover&&!writeFailureUncertain(retry,error)){storage.removeItem(key()!);state.pending=null}}return false}
  finally{if(turn===epoch){state.busy=false;abort=null;emit()}}
 }
 async function submit(path:string,body:Record<string,unknown>,kind:ControlKind,objectId:string|null=null){
  if(scope===null||state.busy||state.pending||state.restorationBlocked)return false
  try{
   const command={id:newId(),path,body:structuredClone(body),kind,objectId}
   if(!valid(command))throw invalid()
   const encoded=JSON.stringify(command);if(encoded.length>65536)throw invalid()
   storage.setItem(key()!,encoded);state.pending=command
  }catch(error){state.error=error;emit();return false}
  return perform(false,false)
 }
 return{reset,submit,recover:()=>perform(true,true),retry:()=>perform(false,true),cancelWait,state:snapshot,
  dispose:()=>{epoch++;abort?.abort();abort=null;state.busy=false}}
}
