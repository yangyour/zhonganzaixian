// @vitest-environment jsdom
// Isolated mounted UI tests; no real policy or account is changed.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ElButton, ElAlert, ElMessageBox } from 'element-plus'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import Page from '../src/pages/IdentitySettingsPage.vue'
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }))
const id = '59000000-0000-4000-8000-000000000001'
const auth: Session = { user:{id,display_name:'合成管理',role:'padmin'},tenant:{id,name:'合成租户'},allowed_tenants:[],capabilities:['roles:read','roles:write','users:read'],permission_version:1,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic' }
function policy(version=1) {return {version,minimum_password_length:null,password_history_count:null,failed_attempt_limit:null,lock_minutes:null,idle_minutes:null,force_change_on_reset:null}}
function deferred() {let resolve!: (v:unknown)=>void; const promise=new Promise(r=>{resolve=r});return {resolve,promise}}
let app:App|undefined,host:HTMLElement
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Page);for(const c of [ElButton,ElAlert])app.component(c.name!,c);app.directive('loading',{});app.mount(host);await settle()}
function button(text:string){const b=[...host.querySelectorAll('button')].find(x=>x.textContent?.trim()===text);if(!b)throw new Error(`Missing ${text}`);return b}
function mockReads(){return vi.spyOn(api,'request').mockImplementation(path=>Promise.resolve(path==='/identity/providers'?{unified_identity:{enabled:true}}:policy()) as never)}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('does not read policy without roles:read',async()=>{session.value={...auth,capabilities:[]};const request=mockReads();await mount();expect(request).not.toHaveBeenCalled();expect(host.querySelector('form')).toBeNull()})
it('clears policy and provider state when grants change',async()=>{mockReads();await mount();expect(host.querySelector('form')).not.toBeNull();session.value={...auth,capabilities:[],permission_version:2};await settle();expect(host.querySelector('form')).toBeNull();expect(host.textContent).not.toContain('统一认证状态：已启用')})
it('rejects old policy responses and never starts a provider read after context changes',async()=>{const old=deferred();const request=vi.spyOn(api,'request').mockImplementation(()=>old.promise as never);await mount();contextGeneration.value++;old.resolve(policy());await settle();expect(host.querySelector('form')).toBeNull();expect(request).toHaveBeenCalledTimes(1);expect(request.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('rejects a late provider response after tenant switching begins',async()=>{const old=deferred();vi.spyOn(api,'request').mockResolvedValueOnce(policy()).mockImplementationOnce(()=>old.promise as never);await mount();switchingTenant.value=true;old.resolve({unified_identity:{enabled:true}});await settle();expect(host.querySelector('form')).toBeNull();expect(host.textContent).not.toContain('统一认证状态：已启用')})
it('restored permission needs explicit fresh reading',async()=>{const request=mockReads();await mount();session.value={...auth,capabilities:[],permission_version:2};await settle();session.value={...auth,permission_version:3};await settle();expect(host.querySelector('form')).toBeNull();expect(request).toHaveBeenCalledTimes(2);button('重新读取设置').click();await settle();expect(host.querySelector('form')).not.toBeNull();expect(request).toHaveBeenCalledTimes(4)})
it('does not refresh after scope changes during draft-discard confirmation',async()=>{const request=mockReads();await mount();const input=host.querySelector('input')!;input.value='12';input.dispatchEvent(new Event('input',{bubbles:true}));await settle();const pending=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>pending.promise as never);button('重新读取设置').click();await settle();contextGeneration.value++;pending.resolve('confirm');await settle();expect(request).toHaveBeenCalledTimes(2);expect(host.querySelector('form')).toBeNull()})
it('newer reads supersede older ones even if the transport ignores abort',async()=>{const old=deferred();const request=vi.spyOn(api,'request').mockImplementationOnce(()=>old.promise as never).mockResolvedValueOnce(policy(2)).mockResolvedValueOnce({unified_identity:{enabled:false}});await mount();button('重新读取设置').click();await settle();old.resolve({...policy(),minimum_password_length:88});await settle();expect((host.querySelector('input') as HTMLInputElement).value).toBe('');expect(request).toHaveBeenCalledTimes(3);expect(request.mock.calls[0]![1]!.signal!.aborted).toBe(true)})
it('does not request providers without users:read',async()=>{session.value={...auth,capabilities:['roles:read']};const request=mockReads();await mount();expect(request).toHaveBeenCalledTimes(1);expect(host.querySelector('fieldset')?.disabled).toBe(true)})
it('hides the writable policy when provider readback fails',async()=>{vi.spyOn(api,'request').mockResolvedValueOnce(policy()).mockRejectedValueOnce(new Error('synthetic provider failure'));await mount();expect(host.querySelector('form')).toBeNull();expect(host.textContent).not.toContain('统一认证状态：已启用')})
it('a retained submit event cannot write after permission removal',async()=>{const request=mockReads();await mount();const form=host.querySelector('form')!;session.value={...auth,capabilities:['roles:read'],permission_version:2};form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle();expect(request.mock.calls.every(([,options])=>!options?.method)).toBe(true);expect(host.querySelector('form')).toBeNull()})
it('preserves a later policy value when the earlier discard is approved',async()=>{
 const request=mockReads();await mount();const state=(app as any)._instance.setupState;state.policy.minimum_password_length=12;state.editor.dirty.value=true;const wait=deferred();vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>wait.promise as never);const pending=state.refresh();state.policy.minimum_password_length=18;wait.resolve('confirm');await pending;expect(state.policy.minimum_password_length).toBe(18);expect(request).toHaveBeenCalledTimes(2)
})
it('keeps an explicit null policy without filling defaults after cancelled discard',async()=>{
 const request=mockReads();await mount();const state=(app as any)._instance.setupState;state.editor.dirty.value=true;vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel');await state.refresh();expect(state.policy.minimum_password_length).toBeNull();expect(state.policy.force_change_on_reset).toBeNull();expect(request).toHaveBeenCalledTimes(2)
})
it('an accepted policy response cannot reload over a newer local setting',async()=>{
 const request=mockReads();await mount();const state=(app as any)._instance.setupState;state.policy.minimum_password_length=12;state.editor.dirty.value=true;const wait=deferred();request.mockImplementationOnce(()=>wait.promise as never);const pending=state.save();state.policy.minimum_password_length=18;wait.resolve({...policy(2),minimum_password_length:12});await pending;expect(state.policy.minimum_password_length).toBe(18);expect(state.command.state).toBe('accepted');expect(state.message).toContain('当前输入已保留');expect(request).toHaveBeenCalledTimes(3)
})
