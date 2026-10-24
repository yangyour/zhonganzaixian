// @vitest-environment jsdom
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, isNavigationFailure, NavigationFailureType, RouterView, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Page from '../src/pages/MonitoringEventsPage.vue'
import { api, ApiError, type RequestOptions } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session, switchingTenant } from '../src/stores/session'
import type { Session } from '../src/api/types'
import { context, sampleDetail, sampleWorkspace } from './monitoringEventControllerFixtures'

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const auth: Session = {
  user: { id: context.actorId, display_name: '合成测试管理员', role: 'padmin' },
  tenant: { id: context.tenantId, name: '合成测试租户' }, allowed_tenants: [],
  capabilities: ['tickets:read', 'ticket_notes:write_internal', 'configurations:write'],
  permission_version: 3, csrf_token: 'SYNTHETIC-D15', mode: 'remote_test', identity_source: 'project_local',
}
let app: App | undefined, host: HTMLElement, router: Router
const detail = (id: string) => {
  const d = sampleDetail().data
  d.event.id = id; d.event.rule_name = `详情-${id === A ? 'A' : 'B'}`
  d.members.forEach(m => m.event_id = id); d.noise_records.forEach(n => n.event_id = id)
  if (d.autoticket_attempt) d.autoticket_attempt.event_id = id
  return d
}
function deferred() { let resolve!: (v: unknown) => void, reject!: (e: unknown) => void; const promise = new Promise<unknown>((r, j) => { resolve = r; reject = j }); return { promise, resolve, reject } }
async function settle() { for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() } }
async function mount(path = '/monitoring-events') {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/monitoring-events', name: 'monitoring-events', component: Page }, { path: '/other', component: { render: () => h('p', '其他页面') } }] })
  await router.push(path); await router.isReady()
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await settle()
}
function setup(callback?: (path: string, options?: RequestOptions) => unknown) {
  // Keep list/detail navigation assertions independent of the recovery panel's separate GET.
  const primary = vi.fn(async (path: string, options?: RequestOptions) => callback ? callback(path, options) : path.startsWith('/events?') ? sampleWorkspace() : detail(path.slice('/events/'.length)))
  vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
    if (path.endsWith('/recovery-observations')) return {
      tenant_id: auth.tenant.id, actor_id: auth.user.id, permission_version: session.value!.permission_version,
      event_id: path.split('/')[2], asset_id: A, event_version: 1, monitor_state: 'PROBLEM', handling_state: 'NEW',
      recovery_evidence_status: 'NOT_CONFIRMED', configuration_status: 'CONFIG_PENDING', missing_reason: 'CONFIG_PENDING',
      policy_release_id: null, policy_release_version: null, rule: null, observed_at: '2026-10-23T10:00:00Z',
      source: 'postgresql', source_binding_available: false,
    }
    return primary(path, options)
  })
  return primary
}
const details = () => host.querySelector('[aria-label="监控事件详情"]')
const button = (label: string) => { const b = [...host.querySelectorAll('button')].find(b => b.textContent?.trim() === label); expect(b, label).toBeDefined(); return b! }
beforeEach(() => { session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0 })
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

describe('monitoring event links with real Vue Router navigation', () => {
  it('opens an initial event outside the newest 25 independently of a delayed list', async () => {
    const list = deferred(), request = setup(path => path.startsWith('/events?') ? list.promise : detail(A))
    await mount(`/monitoring-events?event_id=${A}`)
    expect(sampleWorkspace().events.some(e => e.id === A)).toBe(false)
    expect(request.mock.calls.map(([path]) => path)).toEqual(['/events?window=24h&page_size=25', `/events/${A}`])
    expect(host.querySelector('.event-table-scroll')).toBeNull(); expect(details()?.textContent).toContain('详情-A')
    list.resolve(sampleWorkspace()); await settle()
    expect(host.querySelector('.event-table-scroll')).not.toBeNull(); expect(details()?.textContent).toContain('详情-A')
    expect(request.mock.calls.every(([, options]) => !options?.method && options?.signal instanceof AbortSignal)).toBe(true)
  })
  it('keeps an authorized detail visible when the independent list fails', async () => {
    setup(path => { if (path.startsWith('/events?')) throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成列表故障', 'test'); return detail(A) })
    await mount(`/monitoring-events?event_id=${A}`)
    expect(host.querySelector('.event-table-scroll')).toBeNull(); expect(details()?.textContent).toContain('详情-A')
    expect(host.querySelector('[role="alert"]')).not.toBeNull()
  })
  it('reacts to same-page query changes, Back/Forward, closing, and reopening without remounting or writes', async () => {
    const request = setup(); await mount(`/monitoring-events?event_id=${A}`)
    await router.push({ name: 'monitoring-events', query: { event_id: B } }); await settle()
    expect(details()?.textContent).toContain('详情-B')
    router.back(); await settle(); expect(router.currentRoute.value.query.event_id).toBe(A); expect(details()?.textContent).toContain('详情-A')
    router.forward(); await settle(); expect(router.currentRoute.value.query.event_id).toBe(B); expect(details()?.textContent).toContain('详情-B')
    button('关闭事件详情').click(); await settle(); expect(router.currentRoute.value.query.event_id).toBeUndefined(); expect(details()).toBeNull()
    router.back(); await settle(); expect(details()?.textContent).toContain('详情-B')
    expect(request.mock.calls.filter(([path]) => path.startsWith('/events?'))).toHaveLength(1)
    expect(request.mock.calls.every(([, o]) => !o?.method)).toBe(true)
  })
  it('list detail buttons update the canonical URL and retain unrelated query fields', async () => {
    const data = sampleWorkspace(), id = data.events[0]!.id
    const request = setup(path => path.startsWith('/events?') ? data : detail(id))
    await mount('/monitoring-events?view=all'); button('查看详情').click(); await settle()
    expect(router.currentRoute.value.query).toEqual({ view: 'all', event_id: id })
    expect(request.mock.calls.at(-1)?.[0]).toBe(`/events/${id}`)
    button('关闭事件详情').click(); await settle(); expect(router.currentRoute.value.query).toEqual({ view: 'all' })
  })
  it.each(['', 'bad', '../events', 'https://example.com', A.toUpperCase(), `${A}&event_id=${A}`, `${A}&event_id=${B}`])('rejects malformed or repeated event_id=%s without any detail request', async raw => {
    const request = setup(); await mount(`/monitoring-events?event_id=${raw}`)
    expect(request).toHaveBeenCalledTimes(1); expect(request.mock.calls[0]?.[0]).toContain('/events?')
    expect(details()?.querySelector('[role="alert"]')).not.toBeNull(); expect(host.querySelector('[aria-label="监控事件详情抽屉"]')).toBeNull()
  })
  it('a malformed newer URL clears and aborts the previously selected detail', async () => {
    const wait = deferred(), request = setup(path => path.startsWith('/events?') ? sampleWorkspace() : wait.promise)
    await mount(`/monitoring-events?event_id=${A}`); const signal = request.mock.calls[1]![1]!.signal!
    await router.push('/monitoring-events?event_id=bad'); await settle(); expect(signal.aborted).toBe(true)
    wait.resolve(detail(A)); await settle(); expect(host.textContent).not.toContain('详情-A')
    expect(request).toHaveBeenCalledTimes(2)
  })
  it.each(['foreign', 'nonexistent'])('shows the same safe 404 for a %s ID and clears the old detail', async _kind => {
    const request = setup(path => { if (path.startsWith('/events?')) return sampleWorkspace(); if (path.endsWith(A)) return detail(A); throw new ApiError(404, 'NOT_FOUND', '不存在或无权访问', 'test') })
    await mount(`/monitoring-events?event_id=${A}`)
    await router.push({ query: { event_id: B } }); await settle()
    expect(details()?.textContent).toContain('事件不存在或当前无权查看。'); expect(details()?.textContent).not.toContain('详情-A')
    expect(request.mock.calls.filter(([path]) => path === `/events/${B}`)).toHaveLength(1)
  })
  it('delayed A cannot replace B, clear B loading, or overwrite B errors', async () => {
    const a = deferred(), b = deferred(), request = setup(path => path.startsWith('/events?') ? sampleWorkspace() : path.endsWith(A) ? a.promise : b.promise)
    await mount(`/monitoring-events?event_id=${A}`); const signalA = request.mock.calls[1]![1]!.signal!
    await router.push({ query: { event_id: B } }); await settle(); expect(signalA.aborted).toBe(true)
    a.resolve(detail(A)); await settle(); expect(details()?.textContent).toContain('正在读取所选事件详情'); expect(details()?.textContent).not.toContain('详情-A')
    b.resolve(detail(B)); await settle(); expect(details()?.textContent).toContain('详情-B')
  })
  it.each(['capability', 'role', 'tenantSwitch'] as const)('clears and aborts selected detail on %s permission loss', async kind => {
    const wait = deferred(), request = setup(path => path.startsWith('/events?') ? sampleWorkspace() : wait.promise)
    await mount(`/monitoring-events?event_id=${A}`); const signal = request.mock.calls[1]![1]!.signal!
    if (kind === 'capability') session.value = { ...auth, capabilities: [] }
    if (kind === 'role') session.value = { ...auth, user: { ...auth.user, role: 'viewer' } }
    if (kind === 'tenantSwitch') switchingTenant.value = true
    await settle(); expect(signal.aborted).toBe(true); expect(details()).toBeNull()
    wait.resolve(detail(A)); await settle(); expect(details()).toBeNull(); expect(request).toHaveBeenCalledTimes(2)
  })
  it('treats capabilities as a set on refresh but clears and rereads on a real grant change', async () => {
    const request = setup(); await mount(`/monitoring-events?event_id=${A}`)
    const before = request.mock.calls.length
    session.value = { ...auth, capabilities: [...auth.capabilities].reverse() }; await settle()
    expect(request).toHaveBeenCalledTimes(before); expect(details()?.textContent).toContain('详情-A')
    session.value = { ...auth, capabilities: [...auth.capabilities, 'audit:read'] }; await settle()
    expect(request).toHaveBeenCalledTimes(before + 2)
    session.value = { ...auth, capabilities: ['tickets:read'] }; await settle()
    expect(request).toHaveBeenCalledTimes(before + 4); expect([...host.querySelectorAll('button')].some(b => b.textContent?.trim() === '开始处理')).toBe(false)
  })
  it.each(['pending', 'unknown'] as const)('reordered production session refresh cannot discard a %s write before the same-route guard', async phase => {
    const wait = deferred(), request = setup((path, options) => {
      if (path === '/session') return { ...structuredClone(session.value), capabilities: [...session.value!.capabilities].reverse() }
      if (options?.method === 'POST') return phase === 'pending' ? wait.promise : Promise.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test'))
      return path.startsWith('/events?') ? sampleWorkspace() : detail(A)
    })
    await mount(`/monitoring-events?event_id=${A}`)
    // Production ordering: global beforeEach refreshes and replaces /session before component beforeRouteUpdate.
    router.beforeEach(async () => { await refreshProjectSession() })
    button('开始处理').click(); await settle(); const original = request.mock.calls.find(([, o]) => o?.method === 'POST')!, previousSession = session.value
    const failure = await router.push({ name: 'monitoring-events', query: { event_id: B } }); await settle()
    expect(session.value).not.toBe(previousSession); expect({ ...session.value, capabilities: new Set(session.value!.capabilities) }).toEqual({ ...previousSession, capabilities: new Set(previousSession!.capabilities) })
    expect(isNavigationFailure(failure, NavigationFailureType.aborted)).toBe(true)
    expect(router.currentRoute.value.query.event_id).toBe(A); expect(details()?.textContent).toContain('详情-A')
    expect(request.mock.calls.filter(([path]) => path.startsWith('/events?'))).toHaveLength(1)
    expect(request.mock.calls.some(([path]) => path === `/events/${B}`)).toBe(false)
    expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toEqual([original])
    if (phase === 'pending') { wait.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test')); await settle() }
    expect(host.textContent).toContain('原操作结果尚未确认')
    button('原请求与幂等键重试').click(); await settle()
    expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toEqual([original, original])
  })
  it.each([
    ['pending', 'back'], ['unknown', 'back'], ['pending', 'forward'], ['unknown', 'forward'],
  ] as const)('equivalent refresh during %s-write %s navigation preserves the selected event and operation', async (phase, direction) => {
    const wait = deferred(), request = setup((path, options) => {
      if (path === '/session') return structuredClone(session.value)
      if (options?.method === 'POST') return phase === 'pending' ? wait.promise : Promise.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test'))
      return path.startsWith('/events?') ? sampleWorkspace() : detail(path.slice('/events/'.length))
    })
    await mount(`/monitoring-events?event_id=${A}`)
    await router.push({ name: 'monitoring-events', query: { event_id: B } }); await settle()
    if (direction === 'forward') { router.back(); await settle() }
    const selectedId = router.currentRoute.value.query.event_id, selectedText = details()?.textContent, reads = request.mock.calls.filter(([, o]) => !o?.method).length
    router.beforeEach(async () => { await refreshProjectSession() })
    button('开始处理').click(); await settle(); const original = request.mock.calls.find(([, o]) => o?.method === 'POST')!, previousSession = session.value
    router[direction](); await settle()
    expect(session.value).not.toBe(previousSession); expect(session.value).toEqual(previousSession)
    expect(router.currentRoute.value.query.event_id).toBe(selectedId); expect(details()?.textContent).toBe(selectedText)
    expect(request.mock.calls.filter(([, o]) => !o?.method)).toHaveLength(reads + 1)
    expect(request.mock.calls.at(-1)?.[0]).toBe('/session')
    expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toEqual([original])
    if (phase === 'pending') { wait.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test')); await settle() }
    expect(host.textContent).toContain('原操作结果尚未确认')
  })
  it.each(['pending', 'unknown'] as const)('blocks same-page and cross-page navigation during a %s write, retaining its exact request', async phase => {
    const wait = deferred(), request = setup((path, options) => {
      if (options?.method === 'POST') return phase === 'pending' ? wait.promise : Promise.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test'))
      return path.startsWith('/events?') ? sampleWorkspace() : detail(A)
    })
    await mount(`/monitoring-events?event_id=${A}`)
    button('开始处理').click(); await settle(); const original = request.mock.calls.find(([, o]) => o?.method === 'POST')!
    const same = await router.push({ name: 'monitoring-events', query: { event_id: B } })
    const other = await router.push('/other'); await settle()
    expect(isNavigationFailure(same, NavigationFailureType.aborted)).toBe(true); expect(isNavigationFailure(other, NavigationFailureType.aborted)).toBe(true)
    expect(router.currentRoute.value.query.event_id).toBe(A); expect(details()?.textContent).toContain('详情-A')
    expect(request.mock.calls.filter(([, o]) => o?.method === 'POST')).toEqual([original])
    expect(request.mock.calls.some(([path]) => path === `/events/${B}`)).toBe(false)
    if (phase === 'pending') { wait.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成结果未知', 'test')); await settle() }
    expect(host.textContent).toContain('原操作结果尚未确认')
  })
})
