import {it,expect,vi} from 'vitest'
import {fetchReportPdf} from '../src/utils/inspectionReportPdf'
import capture from '../../../fixtures/quarterly-pdf-http-synthetic-20261021.json'
it('actual protected HTTP PDF validates against its current tenant and frozen document',async()=>{
 const raw=new Uint8Array(Buffer.from(capture.pdf_base64,'base64'));const transport=vi.fn(async()=>new Response(raw,{status:200,headers:capture.headers}));const file=await fetchReportPdf({id:capture.detail.id,tenantId:capture.detail.tenant_id,documentHash:capture.detail.document_sha256,signal:new AbortController().signal,active:()=>true,transport});expect(file.blob.size).toBe(raw.length);expect(file.pages).toBe(Number(new Headers(capture.headers).get('X-MOP-PDF-Pages')))
})
it('actual response cannot be substituted for another selected snapshot',async()=>{
 const transport=vi.fn(async()=>new Response(new Uint8Array(Buffer.from(capture.pdf_base64,'base64')),{status:200,headers:capture.headers}));await expect(fetchReportPdf({id:capture.detail.id,tenantId:capture.detail.tenant_id,documentHash:'0'.repeat(64),signal:new AbortController().signal,active:()=>true,transport})).rejects.toMatchObject({code:'INVALID_REPORT_PDF'})
})
