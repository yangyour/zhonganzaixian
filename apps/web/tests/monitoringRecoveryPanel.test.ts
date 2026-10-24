// @vitest-environment jsdom
import {createApp,nextTick,type App} from 'vue'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {ElMessageBox} from 'element-plus'
import Panel from '../src/components/MonitoringRecoveryPanel.vue'
import {api,ApiError} from '../src/api/client'
import {session,switchingTenant,contextGeneration} from '../src/stores/session'
import type {Session} from '../src/api/types'
import {contextTransitionGuards} from '../src/utils/contextTransitionGuard'
import {displayTime} from '../src/utils/display'

const tenant='10000000-0000-4000-8000-000000000001',actor='10000000-0000-4000-8000-000000000002',event='10000000-0000-4000-8000-000000000003',asset='10000000-0000-4000-8000-000000000004',release='10000000-0000-4000-8000-000000000005'
const auth:Session={user:{id:actor,display_name:'SYNTHETIC',role:'msp'},tenant:{id:tenant,name:'SYNTHETIC'},allowed_tenants:[],capabilities:['tickets:read','ticket_notes:write_internal'],permission_version:7,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'project_local'}
const context=()=>({tenant_id:tenant,actor_id:actor,permission_version:7,event_id:event,asset_id:asset,event_version:2,monitor_state:'PROBLEM',handling_state:'NEW',recovery_evidence_status:'NOT_CONFIRMED',configuration_status:'ACTIVE',missing_reason:null,policy_release_id:release,policy_release_version:1,rule:{metric:'icmp',comparison:'GE',threshold:'1',unit:'',hold_seconds:'60',max_gap_seconds:'120',minimum_samples:2},observed_at:'2026-10-23T10:02:00Z',source:'postgresql',source_binding_available:true})
const receipt=()=>({evidence_version:2,recovery_time_basis:'SOURCE_NTH_MATCHING_SAMPLE',tenant_id:tenant,actor_id:actor,permission_version:7,event_id:event,event_version:3,proof_id:'10000000-0000-4000-8000-000000000006',status:'RECOVERY_CONFIRMED',applied:true,candidate_recovered_at:'2026-10-23T10:01:00Z',recovered_at:'2026-10-23T10:01:00Z',confirmed_at:'2026-10-23T10:02:00Z',policy_release_id:release,policy_release_version:1,observed_at:'2026-10-23T10:02:00Z',latest_sample_at:'2026-10-23T10:02:00Z',span_started_at:'2026-10-23T10:00:30Z',span_seconds:'90',matching_samples:3,source_binding_sha256:'a'.repeat(64),source:'ZABBIX_SIMPLE_CHECK',production_approved:false})
// Element Plus resolves confirm without showInput to the action string; its declaration intersects prompt data.
const confirmed = 'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
let app:App|undefined,host:HTMLElement,pending:boolean,changed:number
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}}
function mount(){host=document.createElement('div');document.body.appendChild(host);app=createApp(Panel,{eventId:event,onPending:(value:boolean)=>{pending=value},onChanged:()=>{changed++}});app.mount(host)}
function button(label:string){const value=[...host.querySelectorAll('button')].find(node=>node.textContent?.trim()===label);expect(value,label).toBeDefined();return value!}
const deferred=<T>()=>{let resolve!:(value:T)=>void;const promise=new Promise<T>(yes=>{resolve=yes});return {promise,resolve}}
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false;contextGeneration.value=0;pending=false;changed=0})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks()})

describe('recovery observation panel',()=>{
 it('loads policy by GET and keeps incomplete configuration unavailable',async()=>{
  const request=vi.spyOn(api,'request').mockResolvedValue({...context(),configuration_status:'CONFIG_PENDING',missing_reason:'CONFIG_PENDING',policy_release_id:null,policy_release_version:null,rule:null,source_binding_available:false})
  mount();await settle();expect(host.textContent).toContain('恢复策略未完整生效');expect(button('核验并记录观察结果').disabled).toBe(true)
  expect(request).toHaveBeenCalledTimes(1);expect(request.mock.calls[0]?.[1]?.method).toBeUndefined();expect(pending).toBe(false)
 })
 it('cancelled explicit confirmation never sends a write',async()=>{
  const request=vi.spyOn(api,'request').mockResolvedValue(context());vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel')
  mount();await settle();button('核验并记录观察结果').click();await settle()
  expect(request.mock.calls.some(([,options])=>options?.method==='POST')).toBe(false);expect(await contextTransitionGuards.approve()).toBe(true)
 })
 it('unknown write blocks voluntary context changes and original GET receipt resolves it',async()=>{
  const request=vi.spyOn(api,'request').mockImplementation(async(path,options)=>{if(options?.method==='POST')throw new ApiError(504,'REQUEST_TIMEOUT','未知','test');return path.includes('/receipts/')?receipt():context()})
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(confirmed)
  mount();await settle();button('核验并记录观察结果').click();await vi.waitFor(()=>expect(host.textContent).toContain('原核验请求结果尚未确认'))
  expect(pending).toBe(true);expect(await contextTransitionGuards.approve()).toBe(false)
  button('只读查询原回执').click();await settle()
  expect(host.textContent).toContain('已按运行策略确认恢复');expect(changed).toBe(1);expect(pending).toBe(false)
  expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(1)
 })
 it('permission change during confirmation cancels the old prepared operation',async()=>{
  const confirmation=deferred<typeof confirmed>();vi.spyOn(ElMessageBox,'confirm').mockReturnValue(confirmation.promise)
  const request=vi.spyOn(api,'request').mockResolvedValue(context())
  mount();await settle();button('核验并记录观察结果').click();await settle()
  session.value={...auth,permission_version:8};confirmation.resolve(confirmed);await settle()
  expect(request.mock.calls.some(([,options])=>options?.method==='POST')).toBe(false)
 })
 it('cancelled refresh restores keyboard focus and ignores a late response',async()=>{
  const later=deferred<unknown>();const request=vi.spyOn(api,'request').mockResolvedValueOnce(context()).mockImplementation(()=>later.promise as Promise<never>)
  mount();await settle();button('读取当前恢复策略').click();await settle();button('取消读取').click();await settle()
  expect(document.activeElement).toBe(button('读取当前恢复策略'));expect(host.textContent).toContain('已取消本次读取')
  later.resolve({...context(),event_version:99});await settle();expect(request).toHaveBeenCalledTimes(2)
 })
 it('legacy recovered marker is visibly unverified and still offers authorized revalidation',async()=>{
  vi.spyOn(api,'request').mockResolvedValue({...context(),monitor_state:'RECOVERED',handling_state:'PENDING_REVIEW',recovery_evidence_status:'UNVERIFIED'})
  mount();await settle();expect(host.textContent).toContain('此恢复标记缺少观察证据');expect(button('核验并记录观察结果').disabled).toBe(false)
 })
 it('displays the authoritative source t2 even when confirmation and the browser clock are later',async()=>{
  const sourceAt='2026-10-23T10:01:00.000000001Z'
  const observed={...receipt(),candidate_recovered_at:sourceAt,recovered_at:sourceAt,confirmed_at:'2026-10-23T10:03:00Z',observed_at:'2026-10-23T10:03:00Z',
   latest_sample_at:'2026-10-23T10:02:00.000000001Z',span_started_at:'2026-10-23T10:00:30.000000001Z'}
  vi.spyOn(Date,'now').mockReturnValue(Date.parse('2099-01-01T00:00:00Z'))
  vi.spyOn(api,'request').mockImplementation(async(_path,options)=>options?.method==='POST'?observed:context())
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(confirmed)
  mount();await settle();button('核验并记录观察结果').click()
  await vi.waitFor(()=>expect(host.textContent).toContain('已按运行策略确认恢复'))
  const displayed=[...host.querySelectorAll('dt')].find(node=>node.textContent==='正式恢复时点 t2')?.nextElementSibling?.textContent
  expect(displayed).toBe(displayTime(sourceAt));expect(displayed).not.toBe(displayTime(observed.confirmed_at))
  expect(changed).toBe(1)
 })
 it('an old receipt without v2 evidence cannot display confirmed recovery or emit changed',async()=>{
  const {evidence_version:_version,recovery_time_basis:_basis,...legacy}=receipt()
  const request=vi.spyOn(api,'request').mockImplementation(async(_path,options)=>options?.method==='POST'?legacy:context())
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(confirmed)
  mount();await settle();button('核验并记录观察结果').click()
  await vi.waitFor(()=>expect(request.mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(1))
  await vi.waitFor(()=>expect(button('读取当前恢复策略').disabled).toBe(false))
  expect(pending).toBe(true);expect(host.textContent).not.toContain('已按运行策略确认恢复');expect(changed).toBe(0)
 })
})
