// @vitest-environment jsdom
// Real Vue Router and session refresh, actual page/workspace setup; null renders replace only the visual controls.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, getCurrentInstance, h, nextTick, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter, isNavigationFailure, RouterView } from 'vue-router'
import { context, id, page, ticket } from './ticketReader.fixture'
const mocks = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn() }))
vi.mock('element-plus', async original => ({ ...await original<typeof import('element-plus')>(), ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/utils/feedback', () => ({ feedback: { warning: mocks.warning } }))
import TicketsPage from '../src/pages/TicketsPage.vue'
import WorklogWorkspace from '../src/components/WorklogWorkspace.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session } from '../src/stores/session'
import type { Session } from '../src/api/types'

const auth: Session = { user: { id: context.actorId, role: 'msp', display_name: '合成内部用户' }, tenant: { id: context.tenantId, name: '合成租户' }, allowed_tenants: [], capabilities: ['tickets:read', 'worklogs:read_own', 'worklogs:write_own'], permission_version: 7, account_version: 1, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'project_local' }
let app: App | undefined, host: HTMLElement, pageState: any, worklogState: any
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
async function mount() {
  const worklog = { ...WorklogWorkspace, render: () => null } as unknown as Component
  const routedPage = { ...TicketsPage, render() {
    pageState = (getCurrentInstance() as unknown as { setupState: any }).setupState
    return h(worklog, { guardedByParent: true, modelValue: pageState.worklogOpen, ticket: pageState.worklogTarget, 'onUpdate:modelValue': (value: boolean) => { pageState.worklogOpen = value }, ref: (vm: any) => { pageState.worklogWorkspace = vm; if (vm) worklogState = vm.$.setupState } })
  } } as unknown as Component
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tickets', component: routedPage }, { path: '/dashboard', component: { render: () => null } }] })
  router.beforeEach(() => refreshProjectSession())
  await router.push(`/tickets?ticket_id=${id(10)}`)
  host = document.createElement('div'); document.body.appendChild(host)
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await router.isReady(); await settle()
  await pageState.openWorklogs(); await settle()
  return router
}
beforeEach(() => {
  session.value = structuredClone(auth); contextGeneration.value = 0; mocks.confirm.mockReset(); mocks.warning.mockReset()
  vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
    if (path === '/session') return structuredClone(auth)
    if (options?.method) throw new ApiError(0, 'NETWORK_ERROR', 'synthetic lost write response', 'test')
    if (path.startsWith('/tickets?')) return page()
    if (path.includes('/worklogs')) return { items: [], has_more: false, next_before_created_at: null, next_before_id: null, source: 'postgresql', scope: 'OWN', page_size_limit: 100 }
    if (path.startsWith('/tickets/')) return ticket()
    throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'synthetic operation read unavailable', 'test')
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })

it('closing detail through the real router retains collapsed input across an unchanged session refresh, and leaving asks before discarding', async () => {
  const router = await mount(); worklogState.note = '收起后仍应保留'; worklogState.open = false; await settle()
  await router.replace('/tickets'); await settle()
  expect(pageState.worklogTarget?.id).toBe(id(10)); expect(worklogState.note).toBe('收起后仍应保留')
  expect(vi.mocked(api.request).mock.calls.filter(([path]) => path === '/session')).toHaveLength(2)
  mocks.confirm.mockRejectedValueOnce('cancel')
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); await settle()
  expect(router.currentRoute.value.path).toBe('/tickets'); expect(worklogState.note).toBe('收起后仍应保留'); expect(mocks.confirm).toHaveBeenCalledOnce()
  mocks.confirm.mockResolvedValueOnce('confirm'); await router.push('/dashboard'); await settle()
  expect(router.currentRoute.value.path).toBe('/dashboard')
  expect(vi.mocked(api.request).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('closing detail cannot erase an uncertain original request during an unchanged session refresh', async () => {
  const router = await mount(); worklogState.kind = 'REMOTE'; worklogState.startedAt = '2026-10-14T00:00:00Z'; worklogState.endedAt = '2026-10-14T01:00:00Z'; worklogState.note = '原请求保留'
  await worklogState.save(); const original = worklogState.state.attempt; expect(original.state).toBe('uncertain')
  worklogState.open = false; await settle(); await router.replace('/tickets'); await settle()
  expect(pageState.worklogTarget?.id).toBe(id(10)); expect(worklogState.state.attempt).toEqual(original)
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(mocks.confirm).not.toHaveBeenCalled()
  expect(vi.mocked(api.request).mock.calls.filter(([, options]) => options?.method)).toHaveLength(1)
})
