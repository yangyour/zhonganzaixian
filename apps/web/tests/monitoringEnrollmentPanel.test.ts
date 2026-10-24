// @vitest-environment jsdom
import {createApp,h,nextTick,type App} from 'vue'
import {createMemoryHistory,createRouter,RouterView,type Router} from 'vue-router'
import {it,expect,beforeEach,afterEach,vi} from 'vitest'
import Panel from '../src/components/MonitoringEnrollmentPanel.vue'
import {emptyMonitoringDraft} from '../src/utils/monitoringOnboarding'
import {api,ApiError} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
vi.mock('../src/components/ApiErrorNotice.vue',()=>({default:{template:'<div />'}}))
const actor='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002'
const auth:Session={user:{id:actor,display_name:'Synthetic',role:'padmin'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['assets:read','configurations:read','configurations:write']}
let app:App|undefined,host:HTMLElement,router:Router
async function settle(){for(let i=0;i<14;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{render:()=>h(Panel,{draft:{...emptyMonitoringDraft(),assetId:actor,host:'198.51.100.1'}})}},{path:'/elsewhere',component:{template:'<p>Other</p>'}}]});await router.push('/');await router.isReady();app=createApp({render:()=>h(RouterView)});app.use(router);app.mount(host);await settle()}
function button(text:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===text)!}
beforeEach(()=>{sessionStorage.clear();session.value=structuredClone(auth);contextGeneration.value++;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;sessionStorage.clear();vi.restoreAllMocks()})
it('does not read new tables or probe anything automatically on entry',async()=>{const request=vi.spyOn(api,'request');await mount();expect(request).not.toHaveBeenCalled();expect(button('读取登记任务')).toBeTruthy()})
it('creates only from an explicitly reviewed server plan and preserves unknown command on reload',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue({tenant_id:tenant,actor_id:actor,permission_version:7,plan:{valid:true,configuration_sha256:'b'.repeat(64),catalog_sha256:'a'.repeat(64),target_count:1,applied:false,continuous_collection_verified:false,diagnostics:[]}})
 await mount();button('核对服务器计划').click();await settle();expect(request.mock.calls[0][0]).toBe('/monitoring-onboarding/plans');expect(JSON.stringify(request.mock.calls[0][1]?.body)).not.toContain('198.51.100.1')
 request.mockRejectedValueOnce(new ApiError(504,'TIMEOUT','unit','unit'));button('保存 1 个停用目标的登记申请').click();await settle();const original=request.mock.calls[1][1];await router.push('/elsewhere');expect(router.currentRoute.value.path).toBe('/')
 app!.unmount();app=undefined;host.remove();contextGeneration.value++;await mount();expect(host.textContent).toContain('操作结果待确认');expect(request).toHaveBeenCalledTimes(2)
 button('按原请求核对').click();await settle();expect(request.mock.calls[2][1]?.idempotencyKey).toBe(original?.idempotencyKey);expect(request.mock.calls[2][1]?.body).toEqual(original?.body)
})
it('revocation aborts the private task list and rejects late results',async()=>{let finish!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{finish=r}));await mount();button('读取登记任务').click();await settle();session.value={...auth,capabilities:[]};await settle();expect(request.mock.calls[0][1]?.signal?.aborted).toBe(true);finish({items:[],next:null,external_configuration_applied:false,continuous_collection_verified:false});await settle();expect(host.querySelector('section')).toBeNull()})
