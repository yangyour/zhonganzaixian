// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { ElAlert, ElButton } from 'element-plus'
import ActionableTicketsPage from '../src/pages/ActionableTicketsPage.vue'
import { api } from '../src/api/client'
import { contextGeneration, session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { actionableClock, actionableContext as c, actionableId as id, actionableItem, actionablePage, actionableSnapshot, unknownActionableClock } from './dashboardActionable.fixture'

const auth: Session = { user: { id: c.actorId, display_name: 'Synthetic', role: 'padmin' }, tenant: { id: c.tenantId, name: 'Synthetic' },
  allowed_tenants: [], permission_version: c.permissionVersion, capabilities: ['dashboard:read', 'tickets:read'], csrf_token: 'SYNTHETIC_UNIT', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
let app: App | undefined, host: HTMLElement, router: Router
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/dashboard/actionable-tickets', component: ActionableTicketsPage },
    { path: '/dashboard', component: { template: '<p>总览</p>' } },
    { path: '/ticket-workflows', component: { template: '<p>处理</p>' } },
    { path: '/tickets', component: { template: '<p>登记</p>' } },
  ] })
  await router.push('/dashboard/actionable-tickets'); await router.isReady()
  host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.component('ElAlert', ElAlert); app.component('ElButton', ElButton); app.mount(host); await settle()
}
const button = (label: string) => [...host.querySelectorAll('button')].find(node => node.textContent?.trim() === label)!
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value++ })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('visits all 42 rows across three server pages and retains the same total with keyboard-readable pagination', async () => {
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    const query = new URL('http://unit' + path).searchParams
    return actionablePage(Number(query.get('page')), Number(query.get('page_size')))
  })
  await mount()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(20)
  expect(button('上一页').disabled).toBe(true)
  expect(host.querySelector('nav[aria-label="待处理工单分页"]')?.textContent).toContain('共 3 页')
  const ids = [...host.querySelectorAll('tbody tr td:first-child')].map(node => node.textContent)
  button('下一页').click(); await settle()
  expect(host.querySelector('[role="status"]')?.textContent).toContain('待处理 42 条')
  ids.push(...[...host.querySelectorAll('tbody tr td:first-child')].map(node => node.textContent))
  await vi.waitFor(() => expect(document.activeElement).toBe(host.querySelector('[role="status"]')))
  button('下一页').click(); await settle()
  ids.push(...[...host.querySelectorAll('tbody tr td:first-child')].map(node => node.textContent))
  expect(new Set(ids).size).toBe(42)
  expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
  expect(button('下一页').disabled).toBe(true)
  expect(request.mock.calls.map(([path]) => path)).toEqual([1, 2, 3].map(page => `/dashboard/actionable-tickets?page=${page}&page_size=20`))
})
it('clears a failed page and retries that page without reverting to the homepage first five', async () => {
  let failed = true
  const request = vi.spyOn(api, 'request').mockImplementation(async path => {
    const page = Number(new URL('http://unit' + path).searchParams.get('page'))
    if (page === 2 && failed) throw Error('synthetic page outage')
    return actionablePage(page)
  })
  await mount(); button('下一页').click(); await settle()
  expect(host.querySelector('table')).toBeNull()
  expect(host.textContent).toContain('读取失败')
  failed = false; button('重试待办汇总').click(); await settle()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(20)
  expect(request.mock.calls.at(-1)?.[0]).toBe('/dashboard/actionable-tickets?page=2&page_size=20')
})
it('returns from a ticket detail to the original second page and freshly reads that page', async () => {
  const request = vi.spyOn(api, 'request').mockImplementation(async path => actionablePage(Number(new URL('http://unit' + path).searchParams.get('page'))))
  await mount(); button('下一页').click(); await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.query.page).toBe('2'))
  expect(router.currentRoute.value.query.view).toMatch(/^[0-9a-f-]{36}$/)
  host.querySelector<HTMLAnchorElement>('a[aria-label="查看工单处理SYN-480"]')!.click(); await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/ticket-workflows'))
  router.back(); await settle()
  await vi.waitFor(() => expect(host.querySelector('nav')?.textContent).toContain('第 2 页'))
  expect(host.querySelector('tbody tr td:first-child')?.textContent).toBe('SYN-480')
  expect(request.mock.calls.map(([path]) => path)).toEqual([1, 2, 2].map(page => `/dashboard/actionable-tickets?page=${page}&page_size=20`))
})
it.each(['tenant', 'permission', 'account', 'generation'])('resets the saved page after %s changes while viewing a detail', async change => {
  const request = vi.spyOn(api, 'request').mockImplementation(async path => actionablePage(Number(new URL('http://unit' + path).searchParams.get('page'))))
  await mount(); button('下一页').click(); await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.query.page).toBe('2'))
  const oldView = router.currentRoute.value.query.view
  host.querySelector<HTMLAnchorElement>('a[aria-label="查看工单处理SYN-480"]')!.click(); await settle()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/ticket-workflows'))
  if (change === 'tenant') session.value = { ...auth, tenant: { id: id(9), name: 'Other synthetic' } }
  if (change === 'permission') session.value = { ...auth, permission_version: 8 }
  if (change === 'account') session.value = { ...auth, account_version: 2 }
  if (change === 'generation') contextGeneration.value++
  request.mockImplementation(async path => ({ ...actionablePage(Number(new URL('http://unit' + path).searchParams.get('page'))), tenant_id: session.value!.tenant.id, permission_version: session.value!.permission_version }))
  router.back(); await settle()
  await vi.waitFor(() => expect(host.querySelector('nav')?.textContent).toContain('第 1 页'))
  await vi.waitFor(() => expect(router.currentRoute.value.query.page).toBe('1'))
  expect(router.currentRoute.value.query.view).not.toBe(oldView)
  expect(request.mock.calls.at(-1)?.[0]).toBe('/dashboard/actionable-tickets?page=1&page_size=20')
})
it('shows service-side remaining time, red breaches, paused pending customer, and unknown values', async () => {
  vi.spyOn(api, 'request').mockResolvedValue(actionableSnapshot({ registrations: 4, known_pending: 3, pending_tickets: 3, evaluated_pending: 3,
    page_size: 20, item_limit: 20, known_overdue: 1, overdue_sla: null, unknown_sla: 1, unavailable_reasons: ['SLA_INPUTS_OR_READER_UNAVAILABLE'], items: [
      actionableItem(20, 'WORKFLOW_V33', 'OPEN', { overdue: true, response_clock: actionableClock({ status: 'BREACHED', breached: true, remaining_millis: 0 }) }),
      actionableItem(19, 'LIFECYCLE_V42', 'PENDING_CUSTOMER', { recovery_clock: actionableClock({ status: 'PAUSED', remaining_basis: 'EXPLICIT_BUDGET_MINUS_ELAPSED', remaining_millis: 0 }) }),
      actionableItem(18, 'LIFECYCLE_V42', 'WAITING_VENDOR', { overdue: null, recovery_clock: unknownActionableClock() }),
    ] }))
  await mount()
  expect(host.querySelector('.wf-clock.wf-overdue')?.textContent).toBe('响应：已超时')
  expect(host.textContent).toContain('恢复：剩余 0秒 · 已暂停')
  expect(host.textContent).toContain('恢复：未知')
  expect(host.textContent).toContain('已确认超时 1 条，另有 1 条SLA未知')
  expect(host.textContent).toContain('待客户确认')
})
it('offers first-page recovery if the population shrinks below the requested page', async () => {
  let total = 42
  vi.spyOn(api, 'request').mockImplementation(async path => actionablePage(Number(new URL('http://unit' + path).searchParams.get('page')), 20, total))
  await mount(); total = 2; button('下一页').click(); await settle()
  expect(host.textContent).toContain('当前页已无工单')
  button('返回第一页').click(); await settle()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(2)
})
it.each(['tenant', 'capability', 'account', 'switching'])('does not show late rows after %s changes', async kind => {
  let finish!: (value: unknown) => void
  const request = vi.spyOn(api, 'request').mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockImplementation(() => new Promise(() => {}))
  await mount()
  if (kind === 'tenant') session.value = { ...auth, tenant: { id: id(9), name: 'Other synthetic' } }
  if (kind === 'capability') session.value = { ...auth, capabilities: ['dashboard:read'] }
  if (kind === 'account') session.value = { ...auth, account_version: 2 }
  if (kind === 'switching') switchingTenant.value = true
  await settle(); finish(actionablePage(1)); await settle()
  expect(request.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  expect(host.querySelector('table')).toBeNull()
  expect(host.textContent).not.toContain('Synthetic pending')
})
