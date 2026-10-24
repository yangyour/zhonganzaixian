// @vitest-environment jsdom
import {acceptanceSample} from './notificationAcceptance.fixture'
import {reachabilitySample} from './assetReachability.fixture'
// Contract layout and authorization regression; synthetic data, not live acceptance.
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
vi.mock('../src/components/LiveMonitoringPanel.vue', () => ({ default: { props: ['autoRefresh', 'mode'], methods: {refresh() {}}, template: '<div data-panel="live" :data-mode="mode"><span data-refresh>{{autoRefresh}}</span></div>' } }))
vi.mock('../src/components/DashboardEventsPanel.vue', () => ({ default: { methods: {refresh() {}}, template: '<div data-panel="events" />' } }))
vi.mock('../src/components/DashboardWorkflowsPanel.vue', () => ({ default: { methods: {refresh() {}}, template: '<div data-panel="workflows" />' } }))
vi.mock('../src/components/ApiErrorNotice.vue', () => ({ default: { template: '<div />' } }))
import Page from '../src/pages/DashboardPage.vue'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { scope, row } from './dashboard.fixture'
import {eventMetrics} from './eventOperationalMetrics.fixture'
import {sample as serviceSample} from './serviceObservations.fixture'
import {noiseTrend} from './eventNoiseTrend.fixture'
const response=(path:string)=>path==='/dashboard/asset-reachability'?{...reachabilitySample(),tenant_id:scope.tenantId,actor_id:scope.actorId,permission_version:scope.permissionVersion}:path.startsWith('/metrics/event-noise-trend?')?noiseTrend(new URL('https://unit'+path).searchParams.get('window') as any):path.startsWith('/metrics/monitoring-events?')?eventMetrics():row(new URL('http://unit'+path).searchParams.get('window') as any)
const auth: Session = { user: { id: scope.actorId, role: 'padmin', display_name: '合成用户' }, tenant: { id: scope.tenantId, name: '合成租户' }, allowed_tenants: [], permission_version: 7, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', capabilities: ['dashboard:read', 'assets:read', 'tickets:read'], mode: 'remote_test', identity_source: 'synthetic-unit-only' }
let app: App, host: HTMLElement
const order = ['ALERT_TREND', 'SERVICE_HEALTH', 'FAULT_TIMELINE', 'RECENT_EVENTS', 'PENDING_TICKETS', 'RESOURCE_TOP5']
async function settle() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
beforeEach(() => {
  vi.spyOn(api, 'request').mockImplementation(async path => response(path))
  session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value++
  host = document.createElement('div'); document.body.append(host); app = createApp(Page); app.mount(host)
})
afterEach(() => { app.unmount(); host.remove(); session.value = null; vi.restoreAllMocks(); vi.useRealTimers() })
it('renders the six contract KPIs and fixed panel order without invented values', async () => {
  await settle()
  expect(host.querySelectorAll('[data-kpi]')).toHaveLength(6)
  expect([...host.querySelectorAll('[data-block]')].map(node => node.getAttribute('data-block'))).toEqual(order)
  expect([...host.querySelectorAll('[data-kpi] strong')].map(node=>node.textContent)).toEqual(['未知','0','无权限','未知','—','无权限'])
  expect(host.textContent).not.toContain('100%')
  expect(host.querySelector('[data-mode="ranking"]')).not.toBeNull()
})
it('switches the actual dashboard request window and displays its unknown state', async () => {
  await settle()
  const range = host.querySelector('[role="group"][aria-label="首页时间范围"]')!
  const week = [...range.querySelectorAll('button')].find(button => button.textContent === '7天')!
  expect(week.getAttribute('aria-pressed')).toBe('false')
  week.click(); await settle()
  expect(vi.mocked(api.request).mock.calls.filter(([path])=>path.startsWith('/dashboard?')).at(-1)).toEqual(['/dashboard?window=DAYS_7', expect.objectContaining({ method: 'GET' })])
  expect(vi.mocked(api.request).mock.calls.filter(([path])=>path.startsWith('/metrics/event-noise-trend?')).at(-1)).toEqual(['/metrics/event-noise-trend?window=DAYS_7',expect.objectContaining({method:'GET'})])
  expect(host.querySelector('[data-block="ALERT_TREND"]')?.textContent).toContain('7天')
  expect(week.getAttribute('aria-pressed')).toBe('true')
})
it('links each permitted KPI to its contract list page and omits unauthorized targets', async () => {
  app.unmount()
  const blank = { template: '<p />' }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/dashboard', name: 'dashboard', component: Page, meta: { capability: 'dashboard:read' } },
    { path: '/event-metrics', name:'event-metrics', component:blank, meta:{roles:['padmin','msp'],capability:'dashboard:read',capabilities:['dashboard:read','tickets:read']} },
    { path: '/assets', name: 'assets', component: blank, meta: { capability: 'assets:read' } },
    { path: '/monitoring-events', name: 'monitoring-events', component: blank, meta: { roles: ['padmin', 'msp'], capability: 'tickets:read' } },
    { path: '/sla-reports', name: 'sla-reports', component: blank, meta: { roles: ['padmin', 'msp'], capability: 'sla_scenarios:read' } },
    { path: '/ticket-workflows', name: 'ticket-workflows', component: blank, meta: { capability: 'tickets:read' } },
    { path: '/tickets', name: 'tickets', component: blank, meta: { capability: 'tickets:read' } },
    { path: '/dashboard/actionable-tickets', name: 'actionable-tickets', component: blank, meta: { capability: 'dashboard:read', capabilities: ['tickets:read'] } },
    { path: '/ticket-notifications', name: 'ticket-notifications', component: blank, meta: { capability: 'tickets:read' } },
  ] })
  await router.push('/dashboard'); await router.isReady()
  app = createApp({ template: '<router-view />' }); app.use(router); app.mount(host); await settle()
  const links = Object.fromEntries([...host.querySelectorAll('[data-kpi]')].map(card => [card.getAttribute('data-kpi'), card.querySelector('a')?.getAttribute('href') ?? null]))
  expect(links).toEqual({ ASSET_ONLINE_RATE: null, CURRENT_PROBLEMS: '/event-metrics?window=HOURS_24&view=CURRENT_PROBLEMS', MONTH_SERVICE_AVAILABILITY: null, PENDING_TICKETS: '/dashboard/actionable-tickets', MTTA_24H: '/event-metrics?window=HOURS_24&view=RECENT_PROBLEMS', DELIVERY_RATE_24H: '/ticket-notifications' })
  expect([...host.querySelectorAll('[data-kpi] strong')].map(node=>node.textContent)).toEqual(['未知','0','无权限','未知','—','无权限'])
})
it('keeps KPI drill-down links behind the same route gates as the menu', async () => {
  await settle()
  // No router is installed in this unit host, so no unauthorized or unresolved link may render.
  expect(host.querySelectorAll('[data-kpi] a')).toHaveLength(0)
  expect([...host.querySelectorAll('[data-kpi="CURRENT_PROBLEMS"] .mop-sev')].map(node => node.textContent)).toEqual(['灾难 0', '严重 0', '一般 0', '警告 0', '信息 0'])
})
it('auto-refresh control reaches the actual resource component without writes', async () => {
  await settle(); const input = host.querySelector('input')!; input.checked = false; input.dispatchEvent(new Event('change')); await nextTick()
  expect(host.querySelector('[data-refresh]')?.textContent).toBe('false')
  expect(vi.mocked(api.request).mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
})
it.each(['assets:read', 'tickets:read'])('retains fixed layout while removing unauthorized children: %s', async cap => {
  session.value = { ...auth, capabilities: auth.capabilities.filter(value => value !== cap) }; await settle()
  expect(host.querySelector(cap === 'assets:read' ? '[data-panel="live"]' : '[data-panel="workflows"]')).toBeNull()
  expect(host.querySelectorAll('[data-block]')).toHaveLength(6)
})
it('clears the full dashboard when tenant switching begins', async () => {
  switchingTenant.value = true; await settle(); expect(host.querySelector('[data-block]')).toBeNull(); expect(host.textContent).toContain('已清除原范围数据')
})
it.each(['viewer', 'tadmin', 'msp', 'padmin'] as const)('keeps the same fixed layout for %s without exposing internal events', async role => {
  session.value = { ...auth, user: { ...auth.user, role } }; await settle()
  expect([...host.querySelectorAll('[data-block]')].map(node => node.getAttribute('data-block'))).toEqual(order)
  expect(Boolean(host.querySelector('[data-panel="events"]'))).toBe(['msp', 'padmin'].includes(role))
})
it('rejects local fixtures and dashboard revocation', async () => {
  session.value = { ...auth, mode: 'local_fixture' }; await settle(); expect(host.querySelector('[data-kpi]')).toBeNull()
  session.value = { ...auth, capabilities: [] }; await settle(); expect(host.querySelector('[data-kpi]')).toBeNull()
})
it('keeps failure distinct from unknown successful metadata', async () => {
  await settle(); vi.mocked(api.request).mockImplementation(async path=>{if(path.startsWith('/dashboard?'))throw new Error('unit failure');return response(path)})
  host.querySelector<HTMLButtonElement>('button.dashboard-refresh')!.click(); await settle()
  expect(host.querySelector('[data-kpi] strong')?.textContent).toBe('未知')
  expect(host.querySelector('[data-kpi="CURRENT_PROBLEMS"] strong')?.textContent).toBe('0')
  expect(host.querySelector('.dashboard-source-status')?.textContent).toContain('问题计数与MTTA来自授权事件投影')
})

it('keeps event source failure independent from available dashboard metadata',async()=>{await settle();vi.mocked(api.request).mockImplementation(async path=>{if(path.startsWith('/metrics/monitoring-events?'))throw new Error('event read failed');return response(path)});host.querySelector<HTMLButtonElement>('button.dashboard-refresh')!.click();await settle();expect(host.querySelector('[data-kpi="CURRENT_PROBLEMS"] strong')?.textContent).toBe('读取失败');expect(host.querySelector('[data-kpi="MTTA_24H"] strong')?.textContent).toBe('读取失败');expect(host.querySelector('[data-kpi="ASSET_ONLINE_RATE"] strong')?.textContent).toBe('未知')})

it('refreshes actual trend once per due interval and skips hidden-page polling',async()=>{app.unmount();vi.useFakeTimers();let now=0;vi.spyOn(performance,'now').mockImplementation(()=>now);const hidden=vi.spyOn(document,'hidden','get').mockReturnValue(false);app=createApp(Page);app.mount(host);await settle();vi.mocked(api.request).mockClear();now=31000;vi.advanceTimersByTime(1000);await settle();expect(vi.mocked(api.request).mock.calls.filter(([path])=>path==='/metrics/event-noise-trend?window=HOURS_24')).toHaveLength(1);hidden.mockReturnValue(true);now=62000;vi.advanceTimersByTime(1000);await settle();expect(vi.mocked(api.request).mock.calls.filter(([path])=>path==='/metrics/event-noise-trend?window=HOURS_24')).toHaveLength(1)})

it.each(['account','tenant','revoke','unmount'])('discards pending service observations on %s change',async change=>{
  await settle();let finish!:(value:unknown)=>void;let signal:AbortSignal|undefined;
  vi.mocked(api.request).mockImplementation(async(path,options)=>{if(path==='/dashboard/service-observations'){signal=options?.signal;return new Promise(resolve=>finish=resolve)}return response(path)})
  session.value={...auth,capabilities:[...auth.capabilities,'sla_scenarios:read'],account_version:1};await settle();expect(signal).toBeDefined();const old=signal;const complete=finish;
  if(change==='account')session.value={...session.value!,account_version:2};
  else if(change==='tenant')switchingTenant.value=true;
  else if(change==='revoke')session.value={...auth};
  else app.unmount();
  await settle();expect(old!.aborted).toBe(true);complete({services:[{name:'STALE_SECRET'}]});await settle();expect(host.textContent).not.toContain('STALE_SECRET');
})

it('monthly service card uses the same verified duration snapshot as the service panels',async()=>{
 await settle();const data=serviceSample();Object.assign(data,{tenant_id:scope.tenantId,actor_id:scope.actorId});
 const service=data.services[0]!;Object.assign(service,{effective_millis:86400000,unavailable_millis:86400000,unknown_millis:0,verified_availability_pct:0,coverage_status:'SUFFICIENT'});Object.assign(service.timeline[0]!,{kind:'FAULT',severity:'HIGH'});Object.assign(data.monthly_summary,{percentage:0,effective_service_millis:86400000,qualified_service_count:1,coverage_status:'ALL_SERVICES_QUALIFIED'});
 vi.mocked(api.request).mockImplementation(async path=>path==='/dashboard/service-observations'?data:response(path));
 session.value={...auth,capabilities:[...auth.capabilities,'sla_scenarios:read']};await settle();
 expect(host.querySelector('[data-kpi="MONTH_SERVICE_AVAILABILITY"] strong')?.textContent).toBe('0.0000%');expect(host.querySelector('[data-block="SERVICE_HEALTH"]')?.textContent).toContain('0.0000%');expect(host.querySelector('[data-kpi="MONTH_SERVICE_AVAILABILITY"]')?.textContent).toContain('1/1 个服务');
 vi.mocked(api.request).mockImplementation(async path=>{if(path==='/dashboard/service-observations')throw Error('service read failed');return response(path)});host.querySelector<HTMLButtonElement>('button.dashboard-refresh')!.click();await settle();expect(host.querySelector('[data-kpi="MONTH_SERVICE_AVAILABILITY"] strong')?.textContent).toBe('读取失败');expect(host.querySelector('[data-block="SERVICE_HEALTH"] .service-row')).toBeNull();
})

it('asset card and detail share full counts and expire locally when refresh is disabled',async()=>{
 app.unmount();vi.useFakeTimers();let now=0;vi.spyOn(performance,'now').mockImplementation(()=>now);
 const data={...reachabilitySample(),tenant_id:scope.tenantId,actor_id:scope.actorId,permission_version:scope.permissionVersion};Object.assign(data,{normal:1,unknown:0,percentage:100});Object.assign(data.items[0]!,{state:'NORMAL',reason:'FRESH_ICMP_SAMPLE',sampled_at:data.observed_at,valid_until:'2026-10-24T00:01:00Z'});
 vi.mocked(api.request).mockImplementation(async path=>path==='/dashboard/asset-reachability'?data:response(path));app=createApp(Page);app.mount(host);await settle();
 expect(host.querySelector('[data-kpi="ASSET_ONLINE_RATE"] strong')?.textContent).toBe('100.0000%');
 const button=[...host.querySelectorAll('button')].find(b=>b.textContent==='资产在线明细')!;button.click();await settle();expect(host.querySelector('[aria-labelledby="asset-reachability-title"]')?.textContent).toContain('正常 1');
 const input=host.querySelector('input')!;input.checked=false;input.dispatchEvent(new Event('change'));await settle();now=60000;vi.advanceTimersByTime(1000);await settle();
 expect(host.querySelector('[data-kpi="ASSET_ONLINE_RATE"] strong')?.textContent).toBe('未知');expect(host.querySelector('[aria-labelledby="asset-reachability-title"]')?.textContent).toContain('未知 1');
 session.value={...auth,capabilities:['dashboard:read']};await settle();expect(host.querySelector('[aria-labelledby="asset-reachability-title"]')).toBeNull();
})

it('shows provider accepted count without inventing delivered rate and clears it on revocation',async()=>{
 await settle();const data={...acceptanceSample(),tenant_id:scope.tenantId,actor_id:scope.actorId,permission_version:scope.permissionVersion};
 vi.mocked(api.request).mockImplementation(async path=>path==='/dashboard/notification-acceptance'?data:response(path));session.value={...auth,capabilities:[...auth.capabilities,'ticket_notes:read_internal']};await settle();
 const card=host.querySelector('[data-kpi="DELIVERY_RATE_24H"]')!;expect(card.querySelector('strong')!.textContent).toBe('未知');expect(card.textContent).toContain('受理 1');expect(card.textContent).toContain('送达 未知');[...card.querySelectorAll('button')].find(b=>b.textContent==='通知受理明细')!.click();await settle();expect(host.querySelector('[aria-labelledby="notification-acceptance-title"]')?.textContent).toContain('受理不等于送达');
 session.value={...auth};await settle();expect(host.querySelector('[aria-labelledby="notification-acceptance-title"]')).toBeNull();expect(host.querySelector('[data-kpi="DELIVERY_RATE_24H"]')?.textContent).not.toContain('受理 1');
})
