// @vitest-environment jsdom
import { createApp, h, nextTick, type App } from 'vue'
import { createRouter, createMemoryHistory, RouterView, useRoute, type Router } from 'vue-router'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { ElAlert, ElButton } from 'element-plus'
import Panel from '../src/components/DashboardWorkflowsPanel.vue'
import { api, ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import type { PendingTicketState } from '../src/utils/dashboardPendingTickets'
import { pendingContext as c, pendingId as id, pendingSnapshot } from './dashboardPending.fixture'

const auth: Session = {
  user: { id: c.actorId, display_name: 'Synthetic', role: 'padmin' },
  tenant: { id: c.tenantId, name: 'Synthetic' }, allowed_tenants: [], permission_version: c.permissionVersion,
  capabilities: ['dashboard:read', 'tickets:read'], csrf_token: 'SYNTHETIC', mode: 'remote_test', identity_source: 'synthetic-unit-only',
}
let host: HTMLElement, app: App | undefined, router: Router, emitted: PendingTicketState[]
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  host = document.createElement('div'); document.body.append(host)
  const detail = { setup() { const route = useRoute(); return () => h('p', { role: 'status' }, 'Synthetic target ' + route.query.ticket_id) } }
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: { render: () => h(Panel, { onState: (value: PendingTicketState) => emitted.push(value) }) } },
    { path: '/ticket-workflows', component: detail }, { path: '/tickets', component: detail },
  ] })
  await router.push('/'); await router.isReady()
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.component('ElAlert', ElAlert); app.component('ElButton', ElButton)
  app.mount(host); await settle()
}
function button(label: string) { return [...host.querySelectorAll('button')].find(value => value.textContent?.trim() === label)! }
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0; emitted = [] })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('uses the cross-state summary once and shares the exact snapshot with the parent', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(pendingSnapshot())
  await mount()
  expect(request).toHaveBeenCalledWith('/dashboard/pending-tickets', expect.objectContaining({ method: 'GET' }))
  expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
  expect(host.querySelectorAll('th[scope="col"]')).toHaveLength(5)
  expect(host.querySelector('select')).toBeNull()
  expect(host.querySelector('caption')?.textContent).toContain('未结束工单')
  expect(host.textContent).toContain('待处理 2 条')
  expect(host.textContent).toContain('等待厂商')
  expect(emitted.at(-1)?.current?.pending_tickets).toBe(2)
  expect(request.mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
})
it('links each model to an actually supported view', async () => {
  vi.spyOn(api, 'request').mockResolvedValue(pendingSnapshot()); await mount()
  expect(host.querySelector('a[aria-label="查看工单处理SYN-20"]')?.getAttribute('href')).toBe('/ticket-workflows?ticket_id=' + id(20))
  const registration = host.querySelector('a[aria-label="查看工单登记SYN-19"]') as HTMLAnchorElement
  expect(registration.getAttribute('href')).toBe('/tickets?ticket_id=' + id(19))
  registration.click()
  await vi.waitFor(() => expect(router.currentRoute.value.query.ticket_id).toBe(id(19)))
})
it('keeps complete zero separate from partial registrations and unknown SLA', async () => {
  vi.spyOn(api, 'request').mockResolvedValue(pendingSnapshot({ registrations: 0, known_pending: 0, terminal: 0, pending_tickets: 0, items: [] }))
  await mount()
  expect(host.textContent).toContain('当前授权范围没有未结束工单')
  expect(host.textContent).toContain('SLA状态尚未接入')
  expect(emitted.at(-1)?.current?.pending_tickets).toBe(0)
})
it('reports missing workflow coverage without substituting the known count as total', async () => {
  vi.spyOn(api, 'request').mockResolvedValue(pendingSnapshot({ registrations: 4, unconfigured: 1, pending_tickets: null, state: 'PARTIAL', unavailable_reason: 'REGISTRATIONS_WITHOUT_WORKFLOW' }))
  await mount()
  expect(host.textContent).toContain('完整待办数未知')
  expect(host.textContent).toContain('已确认未结束 2 条')
  expect(host.textContent).toContain('1 条登记未配置流程')
  expect(emitted.at(-1)?.current?.pending_tickets).toBeNull()
})
it('clears rows and parent count on failed refresh and restores them only after retry', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValueOnce(pendingSnapshot())
    .mockRejectedValueOnce(new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'synthetic', 'unit')).mockResolvedValueOnce(pendingSnapshot())
  await mount(); button('刷新工单').click(); await settle()
  expect(host.querySelector('table')).toBeNull(); expect(emitted.at(-1)?.current).toBeNull()
  expect(host.textContent).toContain('读取失败')
  button('重试待办汇总').click(); await settle()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
  expect(document.activeElement).toBe(host.querySelector('p[role="status"]'))
  expect(request.mock.calls.every(([path, options]) => path === '/dashboard/pending-tickets' && options?.method === 'GET')).toBe(true)
})
it('cancels, restores focus and rejects late transport success', async () => {
  let finish!: (value: unknown) => void
  const request = vi.spyOn(api, 'request').mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await mount(); button('取消读取工单').click(); await settle()
  expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  expect(document.activeElement).toBe(button('刷新工单'))
  finish(pendingSnapshot()); await settle()
  expect(host.querySelector('table')).toBeNull()
  expect(emitted.at(-1)?.current).toBeNull()
  expect(host.textContent).toContain('读取已取消')
})
it('labels customer scope as own reports only', async () => {
  session.value = { ...auth, user: { ...auth.user, role: 'viewer' } }
  vi.spyOn(api, 'request').mockResolvedValue(pendingSnapshot({ scope: 'REPORTER_ONLY' }))
  await mount()
  expect(host.textContent).toContain('我的待处理工单')
  expect(host.textContent).toContain('仅本人报障范围')
})
it.each(['actor', 'role', 'capability', 'mode', 'switching', 'version', 'account'])('clears stale rows and count after %s changes', async kind => {
  let finish!: (value: unknown) => void
  const request = vi.spyOn(api, 'request').mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    .mockImplementation(() => new Promise(() => {}))
  await mount()
  if (kind === 'actor') session.value = { ...auth, user: { ...auth.user, id: id(9) } }
  if (kind === 'role') session.value = { ...auth, user: { ...auth.user, role: 'viewer' } }
  if (kind === 'capability') session.value = { ...auth, capabilities: ['dashboard:read'] }
  if (kind === 'mode') session.value = { ...auth, mode: 'local_fixture' }
  if (kind === 'switching') switchingTenant.value = true
  if (kind === 'version') session.value = { ...auth, permission_version: 8 }
  if (kind === 'account') session.value = { ...auth, account_version: 2 }
  await settle()
  expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  finish(pendingSnapshot()); await settle()
  expect(host.querySelector('table')).toBeNull()
  expect(host.textContent).not.toContain('Synthetic pending')
  expect(emitted.at(-1)?.current).toBeNull()
})
it('never requests data without both capabilities', async () => {
  session.value = { ...auth, capabilities: ['tickets:read'] }
  const request = vi.spyOn(api, 'request'); await mount()
  expect(request).not.toHaveBeenCalled(); expect(host.querySelector('section')).toBeNull()
})
it('unmount aborts in-flight reads without retaining data in the parent', async () => {
  let finish!: (value: unknown) => void
  const request = vi.spyOn(api, 'request').mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await mount(); app!.unmount(); app = undefined; finish(pendingSnapshot()); await settle()
  expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  expect(emitted.at(-1)?.current).toBeNull()
  expect(host.textContent).toBe('')
})
