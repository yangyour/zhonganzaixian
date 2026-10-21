// @vitest-environment jsdom
// Real Vue + Element Plus DOM; synthetic API, not browser/visual acceptance.
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {createApp,nextTick,type App} from 'vue'
const mocks=vi.hoisted(()=>({confirm:vi.fn()}))
vi.mock('vue-router',()=>({onBeforeRouteLeave:()=>{}}))
vi.mock('element-plus',async original=>({...await original<typeof import('element-plus')>(),ElMessageBox:{confirm:mocks.confirm}}))
vi.mock('../src/utils/contextTransitionGuard',()=>({contextTransitionGuards:{register:()=>()=>{}}}))
import ElementPlus from 'element-plus'
import Page from '../src/pages/CustomerContactsPage.vue'
import {api} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
let app:App|undefined,host:HTMLElement,vm:any
const tenant='68000000-0000-4000-8000-000000000002'
const page=()=>({tenant_id:tenant,items:[],has_more:false,next_before_created_at:null,next_before_id:null,view:'CURRENT',source:'postgresql',scope:'CURRENT_TENANT',page_size_limit:100})
const deferred=<T,>()=>{let resolve!:(v:T)=>void;const promise=new Promise<T>(r=>resolve=r);return{promise,resolve}}
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);app.use(ElementPlus);vm=app.mount(host)}
function input(label:string,value:string){const el=host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}))}
function button(label:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!}
beforeEach(()=>{mocks.confirm.mockReset().mockResolvedValue('confirm');session.value={user:{id:'68000000-0000-4000-8000-000000000001',role:'padmin',display_name:'合成'},tenant:{id:tenant,name:'合成'},allowed_tenants:[],permission_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',capabilities:['contacts:read','contacts:write'],mode:'remote_test',identity_source:'synthetic-unit-only'};contextGeneration.value++;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('initial read cancellation focuses status and retry restores the same request without writes',async()=>{const wait=deferred<unknown>(),request=vi.spyOn(api,'request').mockReturnValueOnce(wait.promise).mockResolvedValue(page());mount();await settle();button('取消联系人读取').click();await settle();expect(document.activeElement?.getAttribute('role')).toBe('status');expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true);wait.resolve(page());await settle();expect(host.querySelector('.contact-table')).toBeNull();expect(host.textContent).toContain('数量未知');expect(host.textContent).not.toContain('当前筛选条件下暂无联系人记录');button('重试原联系人读取').click();await settle();expect(request.mock.calls[1]![0]).toBe(request.mock.calls[0]![0]);expect(host.querySelector('.contact-table')).not.toBeNull();expect(request.mock.calls.some(([,o])=>o?.method)).toBe(false)})
it('cancel and original retry preserve native editor input and ignore unsubmitted newer filters',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(page());mount();await settle();await vm.prepareCreate();await settle();input('联系人姓名','保留编辑姓名');input('联系人姓名查询','原查询');const wait=deferred<unknown>();request.mockReturnValueOnce(wait.promise);const pending=vm.browse();await settle();const original=request.mock.calls.at(-1)![0];button('取消联系人读取').click();await settle();input('联系人姓名查询','尚未提交的新查询');wait.resolve(page());expect(await pending).toBe(false);button('重试原联系人读取').click();await settle();expect(request.mock.calls.at(-1)![0]).toBe(original);expect(host.textContent).toContain('输入的筛选尚未应用');expect(host.querySelector<HTMLInputElement>('[aria-label="联系人姓名"]')!.value).toBe('保留编辑姓名');expect(host.querySelector<HTMLInputElement>('[aria-label="联系人姓名查询"]')!.value).toBe('尚未提交的新查询');expect(request.mock.calls.some(([,o])=>o?.method)).toBe(false)})
it('new editor preparation clears canceled-read notice and retry target',async()=>{const wait=deferred<unknown>();vi.spyOn(api,'request').mockReturnValueOnce(wait.promise);mount();await settle();button('取消联系人读取').click();await settle();expect(host.textContent).toContain('已取消读取');await vm.prepareCreate();await settle();expect(host.textContent).not.toContain('已取消读取');expect(button('重试原联系人读取')).toBeUndefined();wait.resolve(page())})
it('account revision removes old recovery target and cancellation status',async()=>{const request=vi.spyOn(api,'request').mockRejectedValueOnce(new TypeError('lost')).mockResolvedValue(page());mount();await settle();expect(button('重试原联系人读取')).toBeDefined();session.value={...session.value!,account_version:2};await settle();expect(button('重试原联系人读取')).toBeUndefined();expect(request).toHaveBeenCalledTimes(2)})
