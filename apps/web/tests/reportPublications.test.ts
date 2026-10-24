import { it,expect,vi } from 'vitest'
import { ApiError } from '../src/api/client'
import { createReportCommands,readPublication } from '../src/utils/reportPublications'
const id='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002',actor='79000000-0000-4000-8000-000000000003'
const scope=()=>({key:tenant+actor,tenant,actor})
const value=(phase='DRAFT',version=1)=>({customer_acceptance_confirmed:false,external_dispatch_enabled:false,state:{version,phase,document:{id,tenant_id:tenant,document_sha256:'a'.repeat(64),captured_at:'2026-10-23T00:00:00Z',incomplete_reasons:[]},recipients:[],html:null,pdf:null}})
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
