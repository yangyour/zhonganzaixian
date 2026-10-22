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

const read=(value:any)=>readTicketSlaInspection(value,padminScope,ticket)
const valid=()=>slaPayload() as any
const ref=()=>({key:'sla.response',release_id:null,version:0,definition_hash:'a'.repeat(64),source:'CONTRACT_DEFAULT'})
it.each([
 ['unknown state',(v:any):unknown=>v.state='FINISHED'],['unknown priority',(v:any):unknown=>v.priority='P0'],['unknown current tier',(v:any):unknown=>v.current_tenant_service_tier='CUSTOM'],
 ['future registration',(v:any):unknown=>v.registration_created_at='2027-01-01T00:00:00Z'],['missing array',(v:any):unknown=>v.clock_tasks={}],['invalid references collection',(v:any):unknown=>v.sla_references=null],['missing values array',(v:any):unknown=>v.sla_missing=[]],['missing numeric reason',(v:any):unknown=>v.sla_missing={response:0}],['blank missing reason',(v:any):unknown=>v.sla_missing={response:' '}]
] as const)('rejects invalid top-level %s',(_label,mutate)=>{const v=valid();mutate(v);expect(()=>read(v)).toThrow()})
it.each([
 ['MET marked breached',(c:any):unknown=>c.breached=true],['future completion',(c:any):unknown=>c.completed_at='2027-01-01T00:00:00Z'],['completion before registration',(c:any):unknown=>c.completed_at='2026-10-17T00:00:00Z'],['deadline before registration',(c:any):unknown=>c.due_at='2026-10-17T00:00:00Z'],['zero windows with deadline',(c:any):unknown=>c.working_windows_count=0],['missing midpoint',(c:any):unknown=>c.thresholds.p50_at=null],['inverted thresholds',(c:any):unknown=>c.thresholds.p50_at='2026-10-18T08:09:00Z'],['wrong 100 percent',(c:any):unknown=>c.thresholds.p100_at='2026-10-18T08:11:00Z'],['pending with deadline',(c:any):unknown=>c.status='PENDING_CONFIG'],['running completed',(c:any):unknown=>c.status='RUNNING'],['breached but false',(c:any):unknown=>c.status='BREACHED'],['breached before deadline',(c:any):unknown=>{c.status='BREACHED';c.breached=true;return undefined}],['paused response',(c:any):unknown=>c.paused=true],['cancelled not cancelled parent',(c:any):unknown=>{c.status='CANCELLED';c.breached=null;return undefined}],['blank missing reason',(c:any):unknown=>c.missing_reason=' '],['wrong date format',(c:any):unknown=>c.due_at='2026-10-18 08:10:00']
] as const)('rejects contradictory response clock %s',(_label,mutate)=>{const v=valid();mutate(v.response_clock);expect(()=>read(v)).toThrow()})
it.each([
 ['non-array task', (v:any):unknown=>v.clock_tasks=[null]],['duplicate key',(v:any):unknown=>v.clock_tasks.push({...v.clock_tasks[0]})],['unknown kind',(v:any):unknown=>v.clock_tasks[0].kind='SEND'],['unknown outcome',(v:any):unknown=>v.clock_tasks[0].outcome='DELIVERED'],['missing completion',(v:any):unknown=>v.clock_tasks[0].completed_at=null],['completion without outcome',(v:any):unknown=>v.clock_tasks[0].outcome=null],['future completion',(v:any):unknown=>v.clock_tasks[0].completed_at='2027-01-01T00:00:00Z'],['completion before due',(v:any):unknown=>v.clock_tasks[0].completed_at=at],['wrong kind key',(v:any):unknown=>v.clock_tasks[0].kind='AUTO_CLOSE'],['auto-close receipt on SLA',(v:any):unknown=>v.clock_tasks[0].outcome='AUTO_CLOSED'],['secret extra field',(v:any):unknown=>v.clock_tasks[0].access_token='synthetic-only'],['out-of-order due',(v:any):unknown=>v.clock_tasks.push({...v.clock_tasks[0],clock_key:'recovery:50',due_at:'2026-10-18T08:04:00Z'})]
] as const)('rejects invalid durable task %s',(_label,mutate)=>{const v=valid();mutate(v);expect(()=>read(v)).toThrow()})
it.each([
 ['missing digest',(r:any):unknown=>delete r.definition_hash],['bad digest',(r:any):unknown=>r.definition_hash='bad'],['unknown source',(r:any):unknown=>r.source='ASSUMED'],['default version',(r:any):unknown=>r.version=1],['default release',(r:any):unknown=>r.release_id=actor],['runtime missing release',(r:any):unknown=>{r.source='RUNTIME_RELEASE';r.version=1;return undefined}],['runtime zero version',(r:any):unknown=>{r.source='RUNTIME_RELEASE';r.release_id=actor;return undefined}],['blank key',(r:any):unknown=>r.key=' ']
] as const)('rejects invalid frozen reference %s',(_label,mutate)=>{const v=valid(),r:any=ref();mutate(r);v.sla_references=[r];expect(()=>read(v)).toThrow()})
const unopened=()=>({...valid(),workflow_version:0,state:null,priority:null,frozen_service_tier:null,response_clock:null,recovery_clock:null,tier_changed_since_open:false,reopen_count:0,reopen_policy_status:'NOT_REOPENED',clock_tasks:[],sla_missing:{},sla_references:[]})
it('accepts truly unstarted workflow without inventing state, priority or clocks',()=>{expect(read(unopened()).workflow_version).toBe(0)})
it.each(['state','priority','clock_tasks','sla_references','sla_missing'] as const)('unstarted workflow cannot carry invented %s',key=>{const v:any=unopened();v[key]=key==='state'?'OPEN':key==='priority'?'P1':key==='clock_tasks'?valid().clock_tasks:key==='sla_references'?[ref()]:{response:'missing'};expect(()=>read(v)).toThrow()})
it('preserves honest pending configuration and unknown breach values',()=>{const v=valid();v.recovery_clock={...v.recovery_clock,due_at:null,completed_at:null,breached:null,status:'PENDING_CONFIG',missing_reason:'CONFIGURATION_UNAVAILABLE',thresholds:{p50_at:null,p80_at:null,p100_at:null},working_windows_count:0};const result=read(v);expect(result.recovery_clock?.breached).toBeNull();expect(result.recovery_clock?.due_at).toBeNull()})
it('accepts monitoring recovery MET with paused true and overdue BREACHED clocks',()=>{const v=valid();v.recovery_clock.status='MET';expect(read(v).recovery_clock?.paused).toBe(true);v.response_clock={...v.response_clock,status:'BREACHED',breached:true,completed_at:null};expect(read(v).response_clock?.breached).toBe(true)})
it('allows cancelled recovery while an already completed response remains MET',()=>{const v=valid();v.state='CANCELLED';v.recovery_clock={...v.recovery_clock,status:'CANCELLED',paused:false,breached:null};expect(read(v).response_clock?.status).toBe('MET')})
it('accepts explicit default and runtime references without asserting their approval',()=>{const v=valid();v.sla_references=[ref(),{...ref(),key:'sla.calendar',source:'RUNTIME_RELEASE',version:2,release_id:actor}];expect(read(v).sla_references).toEqual(v.sla_references)})
it('accepts pending and obsolete durable tasks while preserving their distinct outcomes',()=>{const v=valid();v.clock_tasks=[{clock_key:'confirmation:1',kind:'CONFIRMATION_REMINDER',due_at:'2026-10-18T09:00:00Z',outcome:'OBSOLETE',completed_at:'2026-10-18T09:01:00Z'},{clock_key:'auto_close:1',kind:'AUTO_CLOSE',due_at:'2026-10-19T09:00:00Z',outcome:null,completed_at:null}];expect(read(v).clock_tasks[1]!.outcome).toBeNull()})
it('returned inspection is detached from later response object mutation',()=>{const v=valid();const result=read(v);v.clock_tasks[0].outcome='FORGED';v.response_clock.status='FORGED';expect(result.clock_tasks[0]!.outcome).toBe('EMITTED');expect(result.response_clock?.status).toBe('MET')})
it('date validation reports SLA integrity, not an unrelated asset export error',()=>{const v=valid();v.observed_at='invalid';try{read(v);throw Error('expected rejection')}catch(error){expect(error).toMatchObject({code:'INVALID_SERVICE_TIER_PAYLOAD'})}})
