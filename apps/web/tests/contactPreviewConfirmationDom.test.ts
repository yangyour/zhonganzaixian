// @vitest-environment jsdom
// Real Vue + Element Plus textarea/button DOM. API stubbed; no browser/visual claim.
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {createApp,nextTick,type App} from 'vue'
const mocks=vi.hoisted(()=>({confirm:vi.fn(),leave:null as null|(()=>Promise<boolean>),guard:null as null|(()=>Promise<boolean>)}))
vi.mock('vue-router',()=>({onBeforeRouteLeave:(cb:()=>Promise<boolean>)=>{mocks.leave=cb}}))
vi.mock('element-plus',async original=>({...await original<typeof import('element-plus')>(),ElMessageBox:{confirm:mocks.confirm}}))
vi.mock('../src/utils/contextTransitionGuard',()=>({contextTransitionGuards:{register:(cb:()=>Promise<boolean>)=>{mocks.guard=cb;return()=>{}}}}))
import {ElAlert,ElButton,ElInput,ElEmpty} from 'element-plus'
import Page from '../src/pages/ContactImportPreviewPage.vue'
import {api} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
let app:App|undefined,host:HTMLElement
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);for(const [name,c] of Object.entries({ElAlert,ElButton,ElInput,ElEmpty}))app.component(name,c);app.mount(host)}
function input(value:string){const el=host.querySelector<HTMLTextAreaElement>('textarea[aria-label="联系人预览原始JSON"]')!;el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}))}
function button(label:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!}
const deferred=()=>{let resolve!:(v:string)=>void;const promise=new Promise<string>(r=>resolve=r);return{promise,resolve}}
beforeEach(()=>{mocks.confirm.mockReset().mockResolvedValue('confirm');session.value={user:{id:'68000000-0000-4000-8000-000000000001',role:'padmin',display_name:'合成'},tenant:{id:'68000000-0000-4000-8000-000000000002',name:'合成'},allowed_tenants:[],permission_version:1,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',capabilities:['contacts:read','contacts:write'],mode:'remote_test',identity_source:'synthetic-unit-only'};contextGeneration.value++;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('native clear button preserves newly typed textarea while confirmation waits',async()=>{const request=vi.spyOn(api,'request');mount();await settle();input('{"rows":[]}');await settle();const wait=deferred();mocks.confirm.mockReturnValueOnce(wait.promise);button('清空内容').click();input('较新原始输入');wait.resolve('confirm');await settle();expect(host.querySelector('textarea')!.value).toBe('较新原始输入');expect(request).not.toHaveBeenCalled()})
it.each(['route','context'] as const)('native textarea edit blocks stale %s departure approval',async kind=>{mount();await settle();input('原输入');await settle();const wait=deferred();mocks.confirm.mockReturnValueOnce(wait.promise);const pending=kind==='route'?mocks.leave!():mocks.guard!();input('新输入');wait.resolve('confirm');expect(await pending).toBe(false);await settle();expect(host.querySelector('textarea')!.value).toBe('新输入')})
it('native confirmed clear removes unchanged input without an API write',async()=>{const request=vi.spyOn(api,'request');mount();await settle();input('原输入');await settle();button('清空内容').click();await settle();expect(host.querySelector('textarea')!.value).toBe('');expect(request).not.toHaveBeenCalled()})
it('native textarea clears immediately on account revision change',async()=>{mount();await settle();input('旧账号输入');session.value={...session.value!,account_version:2};await settle();expect(host.querySelector('textarea')!.value).toBe('')})
