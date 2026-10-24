import {ApiError,StaleRequestError} from '../api/client'
import {digestSha256} from './browserCrypto'
import {inspectionUuid} from './inspectionTemplates'
export const reportPdfMaxBytes=8*1024*1024
const invalid=()=>new ApiError(200,'INVALID_REPORT_PDF','PDF 的身份、摘要、长度或文件结构不符，未发起下载','—')
export function abortable<T>(promise:Promise<T>,signal:AbortSignal):Promise<T>{return new Promise((resolve,reject)=>{const abort=()=>{signal.removeEventListener('abort',abort);reject(new StaleRequestError())};signal.addEventListener('abort',abort,{once:true});promise.then(v=>{signal.removeEventListener('abort',abort);resolve(v)},e=>{signal.removeEventListener('abort',abort);reject(e)});if(signal.aborted)abort()})}
export async function bounded(response:Response,limit:number,signal:AbortSignal):Promise<Uint8Array<ArrayBuffer>>{
 if(signal.aborted){void response.body?.cancel().catch(()=>{});throw new StaleRequestError()}
 if(!response.body)throw invalid();const reader=response.body.getReader(),chunks:Uint8Array[]=[];let length=0
 const cancel=()=>{void reader.cancel().catch(()=>{})};signal.addEventListener('abort',cancel,{once:true})
 try{for(;;){const part=await abortable(reader.read(),signal);if(part.done)break;length+=part.value.byteLength;if(length>limit){cancel();throw invalid()}chunks.push(part.value)}if(signal.aborted)throw new StaleRequestError()}
 finally{signal.removeEventListener('abort',cancel);reader.releaseLock()}
 const bytes=new Uint8Array(length);let offset=0;for(const c of chunks){bytes.set(c,offset);offset+=c.length}return bytes
}
export async function fetchReportPdf(input:{id:string;tenantId:string;documentHash:string;signal:AbortSignal;active:()=>boolean;transport?:typeof fetch;onUnauthorized?:()=>void;timeoutMs?:number}){
 if(!inspectionUuid(input.id)||!inspectionUuid(input.tenantId)||!/^[0-9a-f]{64}$/.test(input.documentHash))throw invalid()
 const controller=new AbortController(),cancel=()=>controller.abort();input.signal.addEventListener('abort',cancel,{once:true});if(input.signal.aborted)cancel()
 const timeout=setTimeout(()=>controller.abort(),input.timeoutMs??30000),signal=controller.signal,active=()=>!signal.aborted&&input.active()
 try{
  if(!active())throw new StaleRequestError()
  const response=await abortable<Response>((input.transport??fetch)(`/api/v1/inspection-quarterly-reports/${input.id}/preview.pdf`,{method:'GET',credentials:'same-origin',redirect:'error',headers:{Accept:'application/pdf','If-Match':`"${input.documentHash}"`},signal}).then(r=>{if(signal.aborted||!input.active())void r.body?.cancel().catch(()=>{});return r}),signal)
  if(!active()){void response.body?.cancel().catch(()=>{});throw new StaleRequestError()}
  if(response.status!==200){
   if(response.status===401)input.onUnauthorized?.()
   let code='REPORT_PDF_DOWNLOAD_FAILED',message='PDF 草稿未下载，请根据错误重新读取或使用 HTML 预览',trace=response.headers.get('X-Trace-Id')||'—'
   try{const bytes=await bounded(response,65536,signal);const data=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));if(typeof data?.error?.code==='string'&&data.error.code.length<=100)code=data.error.code;if(typeof data?.error?.message==='string'&&data.error.message.length<=500)message=data.error.message;if(typeof data?.trace_id==='string'&&data.trace_id.length<=128)trace=data.trace_id}catch{/* Do not trust an unbounded or non-JSON error body. */}
   throw new ApiError(response.status,code,message,trace)
  }
  const length=response.headers.get('Content-Length'),sha=response.headers.get('X-MOP-PDF-SHA256'),pages=response.headers.get('X-MOP-PDF-Pages'),filename=`quarterly-draft-${input.id}.pdf`
  if(response.headers.get('Content-Type')?.split(';')[0]?.trim()!=='application/pdf'||response.headers.get('Content-Disposition')!==`attachment; filename="${filename}"`||response.headers.get('X-MOP-Report-ID')!==input.id||response.headers.get('X-MOP-Tenant-ID')!==input.tenantId||response.headers.get('X-MOP-Document-SHA256')!==input.documentHash||response.headers.get('X-MOP-Preview-Status')!=='DRAFT'||!length||! /^[1-9][0-9]{0,7}$/.test(length)||Number(length)>reportPdfMaxBytes||!sha||! /^[0-9a-f]{64}$/.test(sha)||!pages||! /^[1-9][0-9]{0,2}$/.test(pages)||Number(pages)>200||!response.headers.get('Cache-Control')?.split(',').some(v=>v.trim()==='no-store')){void response.body?.cancel().catch(()=>{});throw invalid()}
  const bytes=await bounded(response,reportPdfMaxBytes,signal);if(!active())throw new StaleRequestError()
  if(bytes.length!==Number(length)||new TextDecoder().decode(bytes.subarray(0,5))!=='%PDF-'||!new TextDecoder().decode(bytes.subarray(-32)).trimEnd().endsWith('%%EOF'))throw invalid()
  const actual=Array.from(new Uint8Array(await abortable(digestSha256(bytes),signal)),b=>b.toString(16).padStart(2,'0')).join('');if(!active())throw new StaleRequestError();if(actual!==sha)throw invalid()
  return {blob:new Blob([bytes],{type:'application/pdf'}),filename,sha256:actual,pages:Number(pages)}
 }catch(error){if(signal.aborted&&!input.signal.aborted&&input.active())throw new ApiError(408,'REPORT_PDF_TIMEOUT','PDF 草稿读取超时，未发起下载；可手动重试或使用 HTML 预览','—');if(signal.aborted||!input.active())throw new StaleRequestError();if(error instanceof ApiError||error instanceof StaleRequestError)throw error;throw new ApiError(0,'REPORT_PDF_READ_FAILED','PDF 草稿读取或校验失败，未发起下载；请检查连接后重试','—')}
 finally{clearTimeout(timeout);input.signal.removeEventListener('abort',cancel)}
}
export function downloadReportPdf(file:{blob:Blob;filename:string}){
 if(file.blob.type!=='application/pdf'||!/^quarterly-draft-[0-9a-f-]{36}\.pdf$/.test(file.filename)||file.blob.size<100||file.blob.size>reportPdfMaxBytes)throw invalid()
 const url=URL.createObjectURL(file.blob),anchor=document.createElement('a');try{anchor.href=url;anchor.download=file.filename;anchor.hidden=true;document.body.append(anchor);anchor.click()}finally{anchor.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}
}
