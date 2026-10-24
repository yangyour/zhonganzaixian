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
const unavailable = () => ({ tenant_id: session.value!.tenant.id, actor_id: session.value!.user.id, permission_version: session.value!.permission_version,
  runtime_mode: 'remote_test', runtime_database_restricted: false, database_observed_at: null, database_version: null,
  production_database_kind: null, production_isolation_strategy: null, production_ready: false, activation_performed: false,
  checks: [
    { id: 'DATABASE_OBSERVATION', status: 'NOT_READY', code: 'DATABASE_OBSERVATION_UNAVAILABLE' },
    { id: 'SECURE_SESSION_SETTINGS', status: 'BLOCKED', code: 'SECURE_SESSION_NOT_CONFIGURED' },
    { id: 'PUBLIC_TLS_CERTIFICATE', status: 'NOT_RUN', code: 'PUBLIC_ENTRY_PROBE_REQUIRED' },
    { id: 'PRODUCTION_DATABASE_ISOLATION', status: 'CUSTOMER_GATED', code: 'Q02_PENDING' },
    { id: 'PRODUCTION_RUNTIME', status: 'BLOCKED', code: 'REMOTE_TEST_IS_NOT_PRODUCTION' },
  ] })
it('renders missing observation as not ready and removes all checks when a later payload invents PASS', async () => {
  const forged = unavailable(); forged.checks[0]!.status = 'PASS'
  vi.spyOn(api, 'request').mockResolvedValueOnce(unavailable()).mockResolvedValueOnce(forged)
  app = createApp(Panel); app.component('ElButton', ElButton); app.mount(host)
  host.querySelector<HTMLButtonElement>('button')!.click(); await settle()
  expect(host.textContent).toContain('数据库实际观测：未就绪')
  expect(host.querySelectorAll('li')).toHaveLength(5)
  expect(host.textContent).not.toContain('检查通过')
  host.querySelector<HTMLButtonElement>('button')!.click(); await settle()
  expect(host.querySelectorAll('li')).toHaveLength(0)
  expect(host.textContent).not.toContain('数据库实际观测：检查通过')
})
it.each(['account', 'role', 'mode', 'switching', 'generation'])('preserves synchronous %s invalidation during an inspection', async kind => {
  const original = unavailable()
  let resolve!: (value: unknown) => void
  const request = vi.spyOn(api, 'request').mockImplementation(() => new Promise(done => { resolve = done }))
  app = createApp(Panel); app.component('ElButton', ElButton); app.mount(host)
  host.querySelector<HTMLButtonElement>('button')!.click(); await settle()
  if (kind === 'account') session.value = { ...session.value!, account_version: 2 }
  if (kind === 'role') session.value = { ...session.value!, user: { ...session.value!.user, role: 'msp' } }
  if (kind === 'mode') session.value = { ...session.value!, mode: 'local_fixture' }
  if (kind === 'switching') switchingTenant.value = true
  if (kind === 'generation') contextGeneration.value++
  expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  resolve(original); await settle()
  expect(host.querySelectorAll('li')).toHaveLength(0)
})
