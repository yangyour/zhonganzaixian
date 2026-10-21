// @vitest-environment jsdom
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import { createApp,nextTick,type App } from 'vue'
import Panel from '../src/components/CmdbTypeCatalogPanel.vue'
import { api } from '../src/api/client'
import { session,contextGeneration,switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import fixture from '../../../fixtures/cmdb-type-catalog-controller.json'
let app:App|undefined,host:HTMLElement,state:any
const auth:Session={user:{id:'64000000-0000-4000-8000-000000000001',display_name:'Synthetic',role:'msp'},tenant:{id:fixture.data.tenant_id,name:'Synthetic'},allowed_tenants:[],capabilities:['assets:read'],permission_version:7,csrf_token:'SYNTHETIC_NOT_CREDENTIAL',mode:'remote_test',identity_source:'project_local'}
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.append(host);app=createApp({...Panel,render:()=>null});state=(app.mount(host).$ as any).setupState}
beforeEach(()=>{session.value=structuredClone(auth);contextGeneration.value=0;switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})
it('does not fetch automatically; explicit read has no write method or body',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(fixture.data);mount();await settle();expect(request).not.toHaveBeenCalled();await state.load();expect(state.value.subtypes).toHaveLength(16);expect(request.mock.calls[0]![0]).toBe('/cmdb/type-catalog');expect(request.mock.calls[0]![1]?.method).toBeUndefined();expect(request.mock.calls[0]![1]?.body).toBeUndefined()})
it('filters parent choices and searches attributes without another request',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(fixture.data);mount();await state.load();state.primary='FW';expect(state.selectedTypes).toHaveLength(4);state.search='primary_ip';expect(state.attributes).toHaveLength(1);expect(request).toHaveBeenCalledTimes(1)})
it('revocation immediately clears the dictionary and filters',async()=>{vi.spyOn(api,'request').mockResolvedValue(fixture.data);mount();await state.load();state.primary='FW';session.value={...auth,capabilities:[]};await settle();expect(state.value).toBeNull();expect(state.primary).toBe('');expect(state.expanded).toBe(false)})
it('late old-scope response cannot repopulate display',async()=>{let resolve!:(x:unknown)=>void;vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}));mount();const pending=state.load();contextGeneration.value++;await settle();resolve(fixture.data);await pending;expect(state.value).toBeNull();expect(state.expanded).toBe(false)})
it('invalid read clears previous content instead of retaining it as current',async()=>{const request=vi.spyOn(api,'request').mockResolvedValue(fixture.data);mount();await state.load();request.mockResolvedValue({...fixture.data,tenant_id:'wrong'});await state.load();expect(state.value).toBeNull();expect(state.error.code).toBe('INVALID_CMDB_CATALOG')})
it('unmount aborts the outstanding readonly request',async()=>{const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(()=>{}));mount();void state.load();await settle();app!.unmount();app=undefined;expect(request.mock.calls[0]![1]?.signal?.aborted).toBe(true)})
it('renders the readonly reference and unresolved warning rather than an apply action',async()=>{
 vi.spyOn(api,'request').mockResolvedValue(fixture.data)
 host=document.createElement('div');document.body.append(host)
 app=createApp(Panel);app.component('el-button',{template:'<button type="button"><slot /></button>'})
 state=(app.mount(host).$ as any).setupState
 await state.load();await settle()
 expect(host.textContent).toContain('95 属性')
 expect(host.textContent).toContain('待确认，不自动套用')
 expect(host.textContent).toContain('未知不是正常')
 expect(host.querySelectorAll('select option')).toHaveLength(10)
 expect(Array.from(host.querySelectorAll('button')).some(b=>/发布|应用|导入/.test(b.textContent??''))).toBe(false)
})
