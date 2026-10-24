// @vitest-environment jsdom
// Isolated SFC lifecycle checks; not authenticated browser or PostgreSQL evidence.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, reactive, type App } from 'vue'
import TicketSlaPanel from '../src/components/TicketSlaPanel.vue'
import { api } from '../src/api/client'
import { session, contextGeneration } from '../src/stores/session'
import { ticket, id } from './ticketReader.fixture'
import type { Session } from '../src/api/types'
let app:App|undefined,host:HTMLElement,state:any,props:any
const auth:Session={user:{id:id(2),role:'msp',display_name:'Synthetic'},tenant:{id:id(1),name:'Synthetic'},allowed_tenants:[],capabilities:['tickets:read'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
const clock={status:'RUNNING',deadline:'2026-10-20T01:00:00Z',stopped_at:null,elapsed_ms:1000,budget_ms:60000,breached:false,missing:null}
const result=()=>({status:'PROJECTED',source:'postgresql',production_approved:false,clock:{tenant_id:id(1),ticket_id:id(10),tier:'STANDARD',priority:'P1',observed_at:'2026-10-20T00:00:01Z',lifecycle_version:2,configuration_reference:'sha256:'+'a'.repeat(64),calculation_policy_reference:null,clock_policy:null,response:{...clock},recovery:{...clock},paused_at:null,completed_pauses:[],reminders_delivered:false,production_approved:false}})
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.append(host);app=createApp({...TicketSlaPanel,render:()=>null},props);state=(app.mount(host).$ as any).setupState}
beforeEach(()=>{session.value=structuredClone(auth);contextGeneration.value=0;props=reactive({ticket:ticket(),expectedVersion:2})})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('requests only the scoped server projection',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(result());mount();await settle();expect(state.value.lifecycle_version).toBe(2);expect(request.mock.calls[0]![0]).toBe(`/tickets/${id(10)}/sla`);expect(request.mock.calls[0]![1]?.method).toBeUndefined()})
it('permission revocation clears and aborts protected data',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(result());mount();await settle();session.value={...session.value!,capabilities:[]};await settle();expect(state.value).toBeNull();expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true)})
it('late payload cannot restore old tenant state',async()=>{let resolve!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));mount();await settle();session.value={...auth,tenant:{id:id(9),name:'Other'}};await settle();resolve(result());await settle();expect(state.value).toBeNull()})
it('invalid version never displays stale clocks',async()=>{const raw=result();raw.clock.lifecycle_version=3;vi.spyOn(api,'request').mockResolvedValue(raw);mount();await settle();expect(state.value).toBeNull();expect(state.error.code).toBe('INVALID_TICKET_SLA_RESPONSE')})
it('unmount aborts pending read and never writes',async()=>{const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(()=>{}));mount();await settle();app!.unmount();app=undefined;expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true);expect(request.mock.calls[0]![1]?.body).toBeUndefined()})
it('account-only version changes abort old SLA reads',async()=>{let finish!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>finish=r));mount();await settle();const old=request.mock.calls[0]![1]!.signal;const complete=finish;session.value={...auth,account_version:2};await settle();expect(old?.aborted).toBe(true);complete(result());await settle();expect(state.value).toBeNull()})
