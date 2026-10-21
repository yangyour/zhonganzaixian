import { createRequestId } from './browserCrypto'
import {isCancelled,type ApiClient} from '../api/client'
import {writeFailureUncertain} from './writeFailure'
import {assetExportInput,exportBad,exportEquals,exportSummary,exportTime,exportUuid,validExportContext,readExportReceipt,readExportPage,readExportDetail,type AssetExportContext,type AssetExportReceipt,type AssetExportSummary,type AssetExportDetail,type AssetExportPage} from './assetExportJobs'
interface ReadRequest {id?:string;next:boolean;preserveSelected:boolean;before?:{time:string;id:string}}
interface Attempt {path:'/asset-export-jobs';payload:string;key:string;state:'uncertain'|'accepted'|'rejected'}
export interface AssetExportState {page:AssetExportPage|null;selected:AssetExportDetail|null;receipt:AssetExportReceipt|null;attempt:Attempt|null;paged:boolean;busy:'read'|'write'|null;error:unknown}
export const emptyAssetExportState=():AssetExportState=>({page:null,selected:null,receipt:null,attempt:null,paged:false,busy:null,error:null})
export function createAssetExportWorkspace(client:Pick<ApiClient,'request'>,notify:(state:AssetExportState)=>void,newKey:()=>string=()=>createRequestId()){
 let lastRead:ReadRequest|null=null
 let scope:AssetExportContext|null=null,state=emptyAssetExportState(),epoch=0,abort:AbortController|null=null,disposed=false
 const known=new Map<string,AssetExportSummary>(),details=new Map<string,AssetExportDetail>(),snapshot=()=>({...structuredClone({...state,error:null}),error:state.error}),emit=()=>notify(snapshot()),unresolved=()=>state.busy==='write'||state.attempt?.state==='uncertain'
 function reset(context:AssetExportContext|null){abort?.abort();abort=null;++epoch;lastRead=null;known.clear();details.clear();scope=!disposed&&validExportContext(context)?{...context}:null;state=emptyAssetExportState();emit()}
 function checkKnown(job:AssetExportSummary){const old=known.get(job.id),current=exportSummary(job);if(old){for(const key of ['tenant_id','created_by','accepted_permission_version','accepted_role','input_sha256','filters','created_at'] as const)if(!exportEquals(old[key],current[key]))throw exportBad();if(current.version<old.version||exportTime(current.updated_at)<exportTime(old.updated_at)||(current.version===old.version||['SUCCEEDED','FAILED'].includes(old.status))&&!exportEquals(old,current))throw exportBad()}}
 function remember(job:AssetExportSummary){known.set(job.id,structuredClone(exportSummary(job)));if(known.size>512){const id=known.keys().next().value!;known.delete(id);details.delete(id)}}
 function checkDetails(job:AssetExportDetail){const old=details.get(job.id);if(old&&(job.attempts<old.attempts||old.prepared!==null&&!exportEquals(old.prepared,job.prepared)||(job.version===old.version||['SUCCEEDED','FAILED'].includes(old.status))&&!exportEquals(old,job)))throw exportBad();if(state.receipt?.job_id===job.id&&state.attempt&&!exportEquals(job.filters,JSON.parse(state.attempt.payload).filters))throw exportBad()}
 async function read(id?:string,next=false,preserveSelected=false,recovery?:ReadRequest){
  if(!scope||disposed||state.busy)return false
  const before=recovery?.before??(next&&state.page?.has_more?{time:state.page.next_before_created_at!,id:state.page.next_before_id!}:undefined);if(next&&!before)return false
  if(id!==undefined&&!exportUuid(id)){lastRead=null;state.selected=null;state.error=exportBad();emit();return false}
  lastRead=structuredClone({id,next,preserveSelected,before})
  const turn=++epoch,context={...scope},controller=new AbortController();abort=controller;state={...state,busy:'read',selected:preserveSelected&&state.selected?.id===id?state.selected:null,...(id?{}:{page:null,paged:next}),error:null};emit()
  try{if(turn!==epoch||disposed||controller.signal.aborted)return false;const query=new URLSearchParams({page_size:'15'});if(before){query.set('before_created_at',before.time);query.set('before_id',before.id)}const raw=await client.request<unknown>(id?'/asset-export-jobs/'+id:'/asset-export-jobs?'+query,{signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false
   if(id){const job=readExportDetail(raw,context,id);checkKnown(job);checkDetails(job);remember(job);details.set(job.id,structuredClone(job));state.selected=job}else{const page=readExportPage(raw,context,before);page.items.forEach(checkKnown);page.items.forEach(remember);state.page=page}lastRead=null;return true
  }catch(error){if(turn===epoch&&!disposed&&!isCancelled(error)){state.selected=null;state.error=error}return false}finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 async function send(attempt:Attempt,retry:boolean){
  if(!scope||disposed||state.busy)return false
  lastRead=null;const turn=++epoch,controller=new AbortController();abort=controller;state={...state,page:null,selected:null,busy:'write',error:null,attempt:{...attempt,state:'uncertain'}};emit()
  try{if(turn!==epoch||disposed||controller.signal.aborted)return false;const raw=await client.request<unknown>(attempt.path,{method:'POST',body:JSON.parse(attempt.payload),idempotencyKey:attempt.key,signal:controller.signal});if(turn!==epoch||disposed||controller.signal.aborted)return false;const receipt=readExportReceipt(raw);state.receipt=receipt;state.attempt={...attempt,state:'accepted'};return true
  }catch(error){if(turn===epoch&&!disposed){state.error=error;state.attempt={...attempt,state:writeFailureUncertain(retry,error)?'uncertain':'rejected'}}return false}finally{if(turn===epoch&&!disposed){state.busy=null;abort=null;emit()}}
 }
 async function submit(filters:unknown){if(!scope||disposed||state.busy||state.attempt)return false;const turn=epoch;try{const input=assetExportInput(filters),key=newKey();if(turn!==epoch||!scope||disposed||state.busy||state.attempt)return false;if(typeof key!=='string'||key.length<1||key.length>128||/[^A-Za-z0-9._:-]/.test(key))throw exportBad();return send({path:'/asset-export-jobs',payload:JSON.stringify(input),key,state:'uncertain'},false)}catch(error){if(turn===epoch&&!disposed){state.error=error;emit()}return false}}
 function canRetryRead(){return !!scope&&!disposed&&!state.busy&&lastRead!==null}
 function cancelRead(){if(disposed||state.busy!=='read')return false;++epoch;abort?.abort();abort=null;state={...state,busy:null,selected:null,error:null};emit();return true}
 async function retryRead(){if(!canRetryRead()||!lastRead)return false;const request=structuredClone(lastRead);return read(request.id,request.next,request.preserveSelected,request)}
 emit();return{cancelRead,retryRead,canRetryRead,reset,state:snapshot,unresolved,submit,list:(next=false)=>read(undefined,next),select:(id:string)=>read(id),refreshSelected:()=>state.selected?read(state.selected.id,false,true):Promise.resolve(false),retry:()=>state.attempt?.state==='uncertain'?send(state.attempt,true):Promise.resolve(false),begin:()=>{if(!scope||disposed||state.busy||unresolved())return false;lastRead=null;state={...state,receipt:null,attempt:null,error:null};emit();return true},dispose:()=>{disposed=true;reset(null)}}
}
