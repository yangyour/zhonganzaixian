import {describe,it,expect,vi} from 'vitest'
import {ApiError} from '../src/api/client'
import {createControlCommands,readControlEntry,readControlPage} from '../src/utils/notificationControl'

const command='11111111-1111-4111-8111-111111111111',id='22222222-2222-4222-8222-222222222222'
const receipt=(overrides={})=>({kind:'POLICY',id,version:0,command_id:command,state:{published:false},recorded_at:'2026-10-23T12:00:00Z',...overrides})
const body={expected_version:-1,policy:{published:false,matrix:null}}
function storage(){const values=new Map<string,string>();return{values,getItem:(key:string)=>values.get(key)??null,setItem:(key:string,value:string)=>{values.set(key,value)},removeItem:(key:string)=>{values.delete(key)}}}
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(done=>resolve=done);return{promise,resolve}}

describe('notification control original command recovery',()=>{
 it('persists before submitting and clears only an exact immutable acknowledgment',async()=>{
  const local=storage(),request=vi.fn(async()=>{expect(local.values.size).toBe(1);return receipt()})
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  expect(await workspace.submit('/notification-routing/policy',body,'POLICY')).toBe(true)
  expect(workspace.state().pending).toBeNull();expect(workspace.state().receipt?.id).toBe(id);expect(local.values.size).toBe(0)
 })
 it('connection loss survives reload and blocks a replacement command',async()=>{
  const local=storage(),request=vi.fn().mockRejectedValue(new Error('connection lost'))
  const first=createControlCommands({request},local,()=>{},()=>command);first.reset('scope')
  expect(await first.submit('/notification-routing/policy',body,'POLICY')).toBe(false)
  first.dispose();const next=createControlCommands({request},local,()=>{},()=>crypto.randomUUID());next.reset('scope')
  expect(next.state().pending?.id).toBe(command)
  expect(await next.submit('/notification-routing/policy',body,'POLICY')).toBe(false);expect(request).toHaveBeenCalledTimes(1)
 })
 it('recovery queries the exact command rather than inferring success from a current object',async()=>{
  const local=storage(),request=vi.fn().mockRejectedValueOnce(new Error('lost')).mockResolvedValueOnce(receipt())
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  await workspace.submit('/notification-routing/policy',body,'POLICY');expect(await workspace.recover()).toBe(true)
  expect(request.mock.calls[1]![0]).toBe('/notification-routing/commands/'+command)
  expect(request.mock.calls[1]![1]?.method).toBeUndefined()
 })
 it('a missing recovery result does not erase an in-flight command',async()=>{
  const local=storage(),request=vi.fn().mockRejectedValueOnce(new Error('lost')).mockRejectedValueOnce(new ApiError(404,'NOT_FOUND','missing','t'))
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  await workspace.submit('/notification-routing/policy',body,'POLICY');await workspace.recover()
  expect(workspace.state().pending?.id).toBe(command);expect(local.values.size).toBe(1)
 })
 it('retries retain the body snapshot and request identity',async()=>{
  const local=storage(),request=vi.fn().mockRejectedValueOnce(new Error('lost')).mockResolvedValueOnce(receipt())
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  const input={expected_version:-1,policy:{published:false}};await workspace.submit('/notification-routing/policy',input,'POLICY');input.policy.published=true
  expect(await workspace.retry()).toBe(true)
  expect(request.mock.calls[1]![1]).toMatchObject({idempotencyKey:command,body:{expected_version:-1,policy:{published:false}}})
 })
 it('old-scope responses cannot fill a new tenant and old private commands are removed',async()=>{
  const local=storage(),pending=deferred<unknown>(),request=vi.fn(()=>pending.promise)
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('tenant-a')
  const result=workspace.submit('/notification-routing/policy',body,'POLICY');workspace.reset('tenant-b');pending.resolve(receipt())
  expect(await result).toBe(false);expect(workspace.state().receipt).toBeNull();expect(local.values.size).toBe(0)
 })
 it('stopping the wait never claims that the server command was cancelled',async()=>{
  const local=storage(),pending=deferred<unknown>(),request=vi.fn(()=>pending.promise)
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  const result=workspace.submit('/notification-routing/policy',body,'POLICY');workspace.cancelWait();pending.resolve(receipt())
  expect(await result).toBe(false);expect(workspace.state().pending?.id).toBe(command);expect(workspace.state().receipt).toBeNull()
 })
 it('unavailable storage prevents an unretained write',async()=>{
  const local=storage(),request=vi.fn();local.setItem=()=>{throw new Error('storage unavailable')}
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  expect(await workspace.submit('/notification-routing/policy',body,'POLICY')).toBe(false);expect(request).not.toHaveBeenCalled()
 })
 it('corrupt restored commands fail closed instead of being silently replaced',async()=>{
  const local=storage(),request=vi.fn();local.values.set('mop.notification-control.pending:scope','{"id":"broken"}')
  const workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
  expect(workspace.state().restorationBlocked).toBe(true);await workspace.submit('/notification-routing/policy',body,'POLICY');expect(request).not.toHaveBeenCalled()
 })
 it('another command or version cannot clear an unresolved request',async()=>{
  for(const wrong of [{command_id:id},{version:2},{kind:'ROUTE'}]){
   const local=storage(),request=vi.fn().mockResolvedValue(receipt(wrong)),workspace=createControlCommands({request},local,()=>{},()=>command);workspace.reset('scope')
   expect(await workspace.submit('/notification-routing/policy',body,'POLICY')).toBe(false);expect(workspace.state().pending?.id).toBe(command)
  }
 })
 it('definitive validation rejection on the first attempt releases the unsaved request',async()=>{
  const request=vi.fn().mockRejectedValue(new ApiError(400,'VALIDATION_ERROR','invalid','t')),workspace=createControlCommands({request},storage(),()=>{},()=>command);workspace.reset('scope')
  await workspace.submit('/notification-routing/policy',body,'POLICY');expect(workspace.state().pending).toBeNull()
 })
 it('rejects duplicate rows and malformed paging scope',()=>{
  expect(()=>readControlPage({items:[receipt(),receipt()],next_id:null,next_time:null,source:'postgresql'},'POLICY')).toThrow()
  expect(()=>readControlPage({items:[],next_id:id,next_time:null,source:'postgresql'},'ROUTE')).toThrow()
  expect(()=>readControlEntry(receipt({version:-1}))).toThrow()
 })
})
