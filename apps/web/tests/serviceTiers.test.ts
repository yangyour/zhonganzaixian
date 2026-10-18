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
const at='2026-10-18T08:00:00Z',observed='2026-10-18T08:00:05Z'

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
  working_windows_count:0
 },
 recovery_clock:{
  name:'RECOVERY',due_at:'2026-10-18T10:00:00Z',completed_at:'2026-10-18T09:00:00Z',
  paused:true,breached:false,status:'PAUSED_FOR_CUSTOMER_CONFIRM',missing_reason:null,
  thresholds:{p50_at:'2026-10-18T09:00:00Z',p80_at:'2026-10-18T09:36:00Z',p100_at:'2026-10-18T10:00:00Z'},
  working_windows_count:0
 },
 clock_tasks:[{clock_key:'response:50',kind:'SLA',due_at:'2026-10-18T08:05:00Z',outcome:'EMITTED',completed_at:'2026-10-18T08:05:01Z'}],
 sla_missing:{},sla_references:[],reopen_count:0,reopen_policy_status:'NOT_REOPENED',
 metric_version:'D16_TICKET_SLA_CLOCK_V1',observed_at:observed,source:'postgresql',...patch
})

function setup(scope=padminScope){
 const request=vi.fn(),key=vi.fn().mockReturnValue('synthetic-tier-change')
 const w=createServiceTierWorkspace({request},()=>{},key)
 w.reset(scope)
 return{w,request,key}
}

describe('service tiers and ticket SLA clock inspection',()=>{
 it('validates service tier snapshot, append-only change page and non-retroactive flag',()=>{
  const parsed=readServiceTierPage(page(),padminScope)
  expect(parsed.current?.tier).toBe('ADVANCED')
  expect(parsed.in_flight_ticket_retroactive).toBe(false)
  expect(parsed.customer_approval_status).toBe('UNVERIFIED')
 })

 it('rejects retroactive claims, approved status, broken version chain or foreign tenant records',()=>{
  for(const patch of [
   {in_flight_ticket_retroactive:true},
   {customer_approval_status:'APPROVED'},
   {tenant_id:'55000000-0000-4000-8000-000000000002'},
   {current:null}
  ]){
   expect(()=>readServiceTierPage(page([changeRow()],patch),padminScope)).toThrow()
  }
  expect(()=>readServiceTierSnapshot(snapshot({availability_target:101}),tenant)).toThrow()
  expect(()=>readServiceTierPage(page([changeRow({version:2,previous_version:null})]),padminScope)).toThrow()
 })

 it('validates ticket SLA clock inspection and non-retroactive tier change detection',()=>{
  const sla=readTicketSlaInspection(slaPayload(),padminScope,ticket)
  expect(sla.frozen_service_tier).toBe('STANDARD')
  expect(sla.current_tenant_service_tier).toBe('ADVANCED')
  expect(sla.tier_changed_since_open).toBe(true)
  expect(sla.recovery_clock?.status).toBe('PAUSED_FOR_CUSTOMER_CONFIRM')
  expect(()=>readTicketSlaInspection(slaPayload({tier_changed_since_open:false}),padminScope,ticket)).toThrow()
  expect(()=>readTicketSlaInspection(slaPayload({reopen_count:1,reopen_policy_status:'NOT_REOPENED'}),padminScope,ticket)).toThrow()
 })

 it('preserves idempotency key on uncertain write failure and reloads after retry succeeds',async()=>{
  const x=setup(padminScope)
  x.request
   .mockResolvedValueOnce(page())
   .mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','timeout','t'))
   .mockResolvedValueOnce(snapshot({version:3,tier:'BASIC'}))
   .mockResolvedValueOnce(page([changeRow({version:3,previous_version:2,from_tier:'ADVANCED',to_tier:'BASIC'})],{current:snapshot({version:3,tier:'BASIC'})}))
  await x.w.load(false)
  expect(await x.w.submitChange({
   to_tier:'BASIC',effective_mode:'NEXT_MONTH_FIRST_DAY',
   contract_device_count:80,billing_cycle:'MONTHLY',monthly_fee_fen:1500000,reason:'SYNTHETIC_DOWNGRADE'
  })).toBe(false)
  expect(x.w.unresolved()).toBe(true)
  expect(await x.w.retry()).toBe(true)
  expect(x.w.unresolved()).toBe(false)
  const posts=x.request.mock.calls.filter(c=>c[1]?.method==='POST')
  expect(posts).toHaveLength(2)
  expect(posts[0]?.[1]?.idempotencyKey).toBe('synthetic-tier-change')
  expect(posts[1]?.[1]?.idempotencyKey).toBe('synthetic-tier-change')
  expect(x.w.state().page?.current?.version).toBe(3)
 })

 it('msp can read ledger and inspect SLA clocks but cannot submit tier changes',async()=>{
  const x=setup(mspScope)
  x.request.mockResolvedValueOnce(page()).mockResolvedValueOnce(slaPayload())
  expect(await x.w.load(false)).toBe(true)
  expect(await x.w.submitChange({
   to_tier:'BASIC',effective_mode:'IMMEDIATE',
   contract_device_count:null,billing_cycle:null,monthly_fee_fen:null,reason:'DENIED'
  })).toBe(false)
  expect(await x.w.inspectTicketSla(ticket)).toBe(true)
  expect(x.w.state().slaInspection?.metric_version).toBe('D16_TICKET_SLA_CLOCK_V1')
 })

 it('wires route, navigation and explicit M2-10 / Q27 / Q08 disclosures',()=>{
  const router=readFileSync(resolve('src/router.ts'),'utf8')
  const nav=readFileSync(resolve('src/utils/navigationModules.ts'),'utf8')
  const pageSource=readFileSync(resolve('src/pages/ServiceTiersPage.vue'),'utf8')
  expect(router).toContain("path: '/service-tiers'")
  expect(nav).toContain("['service-tiers','服务档位与SLA台账']")
  expect(pageSource).toContain('M2-10')
  expect(pageSource).toContain('Q27')
  expect(pageSource).toContain('UNVERIFIED')
 })
})
