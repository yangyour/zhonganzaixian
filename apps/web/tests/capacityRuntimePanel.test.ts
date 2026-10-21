// @vitest-environment jsdom
import { createApp, nextTick, reactive, type App } from 'vue'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import Panel from '../src/components/CapacityRuntimeInputsPanel.vue'
import Page from '../src/pages/CapacityEstimatePage.vue'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { capacityRuntimeFields } from '../src/utils/capacityRuntimeInputs'
const id=(n:number)=>'74000000-0000-4000-8000-'+String(n).padStart(12,'0')
const auth:Session={user:{id:id(2),role:'padmin',display_name:'synthetic'},tenant:{id:id(1),name:'synthetic'},permission_version:1,allowed_tenants:[],csrf_token:'SYNTHETIC',capabilities:['capacity:estimate'],mode:'remote_test',identity_source:'synthetic-unit-only'}
function fixture(){return {tenant_id:id(1),actor_id:id(2),permission_version:1,checked_at:'2026-10-15T00:00:00Z',scope:'CURRENT_TENANT_CAPACITY_INPUTS',source:'postgresql',customer_confirmation_status:'UNVERIFIED',production_approved:false,measurement_verified:false,items:capacityRuntimeFields.map(f=>({configuration_key:f.key,status:'UNCONFIGURED',value:null,input_value:null,unit:f.unit,definition_hash:'a'.repeat(64),publication_id:null,publication_version:null,expires_at:null}))}}
let app:App|undefined,host:HTMLElement,state:any,apply:ReturnType<typeof vi.fn>
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0;apply=vi.fn()})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
function mount(component=Panel,props:Record<string,unknown>={}){host=document.createElement('div');document.body.append(host);app=createApp({...component,render:()=>null},{onApply:apply,...props});const vm=app.mount(host);state=(vm.$ as any).setupState}
it('imports only after explicit action and identical session refresh keeps fetched inputs',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(fixture());mount();expect(request).not.toHaveBeenCalled();await state.load();expect(apply).not.toHaveBeenCalled();session.value={...structuredClone(auth),csrf_token:'new'};await settle();expect(state.snapshot).not.toBeNull();state.apply();expect(apply).toHaveBeenCalledOnce();expect(request).toHaveBeenCalledOnce()})
it('failed reload clears old candidates and cannot apply them',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(fixture());mount();await state.load();request.mockRejectedValueOnce(new ApiError(503,'UPSTREAM_UNAVAILABLE','synthetic','unit'));await state.load();expect(state.snapshot).toBeNull();state.apply();expect(apply).not.toHaveBeenCalled()})
it('a late response after changing tenant never applies to the new tenant',async()=>{let finish!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{finish=r}));mount();const pending=state.load();session.value={...auth,tenant:{id:id(9),name:'other'}};await settle();finish(fixture());await pending;expect(state.snapshot).toBeNull();state.apply();expect(apply).not.toHaveBeenCalled()})
it('capacity page retains explicit inputs on identical session refresh and clears on actual identity change',async()=>{mount(Page as typeof Panel);state.draft.inputs.hosts='42';state.importedAt='2026-10-15T00:00:00Z';session.value={...structuredClone(auth),csrf_token:'new'};await settle();expect(state.draft.inputs.hosts).toBe('42');expect(state.importedAt).not.toBeNull();session.value={...auth,user:{...auth.user,id:id(3)}};await settle();expect(state.draft.inputs.hosts).toBe('');expect(state.importedAt).toBeNull()})
it('over-age candidates require a fresh read and never apply silently',async()=>{vi.spyOn(api,'request').mockResolvedValue(fixture());const clock=vi.spyOn(performance,'now').mockReturnValue(1000);mount();await state.load();clock.mockReturnValue(92000);state.apply();expect(apply).not.toHaveBeenCalled()})
it('network delay counts toward freshness instead of extending a returned policy lifetime',async()=>{let finish!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{finish=r}));const clock=vi.spyOn(performance,'now').mockReturnValue(1000);mount();const pending=state.load();clock.mockReturnValue(92000);finish(fixture());await pending;state.apply();expect(apply).not.toHaveBeenCalled()})
it('previews clearing existing values without touching them or applying on load',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(fixture())
 const current={inputs:{bytes_per_value:'128',hosts:'42'},buffer:{bytes_per_value:'256'}}
 mount(Panel,{current});await state.load()
 expect(state.changedCount).toBe(2);expect(state.clearedCount).toBe(2)
 expect(current.inputs.bytes_per_value).toBe('128');expect(apply).not.toHaveBeenCalled()
 session.value={...auth,tenant:{id:id(9),name:'other'}};await settle()
 expect(state.changes).toEqual([]);state.apply();expect(apply).not.toHaveBeenCalled()
})
it('updates the preview when the user changes the draft after reading a configuration',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(fixture())
 const current=reactive({inputs:{bytes_per_value:'128'},buffer:{bytes_per_value:''}})
 mount(Panel,{current});await state.load()
 expect(state.clearedCount).toBe(1)
 current.inputs.bytes_per_value='';current.buffer.bytes_per_value='256';await settle()
 expect(state.changes.find((r:any)=>r.key==='inputs.bytes_per_value').changed).toBe(false)
 expect(state.changes.find((r:any)=>r.key==='buffer.bytes_per_value').before).toBe('256')
 expect(state.clearedCount).toBe(1);expect(apply).not.toHaveBeenCalled()
})
it('renders the replacement warning and both byte inputs before the explicit import action',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(fixture())
 host=document.createElement('div');document.body.append(host)
 app=createApp(Panel,{current:{inputs:{bytes_per_value:'128'},buffer:{bytes_per_value:'256'}},onApply:apply})
 app.config.warnHandler=()=>{}
 const vm=app.mount(host);state=(vm.$ as any).setupState
 await state.load();await settle()
 const preview=host.querySelector('[aria-label="运行参数替换预览"]')!
 expect(preview.textContent).toContain('2 项现有输入将清空')
 expect(preview.querySelectorAll('tbody tr')).toHaveLength(8)
 expect(preview.textContent).toContain('128');expect(preview.textContent).toContain('256')
 const action=Array.from(host.querySelectorAll('el-button')).find(b=>b.textContent?.includes('将本次参数填入估算'))!
 expect(preview.compareDocumentPosition(action)&Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0)
 expect(apply).not.toHaveBeenCalled()
})


it('account-version change clears capacity inputs even if permission version is unchanged',async()=>{mount(Page as typeof Panel);state.draft.inputs.hosts='0';session.value={...structuredClone(auth),account_version:2};await settle();expect(state.draft.inputs.hosts).toBe('');expect(state.importedAt).toBeNull()})
it('account-version change aborts runtime-input read and excludes the late snapshot',async()=>{let finish!:(v:unknown)=>void;let signal:AbortSignal|undefined;vi.spyOn(api,'request').mockImplementation((_p,o)=>{signal=o?.signal;return new Promise(r=>finish=r)});mount();const pending=state.load();session.value={...structuredClone(auth),account_version:2};await settle();expect(signal?.aborted).toBe(true);finish(fixture());await pending;expect(state.snapshot).toBeNull();state.apply();expect(apply).not.toHaveBeenCalled()})
