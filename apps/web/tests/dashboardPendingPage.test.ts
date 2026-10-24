// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
vi.mock('../src/components/LiveMonitoringPanel.vue', () => ({ default: { methods: { refresh() {} }, template: '<div />' } }))
vi.mock('../src/components/DashboardEventsPanel.vue', () => ({ default: { methods: { refresh() {} }, template: '<div />' } }))
vi.mock('../src/components/ApiErrorNotice.vue', () => ({ default: { template: '<div />' } }))
import DashboardPage from '../src/pages/DashboardPage.vue'
import { api } from '../src/api/client'
import { contextGeneration, session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { row, scope } from './dashboard.fixture'
import { eventMetrics } from './eventOperationalMetrics.fixture'
import { actionableSnapshot as pendingSnapshot } from './dashboardActionable.fixture'
import type { ActionableTicketSnapshot } from '../src/utils/dashboardActionableTickets'

const auth: Session = { user: { id: scope.actorId, display_name: 'Synthetic', role: 'padmin' },
  tenant: { id: scope.tenantId, name: 'Synthetic' }, allowed_tenants: [], permission_version: scope.permissionVersion,
  capabilities: ['dashboard:read', 'tickets:read', 'assets:read'], csrf_token: 'SYNTHETIC_UNIT', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
let app: App | undefined, host: HTMLElement, response: ActionableTicketSnapshot, failed: boolean
const fixture = (patch: Partial<ActionableTicketSnapshot> = {}) => pendingSnapshot({ tenant_id: scope.tenantId, actor_id: scope.actorId, permission_version: scope.permissionVersion, ...patch })
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  const blank = { template: '<p />' }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/dashboard', name: 'dashboard', component: DashboardPage, meta: { capability: 'dashboard:read' } },
    { path: '/dashboard/actionable-tickets', name: 'actionable-tickets', component: blank, meta: { capability: 'dashboard:read', capabilities: ['tickets:read'] } },
    { path: '/tickets', name: 'tickets', component: blank, meta: { capability: 'tickets:read' } },
    { path: '/ticket-workflows', name: 'ticket-workflows', component: blank, meta: { capability: 'tickets:read' } },
  ] })
  await router.push('/dashboard'); await router.isReady()
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await settle()
}
const value = () => host.querySelector('[data-kpi="PENDING_TICKETS"] .mop-kpi-value')?.textContent
beforeEach(() => {
  session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value++
  response = fixture(); failed = false
  vi.spyOn(api, 'request').mockImplementation(async path => {
    if(path.startsWith('/metrics/monitoring-events?'))return eventMetrics()
    if (path === '/dashboard/actionable-tickets?page=1&page_size=5') { if (failed) throw new Error('synthetic pending outage'); return response }
    return row(new URL('http://unit' + path).searchParams.get('window') as any)
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('shows the same authoritative complete total as the cross-state list', async () => {
  await mount()
  expect(value()).toBe('2')
  expect(host.querySelector('[data-kpi="PENDING_TICKETS"]')?.classList.contains('unknown')).toBe(false)
  expect(host.querySelectorAll('.dashboard-workflows tbody tr')).toHaveLength(2)
  expect(host.querySelector('.dashboard-workflows [role="status"]')?.textContent).toContain('待处理 2 条')
  expect(vi.mocked(api.request).mock.calls.filter(([path]) => path === '/dashboard/actionable-tickets?page=1&page_size=5')).toHaveLength(1)
  expect([...host.querySelectorAll('[data-kpi]:not([data-kpi="PENDING_TICKETS"]) .mop-kpi-value')].map(node => node.textContent)).toEqual(['未知','0','无权限','—','未知'])
})
it('renders a verified zero and a verified zero overdue SLA', async () => {
  response = fixture({ registrations: 0, known_pending: 0, excluded: 0, pending_tickets: 0, evaluated_pending: 0, total_pages: 0, items: [] })
  await mount()
  expect(value()).toBe('0')
  expect(host.querySelector('[data-kpi="PENDING_TICKETS"] .kpi-parts')?.textContent).toContain('已超 SLA 0')
  expect(host.textContent).toContain('当前授权范围没有未关闭且未解决的工单')
})
it('does not substitute partial known rows for a complete total', async () => {
  response = fixture({ registrations: 4, unconfigured: 1, pending_tickets: null, overdue_sla: null, unknown_sla: 1, unavailable_reasons: ['REGISTRATIONS_WITHOUT_WORKFLOW'] })
  await mount()
  expect(value()).toBe('未知')
  expect(host.querySelector('[data-kpi="PENDING_TICKETS"]')?.textContent).toContain('1 条登记未配置流程')
  expect(host.querySelectorAll('.dashboard-workflows tbody tr')).toHaveLength(2)
})
it('keeps pending failures independent from valid dashboard layout metadata', async () => {
  failed = true; await mount()
  expect(value()).toBe('读取失败')
  expect(host.querySelector('[data-kpi="ASSET_ONLINE_RATE"] .mop-kpi-value')?.textContent).toBe('未知')
  expect(host.querySelector('.dashboard-workflows table')).toBeNull()
})
it('refreshes the single shared snapshot through the top-level action', async () => {
  await mount(); failed = true
  host.querySelector<HTMLButtonElement>('.dashboard-refresh')!.click(); await settle()
  expect(value()).toBe('读取失败')
  expect(vi.mocked(api.request).mock.calls.filter(([path]) => path === '/dashboard/actionable-tickets?page=1&page_size=5')).toHaveLength(2)
})
it('immediately removes the old count on ticket capability revocation', async () => {
  await mount(); session.value = { ...auth, capabilities: ['dashboard:read'] }; await settle()
  expect(value()).toBe('无权限')
  expect(host.querySelector('.dashboard-workflows')).toBeNull()
  expect(vi.mocked(api.request).mock.calls.filter(([path]) => path === '/dashboard/actionable-tickets?page=1&page_size=5')).toHaveLength(1)
})
it('rejects late old-scope data when switching away', async () => {
  let finish!: (value: unknown) => void
  vi.mocked(api.request).mockImplementation(async path => path === '/dashboard/actionable-tickets?page=1&page_size=5'
    ? new Promise(resolve => { finish = resolve }) : row('HOURS_24'))
  await mount(); switchingTenant.value = true; await settle(); finish(fixture()); await settle()
  expect(host.querySelector('[data-kpi]')).toBeNull()
  expect(host.textContent).not.toContain('Synthetic pending')
})
it.each(['viewer', 'tadmin'] as const)('labels a %s count as reporter-scoped rather than tenant-wide', async role => {
  session.value = { ...auth, user: { ...auth.user, role } }
  response = fixture({ scope: 'REPORTER_ONLY' }); await mount()
  expect(value()).toBe('2')
  expect(host.querySelector('[data-kpi="PENDING_TICKETS"]')?.textContent).toContain('仅本人报障')
})
