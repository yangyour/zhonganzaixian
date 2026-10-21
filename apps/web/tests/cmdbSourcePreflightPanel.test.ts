// @vitest-environment jsdom
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import {createApp,reactive,nextTick,type App} from 'vue'
import Panel from '../src/components/CmdbSourcePreflightPanel.vue'
import {api} from '../src/api/client'
import {session,contextGeneration,switchingTenant} from '../src/stores/session'
import {cmdbSource,response,actor} from './fixtures/cmdbSource'
let app:App|undefined,host:HTMLElement,state:any,source:ReturnType<typeof cmdbSource>
async function settle(){for(let i=0;i<8;i++){await Promise.resolve();await nextTick()}}
function mount(){source=reactive(cmdbSource());host=document.createElement('div');document.body.append(host);app=createApp(Panel,{source});app.component('el-button',{template:'<button><slot /></button>'});state=(app.mount(host).$ as any).setupState}
beforeEach(()=>{switchingTenant.value=false;contextGeneration.value=0;session.value={user:{id:actor,display_name:'Synthetic',role:'msp'},tenant:{id:response.data.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:write'],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'project_local'}})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('explicit GET checks reactive source and renders non-importable result',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(response.data);mount();expect(request).not.toHaveBeenCalled();host.querySelector('button')!.click();await settle();expect(state.result.records).toBe(1);expect(request.mock.calls[0]![1]?.method).toBeUndefined();expect(host.textContent).toContain('尚不能入库')})
it('a source version change invalidates an outstanding result immediately',async()=>{let resolve!:(v:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));mount();const pending=state.check();source.version++;resolve(response.data);await pending;expect(state.result).toBeNull();expect(state.busy).toBe(false)})
it('duplicate reads are suppressed and cancellation ignores completion',async()=>{let resolve!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));mount();const pending=state.check();await state.check();expect(request).toHaveBeenCalledTimes(1);state.clear();resolve(response.data);await pending;expect(state.result).toBeNull()})
it('revoked permission clears result and prevents another request',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(response.data);mount();await state.check();session.value={...session.value!,capabilities:[]};await settle();await state.check();expect(state.result).toBeNull();expect(request).toHaveBeenCalledTimes(1);expect(host.textContent).toBe('')})
it('failed read is recoverable and never retains old results',async()=>{const request=vi.spyOn(api,'request').mockRejectedValue(new Error('synthetic'));mount();await state.check();expect(state.result).toBeNull();expect(state.error).not.toBeNull();request.mockResolvedValue(response.data);await state.check();expect(state.error).toBeNull();expect(state.result).not.toBeNull()})
it('cancel returns focus to the remaining read button',async()=>{
 vi.spyOn(api,'request').mockImplementation(()=>new Promise(()=>{}));mount();void state.check();await settle();Array.from(host.querySelectorAll('button')).find(b=>b.textContent==='取消读取校验')!.click();await settle();expect(document.activeElement).toBe(host.querySelector('button'));expect(state.busy).toBe(false)
})
