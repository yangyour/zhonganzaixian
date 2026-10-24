// @vitest-environment jsdom
import {createApp,h,nextTick,type App} from 'vue'
import {createRouter,createMemoryHistory,RouterView,type Router} from 'vue-router'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import Page from '../src/pages/ReportPublicationsPage.vue'
import {api,ApiError} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
vi.mock('../src/components/ApiErrorNotice.vue',()=>({default:{template:'<div />'}}))
const actor='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002',source='79000000-0000-4000-8000-000000000003'
const auth:Session={user:{id:actor,display_name:'Synthetic',role:'padmin'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['dashboard:read','inspection_quarterly_reports:write','configurations:write']}
let app:App|undefined,host:HTMLElement,router:Router
async function settle(){for(let i=0;i<14;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/elsewhere',component:{template:'<p>Other</p>'}},{path:'/inspection-quarterly-reports',component:{template:'<p>Sources</p>'}}]});await router.push({path:'/',query:{source_id:source,source_sha256:'a'.repeat(64)}});await router.isReady();app=createApp({render:()=>h(RouterView)});app.use(router);app.mount(host);await settle()}
function button(text:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===text)!}
beforeEach(()=>{sessionStorage.clear();session.value=structuredClone(auth);contextGeneration.value++;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;sessionStorage.clear();vi.restoreAllMocks()})
it('only reads on entry and requires explicit preparation',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue({items:[],next:null,external_dispatch_enabled:false});await mount();expect(request).toHaveBeenCalledOnce();expect(request.mock.calls[0][1]?.method).toBeUndefined();expect(button('由已选冻结来源创建报告')).toBeTruthy()})
it('retains an unknown create across remount without resubmitting automatically',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue({items:[],next:null,external_dispatch_enabled:false});await mount();request.mockRejectedValueOnce(new ApiError(504,'TIMEOUT','unit','unit'));button('由已选冻结来源创建报告').click();await settle();await router.push('/elsewhere');expect(router.currentRoute.value.path).toBe('/')
 const original=request.mock.calls.find(([,o])=>o?.method==='POST')![1];app!.unmount();app=undefined;host.remove();contextGeneration.value++;await mount()
 expect(host.textContent).toContain('上次操作结果待确认');expect(request.mock.calls.filter(([,o])=>o?.method==='POST')).toHaveLength(1)
 button('核对原请求').click();await settle();const writes=request.mock.calls.filter(([,o])=>o?.method==='POST');expect(writes[1][1]?.idempotencyKey).toBe(original?.idempotencyKey);expect(writes[1][1]?.body).toEqual(original?.body)
})
it('forced revocation discards late private list results',async()=>{
 let finish!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{finish=r}));await mount();session.value={...auth,capabilities:[]};await settle();expect(request.mock.calls[0][1]?.signal?.aborted).toBe(true);finish({items:[],next:null,external_dispatch_enabled:false});await settle();expect(host.textContent).toContain('当前模式或授权不支持');expect(host.textContent).not.toContain('当前页没有可见报告')
})
