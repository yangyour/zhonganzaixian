import {ApiError,type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportTime,exportUuid,validExportContext,type AssetExportContext} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'

export interface ExportMessage {job_id:string;tenant_id:string;recipient_id:string;permission_version:number;published_version:number;file_sha256:string;size_bytes:number;row_count:number;issued_at:string;expires_at:string;version:0|1;read_at:string|null;content_url:string|null;link_status:'AVAILABLE'|'EXPIRED';source:'postgresql'}
export interface ExportInboxPage {items:ExportMessage[];has_more:boolean;next_before_issued_at:string|null;next_before_id:string|null;unread_count:number;observed_at:string}
export interface ReadReceipt {job_id:string;version:1;read_at:string}
interface Attempt {id:string;version:0;key:string}
export interface InboxState {page:ExportInboxPage|null;receipt:ReadReceipt|null;attempt:Attempt|null;busy:'read'|'write'|null;error:unknown;unreadOnly:boolean}
export const emptyInbox=():InboxState=>({page:null,receipt:null,attempt:null,busy:null,error:null,unreadOnly:false})
const invalid=()=>new ApiError(0,'INVALID_EXPORT_MESSAGE','导出消息未通过范围或完整性校验，请刷新核对','—')
const older=(time:string,id:string,before:{time:string;id:string})=>exportTime(time)<exportTime(before.time)||exportTime(time)===exportTime(before.time)&&id<before.id
export function readInboxPage(value:unknown,scope:AssetExportContext,before?:{time:string;id:string},unreadOnly=false):ExportInboxPage{
 if(!validExportContext(scope)||!exportExact(value,['items','has_more','next_before_issued_at','next_before_id','unread_count','observed_at'])||!Array.isArray(value.items)||value.items.length>20||typeof value.has_more!=='boolean'||!exportInteger(value.unread_count))throw invalid()
 const observed=exportTime(value.observed_at);const seen=new Set<string>();let previous=before
 for(const m of value.items){
  if(!exportExact(m,['job_id','tenant_id','recipient_id','permission_version','published_version','file_sha256','size_bytes','row_count','issued_at','expires_at','version','read_at','content_url','link_status','source'])||!exportUuid(m.job_id)||seen.has(m.job_id)||m.tenant_id!==scope.tenantId||m.recipient_id!==scope.actorId||m.permission_version!==scope.permissionVersion||!exportInteger(m.published_version,1)||typeof m.file_sha256!=='string'||!/^[a-f0-9]{64}$/.test(m.file_sha256)||!exportInteger(m.size_bytes,1,16777216)||!exportInteger(m.row_count,0,1000)||!exportInteger(m.version,0,1)||m.source!=='postgresql')throw invalid()
  const issued=exportTime(m.issued_at),expires=exportTime(m.expires_at);if(issued>observed||expires-issued!==900_000_000_000n||(m.version===0)!==(m.read_at===null)||unreadOnly&&m.read_at!==null)throw invalid()
  if(m.read_at!==null&&(exportTime(m.read_at)<issued||exportTime(m.read_at)>observed))throw invalid()
  if(expires>observed){const prefix=`/api/v1/asset-export-jobs/${m.job_id}/content?grant=`;if(m.link_status!=='AVAILABLE'||typeof m.content_url!=='string'||!m.content_url.startsWith(prefix))throw invalid();const token=m.content_url.slice(prefix.length);if(!/^[1-9][0-9]{0,15}\.[A-Za-z0-9_-]{43}$/.test(token)||BigInt(token.split('.')[0]!)*1_000_000n!==expires)throw invalid()}
  else if(m.link_status!=='EXPIRED'||m.content_url!==null)throw invalid()
  if(previous&&!older(m.issued_at as string,m.job_id,previous))throw invalid();previous={time:m.issued_at as string,id:m.job_id};seen.add(m.job_id)
 }
 if(value.has_more){const last=value.items.at(-1);if(value.items.length!==20||!last||value.next_before_id!==last.job_id||value.next_before_issued_at!==last.issued_at)throw invalid()}
 else if(value.next_before_id!==null||value.next_before_issued_at!==null)throw invalid()
 return structuredClone(value) as unknown as ExportInboxPage
}
export function createExportInbox(client:{request<T>(path:string,options?:RequestOptions):Promise<T>},changed:(s:InboxState)=>void,newKey=createRequestId){
 let state=emptyInbox(),context:AssetExportContext|null=null,epoch=0,abort:AbortController|null=null,disposed=false
 const snapshot=()=>({...structuredClone({...state,error:null}),error:state.error}),emit=()=>changed(snapshot())
 const known=new Map<string,ExportMessage>();let observed:bigint|null=null
 function reset(next:AssetExportContext|null){epoch++;abort?.abort();abort=null;context=!disposed&&next&&validExportContext(next)?{...next}:null;state=emptyInbox();known.clear();observed=null;emit()}
 function remember(m:ExportMessage){const old=known.get(m.job_id);if(old){for(const key of ['tenant_id','recipient_id','permission_version','published_version','file_sha256','size_bytes','row_count','issued_at','expires_at'] as const)if(old[key]!==m[key])throw invalid();if(m.version<old.version||old.read_at!==null&&m.read_at!==old.read_at)throw invalid()}known.set(m.job_id,structuredClone(m));if(known.size>512)known.delete(known.keys().next().value!)}
 async function list(unreadOnly=state.unreadOnly,next=false){
  if(disposed||!context||state.busy||state.attempt)return false
  const before=next&&state.page?.has_more?{time:state.page.next_before_issued_at!,id:state.page.next_before_id!}:undefined;if(next&&!before)return false
  const turn=++epoch,scope={...context},controller=new AbortController();abort=controller;state={...state,page:null,busy:'read',error:null,unreadOnly};emit()
  try{const query=new URLSearchParams({page_size:'20',unread_only:String(unreadOnly)});if(before){query.set('before_issued_at',before.time);query.set('before_id',before.id)}const raw=await client.request<unknown>('/my-export-messages?'+query,{signal:controller.signal});if(disposed||turn!==epoch||controller.signal.aborted)return false;const page=readInboxPage(raw,scope,before,unreadOnly);if(observed!==null&&exportTime(page.observed_at)<observed)throw invalid();page.items.forEach(remember);observed=exportTime(page.observed_at);state.page=page;return true}
  catch(error){if(!disposed&&turn===epoch)state.error=error;return false}finally{if(!disposed&&turn===epoch){state.busy=null;abort=null;emit()}}
 }
 async function send(attempt:Attempt,retry=false){
  if(disposed||!context||state.busy)return false
  const turn=++epoch,controller=new AbortController();abort=controller;state={...state,busy:'write',error:null,attempt,receipt:null};emit()
  try{const raw=await client.request<unknown>(`/my-export-messages/${attempt.id}/read`,{method:'POST',body:{expected_version:attempt.version},idempotencyKey:attempt.key,signal:controller.signal});if(disposed||turn!==epoch||controller.signal.aborted)return false;if(!exportExact(raw,['job_id','version','read_at'])||raw.job_id!==attempt.id||raw.version!==1)throw invalid();exportTime(raw.read_at);const original=state.page?.items.find(m=>m.job_id===attempt.id);if(original&&exportTime(raw.read_at)<exportTime(original.issued_at))throw invalid();if(original)remember({...original,version:1,read_at:raw.read_at as string});state.receipt=structuredClone(raw) as unknown as ReadReceipt;state.attempt=null;return true}
  catch(error){if(!disposed&&turn===epoch){state.error=error;if(!writeFailureUncertain(retry,error))state.attempt=null}return false}finally{if(!disposed&&turn===epoch){state.busy=null;abort=null;emit()}}
 }
 emit();return{reset,state:snapshot,list,mark:(id:string)=>{if(disposed||!context||state.busy||state.attempt)return Promise.resolve(false);const m=state.page?.items.find(x=>x.job_id===id);if(!m||m.version!==0)return Promise.resolve(false);const key=newKey();if(!/^[A-Za-z0-9._:-]{1,128}$/.test(key)){state.error=invalid();emit();return Promise.resolve(false)}return send({id,version:0,key})},retry:()=>state.attempt?send(state.attempt,true):Promise.resolve(false),unresolved:()=>state.attempt!==null,dispose:()=>{disposed=true;reset(null)}}
}
