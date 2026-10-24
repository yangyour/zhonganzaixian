// @vitest-environment jsdom
import {createApp,h,nextTick,type App} from 'vue'
import {createRouter,createMemoryHistory,RouterView,type Router} from 'vue-router'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import Page from '../src/pages/NotificationRoutingPage.vue'
import {api,ApiError} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
vi.mock('../src/components/DeliveryJournalPanel.vue',()=>({default:{template:'<div />'}}))

const actor='79000000-0000-4000-8000-000000000002',tenant='79000000-0000-4000-8000-000000000001',id='79000000-0000-4000-8000-000000000003'
const auth:Session={user:{id:actor,display_name:'SYNTHETIC',role:'padmin'},tenant:{id:tenant,name:'SYNTHETIC'},allowed_tenants:[],permission_version:7,account_version:1,
 csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic',capabilities:['configurations:write','ticket_notes:read_internal','audit:read','notification_preferences:read_own']}
let app:App|undefined,host:HTMLElement,router:Router
function entry(cancelled=false,key=actor){return{kind:'REQUEST',id,version:cancelled?1:0,command_id:key,recorded_at:'2026-10-24T12:00:00Z',state:{status:cancelled?'CANCELLED':'WAITING_CONFIGURATION',message_id:id,intent_id:id,receiver_id:actor,phase:'OPENED',cancellation_reason:'取消测试原因',external_dispatch_enabled:false}}}
async function settle(){for(let i=0;i<15;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/other',component:{template:'<p>Other</p>'}}]});await router.push('/');await router.isReady();app=createApp({render:()=>h(RouterView)});app.use(router).use(ElementPlus);app.mount(host);await settle()}
function button(label:string,within:ParentNode=document.body){return[...within.querySelectorAll('button')].find(value=>value.textContent?.trim()===label)!}
function transport(lose=false){let cancelled=false;const request=vi.spyOn(api,'request').mockImplementation(async(path,options)=>{
 if(options?.method==='POST'){cancelled=true;if(lose)throw new ApiError(504,'TIMEOUT','synthetic','unit');return entry(true,options.idempotencyKey) as never}
 if(path.startsWith('/notification-routing/commands/'))return entry(true,path.split('/').at(-1)) as never
 if(path==='/notification-routing/policy')return{record:null,external_dispatch_enabled:false} as never
 return{items:[entry(cancelled)],next_id:null,next_time:null,source:'postgresql'} as never
});return request}
beforeEach(()=>{sessionStorage.clear();session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value++})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;sessionStorage.clear();vi.restoreAllMocks();document.querySelectorAll('.el-overlay').forEach(value=>value.remove())})

it('requires an explicit reason and command confirmation, then hides cancellation for the cancelled request',async()=>{
 const request=transport();await mount();button('取消未派发请求').click();await settle()
 const dialog=document.querySelector('.el-dialog')!;expect(button('确认取消请求',dialog).disabled).toBe(true)
 const input=dialog.querySelector('textarea')!;input.value='取消测试原因';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(0)
 button('确认取消请求',dialog).click();await settle()
 expect(request.mock.calls.filter(([,options])=>options?.method==='POST')[0][1]?.body).toEqual({expected_version:0,reason:'取消测试原因'})
 expect(host.textContent).toContain('已取消');expect(button('取消未派发请求',host)).toBeUndefined()
})
it('restores an unknown cancel across a new context generation without automatically resending',async()=>{
 const request=transport(true);await mount();button('取消未派发请求').click();await settle()
 const dialog=document.querySelector('.el-dialog')!,input=dialog.querySelector('textarea')!;input.value='取消测试原因';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 button('确认取消请求',dialog).click();await settle();expect(dialog.textContent).toContain('取消结果尚未确认')
 app!.unmount();app=undefined;host.remove();contextGeneration.value++;await mount()
 expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(1)
 button('核对原命令').click();await settle();expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(1)
 expect(host.textContent).toContain('已取消')
})
it('unsubmitted reason blocks navigation until explicitly abandoned, and revocation removes the dialog',async()=>{
 transport();await mount();button('取消未派发请求').click();await settle()
 const dialog=document.querySelector('.el-dialog')!,input=dialog.querySelector('textarea')!;input.value='未提交';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 await router.push('/other');expect(router.currentRoute.value.path).toBe('/')
 session.value={...auth,capabilities:['ticket_notes:read_internal']};await settle()
 expect(document.querySelector('.el-dialog')?.textContent??'').not.toContain('未提交')
})
