// @vitest-environment jsdom
// Vue/Element Plus DOM regression using deferred HTTP doubles, not a live authorization test.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ElButton, ElTag, ElAlert, ElMessageBox } from 'element-plus'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import Page from '../src/pages/IdentityRolesPage.vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const tenant = '59000000-0000-4000-8000-000000000001'
const auth: Session = { user:{id:tenant,display_name:'合成管理',role:'padmin'},tenant:{id:tenant,name:'合成租户'},allowed_tenants:[],capabilities:['roles:read','roles:write'],permission_version:1,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic' }
function data(name = '合成旧角色') { return {items:[{id:'59000000-0000-4000-8000-000000000008',tenant_id:tenant,version:1,name,description:null,base_role:'viewer',permissions:[],builtin:false,enabled:true,assigned_members:0}],tenant_id:tenant,permission_catalog:{entries:[],dimensions:[]}} }
function deferred() { let resolve!: (v: unknown) => void; const promise = new Promise(r => {resolve=r}); return {resolve,promise} }
let app: App | undefined, host: HTMLElement
async function settle() { for(let i=0;i<12;i++){await Promise.resolve();await nextTick()} }
async function mount() { host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);for(const c of [ElButton,ElTag,ElAlert])app.component(c.name!,c);app.directive('loading',{});app.mount(host);await settle() }
function button(text:string){const b=[...host.querySelectorAll('button')].find(x=>x.textContent?.trim()===text);if(!b)throw new Error(`Missing ${text}`);return b}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('never requests roles without the selected read capability',async()=>{session.value={...auth,capabilities:[]};const request=vi.spyOn(api,'request').mockResolvedValue(data());await mount();expect(request).not.toHaveBeenCalled();expect(host.textContent).not.toContain('合成旧角色')})
it('clears visible role rows and the editor when read permission is removed',async()=>{vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();session.value={...auth,capabilities:[],permission_version:2};await settle();expect(host.textContent).not.toContain('合成旧角色');expect(host.querySelector('form')).toBeNull()})
it('closes an open role draft when write permission changes',async()=>{vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();session.value={...auth,capabilities:['roles:read'],permission_version:2};await settle();expect(host.querySelector('form')).toBeNull()})
it('does not publish an old response after switching starts',async()=>{const pending=deferred();vi.spyOn(api,'request').mockImplementation(()=>pending.promise as never);await mount();switchingTenant.value=true;pending.resolve(data());await settle();expect(host.textContent).not.toContain('合成旧角色')})
it('does not publish a response after a context epoch change',async()=>{const pending=deferred();vi.spyOn(api,'request').mockImplementation(()=>pending.promise as never);await mount();contextGeneration.value++;pending.resolve(data());await settle();expect(host.textContent).not.toContain('合成旧角色')})
it('a newer explicit refresh wins over an older delayed role list',async()=>{const old=deferred(),fresh=deferred();const request=vi.spyOn(api,'request').mockImplementationOnce(()=>old.promise as never).mockImplementationOnce(()=>fresh.promise as never);await mount();button('刷新角色列表').click();await settle();fresh.resolve(data('合成新角色'));await settle();old.resolve(data());await settle();expect(host.textContent).toContain('合成新角色');expect(host.textContent).not.toContain('合成旧角色');expect(request.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('restored capability requires a fresh explicit read instead of restoring old cached rows',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(data());await mount();session.value={...auth,capabilities:[],permission_version:2};await settle();session.value={...auth,permission_version:3};await settle();expect(host.textContent).not.toContain('合成旧角色');expect(request).toHaveBeenCalledTimes(1);button('刷新角色列表').click();await settle();expect(request).toHaveBeenCalledTimes(2);expect(host.textContent).toContain('合成旧角色')})

it('does not reopen a draft when permission changes while discard confirmation is pending',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle()
 const input=host.querySelector('form input') as HTMLInputElement;input.value='合成未保存';input.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 const confirm=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>confirm.promise as never)
 button('复制为新角色').click();await settle();session.value={...auth,capabilities:['roles:read'],permission_version:2};confirm.resolve('confirm');await settle()
 expect(host.querySelector('form')).toBeNull();expect(host.textContent).not.toContain('合成未保存')
})
