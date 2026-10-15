// @vitest-environment jsdom
// Isolated component checks, not evidence of a real monitoring source.
import { createApp, h, nextTick, reactive, type App } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ElAlert } from 'element-plus'
import Panel from '../src/components/LiveMonitoringPanel.vue'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { snapshot, sampleDevice, history } from './networkMonitoringFixtures'
import { resource } from './firewallResourceFixtures'
let app: App | undefined, host: HTMLElement
const auth: Session = { user: { id:'55000000-0000-4000-8000-000000000002',role:'padmin',display_name:'单元测试' },tenant:{ id:snapshot().tenant_id,name:'测试' },allowed_tenants:[],capabilities:['assets:read'],permission_version:1,csrf_token:'SYNTHETIC',mode:'remote_test',identity_source:'synthetic-unit-only' }
async function settle() { for (let i=0;i<10;i++) { await Promise.resolve(); await nextTick() } }
function mount(props: Record<string,unknown> = {}) { host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,props)});app.component('ElAlert',ElAlert);app.mount(host) }
beforeEach(()=>{session.value=structuredClone(auth);switchingTenant.value=false})
afterEach(()=>{app?.unmount();app=undefined;host?.remove();session.value=null;vi.restoreAllMocks();vi.useRealTimers()})
it('summarizes only loaded binding samples and reads history on explicit selection',async()=>{const req=vi.spyOn(api,'request').mockResolvedValueOnce(snapshot()).mockResolvedValueOnce(history());mount();await settle();expect(host.querySelectorAll('.live-stats strong')[0]?.textContent).toBe('1');expect(host.textContent).toContain('2.00 ms');expect(host.querySelector('svg')).toBeNull();host.querySelector<HTMLButtonElement>('[aria-label^="查看"]')!.click();await settle();expect(req.mock.calls[1]![0]).toContain('/history?metric=latency');expect(host.querySelectorAll('svg circle')).toHaveLength(3)})
it('does not count explicit no response as unknown or reachable',async()=>{const raw=snapshot();raw.devices[0]!.network_state='NO_RESPONSE';raw.devices[0]!.metrics.icmp.value='0';vi.spyOn(api,'request').mockResolvedValue(raw);mount();await settle();expect([...host.querySelectorAll('.live-stats strong')].map(x=>x.textContent)).toEqual(['1','0','1','0'])})
it('shows no binding as an actionable empty state with no fabricated gauges',async()=>{vi.spyOn(api,'request').mockResolvedValue(snapshot());mount({assetId:'unbound'});await settle();expect(host.textContent).toContain('当前资产未绑定监控采集');expect(host.querySelector('table,meter,svg')).toBeNull()})
it('an asset change cannot display another bound device',async()=>{const props=reactive({assetId:sampleDevice().asset_id});vi.spyOn(api,'request').mockResolvedValue(snapshot());mount(props);await settle();expect(host.textContent).toContain('虚构测试设备');props.assetId='unbound';await settle();expect(host.textContent).not.toContain('虚构测试设备')})
it('only current fresh resources are ranked and test certificate status stays visible',async()=>{const raw=snapshot();raw.devices[0]!.vendor_metrics_status='PARTIAL_API';vi.spyOn(api,'request').mockImplementation(async path=>(path.endsWith('/resources')?resource():raw) as any);mount();await settle();expect(host.querySelector('.live-ranking')?.textContent).toContain('12.5%');expect(host.textContent).toContain('证书未核验');const select=host.querySelector<HTMLSelectElement>('.live-ranking select')!;select.value='memory';select.dispatchEvent(new Event('change',{bubbles:true}));await settle();expect(host.querySelector('.live-ranking')?.textContent).toContain('58.0%')})
it('checks show actual closed port observations without calling them formal incidents',async()=>{const raw=snapshot();raw.devices[0]!.metrics.tcp_22.value='0';vi.spyOn(api,'request').mockResolvedValue(raw);mount({mode:'events',assetId:sampleDevice().asset_id});await settle();expect(host.textContent).toContain('未建立连接');expect(host.textContent).toContain('不自动生成正式事件');expect(host.querySelectorAll('.live-table tbody tr')).toHaveLength(1)})
it('permission removal synchronously removes all prior samples',async()=>{const req=vi.spyOn(api,'request').mockResolvedValue(snapshot());mount();await settle();session.value={...auth,capabilities:[]};await settle();expect(host.textContent).not.toContain('虚构测试设备');expect(host.textContent).toContain('没有资产读取权限');expect(req).toHaveBeenCalledTimes(1)})
it('paused auto refresh still expires displayed samples after 90 seconds',async()=>{vi.useFakeTimers();const req=vi.spyOn(api,'request').mockResolvedValue(snapshot());mount({autoRefresh:false});await settle();await vi.advanceTimersByTimeAsync(91000);await settle();expect(req).toHaveBeenCalledTimes(1);expect(host.textContent).toContain('数据过期');expect([...host.querySelectorAll('.live-stats strong')].map(x=>x.textContent)).toEqual(['1','0','0','1'])})

// Policy transport is tested separately; these component checks isolate existing sample workflows.
vi.mock('../src/utils/monitoringRefreshPolicy',async()=>{const {ref}=await import('vue');return {refreshPermissionRejected:(e:unknown)=>e instanceof ApiError&&[401,403].includes(e.status),useMonitoringRefreshPolicy:()=>({intervalSeconds:ref(30),label:ref('isolated unit policy'),error:ref(null),refresh:vi.fn()})}})
