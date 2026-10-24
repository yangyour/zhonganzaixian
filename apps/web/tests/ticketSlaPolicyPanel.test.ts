// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
vi.mock('vue-router',()=>({onBeforeRouteLeave:vi.fn()}))
import TicketSlaPolicyPanel from '../src/components/TicketSlaPolicyPanel.vue'
import { session } from '../src/stores/session'
import { api } from '../src/api/client'
import { ticket,id } from './ticketReader.fixture'
let app:App|undefined,host:HTMLElement,state:any
const available=(v=2)=>({tenant_id:id(1),ticket_id:id(10),lifecycle_version:4,status:'AVAILABLE',release_id:id(4),release_version:v,clock_policy:'EXCLUDE_CONFIRMED_PAUSES',bound_at:null,reference:null,missing:null,source:'postgresql',production_approved:false})
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
beforeEach(()=>{session.value={user:{id:id(2),role:'msp',display_name:'Synthetic'},tenant:{id:id(1),name:'Synthetic'},allowed_tenants:[],capabilities:['tickets:read','tickets:lifecycle_write'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
function mount(){host=document.createElement('div');document.body.append(host);app=createApp({...TicketSlaPolicyPanel,render:()=>null},{ticket:ticket(),expectedVersion:4});state=(app.mount(host).$ as any).setupState}
it('a changed published candidate clears prior user acknowledgment',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(available());mount();await settle();state.acknowledged=true;request.mockResolvedValue(available(3));await state.workspace.refresh();await settle();expect(state.acknowledged).toBe(false);expect(state.state.view.release_version).toBe(3)})
it('binding cannot be sent without explicit acknowledgment',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(available());mount();await settle();await state.bind();expect(request.mock.calls.every(([,o])=>!o?.method)).toBe(true)})
it('lifecycle version or solution change also clears the old action acknowledgment',async()=>{
  const {default:Lifecycle}=await import('../src/components/TicketLifecyclePanel.vue')
  vi.spyOn(api,'request').mockResolvedValue({lifecycle:null,status:'NOT_STARTED',source:'postgresql',production_approved:false})
  host=document.createElement('div');document.body.append(host);app=createApp({...Lifecycle,render:()=>null},{ticket:ticket(),refreshKey:0});state=(app.mount(host).$ as any).setupState;await settle()
  state.state={...state.state,snapshot:{version:1,state:'IN_PROGRESS',solution_snapshot_hash:null}};await settle();state.selected='submit-resolution';await settle();state.acknowledged=true
  state.state={...state.state,snapshot:{version:2,state:'IN_PROGRESS',solution_snapshot_hash:'a'.repeat(64)}};await settle();expect(state.acknowledged).toBe(false);expect(state.selected).toBe('')
})
it('account revision cancels pending policy read and clears prior acknowledgment',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(available());mount();await settle();state.acknowledged=true;let finish!:(v:unknown)=>void;request.mockImplementation(()=>new Promise(resolve=>finish=resolve));const pending=state.workspace.refresh();const old=request.mock.calls.at(-1)![1]!.signal,complete=finish;session.value={...session.value!,account_version:2};await settle();expect(old?.aborted).toBe(true);expect(state.acknowledged).toBe(false);complete(available());await pending;await settle();expect(state.state.view).toBeNull()})
