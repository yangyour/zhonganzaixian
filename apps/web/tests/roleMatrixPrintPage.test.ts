// @vitest-environment jsdom
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import {createApp,nextTick,type App} from 'vue'
import {ElButton,ElTag,ElAlert} from 'element-plus'
import {api} from '../src/api/client'
import {session,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
import Page from '../src/pages/IdentityRolesPage.vue'
import {matrixFixture,matrixTenant} from './roleMatrixFixtures'
vi.mock('vue-router',()=>({onBeforeRouteLeave:vi.fn()}))
const auth:Session={user:{id:'79000000-0000-4000-8000-000000000002',display_name:'Synthetic',role:'padmin'},tenant:{id:matrixTenant,name:'Synthetic tenant'},allowed_tenants:[],capabilities:['roles:read','roles:write'],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only'}
let app:App|undefined,host:HTMLElement
async function settle(){for(let i=0;i<16;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp(Page);for(const component of [ElButton,ElTag,ElAlert])app.component(component.name!,component);app.mount(host);await settle()}
function button(label:string){return [...host.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;Object.defineProperty(URL,'createObjectURL',{configurable:true,value:vi.fn(()=> 'blob:synthetic-matrix')});Object.defineProperty(URL,'revokeObjectURL',{configurable:true,value:vi.fn()})})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('explicit print re-reads saved roles and downloads only a locally generated inert file',async()=>{
 const request=vi.spyOn(api,'request').mockImplementation(async()=>matrixFixture()),click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});await mount();expect(request).toHaveBeenCalledOnce()
 button('下载打印权限矩阵').click();await vi.waitFor(()=>expect(click).toHaveBeenCalledOnce());expect(request).toHaveBeenCalledTimes(2);expect(request.mock.calls.every(([path,options])=>path==='/identity/roles'&&!options?.method)).toBe(true);expect(host.textContent).toContain('4 个已保存角色');expect(host.textContent).toContain('待双方核对签字')
})
it('permission loss during export aborts the read and prevents any download',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValueOnce(matrixFixture()),click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});await mount();let finish!:(v:unknown)=>void;request.mockImplementationOnce(()=>new Promise(r=>{finish=r}));button('下载打印权限矩阵').click();await settle();session.value={...auth,capabilities:[]};await settle();expect(request.mock.calls[1][1]?.signal?.aborted).toBe(true);finish(matrixFixture());await settle();expect(click).not.toHaveBeenCalled();expect(URL.createObjectURL).not.toHaveBeenCalled()
})
it('unsaved editor input blocks export rather than printing a guessed role',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(matrixFixture());await mount();button('新建角色').click();await settle();const input=host.querySelector<HTMLInputElement>('form input')!;input.value='unsaved candidate';input.dispatchEvent(new Event('input',{bubbles:true}));await settle();expect(button('下载打印权限矩阵').disabled).toBe(true);button('下载打印权限矩阵').click();await settle();expect(request).toHaveBeenCalledOnce()
})
it('a fresh 201-row response is rejected without downloading a partial matrix',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValueOnce(matrixFixture()),click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});await mount();const tooMany=matrixFixture();while(tooMany.items.length<201)tooMany.items.push({...tooMany.items[0]!});request.mockResolvedValueOnce(tooMany);button('下载打印权限矩阵').click();await settle();expect(click).not.toHaveBeenCalled();expect(host.textContent).toContain('截断哨兵')
})
