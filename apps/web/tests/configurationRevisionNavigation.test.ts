// @vitest-environment jsdom
// Real router/session refresh plus revision SFC setup. Synthetic transport only.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter, isNavigationFailure, onBeforeRouteLeave, RouterView } from 'vue-router'
const mocks = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn(), secondGuard: vi.fn(() => true) }))
vi.mock('element-plus', async original => ({ ...await original<typeof import('element-plus')>(), ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/utils/feedback', () => ({ feedback: { warning: mocks.warning } }))
import Revisions from '../src/components/ConfigurationRevisions.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session, switchingTenant } from '../src/stores/session'
import { contextTransitionGuards } from '../src/utils/contextTransitionGuard'
import type { Session } from '../src/api/types'

const auth: Session = { user: { id: '20000000-0000-4000-8000-000000000001', role: 'padmin', display_name: '合成管理员' }, tenant: { id: '10000000-0000-4000-8000-000000000001', name: '合成租户' }, allowed_tenants: [], capabilities: ['configurations:read', 'configurations:write'], permission_version: 1, account_version: 1, csrf_token: 'synthetic', mode: 'remote_test', identity_source: 'project_local' }
const configuration = { key: 'synthetic.setting', description: '合成设计登记', type: 'integer', scope: 'tenant', unit: null, value: null, registered_value: null, effective_value: null, decision_status: 'pending', source: 'design-document', question_ids: ['Q08'], activation_gate: 'PENDING', notes: null, allowed_range: null, contract_value_kind: 'pending_customer_decision', version: 1, draft_value: 31, lifecycle_status: 'DRAFT', mode: 'remote_test', source_registry_status: 'design_registry_not_runtime_config' }
const evidence = [{ reference: 'document-ref:synthetic', title: '合成资料', sha256: 'a'.repeat(64) }]
let app: App | undefined, host: HTMLElement | undefined, state: any
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
async function mount(withSecondGuard = false) {
  const revisions = { ...Revisions, render: () => null } as unknown as Component
  const second = defineComponent({ setup() { onBeforeRouteLeave(() => mocks.secondGuard()); const unregister = contextTransitionGuards.register(() => mocks.secondGuard()); onUnmounted(unregister); return () => null } })
  const routed = defineComponent({ render: () => h('div', [h(revisions, { modelValue: true, configuration, ref: (vm: any) => { if (vm) state = vm.$.setupState } }), ...(withSecondGuard ? [h(second)] : [])]) })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/configurations', component: routed }, { path: '/other', component: { render: () => null } }] })
  router.beforeEach(() => refreshProjectSession()); await router.push('/configurations')
  host = document.createElement('div'); document.body.appendChild(host); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await router.isReady(); await settle()
  return router
}
function fill() { state.openCreate(); Object.assign(state.form, { proposed: '60', reason: '未发送提案', evidence_refs: structuredClone(evidence) }) }
function unloadPrevented() { const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); return event.defaultPrevented }
beforeEach(() => {
  contextGeneration.value = 0; switchingTenant.value = false; session.value = structuredClone(auth)
  mocks.confirm.mockReset().mockResolvedValue('confirm'); mocks.warning.mockReset(); mocks.secondGuard.mockReset().mockReturnValue(true)
  vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
    if (path === '/session') return structuredClone(auth)
    if (options?.method) throw new ApiError(0, 'NETWORK_ERROR', 'synthetic uncertain write', 'test')
    return { items: [] }
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); host = undefined; session.value = null; vi.restoreAllMocks() })

it('preserves a proposal across unchanged session refresh and cancelled navigation/context transitions', async () => {
  const router = await mount(); fill(); await router.replace('/configurations?view=history'); await settle()
  expect(state.form.proposed).toBe('60'); expect(unloadPrevented()).toBe(true); expect(mocks.confirm).not.toHaveBeenCalled()
  mocks.confirm.mockRejectedValue('cancel')
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(await contextTransitionGuards.approve()).toBe(false)
  expect(state.form.evidence_refs).toEqual(evidence); expect(state.form.reason).toBe('未发送提案')
  mocks.confirm.mockResolvedValue('confirm'); await router.push('/other'); expect(router.currentRoute.value.path).toBe('/other'); expect(unloadPrevented()).toBe(false)
})
it('an approved discard does not clear a proposal when a later route/context guard refuses', async () => {
  const router = await mount(true); fill(); mocks.secondGuard.mockReturnValue(false)
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(mocks.confirm).toHaveBeenCalledOnce(); expect(state.form.proposed).toBe('60')
  expect(await contextTransitionGuards.approve()).toBe(false); expect(state.form.evidence_refs).toEqual(evidence)
})
it('unsaved review choice or comment prompts before leaving without sending the review', async () => {
  const router = await mount(); state.actionType = 'review'; state.reviewDecision = 'approve'; state.actionComment = '尚未提交的审核意见'
  mocks.confirm.mockRejectedValue('cancel')
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(unloadPrevented()).toBe(true)
  expect(state.actionComment).toBe('尚未提交的审核意见'); expect(state.reviewDecision).toBe('approve')
  expect(vi.mocked(api.request).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('unknown revision writes remain hard-blocked with the original request intact', async () => {
  const router = await mount(); fill(); await state.commands.submit('create', null, { proposed_value: 60, reason: '未发送提案', evidence_refs: evidence })
  const original = structuredClone(state.commandState.attempt)
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(await contextTransitionGuards.approve()).toBe(false); expect(unloadPrevented()).toBe(true)
  expect(state.commandState.attempt).toEqual(original); expect(mocks.confirm).not.toHaveBeenCalled()
})
it('rejects stale approval after editing or forced scope changes, while empty dialogs need no prompt', async () => {
  const router = await mount(); state.openCreate(); expect(unloadPrevented()).toBe(false)
  fill(); let finish!: (value: string) => void; mocks.confirm.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const navigation = router.push('/other'); await settle(); state.form.reason = '后来修改'; finish('confirm')
  expect(isNavigationFailure(await navigation)).toBe(true); expect(state.form.reason).toBe('后来修改')
  mocks.confirm.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const permission = contextTransitionGuards.approve(); await settle(); contextGeneration.value++; finish('confirm')
  expect(await permission).toBe(false); expect(state.form.reason).toBe(''); expect(unloadPrevented()).toBe(false)
})
