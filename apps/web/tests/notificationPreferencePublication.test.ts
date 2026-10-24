// @vitest-environment jsdom
import { createApp, h, nextTick, type App } from 'vue'
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import Panel from '../src/components/NotificationPreferencePublication.vue'
import { api, ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
vi.mock('../src/components/ApiErrorNotice.vue',()=>({default:{template:'<div />'}}))
const actor='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002',draft='79000000-0000-4000-8000-000000000003'
const auth:Session={user:{id:actor,display_name:'Synthetic',role:'viewer'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['notification_preferences:read_own','notification_preferences:write_own']}
let app:App|undefined,host:HTMLElement,router:Router
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>h(Panel,{draftId:draft,draftVersion:3,blocked:false})}},{path:'/elsewhere',component:{template:'<p>Other page</p>'}}]});await router.push('/');await router.isReady();app=createApp({render:()=>h(RouterView)});app.use(router);app.mount(host);await settle()}
function button(label:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!}
beforeEach(()=>{sessionStorage.clear();session.value=structuredClone(auth);contextGeneration.value++;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;sessionStorage.clear();vi.restoreAllMocks()})
it('requires explicit review of the saved revision and never automatically publishes',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue({record:null,external_dispatch_enabled:false});await mount()
 expect(button('发布当前偏好').disabled).toBe(true);expect(request).toHaveBeenCalledOnce()
 expect(request.mock.calls.every(([,o])=>!o?.method)).toBe(true)
})
it('retains unknown publication and recovers the exact original command before navigation',async()=>{
 let command=''
 const request=vi.spyOn(api,'request').mockImplementation(async(path,options)=>{
  if(options?.method==='POST'){command=options.idempotencyKey!;throw new ApiError(504,'REQUEST_TIMEOUT','synthetic lost response','unit')}
  if(path.startsWith('/notification-routing/commands/'))return {kind:'PREFERENCE',id:actor,version:0,command_id:command,state:{published:true,draft_version:3},recorded_at:'2026-10-23T19:00:00Z'}
  return {record:null,external_dispatch_enabled:false}
 });await mount();const ack=host.querySelector<HTMLInputElement>('input[type=checkbox]')!;ack.checked=true;ack.dispatchEvent(new Event('change'));await settle();button('发布当前偏好').click();await settle()
 expect(host.textContent).toContain('发布结果未确认');await router.push('/elsewhere');expect(router.currentRoute.value.path).toBe('/')
 button('核对原命令').click();await settle();expect(host.textContent).toContain('已核对原发布命令')
 expect(request.mock.calls.filter(([,o])=>o?.method==='POST')).toHaveLength(1)
 expect(request.mock.calls.some(([p])=>p==='/notification-routing/commands/'+command)).toBe(true)
})
it('revocation clears an in-flight private response',async()=>{
 let resolve!:(value:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));await mount();session.value={...auth,capabilities:[]};await settle();expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);resolve({record:null,external_dispatch_enabled:false});await settle();expect(host.querySelector('.preference-publication')).toBeNull()
})
