// @vitest-environment jsdom
// Real Vue Router and Element Plus with synthetic production-controller JSON; not browser QA.
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import GlobalSearch from '../src/components/GlobalSearch.vue'
import MonitoringEventsPage from '../src/pages/MonitoringEventsPage.vue'
import { context as eventContext, sampleWorkspace } from './monitoringEventControllerFixtures'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { controllerSearch } from './globalSearchControllerFixtures'

const base = controllerSearch(), event = base.groups[1].items![0]!
const auth: Session = {
  user: { id: '65000000-0000-4000-8000-000000000002', display_name: '合成测试用户', role: 'msp' },
  tenant: { id: base.tenant_id, name: '合成测试租户' }, allowed_tenants: [], capabilities: ['search:read', 'tickets:read'],
  permission_version: 7, csrf_token: 'SYNTHETIC-NOT-CREDENTIAL', mode: 'remote_test', identity_source: 'synthetic-unit-only',
}
let app: App | undefined, host: HTMLElement, router: Router
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount(path = '/home', actualEventPage = false) {
  const placeholder = { render: () => h('p', '已加载页面') }
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/home', component: placeholder }, { path: '/monitoring-events', name: 'monitoring-events', component: actualEventPage ? MonitoringEventsPage : placeholder }, { path: '/assets', name: 'assets', component: placeholder }, { path: '/tickets', name: 'tickets', component: placeholder }] })
  await router.push(path); await router.isReady()
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp({ render: () => h('div', [h(GlobalSearch), h(RouterView)]) }); app.use(router); app.use(ElementPlus); app.mount(host); await settle()
}
function button(label: string) { const b = [...document.querySelectorAll<HTMLButtonElement>('button')].find(x => x.textContent?.trim() === label); expect(b, label).toBeDefined(); return b! }
async function search(query = base.query) {
  document.querySelector<HTMLButtonElement>('.global-search-trigger')!.click(); await settle()
  const input = document.querySelector<HTMLInputElement>('input[aria-label="当前租户搜索词"]')!; expect(input).not.toBeNull()
  input.value = query; input.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  document.querySelector('form.global-search-form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await settle()
}
beforeEach(() => {
  session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })

describe('event hits in the global search dialog', () => {
  it.each(['/home', '/monitoring-events'])('navigates from %s using only the named canonical event route', async path => {
    const request = vi.spyOn(api, 'request').mockResolvedValue(base)
    await mount(path); await search()
    expect(document.querySelectorAll('.search-group')).toHaveLength(4)
    expect(document.body.textContent).toContain(event.rule_code)
    button(event.rule_name).click(); await settle()
    expect(router.currentRoute.value.name).toBe('monitoring-events'); expect(router.currentRoute.value.query).toEqual({ event_id: event.id })
    expect(document.querySelectorAll('.search-group')).toHaveLength(0)
    expect(request).toHaveBeenCalledTimes(1); expect(request.mock.calls[0]?.[0]).toBe('/search?' + new URLSearchParams({ q: base.query }))
  })
  it('preserves the modal and event hits when an unresolved page operation rejects navigation', async () => {
    const request = vi.spyOn(api, 'request').mockResolvedValue(base)
    await mount('/monitoring-events?event_id=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'); await search()
    router.beforeEach(() => false); button(event.rule_name).click(); await settle()
    expect(router.currentRoute.value.query.event_id).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    expect(document.body.textContent).toContain('跳转未完成，搜索结果已保留')
    expect(button(event.rule_name).disabled).toBe(false); expect(request).toHaveBeenCalledTimes(1)
  })
  it('search click preserves modal, query and hits when production refresh precedes the real unresolved-write page guard', async () => {
    session.value = { ...auth, identity_source: 'project_local', capabilities: [...auth.capabilities, 'ticket_notes:write_internal'] }
    const eventList = JSON.parse(JSON.stringify(sampleWorkspace()).replaceAll(eventContext.tenantId, auth.tenant.id))
    const request = vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
      if (path === '/session') return structuredClone(session.value)
      if (options?.method === 'POST') throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test')
      if (path.startsWith('/events?')) return eventList
      if (path.startsWith('/search?')) return base
      throw new Error('An event detail read must not run while the original write is unresolved')
    })
    await mount('/monitoring-events', true)
    router.beforeEach(async () => { await refreshProjectSession() })
    button('开始处理').click(); await settle(); expect(document.body.textContent).toContain('原操作结果尚未确认')
    const original = request.mock.calls.find(([, options]) => options?.method === 'POST')!
    await search(); button(event.rule_name).click(); await settle()
    expect(router.currentRoute.value.query.event_id).toBeUndefined()
    expect(document.body.textContent).toContain('跳转未完成，搜索结果已保留')
    expect(button(event.rule_name).disabled).toBe(false)
    expect(document.querySelector<HTMLInputElement>('input[aria-label="当前租户搜索词"]')?.value).toBe(base.query)
    expect(request.mock.calls.filter(([, options]) => options?.method === 'POST')).toEqual([original])
    expect(request.mock.calls.filter(([path]) => path.startsWith('/events/'))).toEqual([original])
    expect(request.mock.calls.at(-1)?.[0]).toBe('/session')
  })
  it.each(['viewer', 'tadmin'] as const)('shows authorization status without a fabricated zero count for %s', async role => {
    session.value = { ...auth, user: { ...auth.user, role } }
    vi.spyOn(api, 'request').mockResolvedValue(controllerSearch(`role_${role}`))
    await mount(); await search()
    expect(document.body.textContent).toContain('当前角色没有内部监控事件读取权限')
    expect(document.body.textContent).not.toContain('未找到匹配的监控事件')
    expect([...document.querySelectorAll('button')].some(b => b.textContent === event.rule_name)).toBe(false)
  })
  it('asks for a narrower query on overflow rather than promising the event list contains all matches', async () => {
    vi.spyOn(api, 'request').mockResolvedValue(controllerSearch('events_overflow'))
    await mount(); await search()
    const group = document.querySelectorAll('.search-group')[1]!
    expect(group.querySelectorAll('li')).toHaveLength(4); expect(group.textContent).toContain('请细化关键词后重新搜索')
    expect(group.textContent).not.toContain('前往'); expect(group.textContent).not.toContain('全部')
  })
  it('renders contract-valid stored multiline/tab rule text as text and retains safe navigation', async () => {
    const data = controllerSearch('stored_rule_control_text'), hit = data.groups[1].items![0]!
    vi.spyOn(api, 'request').mockResolvedValue(data)
    await mount(); await search(data.query)
    const group = document.querySelectorAll('.search-group')[1]!
    expect(group.textContent).toContain('Link\nDown'); expect(group.textContent).toContain('LINK\tRULE')
    button(hit.rule_name).click(); await settle(); expect(router.currentRoute.value.query.event_id).toBe(hit.id)
  })
  it('clears displayed authorized hits immediately after search permission is lost', async () => {
    vi.spyOn(api, 'request').mockResolvedValue(base); await mount(); await search()
    session.value = { ...auth, capabilities: [] }; await settle()
    expect(document.querySelector('.global-search-trigger')).toBeNull(); expect(document.querySelectorAll('.search-group')).toHaveLength(0)
  })
})
