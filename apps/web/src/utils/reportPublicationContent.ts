import { ApiError, StaleRequestError } from '../api/client'
import { digestSha256 } from './browserCrypto'
import { abortable, bounded } from './inspectionReportPdf'
export async function fetchPublicationContent(input:{id:string;tenant:string;documentHash:string;contentHash:string;phase:string;format:'html'|'pdf';signal:AbortSignal;active:()=>boolean;transport?:typeof fetch}){
 const invalid=()=>new ApiError(200,'INVALID_REPORT_CONTENT','报告文件身份、摘要或状态不一致，未下载','—')
 if(!/^[a-f0-9-]{36}$/.test(input.id)||!['html','pdf'].includes(input.format)||![input.documentHash,input.contentHash].every(s=>/^[a-f0-9]{64}$/.test(s)))throw invalid()
 const controller=new AbortController(),cancel=()=>controller.abort();input.signal.addEventListener('abort',cancel,{once:true});if(input.signal.aborted)cancel();const timeout=setTimeout(cancel,30000)
 const active=()=>!controller.signal.aborted&&input.active()
 try{
  if(!active())throw new StaleRequestError()
  const mime=input.format==='pdf'?'application/pdf':'text/html',filename=`report-${input.id}.${input.format}`
  const response=await abortable((input.transport??fetch)(`/api/v1/report-publications/${input.id}/content/${input.format}`,{credentials:'same-origin',redirect:'error',headers:{Accept:mime,'If-Match':`"${input.documentHash}"`},signal:controller.signal}),controller.signal)
  if(!active()){void response.body?.cancel();throw new StaleRequestError()}
  if(response.status!==200){void response.body?.cancel();throw new ApiError(response.status,'REPORT_CONTENT_UNAVAILABLE','当前报告文件不可读取，请刷新权限和报告状态','—')}
  const length=Number(response.headers.get('Content-Length'))
  if(response.headers.get('Content-Type')?.split(';')[0]!==mime||response.headers.get('X-MOP-Report-ID')!==input.id||response.headers.get('X-MOP-Tenant-ID')!==input.tenant
   ||response.headers.get('X-MOP-Document-SHA256')!==input.documentHash||response.headers.get('X-MOP-Content-SHA256')!==input.contentHash||response.headers.get('X-MOP-Report-Phase')!==input.phase
   ||response.headers.get('Content-Disposition')!==`attachment; filename="${filename}"`||!Number.isSafeInteger(length)||length<1||length>8*1024*1024||response.headers.get('Cache-Control')!=='no-store'){
   void response.body?.cancel();throw invalid()
  }
  const bytes=await bounded(response,8*1024*1024,controller.signal),hash=Array.from(new Uint8Array(await abortable(digestSha256(bytes),controller.signal)),b=>b.toString(16).padStart(2,'0')).join('')
  if(!active())throw new StaleRequestError()
  if(bytes.length!==length||hash!==input.contentHash||input.format==='pdf'&&new TextDecoder().decode(bytes.subarray(0,5))!=='%PDF-')throw invalid()
  return{blob:new Blob([bytes],{type:mime}),filename}
 }finally{clearTimeout(timeout);input.signal.removeEventListener('abort',cancel)}
}
