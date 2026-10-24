// @vitest-environment jsdom
import {createApp,h,nextTick,type App} from 'vue'
import {createRouter,createMemoryHistory,RouterView,type Router} from 'vue-router'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import Page from '../src/pages/ReportPublicationsPage.vue'
import {api,ApiError} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
import {ElMessageBox} from 'element-plus'
vi.mock('../src/components/ApiErrorNotice.vue',()=>({default:{template:'<div />'}}))
const actor='79000000-0000-4000-8000-000000000001',tenant='79000000-0000-4000-8000-000000000002',source='79000000-0000-4000-8000-000000000003'
const auth:Session={user:{id:actor,display_name:'Synthetic',role:'padmin'},tenant:{id:tenant,name:'Synthetic'},allowed_tenants:[],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only',capabilities:['dashboard:read','inspection_quarterly_reports:write','configurations:write']}
let app:App|undefined,host:HTMLElement,router:Router
async function settle(){for(let i=0;i<14;i++){await Promise.resolve();await nextTick()}}
async function mount(query:Record<string,string>={}){host=document.createElement('div');document.body.append(host);router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Page},{path:'/elsewhere',component:{template:'<p>Other</p>'}},{path:'/inspection-quarterly-reports',component:{template:'<p>Sources</p>'}}]});await router.push({path:'/',query:{source_id:source,source_sha256:'a'.repeat(64),...query}});await router.isReady();app=createApp({render:()=>h(RouterView)});app.use(router);app.mount(host);await settle()}
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

const parent='79000000-0000-4000-8000-000000000004',child='79000000-0000-4000-8000-000000000005'
function report(id=parent,revision=1,version=6,phase='RETURNED'){
 return {label:'SYNTHETIC 报告',source_id:source,source_document_sha256:'a'.repeat(64),
  customer_acceptance_confirmed:false,external_dispatch_enabled:false,
  state:{version,phase,reason:revision===1?'待更正':'修正来源',recipients:[],
   document:{id,tenant_id:tenant,family_id:parent,parent_id:revision===1?null:parent,revision,
    document_sha256:'b'.repeat(64),captured_at:'2026-10-24T00:00:00Z',incomplete_reasons:['TEMPLATE_APPROVAL_UNVERIFIED']},
   html:null,pdf:null}}
}
it('loads the selected parent but never creates a revision until explicit confirmation',async()=>{
 const request=vi.spyOn(api,'request').mockImplementation(async(path,options)=>{
  if(options?.method==='POST')return report(child,2,1,'DRAFT') as never
  if(path===`/report-publications/${parent}`)return report() as never
  if(path===`/report-publications/${child}`)return report(child,2,1,'DRAFT') as never
  return {items:[],next:null,external_dispatch_enabled:false} as never
 })
 await mount({revision_id:parent,revision_version:'6'})
 expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(0)
 expect(button('由已选冻结来源创建报告')).toBeUndefined()
 const input=host.querySelector('input')!
 input.value='修正来源';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 button('创建新修订').click();await vi.waitFor(()=>expect(router.currentRoute.value.query).toEqual({publication_id:child}));await settle()
 const writes=request.mock.calls.filter(([,options])=>options?.method==='POST')
 expect(writes).toHaveLength(1)
 expect(writes[0][0]).toBe(`/report-publications/${parent}/revisions`)
 expect(writes[0][1]?.body).toEqual({expected_version:6,source_id:source,expected_source_sha256:'a'.repeat(64),reason:'修正来源'})
 expect(router.currentRoute.value.query).toEqual({publication_id:child})
 expect(host.textContent).toContain('第 2 次修订')
 expect(host.textContent).toContain('查看上一修订')
})
it('does not replace the chosen version with a newer parent response',async()=>{
 const request=vi.spyOn(api,'request').mockImplementation(async(path)=>path===`/report-publications/${parent}`
  ?report(parent,1,7) as never:{items:[],next:null,external_dispatch_enabled:false} as never)
 await mount({revision_id:parent,revision_version:'6'})
 expect(host.textContent).toContain('原报告状态已变化')
 expect(button('创建新修订')).toBeUndefined()
 expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(0)
})
it('invalid revision navigation cannot fall back to creating a new report family',async()=>{
 vi.spyOn(api,'request').mockResolvedValue({items:[],next:null,external_dispatch_enabled:false})
 await mount({revision_id:'invalid',revision_version:'6'})
 expect(host.textContent).toContain('修订入口已失效')
 expect(button('由已选冻结来源创建报告')).toBeUndefined()
})
it('keeps an unsubmitted correction when leaving is cancelled or text changes during confirmation',async()=>{
 vi.spyOn(api,'request').mockImplementation(async(path)=>path===`/report-publications/${parent}`
  ?report() as never:{items:[],next:null,external_dispatch_enabled:false} as never)
 await mount({revision_id:parent,revision_version:'6'})
 const input=host.querySelector('input')!
 input.value='尚未提交';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel')
 await router.push('/elsewhere');expect(router.currentRoute.value.path).toBe('/')
 expect(input.value).toBe('尚未提交')
 let approve!:(value:Awaited<ReturnType<typeof ElMessageBox.confirm>>)=>void
 confirm.mockImplementation(()=>new Promise(resolve=>{approve=resolve}) as ReturnType<typeof ElMessageBox.confirm>)
 const navigation=router.push('/elsewhere');await settle()
 input.value='确认期间的新内容';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 approve('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>);await navigation
 expect(router.currentRoute.value.path).toBe('/')
 expect(input.value).toBe('确认期间的新内容')
 const unload=new Event('beforeunload',{cancelable:true});window.dispatchEvent(unload)
 expect(unload.defaultPrevented).toBe(true)
})
