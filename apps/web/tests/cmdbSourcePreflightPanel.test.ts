// @vitest-environment jsdom
import * as findingExport from '../src/utils/cmdbFindingExport'
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

const named=()=>({...structuredClone(response.data),scope:'SOURCE_DICTIONARY_AND_ASSET_NAMES',database_checks:'EXACT_ASSET_NAMES_ONLY'})
it('name check has an explicit GET and retains non-importable boundaries',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(named());mount();Array.from(host.querySelectorAll('button')).find(b=>b.textContent==='检查字典与库内重名')!.click();await settle();expect(request.mock.calls[0]![0]).toContain('/cmdb-name-check');expect(host.textContent).toContain('不预留名称');expect(host.textContent).toContain('负责人引用');expect(document.activeElement).toBe(host.querySelector('[role="status"]'))
})
it('failed name check clears earlier dictionary result and can retry',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(response.data);mount();await state.check();request.mockRejectedValue(new Error('offline'));await state.check(true);expect(state.result).toBeNull();expect(state.error).not.toBeNull();request.mockResolvedValue(named());await state.check(true);expect(state.error).toBeNull();expect(state.result.database_checks).toBe('EXACT_ASSET_NAMES_ONLY')
})
it('cancel name check aborts and returns focus to the initiating button',async()=>{
 let resolve!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));mount();const pending=state.check(true);await settle();await state.cancel();expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true);expect((document.activeElement as HTMLElement).textContent).toBe('检查字典与库内重名');resolve(named());await pending;expect(state.result).toBeNull()
})
it('mode change clears results and forbids new requests',async()=>{
 const request=vi.spyOn(api,'request').mockResolvedValue(named());mount();await state.check(true);session.value={...session.value!,mode:'mock'} as any;await settle();await state.check(true);expect(host.textContent).toBe('');expect(state.result).toBeNull();expect(request).toHaveBeenCalledTimes(1)
})
it('actual synthetic HTTP source and diagnostic render with bound owner scope',async()=>{
 const capture=await import('../../../fixtures/cmdb-name-http-synthetic-20261021.json');vi.spyOn(api,'request').mockResolvedValue(capture.name_check);mount();Object.assign(source,capture.source)
 session.value={...session.value!,user:{...session.value!.user,id:source.created_by},tenant:{id:source.tenant_id,name:'Synthetic'}};await settle();await state.check(true)
 expect(host.textContent).toContain('4 条记录，1 条含错误');expect(host.textContent).toContain('不预留名称');expect(host.textContent).not.toContain('SYNTHETIC_FOREIGN_ONLY')
})
it('exports complete checked result without new GET and clears access after source change',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(response.data),download=vi.spyOn(findingExport,'downloadCmdbFindings').mockImplementation(()=>{});mount();await state.check();state.page=2;state.exportFindings();expect(download).toHaveBeenCalledOnce();expect(download.mock.calls[0]![0].findingCount).toBe(response.data.findings.length);expect(request).toHaveBeenCalledTimes(1);source.version++;state.exportFindings();expect(download).toHaveBeenCalledOnce();expect(state.exportStatus).toBe('')})
it('export error retains the checked result and never implies import or asset writes',async()=>{vi.spyOn(api,'request').mockResolvedValue(named());const download=vi.spyOn(findingExport,'downloadCmdbFindings').mockImplementation(()=>{throw Error('合成下载失败')});mount();await state.check(true);state.exportFindings();expect(state.exportError).toContain('合成下载失败');expect(state.result.database_checks).toBe('EXACT_ASSET_NAMES_ONLY');download.mockImplementation(()=>{});state.exportFindings();expect(state.exportStatus).toContain('不代表已保存或允许入库');expect(state.exportError).toBe('')})
