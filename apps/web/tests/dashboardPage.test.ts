// @vitest-environment jsdom
// Contract layout and authorization regression; synthetic data, not live acceptance.
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
vi.mock('../src/components/LiveMonitoringPanel.vue', () => ({ default: { props: ['autoRefresh', 'mode'], methods: {refresh() {}}, template: '<div data-panel="live" :data-mode="mode"><span data-refresh>{{autoRefresh}}</span></div>' } }))
vi.mock('../src/components/DashboardEventsPanel.vue', () => ({ default: { methods: {refresh() {}}, template: '<div data-panel="events" />' } }))
vi.mock('../src/components/DashboardWorkflowsPanel.vue', () => ({ default: { methods: {refresh() {}}, template: '<div data-panel="workflows" />' } }))
vi.mock('../src/components/ApiErrorNotice.vue', () => ({ default: { template: '<div />' } }))
import Page from '../src/pages/DashboardPage.vue'
import { api } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { scope, row } from './dashboard.fixture'
const auth: Session = { user: { id: scope.actorId, role: 'padmin', display_name: '合成用户' }, tenant: { id: scope.tenantId, name: '合成租户' }, allowed_tenants: [], permission_version: 7, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', capabilities: ['dashboard:read', 'assets:read', 'tickets:read'], mode: 'remote_test', identity_source: 'synthetic-unit-only' }
let app: App, host: HTMLElement
const order = ['ALERT_TREND', 'SERVICE_HEALTH', 'FAULT_TIMELINE', 'RECENT_EVENTS', 'PENDING_TICKETS', 'RESOURCE_TOP5']
async function settle() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
beforeEach(() => {
  vi.spyOn(api, 'request').mockImplementation(async path => row(new URL('http://unit' + path).searchParams.get('window') as any))
  session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value++
  host = document.createElement('div'); document.body.append(host); app = createApp(Page); app.mount(host)
})
afterEach(() => { app.unmount(); host.remove(); session.value = null; vi.restoreAllMocks(); vi.useRealTimers() })
it('renders the six contract KPIs and fixed panel order without invented values', async () => {
  await settle()
  expect(host.querySelectorAll('[data-kpi]')).toHaveLength(6)
  expect([...host.querySelectorAll('[data-block]')].map(node => node.getAttribute('data-block'))).toEqual(order)
  expect([...host.querySelectorAll('[data-kpi] strong')].every(node => node.textContent === '未知')).toBe(true)
  expect(host.textContent).not.toContain('100%')
  expect(host.querySelector('[data-mode="ranking"]')).not.toBeNull()
})
it('switches the actual dashboard request window and displays its unknown state', async () => {
  await settle(); const select = host.querySelector('select')!; select.value = 'DAYS_7'; select.dispatchEvent(new Event('change')); await settle()
  expect(api.request).toHaveBeenLastCalledWith('/dashboard?window=DAYS_7', expect.objectContaining({ method: 'GET' }))
  expect(host.querySelector('[data-block="ALERT_TREND"]')?.textContent).toContain('7天')
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
  await settle(); vi.mocked(api.request).mockRejectedValueOnce(new Error('unit failure'))
  host.querySelector('button')!.click(); await settle()
  expect(host.querySelector('[data-kpi] strong')?.textContent).toBe('读取失败')
  expect(host.querySelector('.dashboard-source-status')?.textContent).toContain('尚未取得有效首页状态')
})
