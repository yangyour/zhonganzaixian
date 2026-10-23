import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {webcrypto} from 'node:crypto'
import {ApiError} from '../src/api/client'
import {createReportWorkspace} from '../src/utils/inspectionQuarterlyReportWorkspace'
import {readReportCapturePayload,readCaptureReceipt} from '../src/utils/inspectionReportCapture'
import {collection,payload,summary,report,page,scope,id} from './inspectionQuarterlyReport.fixture'

beforeEach(()=>vi.stubGlobal('crypto',webcrypto))
afterEach(()=>{vi.restoreAllMocks();vi.unstubAllGlobals()})
const capture=()=>{const p=payload();return {expected_version:0,label:p.label,year:p.year,quarter:p.quarter,configuration:p.configuration,
  sources:collection().sources.map(s=>({worksheet_id:s.worksheet_id,version:s.version,expected_source_sha256:s.source_snapshot_sha256}))}}
const receipt=()=>({...summary(),capture_mode:'ATOMIC_EXPLICIT_WORKSHEET_REVISIONS'})
const setup=()=>{const request=vi.fn().mockResolvedValue(receipt()),key=vi.fn(()=> 'capture-original-key'),workspace=createReportWorkspace({request},()=>{},key);workspace.reset(scope);return {request,key,workspace}}

it('validates exact capture fields, null configuration, duplicate and revision guards',()=>{
 expect(readReportCapturePayload(capture())).toEqual(capture())
 for(const patch of [{source_collection_id:id(50)},{report_published:true},{quarter:0},{year:2026.1},{expected_version:1}])expect(()=>readReportCapturePayload({...capture(),...patch})).toThrow()
 const p=capture();p.sources.push(p.sources[0]!);expect(()=>readReportCapturePayload(p)).toThrow()
})
it('binds capture receipt to explicit sources, period and configuration and strips transport marker',async()=>{
 expect(await readCaptureReceipt(receipt(),scope,readReportCapturePayload(capture()))).toEqual(summary())
 for(const patch of [{capture_mode:'OTHER'},{source_manifest_sha256:'f'.repeat(64)},{report_sha256:'f'.repeat(64)},{year:2025},{created_by:id(99)},{report_published:true}])await expect(readCaptureReceipt({...receipt(),...patch},scope,readReportCapturePayload(capture()))).rejects.toThrow()
})
it('sends one atomic request and requires a separately verified detail read',async()=>{
 const h=setup();expect(await h.workspace.submitCapture(capture())).toBe(true)
 expect(h.request.mock.calls[0]).toEqual(['/inspection-quarterly-reports/capture',expect.objectContaining({method:'POST',body:capture(),idempotencyKey:'capture-original-key'})])
 expect(h.workspace.state().attempt?.mode).toBe('capture');expect(h.workspace.state().selected).toBeNull()
 h.request.mockResolvedValueOnce(report());expect(await h.workspace.select(id(80))).toBe(true)
})
it('unknown capture result retains exact path/body/key across list reads and denied retries',async()=>{
 const h=setup();h.request.mockRejectedValueOnce(new TypeError('connection lost'));expect(await h.workspace.submitCapture(capture())).toBe(false)
 h.request.mockResolvedValueOnce(page());expect(await h.workspace.list()).toBe(true);expect(h.workspace.unresolved()).toBe(true)
 h.request.mockRejectedValueOnce(new ApiError(403,'REVOKED','denied','test'));expect(await h.workspace.retry()).toBe(false);expect(h.workspace.unresolved()).toBe(true)
 h.request.mockResolvedValueOnce(receipt());expect(await h.workspace.retry()).toBe(true)
 const posts=h.request.mock.calls.filter(([,options])=>options.method==='POST')
 expect(posts).toHaveLength(3);for(const [path,options] of posts){expect(path).toBe('/inspection-quarterly-reports/capture');expect(options.body).toEqual(capture());expect(options.idempotencyKey).toBe('capture-original-key')}
 expect(h.key).toHaveBeenCalledOnce()
})
it('first definitive rejection unlocks without changing the request silently',async()=>{
 const h=setup();h.request.mockRejectedValueOnce(new ApiError(409,'SOURCE_HASH_CONFLICT','changed','test'))
 expect(await h.workspace.submitCapture(capture())).toBe(false);expect(h.workspace.state().attempt?.state).toBe('rejected');expect(await h.workspace.retry()).toBe(false)
 expect(h.workspace.begin()).toBe(true)
})
it('bad capture metadata is uncertain rather than accepted',async()=>{
 const h=setup();h.request.mockResolvedValueOnce({...receipt(),source_manifest_sha256:'f'.repeat(64)})
 expect(await h.workspace.submitCapture(capture())).toBe(false);expect(h.workspace.unresolved()).toBe(true);expect(h.workspace.state().receipt).toBeNull()
})
it('scope changes during command key generation prevent capture dispatch',async()=>{
 const request=vi.fn();let workspace:ReturnType<typeof createReportWorkspace>
 workspace=createReportWorkspace({request},()=>{},()=>{workspace.reset(null);return 'old-key'})
 workspace.reset(scope);expect(await workspace.submitCapture(capture())).toBe(false);expect(request).not.toHaveBeenCalled()
})
it('scope revocation aborts a pending write and prevents late receipt acceptance',async()=>{
 const h=setup();let release!:(value:unknown)=>void;h.request.mockImplementation(()=>new Promise(resolve=>release=resolve))
 const pending=h.workspace.submitCapture(capture());const signal=h.request.mock.calls[0]![1].signal
 expect(await h.workspace.submitCapture(capture())).toBe(false);h.workspace.reset(null);expect(signal.aborted).toBe(true)
 release(receipt());expect(await pending).toBe(false);expect(h.workspace.state().receipt).toBeNull()
})
it('freezes source revisions before dispatch and rejects stale configuration fingerprints',async()=>{
 const h=setup(),p=capture(),original=structuredClone(p),pending=h.workspace.submitCapture(p)
 p.sources[0]!.version=50;p.configuration.revision='changed'
 expect(await pending).toBe(true);expect(h.request.mock.calls[0]![1].body).toEqual(original)
})
