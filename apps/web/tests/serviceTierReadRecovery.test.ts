import {describe,it,expect,vi} from 'vitest'
import {readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {ApiError} from '../src/api/client'
import {
 createServiceTierWorkspace,readServiceTierPage,readServiceTierSnapshot,readTicketSlaInspection,type ServiceTierContext
} from '../src/utils/serviceTiers'

const tenant='55000000-0000-4000-8000-000000000001',actor='55000000-0000-4000-8000-000000000011',changeId='55000000-0000-4000-8000-000000000099',ticket='55000000-0000-4000-8000-000000000077'
const padminScope:ServiceTierContext={tenantId:tenant,actorId:actor,permissionVersion:4,canWrite:true}
const mspScope:ServiceTierContext={...padminScope,canWrite:false}
const at='2026-10-18T08:00:00Z',observed='2026-10-18T10:00:05Z'

const snapshot=(patch:Record<string,unknown>={})=>({
 tenant_id:tenant,version:2,tier:'ADVANCED',availability_target:99.99,
 contract_device_count:120,billing_cycle:'MONTHLY',monthly_fee_fen:2500000,
 effective_mode:'IMMEDIATE',effective_from:at,updated_by:actor,updated_at:at,
 customer_approval_status:'UNVERIFIED',...patch
})
const changeRow=(patch:Record<string,unknown>={})=>({
 id:changeId,tenant_id:tenant,version:2,previous_version:1,
 from_tier:'STANDARD',to_tier:'ADVANCED',availability_target:99.99,
 contract_device_count:120,billing_cycle:'MONTHLY',monthly_fee_fen:2500000,
 effective_mode:'IMMEDIATE',effective_from:at,reason:'SYNTHETIC_TIER_UPGRADE',
 approved_by:actor,changed_at:at,customer_approval_status:'UNVERIFIED',...patch
})
const page=(items:unknown[]=[changeRow()],patch:Record<string,unknown>={})=>({
 tenant_id:tenant,current:snapshot(),items,has_more:false,next_before_version:null,
 observed_at:observed,customer_approval_status:'UNVERIFIED',in_flight_ticket_retroactive:false,
 source:'postgresql',...patch
})
const slaPayload=(patch:Record<string,unknown>={})=>({
 ticket_id:ticket,tenant_id:tenant,receipt_no:'MOP-20261018-0001',registration_created_at:at,
 workflow_version:3,state:'RESOLVED',priority:'P1',frozen_service_tier:'STANDARD',current_tenant_service_tier:'ADVANCED',
 tier_changed_since_open:true,
 response_clock:{
  name:'RESPONSE',due_at:'2026-10-18T08:10:00Z',completed_at:'2026-10-18T08:04:00Z',
  paused:false,breached:false,status:'MET',missing_reason:null,
  thresholds:{p50_at:'2026-10-18T08:05:00Z',p80_at:'2026-10-18T08:08:00Z',p100_at:'2026-10-18T08:10:00Z'},
  working_windows_count:1
 },
 recovery_clock:{
  name:'RECOVERY',due_at:'2026-10-18T10:00:00Z',completed_at:'2026-10-18T09:00:00Z',
  paused:true,breached:false,status:'PAUSED_FOR_CUSTOMER_CONFIRM',missing_reason:null,
  thresholds:{p50_at:'2026-10-18T09:00:00Z',p80_at:'2026-10-18T09:36:00Z',p100_at:'2026-10-18T10:00:00Z'},
  working_windows_count:1
 },
 clock_tasks:[{clock_key:'response:50',kind:'SLA',due_at:'2026-10-18T08:05:00Z',outcome:'EMITTED',completed_at:'2026-10-18T08:05:01Z'}],
 sla_missing:{},sla_references:[],reopen_count:0,reopen_policy_status:'NOT_REOPENED',
 metric_version:'SLA_CLOCK_V2',observed_at:observed,source:'postgresql',...patch
})

function setup(scope=padminScope){
 const request=vi.fn(),key=vi.fn().mockReturnValue('synthetic-tier-change')
 const w=createServiceTierWorkspace({request},()=>{},key)
 w.reset(scope)
 return{w,request,key}
}

function deferred(){let resolve!:(v:any)=>void,reject!:(v:any)=>void;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
it('failed next page retries its original boundary, not the first page',async()=>{const x=setup();const items=Array.from({length:25},(_,i)=>changeRow({id:'55000000-0000-4000-8000-'+(100+i).toString(16).padStart(12,'0'),version:100-i,previous_version:99-i}));x.request.mockResolvedValueOnce(page(items,{current:snapshot({version:100}),has_more:true,next_before_version:76})).mockRejectedValueOnce(Error('lost')).mockResolvedValueOnce(page([changeRow({version:75,previous_version:74})],{current:snapshot({version:100})}));await x.w.load();expect(await x.w.load(true)).toBe(false);expect(x.w.state().readTarget).toEqual({before:76});expect(await x.w.retryRead()).toBe(true);expect(x.request.mock.calls[1]![0]).toContain('before_version=76');expect(x.request.mock.calls[2]![0]).toBe(x.request.mock.calls[1]![0]);expect(x.w.state().readTarget).toBeNull()})
it('cancelled old ledger response cannot finish a new read or clear its busy state',async()=>{const x=setup(),a=deferred(),b=deferred();x.request.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);const old=x.w.load();expect(x.w.cancelRead()).toBe(true);expect(x.request.mock.calls[0]![1].signal.aborted).toBe(true);const newer=x.w.retryRead();a.resolve(page());expect(await old).toBe(false);expect(x.w.state().busy).toBe('read');b.resolve(page());expect(await newer).toBe(true)})
it('ledger and SLA cancellation are independent and retain their own targets',async()=>{const x=setup(),a=deferred(),b=deferred();x.request.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);const ledger=x.w.load(),sla=x.w.inspectTicketSla(ticket);expect(x.w.cancelRead()).toBe(true);expect(x.request.mock.calls[0]![1].signal.aborted).toBe(true);expect(x.request.mock.calls[1]![1].signal.aborted).toBe(false);expect(x.w.cancelSlaRead()).toBe(true);a.resolve(page());b.resolve(slaPayload());expect(await ledger).toBe(false);expect(await sla).toBe(false);expect(x.w.canRetryRead()).toBe(true);expect(x.w.canRetrySlaRead()).toBe(true)})
it('highest tier version survives failure and refuses an empty or older current snapshot',async()=>{const x=setup();x.request.mockResolvedValueOnce(page()).mockRejectedValueOnce(Error('loss')).mockResolvedValueOnce(page([],{current:null})).mockResolvedValueOnce(page([changeRow({version:1,previous_version:null,from_tier:null})],{current:snapshot({version:1})})).mockResolvedValueOnce(page());await x.w.load();expect(await x.w.load()).toBe(false);expect(await x.w.retryRead()).toBe(false);expect(await x.w.retryRead()).toBe(false);expect(await x.w.retryRead()).toBe(true)})
it('accepted write receipt establishes readback floor and GET-only recovery',async()=>{const x=setup();const input={to_tier:'ADVANCED' as const,effective_mode:'IMMEDIATE' as const,contract_device_count:120,billing_cycle:'MONTHLY' as const,monthly_fee_fen:2500000,reason:'SYNTHETIC'};x.request.mockResolvedValueOnce(page()).mockResolvedValueOnce(snapshot({version:3})).mockResolvedValueOnce(page()).mockResolvedValueOnce(page([],{current:snapshot({version:3})}));await x.w.load();expect(await x.w.submitChange(input,2)).toBe(true);expect(x.w.state().receipt?.version).toBe(3);expect(x.w.state().page).toBeNull();expect(x.w.canRetryRead()).toBe(true);expect(await x.w.retryRead()).toBe(true);expect(x.request.mock.calls.filter(c=>c[1]?.method)).toHaveLength(1)})
it('SLA original retry pins its ticket and protects highest observed workflow version',async()=>{const x=setup();x.request.mockResolvedValueOnce(slaPayload()).mockRejectedValueOnce(Error('loss')).mockResolvedValueOnce(slaPayload({workflow_version:2})).mockResolvedValueOnce(slaPayload({workflow_version:4}));expect(await x.w.inspectTicketSla(ticket)).toBe(true);expect(await x.w.inspectTicketSla(ticket)).toBe(false);expect(await x.w.retrySlaRead()).toBe(false);expect(await x.w.retrySlaRead()).toBe(true);expect(new Set(x.request.mock.calls.map(c=>c[0])).size).toBe(1)})
it('duplicate original retries start only one GET for each reader',async()=>{const x=setup(),a=deferred(),b=deferred();x.request.mockRejectedValueOnce(Error('ledger')).mockRejectedValueOnce(Error('sla'));await x.w.load();await x.w.inspectTicketSla(ticket);x.request.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);const p=x.w.retryRead(),q=x.w.retrySlaRead();expect(await x.w.retryRead()).toBe(false);expect(await x.w.retrySlaRead()).toBe(false);expect(x.request).toHaveBeenCalledTimes(4);a.resolve(page());b.resolve(slaPayload());expect(await p).toBe(true);expect(await q).toBe(true)})
it('reset removes old targets and version floors without dispatching obsolete retries',async()=>{const x=setup();x.request.mockResolvedValueOnce(page()).mockRejectedValueOnce(Error('loss')).mockRejectedValueOnce(Error('sla'));await x.w.load();await x.w.load();await x.w.inspectTicketSla(ticket);x.w.reset(null);x.request.mockClear();expect(await x.w.retryRead()).toBe(false);expect(await x.w.retrySlaRead()).toBe(false);expect(x.request).not.toHaveBeenCalled();x.w.reset(padminScope);x.request.mockResolvedValueOnce(page([],{current:null}));expect(await x.w.load()).toBe(true)})
it('cancelling SLA cannot cancel or clear an unknown write attempt',async()=>{const x=setup(),wait=deferred();x.request.mockResolvedValueOnce(page()).mockRejectedValueOnce(Error('unknown')).mockReturnValueOnce(wait.promise);await x.w.load();await x.w.submitChange({to_tier:'ADVANCED',effective_mode:'IMMEDIATE',contract_device_count:120,billing_cycle:'MONTHLY',monthly_fee_fen:2500000,reason:'SYNTHETIC'},2);const original=x.w.state().attempt;const pending=x.w.inspectTicketSla(ticket);expect(x.w.cancelSlaRead()).toBe(true);expect(x.w.cancelRead()).toBe(false);expect(await x.w.retryRead()).toBe(false);expect(x.w.state().attempt).toEqual(original);wait.resolve(slaPayload());expect(await pending).toBe(false)})
it('cancellation in a synchronous state notification prevents the original GET',async()=>{const request=vi.fn();let w!:ReturnType<typeof createServiceTierWorkspace>;w=createServiceTierWorkspace({request},s=>{if(s.busy==='read')w.cancelRead();if(s.slaBusy)w.cancelSlaRead()});w.reset(padminScope);expect(await w.load()).toBe(false);expect(await w.inspectTicketSla(ticket)).toBe(false);expect(request).not.toHaveBeenCalled()})
