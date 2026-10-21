import {ApiError,type RequestOptions} from '../api/client'
import {exportExact,exportInteger,exportUuid} from './assetExportJobs'
export interface OnCallMemberCandidate{id:string;display_name:string;role:'padmin'|'msp'}
export interface OnCallMemberContext{tenantId:string;actorId:string;permissionVersion:number}
export interface OnCallMemberState{items:OnCallMemberCandidate[];busy:boolean;loaded:boolean;cancelled:boolean;error:unknown}
export const emptyOnCallMemberState=():OnCallMemberState=>({items:[],busy:false,loaded:false,cancelled:false,error:null})
const bad=()=>new ApiError(0,'INVALID_ONCALL_MEMBER_CANDIDATES','内部候选人员未通过完整性核对，未更新选择项','—')
export function readOnCallMemberCandidates(raw:unknown):OnCallMemberCandidate[]{
 if(!exportExact(raw,['items','source'])||raw.source!=='postgresql'||!Array.isArray(raw.items)||raw.items.length>100)throw bad()
 const ids=new Set<string>();for(const row of raw.items){if(!exportExact(row,['id','display_name','role'])||!exportUuid(row.id)||ids.has(row.id)||typeof row.display_name!=='string'||!row.display_name.trim()||row.display_name.length>200||Array.from(row.display_name).length>100||!['msp','padmin'].includes(row.role as string))throw bad();ids.add(row.id)}
 return structuredClone(raw.items) as OnCallMemberCandidate[]
}
/** Explicit read only. The endpoint's server authorization remains authoritative; candidates are not an execution grant. */
export function createOnCallMemberCandidates(client:{request<T>(path:string,options?:RequestOptions):Promise<T>},changed:(state:OnCallMemberState)=>void){
 let state=emptyOnCallMemberState(),scope:OnCallMemberContext|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>({...state,items:structuredClone(state.items)}),emit=()=>changed(snapshot())
 function reset(next:OnCallMemberContext|null){epoch++;abort?.abort();abort=null;scope=!disposed&&next&&exportUuid(next.tenantId)&&exportUuid(next.actorId)&&exportInteger(next.permissionVersion,1)?{...next}:null;state=emptyOnCallMemberState();emit()}
 async function load(){if(disposed||!scope||state.busy)return false;const turn=++epoch,controller=new AbortController();abort=controller;state={...emptyOnCallMemberState(),busy:true};emit()
  try{if(disposed||turn!==epoch||controller.signal.aborted)return false;const raw=await client.request('/ticket-workflows/assignees',{signal:controller.signal});if(disposed||turn!==epoch)return false;state={...emptyOnCallMemberState(),loaded:true,items:readOnCallMemberCandidates(raw)};abort=null;emit();return !disposed&&turn===epoch}
  catch(error){if(disposed||turn!==epoch)return false;abort=null;state={...emptyOnCallMemberState(),error};emit();return false}
 }
 function cancel(){if(disposed||!state.busy)return false;epoch++;abort?.abort();abort=null;state={...emptyOnCallMemberState(),cancelled:true};emit();return true}
 return {state:snapshot,reset,load,cancel,dispose(){disposed=true;epoch++;abort?.abort();abort=null}}
}
