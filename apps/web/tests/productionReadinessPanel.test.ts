// @vitest-environment jsdom
import {createApp,nextTick,type App} from 'vue'
import {ElButton} from 'element-plus'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import Panel from '../src/components/ProductionReadinessPanel.vue'
import {api} from '../src/api/client'
import {session,switchingTenant,contextGeneration} from '../src/stores/session'
vi.mock('../src/components/ApiErrorNotice.vue',()=>({default:{template:'<div />'}}))
let app:App|undefined,host:HTMLElement
beforeEach(()=>{session.value={user:{id:'79000000-0000-4000-8000-000000000001',display_name:'Synthetic',role:'padmin'},tenant:{id:'79000000-0000-4000-8000-000000000002',name:'Synthetic'},allowed_tenants:[],permission_version:1,csrf_token:'SYNTHETIC',capabilities:['configurations:read'],mode:'remote_test',identity_source:'synthetic-unit-only'};switchingTenant.value=false;contextGeneration.value++;host=document.createElement('div');document.body.append(host)})
afterEach(()=>{app?.unmount();app=undefined;host.remove();session.value=null;vi.restoreAllMocks()})
async function settle(){for(let i=0;i<5;i++){await Promise.resolve();await nextTick()}}
it('only performs the explicit diagnostic and aborts synchronously on permission revocation',async()=>{
 let resolve!:(v:unknown)=>void;const request=vi.spyOn(api,'request').mockImplementation(()=>new Promise(r=>{resolve=r}))
 app=createApp(Panel);app.component('ElButton',ElButton);app.mount(host);await settle();expect(request).not.toHaveBeenCalled()
 host.querySelector<HTMLButtonElement>('button')!.click();await settle();expect(request).toHaveBeenCalledOnce();expect(request.mock.calls[0]?.[1]?.method).toBeUndefined()
 session.value={...session.value!,capabilities:[]};await settle();expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
 resolve({production_ready:true});await settle();expect(host.querySelector('details')).toBeNull()
})
