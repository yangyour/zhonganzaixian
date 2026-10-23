// @vitest-environment jsdom
import { createApp, h, nextTick, type App } from 'vue'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { ElAlert, ElButton } from 'element-plus'
import HeaderAlertBell from '../src/components/HeaderAlertBell.vue'
import { api, ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { bellContext as c, bellId as id, bellItem, bellSnapshot } from './alertBell.fixture'

const auth: Session = { user: { id: c.actorId, display_name: 'Synthetic', role: 'padmin' }, tenant: { id: c.tenantId, name: 'Synthetic' },
  allowed_tenants: [], permission_version: c.permissionVersion, account_version: 1, capabilities: ['dashboard:read', 'tickets:read'],
  csrf_token: 'SYNTHETIC', mode: 'remote_test', identity_source: 'synthetic-unit-only' }
let host: HTMLElement, app: App | undefined, router: Router
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  host = document.createElement('div'); document.body.append(host)
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { render: () => h('div') } },
    { path: '/monitoring-events', component: { render: () => h('div') } }] })
  await router.push('/'); await router.isReady()
  app = createApp(HeaderAlertBell); app.use(router); app.component('ElAlert', ElAlert); app.component('ElButton', ElButton)
  app.mount(host); await settle()
}
const trigger = () => host.querySelector('.alert-bell-trigger') as HTMLButtonElement
const panel = () => document.querySelector('.alert-bell-panel') as HTMLElement
const button = (name: string) => [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === name)!
async function open() { trigger().click(); await vi.waitFor(() => expect(panel()).not.toBeNull()); await settle() }
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0 })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('shows authoritative count and latest rows with suppression, recurrence and event links', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(bellSnapshot()); await mount(); await open()
  expect(trigger().getAttribute('aria-label')).toBe('当前租户未确认告警 2 条')
  expect(panel().querySelectorAll('li')).toHaveLength(2)
  expect(panel().textContent).toContain('×5'); expect(panel().textContent).toContain('已抑制')
  const link = panel().querySelector('a') as HTMLAnchorElement
  expect(link.getAttribute('href')).toBe('/monitoring-events?event_id=' + id(20))
  link.click(); await vi.waitFor(() => expect(router.currentRoute.value.query.event_id).toBe(id(20)))
  expect(request.mock.calls.every(([path, options]) => path === '/dashboard/alert-bell' && options?.method === 'GET')).toBe(true)
})
it('distinguishes zero and caps only the visible badge, not the accessible total', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(bellSnapshot({ total: 0, items: [] })); await mount(); await open()
  expect(panel().textContent).toContain('当前没有符合条件的未确认告警')
  request.mockResolvedValue(bellSnapshot({ total: 120, items: Array.from({ length: 5 }, (_, i) => bellItem(30 - i)) }))
  button('刷新告警').click(); await settle()
  expect(trigger().getAttribute('aria-label')).toBe('当前租户未确认告警 120 条')
  expect(trigger().textContent).toContain('99+')
})
it.each(['viewer', 'tadmin', 'missing-capability', 'local_fixture', 'switching'])('does not request or display for %s', async kind => {
  if (kind === 'missing-capability') session.value = { ...auth, capabilities: ['dashboard:read'] }
  else if (kind === 'local_fixture') session.value = { ...auth, mode: 'local_fixture' }
  else if (kind === 'switching') switchingTenant.value = true
  else session.value = { ...auth, user: { ...auth.user, role: kind as 'viewer' | 'tadmin' } }
  const request = vi.spyOn(api, 'request'); await mount()
  expect(trigger()).toBeNull(); expect(request).not.toHaveBeenCalled()
})
it('clears old data on refresh failure and retries with keyboard focus retained', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(bellSnapshot()); await mount(); await open()
  request.mockRejectedValueOnce(new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'synthetic outage', 'unit'))
  button('刷新告警').click(); await settle()
  expect(panel().querySelectorAll('li')).toHaveLength(0); expect(trigger().textContent).toContain('—')
  expect(trigger().getAttribute('aria-label')).toContain('读取失败')
  button('重试告警读取').click(); await settle()
  expect(panel().querySelectorAll('li')).toHaveLength(2)
  expect(document.activeElement).toBe(panel().querySelector('[role="status"]'))
  panel().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await settle()
  expect(trigger().getAttribute('aria-expanded')).toBe('false'); expect(document.activeElement).toBe(trigger())
})
it.each(['account', 'version', 'actor', 'tenant', 'generation', 'capability'])('invalidates old requests immediately on %s change', async kind => {
  let finish!: (data: unknown) => void
  vi.spyOn(api, 'request').mockImplementation(() => new Promise(resolve => { finish = resolve })); await mount(); await open()
  const oldFinish = finish
  if (kind === 'generation') contextGeneration.value++
  else if (kind === 'account') session.value = { ...auth, account_version: 2 }
  else if (kind === 'version') session.value = { ...auth, permission_version: 8 }
  else if (kind === 'actor') session.value = { ...auth, user: { ...auth.user, id: id(9) } }
  else if (kind === 'tenant') session.value = { ...auth, tenant: { ...auth.tenant, id: id(9) } }
  else session.value = { ...auth, capabilities: [] }
  oldFinish(bellSnapshot()); await settle()
  expect(document.querySelector('.alert-bell-panel li')).toBeNull()
  if (trigger()) expect(trigger().getAttribute('aria-expanded')).toBe('false')
})
it('cancels a pending read and restores focus to the remaining refresh control', async () => {
  let finish!: (data: unknown) => void
  vi.spyOn(api, 'request').mockImplementation(() => new Promise(resolve => { finish = resolve }))
  await mount(); await open(); button('取消读取告警').click(); await settle()
  expect(document.activeElement).toBe(button('刷新告警'))
  expect(panel().textContent).toContain('读取已取消')
  finish(bellSnapshot()); await settle()
  expect(panel().querySelectorAll('li')).toHaveLength(0)
  expect(document.activeElement).toBe(button('刷新告警'))
})
it('keeps cancellation focus when the transport rejects immediately on abort', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(bellSnapshot())
  await mount(); await open()
  request.mockImplementation((_path, options) => new Promise((_resolve, reject) => {
    options?.signal?.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')))
  }))
  button('刷新告警').click(); await settle(); button('取消读取告警').click(); await settle()
  expect(document.activeElement).toBe(button('刷新告警'))
  expect(panel().querySelectorAll('li')).toHaveLength(0)
})
