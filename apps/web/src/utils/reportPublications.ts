import { ref } from 'vue'
import { api, ApiError } from '../api/client'
import { createRequestId } from './browserCrypto'
import { writeFailureUncertain } from './writeFailure'
export type Phase='DRAFT'|'GENERATING'|'REVIEW_REQUIRED'|'APPROVED'|'PUBLISHED'|'RETURNED'|'FAILED'
export interface Publication {
 label?:string;source_id?:string;source_document_sha256?:string
 customer_acceptance_confirmed:false;external_dispatch_enabled:false
 state:{version:number;phase:Phase;reason:string|null;updated_at:string;recipients:string[];
 document:{id:string;tenant_id:string;kind:string;document_sha256:string;captured_at:string;incomplete_reasons:string[];template_version:string|null;metric_version:string|null};
 html:{content_sha256:string}|null;pdf:{content_sha256:string}|null}
}
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(v)
const hash=(v:unknown)=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
const invalid=()=>new ApiError(200,'INVALID_REPORT_PUBLICATION','报告范围、状态或原请求回执未通过核对','—')
export function readPublication(raw:unknown,tenant:string,id?:string):Publication {
 if(!object(raw)||raw.customer_acceptance_confirmed!==false||raw.external_dispatch_enabled!==false||!object(raw.state))throw invalid()
 const s=raw.state,d=s.document
 if(!object(d)||!uuid(d.id)||d.tenant_id!==tenant||id!==undefined&&id!==d.id||!hash(d.document_sha256)||typeof d.captured_at!=='string'||!Number.isFinite(Date.parse(d.captured_at))
  ||!Number.isSafeInteger(s.version)||(s.version as number)<1||!['DRAFT','GENERATING','REVIEW_REQUIRED','APPROVED','PUBLISHED','RETURNED','FAILED'].includes(String(s.phase))
  ||!Array.isArray(d.incomplete_reasons)||d.incomplete_reasons.some(x=>typeof x!=='string')||!Array.isArray(s.recipients)||s.recipients.some(x=>!uuid(x)))throw invalid()
 for(const format of ['html','pdf'])if(s[format]!==null&&(!object(s[format])||!hash((s[format] as Record<string,unknown>).content_sha256)))throw invalid()
 if(s.pdf!==null&&s.html===null||['REVIEW_REQUIRED','APPROVED','PUBLISHED'].includes(String(s.phase))&&(s.html===null||s.pdf===null))throw invalid()
 return raw as unknown as Publication
}
export interface ReportCommand {key:string;path:string;body:Record<string,unknown>}
type StorageLike=Pick<Storage,'getItem'|'setItem'|'removeItem'>
export function createReportCommands(scope:()=>{key:string;tenant:string;actor:string},storage:StorageLike,client:Pick<typeof api,'request'>=api){
 const pending=ref<ReportCommand|null>(null),busy=ref(false),error=ref<unknown>(null),blocked=ref(false)
 let epoch=0,controller:AbortController|null=null,owner=scope().key
 const bucket=()=>`mop.report-publication.pending:${owner}`
 function valid(v:unknown):v is ReportCommand {
  if(!object(v)||!uuid(v.key)||!object(v.body)||typeof v.path!=='string')return false
  if(v.path==='/report-publications')return Object.keys(v.body).sort().join(',')==='expected_source_sha256,source_id'&&uuid(v.body.source_id)&&hash(v.body.expected_source_sha256)
  if(!/^\/report-publications\/[a-f0-9-]{36}\/(actions|read)$/.test(v.path)||!uuid(v.path.split('/')[2])||!Number.isSafeInteger(v.body.expected_version)||(v.body.expected_version as number)<1||(v.body.expected_version as number)>=9007199254740990)return false
  return v.path.endsWith('/read')?Object.keys(v.body).join(',')==='expected_version':Object.keys(v.body).sort().join(',')==='action,expected_version,reason'&&['RENDER','APPROVE','RETURN','PUBLISH','RESEND'].includes(String(v.body.action))&&(v.body.reason===null||typeof v.body.reason==='string'&&v.body.reason.length<=3000)
 }
 function dispose(){epoch++;controller?.abort();controller=null;busy.value=false;pending.value=null;error.value=null}
 function reset(){dispose();owner=scope().key;blocked.value=false;try{const raw=storage.getItem(bucket());if(raw){if(raw.length>16384)throw invalid();const value:unknown=JSON.parse(raw);if(!valid(value))throw invalid();pending.value=value}}catch(e){blocked.value=true;error.value=e}}
 function clear(){storage.removeItem(bucket());pending.value=null}
 async function perform(retry:boolean){
  if(!pending.value||busy.value||blocked.value||owner!==scope().key)return false
  const command=JSON.parse(JSON.stringify(pending.value)) as ReportCommand,turn=++epoch,tenant=scope().tenant,abort=new AbortController();controller=abort;busy.value=true;error.value=null
  try{
   const raw=await client.request<unknown>(command.path,{method:'POST',body:command.body,idempotencyKey:command.key,signal:abort.signal})
   if(turn!==epoch||abort.signal.aborted||owner!==scope().key)return false
   if(command.path.endsWith('/read')){const id=command.path.split('/')[2];if(!object(raw)||raw.tenant_id!==tenant||raw.document_id!==id||raw.customer_id!==scope().actor||!hash(raw.document_sha256)||typeof raw.first_read_at!=='string'||!Number.isFinite(Date.parse(raw.first_read_at)))throw invalid()}
   else {const id=command.path==='/report-publications'?undefined:command.path.split('/')[2];const result=readPublication(raw,tenant,id)
    if(id){const delta=command.body.action==='RENDER'?2:command.body.action==='RESEND'?0:1;if(result.state.version!==Number(command.body.expected_version)+delta)throw invalid();const phases:Record<string,string[]>={RENDER:['REVIEW_REQUIRED','FAILED'],APPROVE:['APPROVED'],RETURN:['RETURNED'],PUBLISH:['PUBLISHED'],RESEND:['PUBLISHED']};if(!phases[String(command.body.action)]?.includes(result.state.phase))throw invalid()}
    else if(result.state.version!==1||result.state.phase!=='DRAFT'||result.source_id!==command.body.source_id||result.source_document_sha256!==command.body.expected_source_sha256)throw invalid()
   }
   clear();return true
  }catch(e){if(turn===epoch){error.value=e;if(!writeFailureUncertain(retry,e)){try{clear()}catch(failure){blocked.value=true;error.value=failure}}}return false}
  finally{if(turn===epoch){busy.value=false;controller=null}}
 }
 async function submit(path:string,body:Record<string,unknown>){if(pending.value||busy.value||blocked.value||owner!==scope().key)return false
  try{const value={key:createRequestId(),path,body:structuredClone(body)};if(!valid(value))throw invalid();storage.setItem(bucket(),JSON.stringify(value));pending.value=value}catch(e){error.value=e;return false}return perform(false)}
 reset();return{pending,busy,error,blocked,reset,dispose,submit,retry:()=>perform(true),cancel:()=>controller?.abort()}
}
