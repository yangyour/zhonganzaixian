import {describe,it,expect,vi} from 'vitest'
import {ApiError,type RequestOptions} from '../src/api/client'
import {createMonitoringRecovery,emptyRecoveryState,readRecoveryView,readRecoveryReceipt,type RecoveryScope,type RecoveryState} from '../src/utils/monitoringRecovery'

const tenant='10000000-0000-4000-8000-000000000001',actor='10000000-0000-4000-8000-000000000002',event='10000000-0000-4000-8000-000000000003',asset='10000000-0000-4000-8000-000000000004',release='10000000-0000-4000-8000-000000000005',proof='10000000-0000-4000-8000-000000000006'
const scope:RecoveryScope={tenantId:tenant,actorId:actor,permissionVersion:7,eventId:event,canWrite:true}
const rule={metric:'icmp',comparison:'GE',threshold:'1',unit:'',hold_seconds:'60',max_gap_seconds:'120',minimum_samples:2}
const view=()=>({tenant_id:tenant,actor_id:actor,permission_version:7,event_id:event,asset_id:asset,event_version:2,monitor_state:'PROBLEM',handling_state:'NEW',recovery_evidence_status:'NOT_CONFIRMED',configuration_status:'ACTIVE',missing_reason:null,policy_release_id:release,policy_release_version:1,rule:{...rule},observed_at:'2026-10-23T10:02:00Z',source:'postgresql',source_binding_available:true})
const receipt=()=>({tenant_id:tenant,actor_id:actor,permission_version:7,event_id:event,event_version:3,proof_id:proof,status:'RECOVERY_CONFIRMED',applied:true,candidate_recovered_at:'2026-10-23T10:00:59.123456789Z',recovered_at:'2026-10-23T10:00:59.123Z',confirmed_at:'2026-10-23T10:02:00Z',policy_release_id:release,policy_release_version:1,observed_at:'2026-10-23T10:02:00Z',latest_sample_at:'2026-10-23T10:01:59.123456789Z',span_started_at:'2026-10-23T10:00:29.123456789Z',span_seconds:'90.000000000',matching_samples:3,source_binding_sha256:'a'.repeat(64),source:'ZABBIX_SIMPLE_CHECK',production_approved:false})
const attempt=()=>({key:'test-key',hash:'b'.repeat(64),body:{expected_version:2,policy_release_id:release,policy_release_version:1},rule:{...rule},uncertain:false})
function harness(response:(path:string,options?:RequestOptions)=>Promise<unknown>){
 let state:RecoveryState=emptyRecoveryState()
 const request=vi.fn(response)
 const workspace=createMonitoringRecovery({request:<T>(path:string,options?:RequestOptions)=>request(path,options) as Promise<T>},next=>{state=next})
 workspace.reset(scope)
 return {workspace,request,get state(){return state}}
}
const deferred=<T>()=>{let resolve!:(value:T)=>void;let reject!:(reason:unknown)=>void;const promise=new Promise<T>((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}

describe('server sourced recovery observation',()=>{
 it('accepts exact source nanoseconds without converting the recovery decision',()=>{
  expect(readRecoveryView(view(),scope).rule).toEqual(rule)
  expect(readRecoveryReceipt(receipt(),scope,attempt()).latest_sample_at).toBe('2026-10-23T10:01:59.123456789Z')
 })
 it('retains source t2 after the observation window and rejects substituting request time',()=>{
  const result=readRecoveryReceipt(receipt(),scope,attempt())
  expect(result.recovered_at).toBe('2026-10-23T10:00:59.123Z')
  expect(result.candidate_recovered_at).toBe('2026-10-23T10:00:59.123456789Z')
  expect(result.confirmed_at).toBe('2026-10-23T10:02:00Z')
  expect(()=>readRecoveryReceipt({...receipt(),recovered_at:receipt().confirmed_at},scope,attempt())).toThrow()
  expect(()=>readRecoveryReceipt({...receipt(),candidate_recovered_at:'2026-10-23T10:01:30Z',recovered_at:'2026-10-23T10:01:30Z'},scope,attempt())).toThrow()
 })
 it.each([
  {tenant_id:asset},{actor_id:asset},{permission_version:8},{event_id:asset},{event_version:4},{policy_release_version:2},{production_approved:true},
  {applied:false},{confirmed_at:null},{matching_samples:1},{span_seconds:'59.999999999'},{latest_sample_at:'2026-10-23T10:03:00Z'},
 ])('rejects mismatched or incomplete confirmed evidence %j',patch=>{
  expect(()=>readRecoveryReceipt({...receipt(),...patch},scope,attempt())).toThrow()
 })
 it('preserves unconfigured source as a disabled action, never manufactures a policy',async()=>{
  const h=harness(async()=>({...view(),configuration_status:'CONFIG_PENDING',missing_reason:'CONFIG_PENDING',policy_release_id:null,policy_release_version:null,rule:null,source_binding_available:false}))
  expect(await h.workspace.load()).toBe(true)
  expect(await h.workspace.submit()).toBe(false)
  expect(h.request).toHaveBeenCalledTimes(1)
 })
 it('posts only the selected versions and a stable idempotency key',async()=>{
  const h=harness(async(_path,options)=>options?.method==='POST'?receipt():view())
  await h.workspace.load();expect(await h.workspace.submit()).toBe(true)
  const post=h.request.mock.calls.find(([,options])=>options?.method==='POST')!
  expect(post[1]?.body).toEqual({expected_version:2,policy_release_id:release,policy_release_version:1})
  expect(post[1]?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  expect(h.state.receipt?.applied).toBe(true);expect(h.state.attempt).toBeNull()
  expect(await h.workspace.submit()).toBe(false)
 })
 it('recovers an unknown write by GET without dispatching another POST',async()=>{
  const h=harness(async(path,options)=>{if(options?.method==='POST')throw new ApiError(504,'REQUEST_TIMEOUT','unknown','trace');return path.includes('/receipts/')?receipt():view()})
  await h.workspace.load();expect(await h.workspace.submit()).toBe(false)
  expect(h.workspace.unresolved()).toBe(true);const original=h.state.attempt
  expect(await h.workspace.recover()).toBe(true)
  expect(h.request.mock.calls.filter(([,o])=>o?.method==='POST')).toHaveLength(1)
  expect(h.request.mock.calls.at(-1)?.[0]).toContain(`/receipts/${original?.hash}`)
  expect(h.state.receipt?.proof_id).toBe(proof);expect(h.workspace.unresolved()).toBe(false)
 })
 it('404 receipt keeps the original request and explicit retry uses the exact original body',async()=>{
  let posts=0
  const h=harness(async(path,options)=>{if(options?.method==='POST'){posts++;if(posts===1)throw new Error('lost response');return receipt()}if(path.includes('/receipts/'))throw new ApiError(404,'RESOURCE_NOT_FOUND','unknown','trace');return view()})
  await h.workspace.load();await h.workspace.submit();const original=structuredClone(h.state.attempt)
  expect(await h.workspace.recover()).toBe(false);expect(h.state.attempt?.key).toBe(original?.key)
  h.state.attempt!.body.expected_version=99;h.state.attempt!.rule.hold_seconds='0'
  expect(await h.workspace.retry()).toBe(true)
  const calls=h.request.mock.calls.filter(([,o])=>o?.method==='POST')
  expect(calls[1]?.[1]?.body).toEqual(calls[0]?.[1]?.body)
  expect(calls[1]?.[1]?.idempotencyKey).toBe(calls[0]?.[1]?.idempotencyKey)
 })
 it('cancelled GET preserves the unknown request and ignores a late receipt',async()=>{
  const later=deferred<unknown>()
  const h=harness(async(path,options)=>{if(options?.method==='POST')throw new Error('lost');return path.includes('/receipts/')?later.promise:view()})
  await h.workspace.load();await h.workspace.submit();const key=h.state.attempt?.key
  const pending=h.workspace.recover();h.workspace.cancelRead();later.resolve(receipt())
  expect(await pending).toBe(false);expect(h.state.attempt?.key).toBe(key);expect(h.state.receipt).toBeNull();expect(h.state.cancelled).toBe(true)
 })
 it('context invalidation clears private pending state and rejects late write replies',async()=>{
  const later=deferred<unknown>()
  const h=harness(async(_path,options)=>options?.method==='POST'?later.promise:view())
  await h.workspace.load();const pending=h.workspace.submit();await vi.waitFor(()=>expect(h.state.busy).toBe('write'))
  h.workspace.reset(null);later.resolve(receipt());expect(await pending).toBe(false)
  expect(h.state).toEqual(emptyRecoveryState())
 })
 it('a denied retry after an uncertain response never discards the original request',async()=>{
  let posts=0
  const h=harness(async(_path,options)=>{if(options?.method==='POST'){posts++;throw posts===1?new Error('lost'):new ApiError(403,'ACTION_FORBIDDEN','denied','trace')}return view()})
  await h.workspace.load();await h.workspace.submit();const key=h.state.attempt?.key;await h.workspace.retry()
  expect(h.state.attempt?.key).toBe(key);expect(h.workspace.unresolved()).toBe(true)
 })
 it('read-only role cannot dispatch a write and invalid contexts cannot read',async()=>{
  const h=harness(async()=>view());h.workspace.reset({...scope,canWrite:false});await h.workspace.load()
  expect(await h.workspace.submit()).toBe(false)
  h.workspace.reset({...scope,eventId:'invalid'});expect(await h.workspace.load()).toBe(false)
  expect(h.request).toHaveBeenCalledTimes(1)
 })
})
