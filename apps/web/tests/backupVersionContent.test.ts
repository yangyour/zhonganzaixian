import { expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from '../src/api/client'
import { createBackupContentReader, readBackupDifference, type BackupContentState } from '../src/utils/backupVersionContent'
import { scope, id } from '../qa/backup-inventory.data'

const before = id(10), after = id(11)
const difference = () => ({ tenant_id:scope.tenantId, asset_id:scope.assetId, before_version_id:before, after_version_id:after,
  status:'CHANGED', changed:true, before_lines:1, after_lines:1, hunks:[{before_start:0,before_count:1,after_start:0,after_count:1}],
  redaction:'ALL_TEXT_WITHHELD', noise_policy:'NONE_APPLIED_UNCONFIRMED', change_approved:false, automatic_ticket_allowed:false, actual_recovery_verified:false })
const deferred = <T>() => { let resolve!:(value:T)=>void; const promise=new Promise<T>(r=>{resolve=r}); return {promise,resolve} }
function setup() {
  const states:BackupContentState[] = [], delivered = vi.fn()
  const client = {request:vi.fn().mockResolvedValue(difference()),requestFile:vi.fn().mockResolvedValue(new Blob([new Uint8Array(247)],{type:'application/octet-stream'}))}
  const reader = createBackupContentReader(client,value=>states.push(value),delivered)
  reader.reset(scope)
  return {reader,client,states,delivered,last:()=>states.at(-1)!}
}
it('keeps default state empty and only explicitly downloads a selected version',async()=>{
  const x=setup();expect(x.client.requestFile).not.toHaveBeenCalled()
  expect(await x.reader.execute({kind:'download',version:before,format:'envelope'})).toBe(true)
  expect(x.client.requestFile.mock.calls[0]![0]).toBe(`/assets/${scope.assetId}/backup-versions/${before}/content?format=envelope`)
  expect(x.delivered).toHaveBeenCalledWith(expect.any(Blob),`backup-${before}.envelope`)
  expect(x.last().downloaded).toBe(true)
})
it('compares exact selected versions and accepts no plaintext or approval fields',async()=>{
  const x=setup();expect(await x.reader.execute({kind:'diff',before,after})).toBe(true)
  expect(x.client.request.mock.calls[0]![0]).toBe(`/assets/${scope.assetId}/backup-versions/diff?before_version_id=${before}&after_version_id=${after}`)
  expect(x.client.request.mock.calls[0]![1].body).toBeUndefined()
  expect(x.last().difference?.changed).toBe(true)
  expect(()=>readBackupDifference({...difference(),content:'SYNTHETIC secret'},scope,before,after)).toThrow()
  expect(()=>readBackupDifference({...difference(),automatic_ticket_allowed:true},scope,before,after)).toThrow()
})
it.each(['tenant_id','asset_id','before_version_id','after_version_id'])('rejects foreign response %s',field=>{
  expect(()=>readBackupDifference({...difference(),[field]:id(99)},scope,before,after)).toThrow()
})
it.each(['LIMIT_EXCEEDED','CANCELLED','UNAVAILABLE'])('preserves unknown %s rather than normalizing to identical',status=>{
  const value=readBackupDifference({...difference(),status,changed:null,before_lines:null,after_lines:null,hunks:[]},scope,before,after)
  expect(value.changed).toBeNull()
  expect(()=>readBackupDifference({...value,changed:false},scope,before,after)).toThrow()
})
it('cancels pending download and prevents an ignored-abort response from starting a browser download',async()=>{
  const x=setup(),late=deferred<Blob>();x.client.requestFile.mockReturnValueOnce(late.promise)
  const pending=x.reader.execute({kind:'download',version:before,format:'plaintext'})
  expect(x.reader.cancel()).toBe(true);expect(x.client.requestFile.mock.calls[0]![1].signal.aborted).toBe(true)
  late.resolve(new Blob(['SYNTHETIC'],{type:'application/octet-stream'}));expect(await pending).toBe(false)
  expect(x.delivered).not.toHaveBeenCalled();expect(x.last().cancelled).toBe(true)
})
it.each(['reset','dispose'])('suppresses late diff after %s',async action=>{
  const x=setup(),late=deferred<unknown>();x.client.request.mockReturnValueOnce(late.promise)
  const pending=x.reader.execute({kind:'diff',before,after})
  if(action==='reset')x.reader.reset({...scope,tenantId:id(99)});else x.reader.dispose()
  late.resolve(difference());expect(await pending).toBe(false);expect(x.last().difference).toBeNull()
})
it('retry retains original operation and clears stale successful results on failure',async()=>{
  const x=setup();await x.reader.execute({kind:'diff',before,after})
  x.client.request.mockRejectedValueOnce(new ApiError(503,'BACKUP_CONTENT_UNAVAILABLE','不可用','trace'))
  expect(await x.reader.execute({kind:'diff',before,after})).toBe(false);expect(x.last().difference).toBeNull()
  await x.reader.retry();expect(x.client.request.mock.calls[2]![0]).toEqual(x.client.request.mock.calls[1]![0])
})
it('rejects unexpected content types and truncated envelopes without delivering a file',async()=>{
  const x=setup()
  for(const blob of [new Blob(['secret'],{type:'text/html'}),new Blob(['x'],{type:'application/octet-stream'})]) {
    x.client.requestFile.mockResolvedValueOnce(blob);expect(await x.reader.execute({kind:'download',version:before,format:'envelope'})).toBe(false)
  }
  expect(x.delivered).not.toHaveBeenCalled();expect(x.last().error).toBeInstanceOf(ApiError)
})
it('does not emit old downloaded state when delivery changes context',async()=>{
  let last:BackupContentState|undefined
  const client={request:vi.fn(),requestFile:vi.fn().mockResolvedValue(new Blob(['SYNTHETIC'],{type:'application/octet-stream'}))}
  const reader=createBackupContentReader(client,value=>{last=value},()=>reader.reset(null))
  reader.reset(scope);expect(await reader.execute({kind:'download',version:before,format:'plaintext'})).toBe(false)
  expect(last?.downloaded).toBe(false);expect(last?.request).toBeNull()
})
it('rejects malformed scope and request before transport',async()=>{
  const x=setup();x.reader.reset({...scope,assetId:'../other'})
  expect(await x.reader.execute({kind:'download',version:before,format:'envelope'})).toBe(false)
  expect(x.client.requestFile).not.toHaveBeenCalled()
})
it('file transport uses octet-stream accept and keeps session invalidation protections',async()=>{
  const response=deferred<Response>(),fetcher=vi.fn().mockReturnValue(response.promise)
  const client=new ApiClient(fetcher)
  const pending=client.requestFile('/assets/test/file');client.invalidateContext()
  await expect(pending).rejects.toMatchObject({name:'AbortError'})
  expect(fetcher.mock.calls[0]![1]).toMatchObject({headers:{Accept:'application/octet-stream'},cache:'no-store',redirect:'error',credentials:'same-origin'})
  response.resolve(new Response('SYNTHETIC',{headers:{'Content-Type':'application/octet-stream'}}))
})
