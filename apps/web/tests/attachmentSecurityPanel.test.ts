// @vitest-environment jsdom
import { createApp, h, nextTick, type App } from 'vue'
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import Panel from '../src/components/AttachmentSecurityPanel.vue'
import { api, ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
vi.mock('../src/components/ApiErrorNotice.vue',()=>({default:{template:'<div />'}}))
const actor='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002',file='79000000-0000-4000-8000-000000000003'
const auth:Session={user:{id:actor,display_name:'Synthetic',role:'viewer'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['tickets:read']}
const snapshot={file_id:file,tenant_id:tenant,version:0,state:'QUARANTINED',scan_request_id:null,content_sha256:'a'.repeat(64),scanned_at:null,valid_until:null,recorded_at:null,release_allowed:false,content_allowed:false,can_manage_release:false,can_request_scan:true,scanner_status:'NOT_CONFIGURED'}
let app:App|undefined,host:HTMLElement,router:Router
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>h(Panel,{fileId:file})}},{path:'/elsewhere',component:{template:'<p>Other page</p>'}}]});await router.push('/');await router.isReady();app=createApp({render:()=>h(RouterView)});app.component('el-button',{props:['disabled','loading'],template:'<button :disabled="disabled || loading"><slot /></button>'});app.use(router);app.mount(host);await settle()}
function button(label:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!}
beforeEach(()=>{sessionStorage.clear();session.value=structuredClone(auth);contextGeneration.value++;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;sessionStorage.clear();vi.restoreAllMocks()})
it('retains unknown commands across unmount and blocks navigation until exact replay',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(snapshot);await mount()
 request.mockRejectedValueOnce(new ApiError(504,'REQUEST_TIMEOUT','synthetic','unit'));button('申请扫描').click();await settle()
 const original=request.mock.calls.find(([,o])=>o?.method==='POST')![1]!
 await router.push('/elsewhere');expect(router.currentRoute.value.path).toBe('/')
 app!.unmount();app=undefined;host.remove();await mount()
 expect(host.textContent).toContain('附件操作结果待确认')
 request.mockResolvedValue({...snapshot,version:1,state:'QUEUED'});button('按原请求确认结果').click();await settle()
 const writes=request.mock.calls.filter(([,o])=>o?.method==='POST');expect(writes).toHaveLength(2)
 expect(writes[1][1]?.idempotencyKey).toBe(original.idempotencyKey);expect(writes[1][1]?.body).toEqual(original.body)
 await router.push('/elsewhere');expect(router.currentRoute.value.path).toBe('/elsewhere')
})
it('revocation hides state and aborts reads before a late response',async()=>{
 let resolve!:(value:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));await mount()
 session.value={...auth,capabilities:[]};await settle();expect(request.mock.calls[0][1]?.signal?.aborted).toBe(true)
 resolve({...snapshot,state:'RELEASED',content_allowed:true});await settle();expect(host.querySelector('section')).toBeNull()
})
