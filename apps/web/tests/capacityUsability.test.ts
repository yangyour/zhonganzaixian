// @vitest-environment jsdom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElButton, ElForm, ElFormItem, ElInput, ElCheckbox, ElMessageBox } from 'element-plus'
import Page from '../src/pages/CapacityEstimatePage.vue'
import Growth from '../src/components/CapacityGrowthPanel.vue'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
vi.mock('../src/components/CapacityScenarioPanel.vue',()=>({default:{render:()=>null}}))
vi.mock('../src/components/CapacityRuntimeInputsPanel.vue',()=>({default:{render:()=>null}}))
const route = vi.hoisted(()=>({leave:null as null|(()=>Promise<boolean>)}))
vi.mock('vue-router',()=>({onBeforeRouteLeave:(guard:()=>Promise<boolean>)=>{route.leave=guard}}))
const auth:Session={user:{id:'74000000-0000-4000-8000-000000000002',role:'padmin',display_name:'synthetic'},tenant:{id:'74000000-0000-4000-8000-000000000001',name:'synthetic'},permission_version:1,account_version:1,allowed_tenants:[],csrf_token:'SYNTHETIC',capabilities:['capacity:estimate'],mode:'remote_test',identity_source:'synthetic-unit-only'}
let app:App|undefined,host:HTMLElement,state:any
async function settle(){for(let i=0;i<5;i++){await Promise.resolve();await nextTick()}}
function mount(component:any=Page,props:Record<string,unknown>={}){host=document.createElement('div');document.body.append(host);app=createApp(component,props);app.config.warnHandler=()=>{};for(const [n,c] of Object.entries({ElButton,ElForm,ElFormItem,ElInput,ElCheckbox}))app.component(n,c);state=(app.mount(host).$ as any).setupState}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0;route.leave=null})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('blank page can leave without confirmation, including no default coefficients',async()=>{const confirm=vi.spyOn(ElMessageBox,'confirm');mount();expect(await route.leave!()).toBe(true);expect(confirm).not.toHaveBeenCalled();expect(host.querySelector<HTMLInputElement>('input[aria-label="主机数"]')?.value).toBe('')})
it('legal zero is a dirty input and cancelling discard preserves it',async()=>{vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel');mount();state.draft.inputs.hosts='0';await settle();expect(await route.leave!()).toBe(false);await state.resetDraft();expect(state.draft.inputs.hosts).toBe('0');expect(state.discarding).toBe(false)})
it('confirmed reset clears the draft and returns focus to first model input',async()=>{vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>);mount();state.draft.inputs.hosts='42';await settle();await state.resetDraft();expect(state.draft.inputs.hosts).toBe('');expect(document.activeElement).toBe(host.querySelector('input[aria-label="主机数"]'))})
it('editing while confirmation is pending invalidates the old decision',async()=>{let accept!:(v:any)=>void;vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>new Promise(r=>accept=r));mount();state.draft.inputs.hosts='42';const pending=state.resetDraft();state.draft.inputs.hosts='43';accept('confirm');await pending;expect(state.draft.inputs.hosts).toBe('43')})
it('context change while confirming cannot reset the new account draft',async()=>{let accept!:(v:any)=>void;vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>new Promise(r=>accept=r));mount();state.draft.inputs.hosts='42';const pending=state.resetDraft();session.value={...structuredClone(auth),account_version:2};state.draft.inputs.hosts='7';accept('confirm');await pending;expect(state.draft.inputs.hosts).toBe('7')})
it('cancelled readonly calculation keeps input and focus, ignores late failure',async()=>{let reject!:(e:unknown)=>void,signal:AbortSignal|undefined;const request=vi.spyOn(api,'request').mockImplementation((_p,o)=>{signal=o?.signal;return new Promise((_r,j)=>reject=j)});mount();state.draft.inputs.hosts='0';const pending=state.calculate();await settle();expect(host.textContent).toContain('取消本次估算');await state.cancelCalculation();expect(signal?.aborted).toBe(true);expect(state.draft.inputs.hosts).toBe('0');expect(document.activeElement).toBe(host.querySelector('input[aria-label="主机数"]'));reject(new TypeError('late failure'));await pending;expect(state.state.error).toBeNull();expect(request).toHaveBeenCalledOnce()})
it('reload guard is registered for unsaved inputs and removed on unmount',()=>{mount();state.draft.inputs.hosts='0';const event=new Event('beforeunload',{cancelable:true});window.dispatchEvent(event);expect(event.defaultPrevented).toBe(true);app!.unmount();app=undefined;const after=new Event('beforeunload',{cancelable:true});window.dispatchEvent(after);expect(after.defaultPrevented).toBe(false)})
it('growth host series clears on account-version change without permission-version change',async()=>{mount(Growth,{calculation:{inputs:null,model:null,proxy_buffer:null}});state.hosts='0,1';session.value={...structuredClone(auth),account_version:2};await settle();expect(state.hosts).toBe('');expect(state.state.points).toBeNull()})
