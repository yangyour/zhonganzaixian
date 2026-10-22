// @vitest-environment jsdom
// Isolated mounted UI tests; no real policy or account is changed.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ElButton, ElAlert, ElMessageBox } from 'element-plus'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import Page from '../src/pages/AccountPage.vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const id = '59000000-0000-4000-8000-000000000001'
const auth: Session = { user:{id,display_name:'合成管理',role:'padmin'},tenant:{id,name:'合成租户'},allowed_tenants:[],capabilities:['roles:read','roles:write','users:read'],permission_version:1,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic' }
function policy(version=1) {return {version,minimum_password_length:null,password_history_count:null,failed_attempt_limit:null,lock_minutes:null,idle_minutes:null,force_change_on_reset:null}}
function deferred() {let resolve!: (v:unknown)=>void; const promise=new Promise(r=>{resolve=r});return {resolve,promise}}
let app:App|undefined,host:HTMLElement
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);for(const c of [ElButton,ElAlert])app.component(c.name!,c);app.directive('loading',{});app.mount(host);await settle()}
function button(text:string){const b=[...host.querySelectorAll('button')].find(x=>x.textContent?.trim()===text);if(!b)throw new Error(`Missing ${text}`);return b}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
function account(name='合成本人', actor=id) {return {id:actor,display_name:name,active:true,username:'synthetic-user',must_change_password:null,last_login_at:null,version:1,password_policy:policy()}}
it('does not read without a current session',async()=>{session.value=null;const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();expect(request).not.toHaveBeenCalled()})
it('clears account details and password inputs synchronously after logout',async()=>{vi.spyOn(api,'request').mockResolvedValue(account());await mount();const input=host.querySelector('input')!;input.value='SYNTHETIC-ONLY';input.dispatchEvent(new Event('input',{bubbles:true}));session.value=null;await settle();expect(host.querySelector('form')).toBeNull();expect(host.textContent).not.toContain('合成本人')})
it('discards an old account response after the actor changes',async()=>{const old=deferred();const request=vi.spyOn(api,'request').mockImplementation(()=>old.promise as never);await mount();session.value={...auth,user:{...auth.user,id:'59000000-0000-4000-8000-000000000002'}};old.resolve(account());await settle();expect(host.textContent).not.toContain('合成本人');expect(request.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('rejects account data whose id is not the current actor',async()=>{vi.spyOn(api,'request').mockResolvedValue(account('错误账号','59000000-0000-4000-8000-000000000002'));await mount();expect(host.textContent).not.toContain('错误账号');expect(host.querySelector('form')).toBeNull()})
it('clears account details when context changes and requires explicit reread',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();contextGeneration.value++;await settle();expect(host.querySelector('form')).toBeNull();expect(request).toHaveBeenCalledTimes(1);button('刷新账号信息').click();await settle();expect(host.querySelector('form')).not.toBeNull();expect(request).toHaveBeenCalledTimes(2)})
it('does not read while a tenant transition is pending',async()=>{switchingTenant.value=true;const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();expect(request).not.toHaveBeenCalled()})
it('does not refresh a new scope after an old discard confirmation',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();const input=host.querySelector('input')!;input.value='SYNTHETIC-ONLY';input.dispatchEvent(new Event('input',{bubbles:true}));await settle();const pending=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>pending.promise as never);button('刷新账号信息').click();await settle();contextGeneration.value++;pending.resolve('confirm');await settle();expect(request).toHaveBeenCalledTimes(1);expect(host.querySelector('form')).toBeNull()})
it('retained submit events cannot change a password after logout',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();const form=host.querySelector('form')!;session.value=null;form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();expect(request.mock.calls.every(([,options])=>!options?.method)).toBe(true)})
it('a newer read wins when an older transport ignores cancellation',async()=>{const old=deferred();const request=vi.spyOn(api,'request').mockImplementationOnce(()=>old.promise as never).mockResolvedValueOnce(account('新读取'));await mount();button('刷新账号信息').click();await settle();old.resolve(account('过时读取'));await settle();expect(host.textContent).toContain('新读取');expect(host.textContent).not.toContain('过时读取');expect(request.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('failed rereading hides the previous password form',async()=>{vi.spyOn(api,'request').mockResolvedValueOnce(account()).mockRejectedValueOnce(new Error('synthetic read failure'));await mount();button('刷新账号信息').click();await settle();expect(host.querySelector('form')).toBeNull();expect(host.textContent).not.toContain('合成本人')})
it('retains newer synthetic password input while discard confirmation waits',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();const state=(app as any)._instance.setupState;state.current='synthetic original';state.editor.dirty.value=true;const wait=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>wait.promise as never);const pending=state.refresh();state.current='synthetic newer';wait.resolve('confirm');await pending;expect(state.current).toBe('synthetic newer');expect(request).toHaveBeenCalledTimes(1)
})
it('a clean account refresh cannot erase input entered during its leave microtask',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();const state=(app as any)._instance.setupState;const pending=state.refresh();state.replacement='synthetic new input';state.editor.dirty.value=true;await pending;expect(state.replacement).toBe('synthetic new input');expect(request).toHaveBeenCalledTimes(1)
})
it('unknown password write retains original command and blocks voluntary discard',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(account());await mount();const state=(app as any)._instance.setupState;state.current='synthetic old';state.replacement=state.repeated='synthetic new';state.editor.dirty.value=true;request.mockRejectedValueOnce(Error('lost'));await state.save();const key=state.command.key;expect(state.command.state).toBe('uncertain');const confirm=vi.spyOn(ElMessageBox,'confirm');expect(await state.editor.leave()).toBe(false);expect(confirm).not.toHaveBeenCalled();expect(state.command.key).toBe(key);expect(state.replacement).toBe('synthetic new')
})
