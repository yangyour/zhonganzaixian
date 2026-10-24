import { it,expect,vi } from 'vitest'
import { ApiError } from '../src/api/client'
import { createReportCommands,readPublication } from '../src/utils/reportPublications'
const id='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002',actor='79000000-0000-4000-8000-000000000003'
const scope=()=>({key:tenant+actor,tenant,actor})
const value=(phase='DRAFT',version=1)=>({customer_acceptance_confirmed:false,external_dispatch_enabled:false,state:{version,phase,reason:null as string|null,document:{id,tenant_id:tenant,family_id:id,parent_id:null as string|null,revision:1,document_sha256:'a'.repeat(64),captured_at:'2026-10-23T00:00:00Z',incomplete_reasons:[]},recipients:[],html:null,pdf:null}})
function storage(){const m=new Map<string,string>();return{getItem:(k:string)=>m.get(k)??null,setItem:(k:string,v:string)=>{m.set(k,v)},removeItem:(k:string)=>{m.delete(k)}}}
it('rejects foreign tenant and published reports without both artifacts',()=>{expect(()=>readPublication(value(),actor)).toThrow();expect(()=>readPublication(value('PUBLISHED'),tenant)).toThrow()})
it('persists original body/key before sending and restores after reload',async()=>{
 const journal=storage(),request=vi.fn().mockRejectedValue(new ApiError(503,'UNAVAILABLE','unit','unit'))
 const first=createReportCommands(scope,journal,{request});await first.submit(`/report-publications/${id}/actions`,{expected_version:1,action:'RENDER',reason:null})
 const original=JSON.parse(JSON.stringify(first.pending.value));first.dispose()
 const second=createReportCommands(scope,journal,{request});expect(second.pending.value).toEqual(original)
 request.mockResolvedValue(value('FAILED',3));expect(await second.retry()).toBe(true)
 expect(request.mock.calls[1][1].body).toEqual(original.body);expect(request.mock.calls[1][1].idempotencyKey).toBe(original.key);expect(second.pending.value).toBeNull()
})
it('wrong-action acknowledgment cannot erase an uncertain request',async()=>{
 const request=vi.fn().mockResolvedValue(value('DRAFT',3)),command=createReportCommands(scope,storage(),{request})
 await command.submit(`/report-publications/${id}/actions`,{expected_version:1,action:'RENDER',reason:null});expect(command.pending.value).not.toBeNull()
 request.mockRejectedValueOnce(new ApiError(403,'FORBIDDEN','unit','unit'));await command.retry();expect(command.pending.value).not.toBeNull()
})
it('storage failure prevents unretained writes',async()=>{
 const request=vi.fn(),journal=storage();journal.setItem=()=>{throw new Error('denied')}
 const command=createReportCommands(scope,journal,{request});await command.submit(`/report-publications/${id}/read`,{expected_version:5});expect(request).not.toHaveBeenCalled()
})
it('read acknowledgment must name this recipient',async()=>{
 const request=vi.fn().mockResolvedValue({tenant_id:tenant,document_id:id,customer_id:id,document_sha256:'a'.repeat(64),first_read_at:'2026-10-23T00:00:00Z'})
 const command=createReportCommands(scope,storage(),{request});await command.submit(`/report-publications/${id}/read`,{expected_version:5});expect(command.pending.value).not.toBeNull()
})
it('known first-write conflict permits a new explicit command',async()=>{
 const request=vi.fn().mockRejectedValue(new ApiError(409,'VERSION_CONFLICT','unit','unit')),command=createReportCommands(scope,storage(),{request})
 await command.submit(`/report-publications/${id}/actions`,{expected_version:1,action:'RENDER',reason:null});expect(command.pending.value).toBeNull()
})
it('changed account scope never restores another pending command',async()=>{
 const journal=storage(),request=vi.fn().mockRejectedValue(new Error('network')),first=createReportCommands(scope,journal,{request})
 await first.submit(`/report-publications/${id}/read`,{expected_version:5});expect(createReportCommands(()=>({...scope(),key:'different'}),journal,{request}).pending.value).toBeNull()
})

const child='79000000-0000-4000-8000-000000000004'
const revisionBody={expected_version:6,source_id:actor,expected_source_sha256:'b'.repeat(64),reason:'修正来源'}
function revised(){
 const result={...value(),source_id:actor,source_document_sha256:'b'.repeat(64)}
 result.state.document={...result.state.document,id:child,parent_id:id,revision:2}
 result.state.reason=revisionBody.reason
 return result
}
it('recovers a revision with the original source, reason and key, and exposes the confirmed child',async()=>{
 const journal=storage(),request=vi.fn().mockRejectedValue(new ApiError(504,'TIMEOUT','unit','unit'))
 const first=createReportCommands(scope,journal,{request})
 await first.submit(`/report-publications/${id}/revisions`,revisionBody)
 const pending=structuredClone(JSON.parse(JSON.stringify(first.pending.value)))
 first.dispose()
 const second=createReportCommands(scope,journal,{request})
 expect(second.pending.value).toEqual(pending)
 request.mockResolvedValue(revised())
 expect(await second.retry()).toBe(true)
 expect(request.mock.calls[1][1].body).toEqual(revisionBody)
 expect(request.mock.calls[1][1].idempotencyKey).toBe(pending.key)
 expect(second.confirmed.value?.state.document.id).toBe(child)
 expect(second.pending.value).toBeNull()
})
it.each(['parent','source','phase','reason','identity'])('rejects a mismatched %s in a revision acknowledgment',async(field)=>{
 const result=revised()
 if(field==='parent')result.state.document.parent_id=actor
 if(field==='source')result.source_document_sha256='c'.repeat(64)
 if(field==='phase')result.state.phase='FAILED'
 if(field==='reason')result.state.reason='different'
 if(field==='identity')result.state.document.id=id
 const command=createReportCommands(scope,storage(),{request:vi.fn().mockResolvedValue(result)})
 expect(await command.submit(`/report-publications/${id}/revisions`,revisionBody)).toBe(false)
 expect(command.pending.value).not.toBeNull()
 expect(command.confirmed.value).toBeNull()
})
it('refuses invalid revision input before storing or sending',async()=>{
 const request=vi.fn(),command=createReportCommands(scope,storage(),{request})
 expect(await command.submit(`/report-publications/${id}/revisions`,{...revisionBody,reason:' '})).toBe(false)
 expect(await command.submit(`/report-publications/${id}/revisions`,{...revisionBody,revision:2})).toBe(false)
 expect(request).not.toHaveBeenCalled()
 expect(command.pending.value).toBeNull()
})
it('validates revision lineage instead of treating a child as a new root',()=>{
 const raw=revised()
 raw.state.document.parent_id=null
 expect(()=>readPublication(raw,tenant)).toThrow()
})
