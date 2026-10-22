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
it('does not discard newer role text typed while refresh confirmation waits',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const input=host.querySelector('form input') as HTMLInputElement;input.value='original draft';input.dispatchEvent(new Event('input',{bubbles:true}));await settle();const confirm=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>confirm.promise as never);button('刷新角色列表').click();await settle();input.value='newer draft';input.dispatchEvent(new Event('input',{bubbles:true}));confirm.resolve('confirm');await settle();expect(host.querySelector<HTMLInputElement>('form input')?.value).toBe('newer draft');expect(request).toHaveBeenCalledTimes(1)
})
it('cancelled discard keeps role text and dispatches no list refresh',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const input=host.querySelector('form input') as HTMLInputElement;input.value='keep me';input.dispatchEvent(new Event('input',{bubbles:true}));vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel');button('刷新角色列表').click();await settle();expect(host.querySelector<HTMLInputElement>('form input')?.value).toBe('keep me');expect(request).toHaveBeenCalledTimes(1)
})
it('unchanged confirmed draft can be replaced by the requested role copy',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const input=host.querySelector('form input') as HTMLInputElement;input.value='discard me';input.dispatchEvent(new Event('input',{bubbles:true}));vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as never);button('复制为新角色').click();await settle();expect(host.querySelector<HTMLInputElement>('form input')?.value).toBe('合成旧角色 副本')
})
it('confirmation locks duplicate modal requests and saves before any transport',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const state=(app as any)._instance.setupState;state.form.name='pending';state.editor.dirty.value=true;const wait=deferred();const confirm=vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>wait.promise as never);const first=state.refresh();await settle();expect(host.querySelector<HTMLFieldSetElement>('form fieldset')?.disabled).toBe(true);await state.closeEditor();await state.save();expect(confirm).toHaveBeenCalledTimes(1);expect(request).toHaveBeenCalledTimes(1);wait.resolve('confirm');await first;expect(request).toHaveBeenCalledTimes(2)
})
it('unknown role submission cannot be discarded by route or context confirmation',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const state=(app as any)._instance.setupState;state.form.name='unknown original';request.mockRejectedValueOnce(Error('lost response'));await state.save();expect(state.command.state).toBe('uncertain');const key=state.command.key;const confirm=vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as never);expect(await state.editor.leave()).toBe(false);expect(confirm).not.toHaveBeenCalled();expect(state.command.key).toBe(key);expect(state.form.name).toBe('unknown original');expect(state.opened).toBe(true)
})
it('a stale role row cannot reopen an editor after the list has been replaced',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(data());await mount();const state=(app as any)._instance.setupState,old=state.data.items[0];await state.refresh();await state.edit(old);expect(state.opened).toBe(false)
})
it('a candidate role mutated while discard waits cannot be copied using old approval',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const state=(app as any)._instance.setupState;state.form.name='original draft';state.editor.dirty.value=true;const candidate=state.data.items[0];const wait=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>wait.promise as never);const pending=state.edit(candidate,true);candidate.name='changed source';wait.resolve('confirm');await pending;expect(state.form.name).toBe('original draft');expect(state.selected.id).toBe(candidate.id)
})
it('a clean editor is not replaced if new input arrives during the leave microtask',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(data());await mount();button('查看 / 编辑').click();await settle();const state=(app as any)._instance.setupState;expect(state.editor.dirty.value).toBe(false);const pending=state.refresh();state.form.name='arrived after clean check';state.editor.dirty.value=true;await pending;expect(state.form.name).toBe('arrived after clean check');expect(state.opened).toBe(true)
})
