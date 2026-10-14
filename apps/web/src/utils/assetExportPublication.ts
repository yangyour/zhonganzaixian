import {ApiError,type RequestOptions} from '../api/client'
import {createRequestId} from './browserCrypto'
import {exportExact,exportInteger,exportTime,exportUuid,validExportContext,type AssetExportContext} from './assetExportJobs'
import {writeFailureUncertain} from './writeFailure'

export interface ExportPublication {job_id:string;tenant_id:string;created_by:string;permission_version:number;version:number;status:'NOT_PUBLISHED'|'PUBLISHED';prepared:boolean;file_sha256:string|null;size_bytes:number|null;row_count:number|null;stored_at:string|null;observed_at:string;publication_enabled:boolean;download_grant_available:boolean;notification_sent:false;source:'postgresql'}
export interface ExportGrant {job_id:string;content_url:string;expires_at:string;size_bytes:number;file_sha256:string}
const digest=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{64}$/.test(v)
const invalid=()=>new ApiError(0,'INVALID_EXPORT_PUBLICATION','发布或下载结果未通过核对，请保留原请求重试','—')
export function readExportPublication(value:unknown,c:AssetExportContext,id:string):ExportPublication{
 if(!validExportContext(c)||!exportUuid(id)||!exportExact(value,['job_id','tenant_id','created_by','permission_version','version','status','prepared','file_sha256','size_bytes','row_count','stored_at','observed_at','publication_enabled','download_grant_available','notification_sent','source'])||value.job_id!==id||value.tenant_id!==c.tenantId||value.created_by!==c.actorId||value.permission_version!==c.permissionVersion||!exportInteger(value.version)||!['PUBLISHED','NOT_PUBLISHED'].includes(String(value.status))||typeof value.prepared!=='boolean'||typeof value.publication_enabled!=='boolean'||typeof value.download_grant_available!=='boolean'||value.notification_sent!==false||value.source!=='postgresql')throw invalid()
 exportTime(value.observed_at)
 if(value.prepared){if(!digest(value.file_sha256)||!exportInteger(value.size_bytes,1,16777216)||!exportInteger(value.row_count,0,1000))throw invalid()}
 else if(value.file_sha256!==null||value.size_bytes!==null||value.row_count!==null)throw invalid()
 if(value.status==='PUBLISHED'){if(!value.prepared||!exportInteger(value.version,1)||value.stored_at===null||exportTime(value.stored_at)>exportTime(value.observed_at))throw invalid()}
 else if(value.stored_at!==null||value.download_grant_available)throw invalid()
 if(value.download_grant_available&&(!value.publication_enabled||value.status!=='PUBLISHED'))throw invalid()
 return structuredClone(value) as unknown as ExportPublication
}
export function readExportGrant(value:unknown,p:ExportPublication):ExportGrant{
 if(!exportExact(value,['job_id','content_url','expires_at','size_bytes','file_sha256'])||value.job_id!==p.job_id||p.status!=='PUBLISHED'||value.size_bytes!==p.size_bytes||value.file_sha256!==p.file_sha256||typeof value.content_url!=='string')throw invalid()
 const prefix=`/api/v1/asset-export-jobs/${p.job_id}/content?grant=`
 if(!value.content_url.startsWith(prefix))throw invalid()
 const token=value.content_url.slice(prefix.length)
 if(!/^[1-9][0-9]{0,15}\.[A-Za-z0-9_-]{43}$/.test(token)||BigInt(token.split('.')[0]!) * 1_000_000n!==exportTime(value.expires_at))throw invalid()
 return structuredClone(value) as unknown as ExportGrant
}
interface Transport {request<T>(path:string,options?:RequestOptions):Promise<T>}
type Kind='publication'|'content-grants'
interface Attempt {kind:Kind;key:string;body:Record<string,unknown>;uncertain:boolean}
export interface ExportPublicationState {current:ExportPublication|null;grant:ExportGrant|null;busy:boolean;attempt:Attempt|null;error:ApiError|null}
export const emptyPublicationState=():ExportPublicationState=>({current:null,grant:null,busy:false,attempt:null,error:null})
export function createExportPublicationWorkspace(transport:Transport,changed:(s:ExportPublicationState)=>void){
 let context:AssetExportContext|null=null,id:string|null=null,epoch=0,controller:AbortController|null=null,state=emptyPublicationState()
 const emit=()=>changed({...state,current:state.current?structuredClone(state.current):null,grant:state.grant?structuredClone(state.grant):null,attempt:state.attempt?structuredClone(state.attempt):null})
 const active=()=>validExportContext(context)&&id!==null&&exportUuid(id)
 const error=(e:unknown)=>e instanceof ApiError?e:new ApiError(0,'REQUEST_UNCONFIRMED','请求结果未确认，请使用原请求重试','—')
 function reset(c:AssetExportContext|null,job:string|null){epoch++;controller?.abort();context=c?{...c}:null;id=job;state=emptyPublicationState();emit()}
 async function read(){
  if(!active()||state.busy)return false
  const turn=epoch,c={...context!},job=id!;controller=new AbortController();state.busy=true;state.current=null;state.grant=null;state.error=null;emit()
  try{const raw=await transport.request<unknown>(`/asset-export-jobs/${job}/publication`,{signal:controller.signal});if(turn!==epoch)return false;state.current=readExportPublication(raw,c,job);return true}
  catch(e){if(turn===epoch)state.error=error(e);return false}
  finally{if(turn===epoch){state.busy=false;emit()}}
 }
 async function perform(attempt:Attempt){
  if(!active()||state.busy)return false
  const turn=epoch,c={...context!},job=id!,previous=attempt.uncertain,p=state.current?structuredClone(state.current):null
  controller=new AbortController();state.busy=true;state.attempt={...attempt,body:structuredClone(attempt.body),uncertain:true};state.error=null;state.grant=null;emit()
  try{
   const raw=await transport.request<unknown>(`/asset-export-jobs/${job}/${attempt.kind}`,{method:'POST',body:structuredClone(attempt.body),idempotencyKey:attempt.key,signal:controller.signal})
   if(turn!==epoch)return false
   if(attempt.kind==='publication'){const result=readExportPublication(raw,c,job);if(result.status!=='PUBLISHED'||result.file_sha256!==attempt.body.file_sha256||result.version!==Number(attempt.body.expected_version)+2||p&&(result.size_bytes!==p.size_bytes||result.row_count!==p.row_count))throw invalid();state.current=result}
   else{if(!p)throw invalid();state.grant=readExportGrant(raw,p)}
   state.attempt=null;return true
  }catch(e){if(turn===epoch){state.error=error(e);if(writeFailureUncertain(previous,e)){state.attempt={...attempt,uncertain:true}}else state.attempt=null}return false}
  finally{if(turn===epoch){state.busy=false;emit()}}
 }
 async function start(kind:Kind){
  if(!active()||state.busy||state.attempt||!state.current)return false
  const p=state.current
  if(!p.publication_enabled||kind==='publication'&&(!p.prepared||p.status!=='NOT_PUBLISHED')||kind==='content-grants'&&(!p.download_grant_available||p.status!=='PUBLISHED'))return false
  const body:Record<string,unknown>=kind==='publication'?{expected_version:p.version,file_sha256:p.file_sha256,action:'PUBLISH_PREPARED_FILE'}:{}
  return perform({kind,key:createRequestId(),body,uncertain:false})
 }
 return{reset,read,publish:()=>start('publication'),grant:()=>start('content-grants'),retry:()=>state.attempt?perform(structuredClone(state.attempt)):Promise.resolve(false),unresolved:()=>state.busy||state.attempt!==null,dispose:()=>reset(null,null)}
}
