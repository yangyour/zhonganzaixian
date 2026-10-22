// @vitest-environment jsdom
import {createApp,nextTick,type App} from 'vue'
import {it,expect,vi,beforeEach,afterEach} from 'vitest'
import {webcrypto} from 'node:crypto'
const mocks=vi.hoisted(()=>({confirm:vi.fn()}))
vi.mock('vue-router',()=>({onBeforeRouteLeave:()=>{}}))
vi.mock('../src/utils/contextTransitionGuard',()=>({contextTransitionGuards:{register:()=>()=>{}}}))
vi.mock('element-plus',()=>({ElMessageBox:{confirm:mocks.confirm}}))
import Page from '../src/pages/ServiceTiersPage.vue'
import {api} from '../src/api/client'
import {session,switchingTenant} from '../src/stores/session'
import type {Session} from '../src/api/types'
const tenant='55000000-0000-4000-8000-000000000001',actor='55000000-0000-4000-8000-000000000011',at='2026-10-18T08:00:00Z'
const auth:Session={user:{id:actor,role:'padmin',display_name:'合成'},tenant:{id:tenant,name:'合成'},allowed_tenants:[],capabilities:['sla_scenarios:read','configurations:write'],permission_version:3,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only'}
const snapshot=()=>({tenant_id:tenant,version:1,tier:'STANDARD',availability_target:null,contract_device_count:10,billing_cycle:'MONTHLY',monthly_fee_fen:100,effective_mode:'IMMEDIATE',effective_from:at,updated_by:actor,updated_at:at,customer_approval_status:'UNVERIFIED'})
function page(current:any=snapshot()){return {tenant_id:tenant,current,items:current?[{id:'55000000-0000-4000-8000-000000000099',tenant_id:tenant,version:current.version,previous_version:current.version===1?null:current.version-1,from_tier:current.version===1?null:'STANDARD',to_tier:current.tier,availability_target:current.availability_target,contract_device_count:current.contract_device_count,billing_cycle:current.billing_cycle,monthly_fee_fen:current.monthly_fee_fen,effective_mode:current.effective_mode,effective_from:at,reason:'SYNTHETIC',approved_by:actor,changed_at:at,customer_approval_status:'UNVERIFIED'}]:[],has_more:false,next_before_version:null,observed_at:at,customer_approval_status:'UNVERIFIED',in_flight_ticket_retroactive:false,source:'postgresql'}}
let app:App|undefined,host:HTMLElement,vm:any
async function settle(){for(let i=0;i<10;i++){await Promise.resolve();await nextTick()}}
async function mount(){host=document.createElement('div');document.body.append(host);app=createApp(Page);app.config.warnHandler=()=>{};app.component('RouterLink',{template:'<a><slot /></a>'});vm=app.mount(host);await settle()}
function input(label:string,value:string){const el=host.querySelector<HTMLInputElement>(`[aria-label="${label}"]`)!;el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));return el}
beforeEach(()=>{vi.stubGlobal('crypto',webcrypto);session.value=structuredClone(auth);switchingTenant.value=false;mocks.confirm.mockReset().mockResolvedValue(true)})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks();vi.unstubAllGlobals()})

const ticket='55000000-0000-4000-8000-000000000077',observed='2026-10-18T10:00:05Z'
const slaPayload=(patch:Record<string,unknown>={})=>({
 ticket_id:ticket,tenant_id:tenant,receipt_no:'MOP-20261018-0001',registration_created_at:at,
 workflow_version:3,state:'RESOLVED',priority:'P1',frozen_service_tier:'STANDARD',current_tenant_service_tier:'ADVANCED',
 tier_changed_since_open:true,
 response_clock:{
  name:'RESPONSE',due_at:'2026-10-18T08:10:00Z',completed_at:'2026-10-18T08:04:00Z',
  paused:false,breached:false,status:'MET',missing_reason:null,
  thresholds:{p50_at:'2026-10-18T08:05:00Z',p80_at:'2026-10-18T08:08:00Z',p100_at:'2026-10-18T08:10:00Z'},
  working_windows_count:1
 },
 recovery_clock:{
  name:'RECOVERY',due_at:'2026-10-18T10:00:00Z',completed_at:'2026-10-18T09:00:00Z',
  paused:true,breached:false,status:'PAUSED_FOR_CUSTOMER_CONFIRM',missing_reason:null,
  thresholds:{p50_at:'2026-10-18T09:00:00Z',p80_at:'2026-10-18T09:36:00Z',p100_at:'2026-10-18T10:00:00Z'},
  working_windows_count:1
 },
 clock_tasks:[{clock_key:'response:50',kind:'SLA',due_at:'2026-10-18T08:05:00Z',outcome:'EMITTED',completed_at:'2026-10-18T08:05:01Z'}],
 sla_missing:{},sla_references:[],reopen_count:0,reopen_policy_status:'NOT_REOPENED',
 metric_version:'SLA_CLOCK_V2',observed_at:observed,source:'postgresql',...patch
})
function deferred(){let resolve!:(v:any)=>void,reject!:(v:any)=>void;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function click(label:string){const button=[...host.querySelectorAll<HTMLButtonElement>('button')].find(b=>b.textContent?.trim()===label);expect(button).toBeDefined();button!.click()}
async function slaTab(){click('工单 SLA 冻结时钟核验');await settle()}
it('cancel initial ledger read, ignore late response, retry and return focus to heading',async()=>{const wait=deferred();const req=vi.spyOn(api,'request').mockReturnValueOnce(wait.promise as any).mockResolvedValue(page());await mount();click('取消服务档位台账读取');await settle();expect(req.mock.calls[0]![1]!.signal!.aborted).toBe(true);const notice=host.querySelector('.read-recovery [role="status"]');expect(document.activeElement).toBe(notice);wait.resolve(page());await settle();expect(document.activeElement).toBe(notice);expect(vm.state.page).toBeNull();expect(await vm.readLedger(false,true)).toBe(true);expect(document.activeElement).toBe(host.querySelector('h1'))})
it('ledger failure focuses named feedback; recovery preserves unsaved fees and reason',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue(page());await mount();await vm.prepareChange();vm.monthlyFeeFen='00042';vm.reason='draft reason';req.mockRejectedValueOnce(Error('loss'));expect(await vm.readLedger()).toBe(false);expect(document.activeElement).toBe(host.querySelector('[aria-label="服务档位读取结果"]'));expect(await vm.readLedger(false,true)).toBe(true);expect(vm.monthlyFeeFen).toBe('00042');expect(vm.reason).toBe('draft reason');expect(vm.dirty).toBe(true);expect(req.mock.calls.every(c=>!c[1]?.method)).toBe(true)})
it('SLA original recovery ignores newer unsubmitted ticket text and focuses result heading',async()=>{const req=vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockRejectedValueOnce(Error('sla loss')).mockResolvedValueOnce(slaPayload());await mount();await slaTab();vm.ticketIdInput=ticket;expect(await vm.readSla()).toBe(false);expect(document.activeElement).toBe(host.querySelector('[aria-label="工单 SLA 读取结果"]'));vm.ticketIdInput='new unsubmitted raw input';expect(await vm.readSla(true)).toBe(true);expect(req.mock.calls[2]![0]).toBe(req.mock.calls[1]![0]);expect(vm.ticketIdInput).toBe('new unsubmitted raw input');expect(document.activeElement?.textContent).toBe('原工单 SLA 核验结果')})
it('invalid UUID is explained and focused without any new request',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue(page());await mount();await slaTab();vm.ticketIdInput='not-an-id';req.mockClear();expect(await vm.readSla()).toBe(false);expect(req).not.toHaveBeenCalled();expect(host.textContent).toContain('请输入完整有效的工单 UUID');expect(document.activeElement).toBe(host.querySelector('.sla-search input'))})
it('cancel SLA retains typed text and returns focus to cancellation status',async()=>{const wait=deferred();const req=vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockReturnValueOnce(wait.promise as any);await mount();await slaTab();vm.ticketIdInput=ticket;const pending=vm.readSla();await settle();vm.ticketIdInput='new input';click('取消工单 SLA读取');await settle();expect(req.mock.calls[1]![1]!.signal!.aborted).toBe(true);expect(document.activeElement).toBe(host.querySelector('.read-recovery [role="status"]'));wait.resolve(slaPayload());expect(await pending).toBe(false);expect(vm.ticketIdInput).toBe('new input')})
it('a voluntary focus movement is not stolen by completed SLA read',async()=>{const wait=deferred();vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockReturnValueOnce(wait.promise as any);await mount();await slaTab();vm.ticketIdInput=ticket;const pending=vm.readSla();await settle();const input=host.querySelector<HTMLInputElement>('.sla-search input')!;input.focus();wait.resolve(slaPayload());expect(await pending).toBe(true);expect(document.activeElement).toBe(input)})
it('account switch clears both recovery targets and rejects late original read',async()=>{const wait=deferred();vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockReturnValueOnce(wait.promise as any).mockResolvedValue(page());await mount();await slaTab();vm.ticketIdInput=ticket;const pending=vm.readSla();session.value={...structuredClone(auth),account_version:2};await settle();wait.resolve(slaPayload());expect(await pending).toBe(false);expect(vm.state.slaReadTarget).toBeNull();expect(vm.ticketIdInput).toBe('');expect(await vm.readSla(true)).toBe(false)})
it('ledger table has a caption, scoped headers and named keyboard scroll region',async()=>{vi.spyOn(api,'request').mockResolvedValue(page());await mount();const region=host.querySelector('[aria-label="服务档位变更历史，可横向滚动"]');expect(region?.getAttribute('tabindex')).toBe('0');expect(region?.querySelector('caption')?.textContent).toContain('当前读取范围');expect(region?.querySelectorAll('th[scope="col"]')).toHaveLength(7)})

it('malformed SLA task payload replaces no result and returns a recoverable error',async()=>{const bad={...slaPayload(),clock_tasks:[{...slaPayload().clock_tasks[0],outcome:'DELIVERED'}]};const req=vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockResolvedValueOnce(slaPayload()).mockResolvedValueOnce(bad).mockResolvedValueOnce(slaPayload());await mount();await slaTab();vm.ticketIdInput=ticket;expect(await vm.readSla()).toBe(true);expect(await vm.readSla()).toBe(false);expect(vm.state.slaInspection).toBeNull();expect(host.querySelector('.sla-detail')).toBeNull();expect(document.activeElement).toBe(host.querySelector('[aria-label="工单 SLA 读取结果"]'));expect(await vm.readSla(true)).toBe(true);expect(req.mock.calls.every(c=>!c[1]?.method)).toBe(true)})
it('SLA clocks have their own accurate caption and keyboard scroll region',async()=>{vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockResolvedValueOnce(slaPayload());await mount();await slaTab();vm.ticketIdInput=ticket;await vm.readSla();const region=host.querySelector('[aria-label="原工单 SLA 时钟，可横向滚动"]');expect(region?.getAttribute('tabindex')).toBe('0');expect(region?.querySelector('caption')?.textContent).toBe('原工单响应与恢复 SLA 时钟');expect(region?.querySelectorAll('th[scope="col"]')).toHaveLength(7)})

it('the real service tier page includes task, missing configuration and frozen source details',async()=>{const data=slaPayload({sla_missing:{confirmation:'CONFIGURATION_UNAVAILABLE'},sla_references:[{key:'sla.confirmation',release_id:null,version:0,definition_hash:'a'.repeat(64),source:'CONTRACT_DEFAULT'}]});vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockResolvedValueOnce(data);await mount();await slaTab();vm.ticketIdInput=ticket;expect(await vm.readSla()).toBe(true);const evidence=host.querySelector('[aria-label="工单 SLA 核对明细"]');expect(evidence?.textContent).toContain('response:50');expect(evidence?.textContent).toContain('CONFIGURATION_UNAVAILABLE');expect(evidence?.textContent).toContain('a'.repeat(64));expect(evidence?.textContent).toContain('非外部送达')})
it('account change removes evidence from the prior account without waiting for new reads',async()=>{vi.spyOn(api,'request').mockResolvedValueOnce(page()).mockResolvedValueOnce(slaPayload()).mockImplementation(()=>new Promise(()=>{}));await mount();await slaTab();vm.ticketIdInput=ticket;await vm.readSla();expect(host.querySelector('.sla-evidence')).not.toBeNull();session.value={...structuredClone(auth),account_version:2};await settle();expect(host.querySelector('.sla-evidence')).toBeNull();expect(host.textContent).not.toContain('response:50')})
