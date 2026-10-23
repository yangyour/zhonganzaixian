// @vitest-environment jsdom
// Real Vue Router and Element Plus with synthetic production-controller JSON; not browser QA.
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import axe from 'axe-core'
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
  localStorage.clear()
  session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = '' })

describe('event hits in the global search dialog', () => {
  it('keeps dialog chrome inside native sectioning content instead of adding page-wide landmarks', async () => {
    const banner=document.createElement('header');banner.textContent='合成页面顶栏';document.body.append(banner)
    await mount();document.querySelector<HTMLButtonElement>('.global-search-trigger')!.click();await settle()
    const dialog=document.querySelector<HTMLElement>('[role="dialog"]')!
    expect(dialog.getAttribute('aria-label')).toBe('搜索当前租户')
    expect(dialog.querySelector('.el-dialog__header')?.closest('section')).toBe(document.querySelector('.global-search-portal'))
    expect(dialog.querySelector('.el-dialog__footer')?.closest('section')).toBe(document.querySelector('.global-search-portal'))
    // Reproduce the former body-teleport semantics with the same real component DOM.
    const portal=document.querySelector('.global-search-portal')!,overlay=dialog.closest('.el-overlay')!
    document.body.append(overlay)
    try {
      const former=await axe.run(document.body,{runOnly:{type:'rule',values:['landmark-no-duplicate-banner']}})
      expect(former.violations.map(v=>v.id)).toContain('landmark-no-duplicate-banner')
    } finally { portal.append(overlay) }
    const result=await axe.run(document.body,{runOnly:{type:'rule',values:['landmark-no-duplicate-banner','landmark-no-duplicate-contentinfo','landmark-banner-is-top-level','landmark-contentinfo-is-top-level','aria-dialog-name']}})
    expect(result.violations).toEqual([])
    const input=document.querySelector<HTMLInputElement>('input[aria-label="当前租户搜索词"]')!;input.focus();expect(document.activeElement).toBe(input)
    button('关闭').click();await settle();await vi.waitFor(()=>expect((document.querySelector('.el-overlay') as HTMLElement).style.display).toBe('none'))
  })

  it('opens with slash without a request, lets users disable it and retains Control K', async () => {
    const request=vi.spyOn(api,'request')
    await mount()
    window.dispatchEvent(new KeyboardEvent('keydown',{key:'/',bubbles:true,cancelable:true}));await settle()
    const checkbox=document.querySelector('.search-shortcut-preference input') as HTMLInputElement
    expect(checkbox.checked).toBe(true);expect(request).not.toHaveBeenCalled()
    checkbox.click();await settle();expect(localStorage.getItem('mop.global-search.slash-enabled')).toBe('false')
    button('关闭').click();await settle()
    await vi.waitFor(()=>expect((document.querySelector('.el-overlay') as HTMLElement).style.display).toBe('none'))
    window.dispatchEvent(new KeyboardEvent('keydown',{key:'/',bubbles:true,cancelable:true}));await settle()
    expect((document.querySelector('.el-overlay') as HTMLElement).style.display).toBe('none')
    window.dispatchEvent(new KeyboardEvent('keydown',{key:'k',ctrlKey:true,bubbles:true,cancelable:true}));await settle()
    await vi.waitFor(()=>expect((document.querySelector('.el-overlay') as HTMLElement).style.display).not.toBe('none'))
    expect(checkbox.checked).toBe(false);expect(request).not.toHaveBeenCalled()
  })
  it('respects a saved slash opt-out after remount and tolerates unavailable storage', async () => {
    localStorage.setItem('mop.global-search.slash-enabled','false');await mount()
    expect(document.querySelector('.global-search-trigger')?.getAttribute('aria-keyshortcuts')).toBe('Control+k Meta+k')
    app!.unmount();app=undefined;host.remove()
    vi.spyOn(Storage.prototype,'getItem').mockImplementation(()=>{throw new Error('synthetic blocked storage')})
    vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('synthetic blocked storage')})
    await mount()
    window.dispatchEvent(new KeyboardEvent('keydown',{key:'/',bubbles:true,cancelable:true}));await settle()
    const checkbox=document.querySelector('.search-shortcut-preference input') as HTMLInputElement
    expect(checkbox.checked).toBe(true);checkbox.click();await settle();expect(checkbox.checked).toBe(false)
  })
  it('clears displayed results and query when the account version changes',async()=>{
    vi.spyOn(api,'request').mockResolvedValue(base);await mount();await search()
    session.value={...auth,account_version:2};await settle()
    expect(document.querySelectorAll('.search-group')).toHaveLength(0)
    expect(document.querySelector<HTMLInputElement>('input[aria-label="当前租户搜索词"]')?.value).toBe('')
  })
  it.each(['/home', '/monitoring-events'])('navigates from %s using only the named canonical event route', async path => {
    const request = vi.spyOn(api, 'request').mockResolvedValue(base)
    await mount(path); await search()
    expect(document.querySelectorAll('.search-group')).toHaveLength(3)
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
