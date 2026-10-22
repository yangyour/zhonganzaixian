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

const changeInput=()=>({to_tier:'ADVANCED' as const,effective_mode:'IMMEDIATE' as const,contract_device_count:120,billing_cycle:'MONTHLY' as const,monthly_fee_fen:2500000,reason:'SYNTHETIC'})
it.each(['read','write','sla'])('scope revoked by %s state notification prevents request dispatch',async kind=>{const request=vi.fn().mockResolvedValue(page());let revoke=false;let w!:ReturnType<typeof createServiceTierWorkspace>;w=createServiceTierWorkspace({request},s=>{if(revoke&&(kind==='sla'?s.slaBusy:s.busy===kind))w.reset(null)},()=> 'synthetic-key');w.reset(padminScope);if(kind==='write')await w.load();request.mockClear();revoke=true;expect(await(kind==='read'?w.load():kind==='sla'?w.inspectTicketSla(ticket):w.submitChange(changeInput(),2))).toBe(false);expect(request).not.toHaveBeenCalled();expect(w.state().error).toBeNull()})
it('key generation cannot rebind an old change to a new actor scope',async()=>{const request=vi.fn().mockResolvedValue(page());let w!:ReturnType<typeof createServiceTierWorkspace>;w=createServiceTierWorkspace({request},()=>{},()=>{w.reset({...padminScope,actorId:'55000000-0000-4000-8000-000000000012'});return 'synthetic-key'});w.reset(padminScope);await w.load();request.mockClear();expect(await w.submitChange(changeInput(),2)).toBe(false);expect(request).not.toHaveBeenCalled();expect(w.state().attempt).toBeNull()})
it('key callback mutations cannot alter original payload',async()=>{const request=vi.fn().mockResolvedValueOnce(page()).mockResolvedValueOnce(snapshot({version:3})).mockResolvedValueOnce(page([], {current:snapshot({version:3})}));const input=changeInput();const w=createServiceTierWorkspace({request},()=>{},()=>{input.monthly_fee_fen=0;input.reason='changed';return 'synthetic-key'});w.reset(padminScope);await w.load();expect(await w.submitChange(input,2)).toBe(true);expect(request.mock.calls[1]![1].body).toMatchObject({monthly_fee_fen:2500000,reason:'SYNTHETIC'})})
it('key exception after reset never leaks into the new scope',async()=>{const request=vi.fn().mockResolvedValue(page());let w!:ReturnType<typeof createServiceTierWorkspace>;w=createServiceTierWorkspace({request},()=>{},()=>{w.reset(null);throw Error('old callback failure')});w.reset(padminScope);await w.load();request.mockClear();expect(await w.submitChange(changeInput(),2)).toBe(false);expect(w.state().error).toBeNull();expect(request).not.toHaveBeenCalled()})
it('accepted receipt callback reset prevents automatic read in a new scope',async()=>{const request=vi.fn().mockResolvedValueOnce(page()).mockResolvedValueOnce(snapshot({version:3}));let w!:ReturnType<typeof createServiceTierWorkspace>;w=createServiceTierWorkspace({request},s=>{if(s.receipt)w.reset({...padminScope,actorId:'55000000-0000-4000-8000-000000000012'})},()=> 'synthetic-key');w.reset(padminScope);await w.load();expect(await w.submitChange(changeInput(),2)).toBe(false);expect(request).toHaveBeenCalledTimes(2);expect(w.state().receipt).toBeNull()})
