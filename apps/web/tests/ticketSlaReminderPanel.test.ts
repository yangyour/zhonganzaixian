// @vitest-environment jsdom
// Isolated SFC/read state, not live database or notification delivery.
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import { createApp,nextTick,reactive,type App } from 'vue'
import TicketSlaReminderPanel from '../src/components/TicketSlaReminderPanel.vue'
import { api } from '../src/api/client'
import { session,contextGeneration } from '../src/stores/session'
import { ticket,id } from './ticketReader.fixture'
import type { Session } from '../src/api/types'
let app:App|undefined,host:HTMLElement,state:any,props:any
const auth:Session={user:{id:id(2),role:'msp',display_name:'Synthetic'},tenant:{id:id(1),name:'Synthetic'},allowed_tenants:[],capabilities:['tickets:read'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
const result=()=>({tenant_id:id(1),ticket_id:id(10),lifecycle_version:2,scan_state:'PENDING',last_outcome:null,last_scan_at:null,items:[],source:'postgresql',production_approved:false})
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.append(host);app=createApp({...TicketSlaReminderPanel,render:()=>null},props);state=(app.mount(host).$ as any).setupState}
beforeEach(()=>{session.value=structuredClone(auth);contextGeneration.value=0;props=reactive({ticket:ticket(),expectedVersion:2})})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('only reads the scoped reminder endpoint',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(result());mount();await settle();expect(state.value.scan_state).toBe('PENDING');expect(request.mock.calls[0]![0]).toBe(`/tickets/${id(10)}/sla/reminders`);expect(request.mock.calls[0]![1]?.method).toBeUndefined()})
it('revoked access clears records immediately',async()=>{vi.spyOn(api,'request').mockResolvedValue(result());mount();await settle();session.value={...auth,capabilities:[]};await settle();expect(state.value).toBeNull()})
it('late old tenant response cannot restore records',async()=>{let resolve!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));mount();await settle();session.value={...auth,tenant:{id:id(9),name:'Other'}};await settle();resolve(result());await settle();expect(state.value).toBeNull()})
it('wrong version fails without stale displayed data',async()=>{const raw=result();raw.lifecycle_version=3;vi.spyOn(api,'request').mockResolvedValue(raw);mount();await settle();expect(state.value).toBeNull();expect(state.error.code).toBe('INVALID_SLA_REMINDER_RESPONSE')})
it('unmount aborts and never causes a send',async()=>{const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(()=>{}));mount();await settle();app!.unmount();app=undefined;expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true);expect(request.mock.calls.every(([,o])=>!o?.method&&!o?.body)).toBe(true)})
it('account-only revision invalidates pending reminder read',async()=>{let finish!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(resolve=>finish=resolve));mount();await settle();const old=request.mock.calls[0]![1]!.signal,complete=finish;session.value={...auth,account_version:2};await settle();expect(old?.aborted).toBe(true);complete(result());await settle();expect(state.value).toBeNull()})
