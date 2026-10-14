// @vitest-environment jsdom
// Real Vue Router plus actual page/form setup and synthetic HTTP; visual rendering is outside this regression.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, getCurrentInstance, h, nextTick, onUnmounted, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter, isNavigationFailure, onBeforeRouteLeave, RouterView } from 'vue-router'
import { context, id, page, photo, ticket } from './ticketReader.fixture'
const mocks = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn(), secondGuard: vi.fn(() => true) }))
vi.mock('element-plus', async original => ({ ...await original<typeof import('element-plus')>(), ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/utils/feedback', () => ({ feedback: { warning: mocks.warning } }))
import TicketsPage from '../src/pages/TicketsPage.vue'
import TicketReportForm from '../src/components/TicketReportForm.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session } from '../src/stores/session'
import { contextTransitionGuards } from '../src/utils/contextTransitionGuard'
import type { Session } from '../src/api/types'
const auth: Session = { user: { id: context.actorId, role: 'viewer', display_name: '合成报障用户' }, tenant: { id: context.tenantId, name: '合成租户' }, allowed_tenants: [], capabilities: ['tickets:read', 'tickets:report'], permission_version: 7, account_version: 1, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL', mode: 'remote_test', identity_source: 'project_local' }
const policy = { purpose: 'customer_report_photo', allowed_media_types: ['image/jpeg', 'image/png'], max_size_bytes: 5_000_000, max_attachments: 3, policy_source: 'USER_DEVELOPMENT_DIRECTION', production_approval: 'UNVERIFIED', scan_status: 'UNSCANNED', retention_until: null }
let app: App | undefined, host: HTMLElement, pageState: any, formState: any
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
async function mount(withSecondGuard = false) {
  const form = { ...TicketReportForm, render: () => null } as unknown as Component
  const second = defineComponent({ setup() { onBeforeRouteLeave(() => mocks.secondGuard()); const unregister = contextTransitionGuards.register(() => mocks.secondGuard()); onUnmounted(unregister); return () => null } })
  const routedPage = { ...TicketsPage, render() {
    pageState = (getCurrentInstance() as unknown as { setupState: any }).setupState
    return h('div', [h(form, { modelValue: pageState.createOpen, 'onUpdate:modelValue': (value: boolean) => { pageState.createOpen = value }, ref: (vm: any) => { if (vm) formState = vm.$.setupState } }), ...(withSecondGuard ? [h(second)] : [])])
  } } as unknown as Component
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/tickets', component: routedPage }, { path: '/dashboard', component: { render: () => null } }] })
  router.beforeEach(() => refreshProjectSession()); await router.push(`/tickets?ticket_id=${id(10)}`)
  host = document.createElement('div'); document.body.appendChild(host); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await router.isReady(); await settle()
  pageState.createOpen = true; await settle(); return router
}
function fill() { Object.assign(formState.form, { title: '待登记故障', description: '未保存报障输入', category: '网络不通', impact_scope: 'company', contact_name: '合成联系人', contact_phone: '123456', urgency: 'normal' }) }
function unloadPrevented() { const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); return event.defaultPrevented }
const writes = () => vi.mocked(api.request).mock.calls.filter(([, options]) => options?.method)
beforeEach(() => {
  session.value = structuredClone(auth); contextGeneration.value = 0; mocks.confirm.mockReset().mockResolvedValue('confirm'); mocks.warning.mockReset(); mocks.secondGuard.mockReset().mockReturnValue(true)
  const OriginalURL = URL; vi.stubGlobal('URL', class extends OriginalURL { static createObjectURL = vi.fn(() => 'blob:synthetic'); static revokeObjectURL = vi.fn() })
  vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
    if (path === '/session') return structuredClone(auth)
    if (options?.method) throw new ApiError(0, 'NETWORK_ERROR', 'synthetic lost write response', 'test')
    if (path === '/files/policy') return policy
    if (path.startsWith('/tickets?')) return page()
    if (path.startsWith('/tickets/')) return ticket()
    throw new ApiError(503, 'UPSTREAM_UNAVAILABLE', 'synthetic unavailable read', 'test')
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('collapsed partial report input survives cancelled route/context transitions and unload warns; confirmed route may leave', async () => {
  const router = await mount(); formState.form.contact_phone = '123'; formState.open = false; await settle()
  expect(unloadPrevented()).toBe(true); mocks.confirm.mockRejectedValue('cancel')
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(await contextTransitionGuards.approve()).toBe(false)
  expect(router.currentRoute.value.path).toBe('/tickets'); expect(formState.form.contact_phone).toBe('123')
  pageState.createOpen = true; await settle(); expect(formState.form.contact_phone).toBe('123')
  mocks.confirm.mockResolvedValue('confirm'); await router.push('/dashboard'); expect(router.currentRoute.value.path).toBe('/dashboard'); expect(unloadPrevented()).toBe(false); expect(writes()).toHaveLength(0)
})
it('later route or context guard refusal does not clear a report draft whose discard was already approved', async () => {
  const router = await mount(true); fill(); mocks.secondGuard.mockReturnValue(false)
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(mocks.confirm).toHaveBeenCalledOnce()
  expect(formState.form.description).toBe('未保存报障输入'); expect(await contextTransitionGuards.approve()).toBe(false)
  expect(mocks.confirm).toHaveBeenCalledTimes(2); expect(formState.form.description).toBe('未保存报障输入'); expect(unloadPrevented()).toBe(true)
})
it.each(['uncertain', 'accepted'])('%s report retains original key/operation when closed, navigated and unloaded', async outcome => {
  const router = await mount(); fill()
  if (outcome === 'accepted') vi.mocked(api.request).mockResolvedValueOnce({ operation_id: id(1010), status: 'PENDING' })
  await formState.submitReport(); const original = JSON.stringify(formState.attempt), operationId = formState.acceptedOperationId
  formState.open = false; await settle(); await router.replace('/tickets'); await settle()
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(unloadPrevented()).toBe(true)
  expect(JSON.stringify(formState.attempt)).toBe(original); expect(formState.acceptedOperationId).toBe(operationId); expect(mocks.confirm).not.toHaveBeenCalled(); expect(writes()).toHaveLength(1)
})
it('an in-flight registration blocks route navigation and keeps the original command until its response is known', async () => {
  const router = await mount(); fill(); let reject!: (reason: unknown) => void
  vi.mocked(api.request).mockImplementationOnce(() => new Promise((_resolve, fail) => { reject = fail }))
  const saving = formState.submitReport(), key = formState.attempt.key
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(unloadPrevented()).toBe(true)
  expect(formState.attempt.key).toBe(key); expect(writes()[0]![1]!.signal!.aborted).toBe(false)
  reject(new ApiError(0, 'NETWORK_ERROR', 'synthetic lost response', 'test')); await saving
  expect(formState.uncertain).toBe(true); expect(mocks.confirm).not.toHaveBeenCalled()
})
it.each(['uncertain', 'accepted'])('%s upload inside a collapsed report form blocks navigation and keeps its original file/key', async outcome => {
  const router = await mount(), queue = formState.photos, originalFile = new File(['synthetic'], 'synthetic.jpg', { type: 'image/jpeg' })
  expect(queue.add(originalFile, 1, 0)).toBeNull()
  if (outcome === 'accepted') vi.mocked(api.request).mockResolvedValueOnce({ operation_id: id(1030), status: 'PENDING' })
  await queue.upload(1); await settle(); const key = queue.rows.value[0].attempt.key, operationId = queue.rows.value[0].operationId
  formState.open = false; await settle(); await router.replace('/tickets')
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(unloadPrevented()).toBe(true)
  expect(queue.rows.value[0].file).toBe(originalFile); expect(queue.rows.value[0].attempt.key).toBe(key); expect(queue.rows.value[0].operationId).toBe(operationId)
  expect(mocks.confirm).not.toHaveBeenCalled(); expect(writes()).toHaveLength(1)
})
it('confirmed registration readback permits navigation without an unsaved prompt', async () => {
  const router = await mount(); fill()
  vi.mocked(api.request).mockResolvedValueOnce({ operation_id: id(1010), status: 'PENDING' })
    .mockResolvedValueOnce({ id: id(1010), tenant_id: context.tenantId, kind: 'CUSTOMER_REPORT_REGISTRATION', status: 'PENDING', step: 'AWAITING_TICKET_CONFIGURATION', progress: null, result_ref: `/api/v1/tickets/${id(10)}`, error: { code: 'CONFIG_PENDING', message: '合成规则门禁', retryable: false, question_ids: ['Q07'] }, created_at: '2026-10-14T00:00:00Z', updated_at: '2026-10-14T00:00:00Z' })
    .mockResolvedValueOnce(ticket())
  await formState.submitReport()
  expect(formState.complete).toBe(true); expect(formState.registeredTicket.id).toBe(id(10))
  expect(unloadPrevented()).toBe(false); await router.push('/dashboard'); expect(router.currentRoute.value.path).toBe('/dashboard'); expect(mocks.confirm).not.toHaveBeenCalled()
})
it.each(['uncertain-upload', 'accepted-upload', 'uncertain-append'])('retained %s from ticket details cannot be discarded through route navigation or unload', async kind => {
  const router = await mount(); pageState.createOpen = false; await settle()
  const queue = pageState.detailPhotos, originalFile = new File(['synthetic'], 'synthetic.jpg', { type: 'image/jpeg' })
  expect(queue.add(originalFile, 1, 0)).toBeNull()
  if (kind === 'uncertain-append') { Object.assign(queue.rows.value[0], { status: 'READY', record: { ...photo(), ticket_id: null }, operationId: photo().operation_id }); await pageState.detailAppender.append() }
  else { if (kind === 'accepted-upload') vi.mocked(api.request).mockResolvedValueOnce({ operation_id: id(1030), status: 'PENDING' }); await queue.upload(1); await settle() }
  const original = kind === 'uncertain-append' ? JSON.stringify(pageState.detailAppender.attempt.value) : queue.rows.value[0].attempt.key
  await router.replace('/tickets'); await settle(); expect(pageState.photoTargetId).toBe(id(10))
  expect(isNavigationFailure(await router.push('/dashboard'))).toBe(true); expect(unloadPrevented()).toBe(true); expect(mocks.confirm).not.toHaveBeenCalled()
  expect(queue.rows.value[0].file).toBe(originalFile)
  expect(kind === 'uncertain-append' ? JSON.stringify(pageState.detailAppender.attempt.value) : queue.rows.value[0].attempt.key).toBe(original)
  expect(writes()).toHaveLength(1)
})
