// @vitest-environment jsdom
// Real router/session refresh and actual SFC setup. Synthetic transport, no server or browser acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, onUnmounted, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter, isNavigationFailure, onBeforeRouteLeave, RouterView } from 'vue-router'
const mocks = vi.hoisted(() => ({ confirm: vi.fn(), warning: vi.fn(), secondGuard: vi.fn(() => true) }))
vi.mock('element-plus', async original => ({ ...await original<typeof import('element-plus')>(), ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/utils/feedback', () => ({ feedback: { warning: mocks.warning } }))
import Workspace from '../src/components/AssetImportWorkspace.vue'
import { api, ApiError } from '../src/api/client'
import { contextGeneration, refreshProjectSession, session, switchingTenant } from '../src/stores/session'
import { contextTransitionGuards } from '../src/utils/contextTransitionGuard'
import { assetImportTargets } from '../src/utils/assetImportMapping'
import type { Session } from '../src/api/types'

const auth: Session = { user: { id: '20000000-0000-4000-8000-000000000001', role: 'padmin', display_name: '合成管理员' }, tenant: { id: '10000000-0000-4000-8000-000000000001', name: '合成租户' }, allowed_tenants: [], capabilities: ['assets:write'], permission_version: 1, account_version: 1, csrf_token: 'synthetic', mode: 'remote_test', identity_source: 'project_local' }
const policy = { purpose: 'asset_import_source', allowed_formats: ['CSV', 'XLSX'], format_source: 'CONTRACT_M3_6', max_source_bytes: 20 * 1024 * 1024, max_data_rows: 500, max_source_columns: 64, max_sheets: 32, max_header_row: 20_000, max_cell_chars: 4096, resource_policy_version: 'asset-import-technical-v1', resource_limit_kind: 'TECHNICAL_CONTAINMENT', schema_version: 'contract-m3-6-standard15-v1', target_columns: assetImportTargets.map(target => ({ code: target.code, label: target.label, contract_always_required: target.alwaysMapped })), csv_encodings: ['UTF-8', 'UTF-16LE', 'UTF-16BE', 'GB18030'], csv_delimiters: [',', ';', '\t'], sheet_index_base: 0, column_index_base: 0, row_number_base: 1, scan_status: 'UNSCANNED', production_approval: 'UNVERIFIED', retention_until: null, business_status: 'CONFIG_PENDING', can_import: false, question_ids: ['Q20', 'Q31'] }
let app: App | undefined, host: HTMLElement | undefined, state: any
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
async function mount(withSecondGuard = false) {
  const workspace = { ...Workspace, render: () => null } as unknown as Component
  const second = defineComponent({ setup() { onBeforeRouteLeave(() => mocks.secondGuard()); const unregister = contextTransitionGuards.register(() => mocks.secondGuard()); onUnmounted(unregister); return () => null } })
  const routed = defineComponent({ render: () => h('div', [h(workspace, { modelValue: true, ref: (vm: any) => { if (vm) state = vm.$.setupState } }), ...(withSecondGuard ? [h(second)] : [])]) })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/assets', component: routed }, { path: '/other', component: { render: () => null } }] })
  router.beforeEach(() => refreshProjectSession()); await router.push('/assets')
  host = document.createElement('div'); document.body.appendChild(host); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(host); await router.isReady(); await settle()
  return router
}
function choose() { const file = new File(['synthetic'], 'synthetic.csv', { type: 'text/csv' }); expect(state.workspace.chooseFile(file)).toBe(true); return file }
function unloadPrevented() { const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); return event.defaultPrevented }
beforeEach(() => {
  contextGeneration.value = 0; switchingTenant.value = false; session.value = structuredClone(auth)
  mocks.confirm.mockReset().mockResolvedValue('confirm'); mocks.warning.mockReset(); mocks.secondGuard.mockReset().mockReturnValue(true)
  vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
    if (path === '/session') return structuredClone(auth)
    if (path === '/assets/import-policy') return policy
    if (options?.method) throw new ApiError(0, 'NETWORK_ERROR', 'synthetic uncertain write', 'test')
    throw new Error('UNEXPECTED_SYNTHETIC_REQUEST')
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); host = undefined; session.value = null; vi.restoreAllMocks() })

it('selected source survives same-page navigation and cancelled route/context transitions; confirmed departure may dispose it', async () => {
  const router = await mount(), original = choose()
  state.selection = { encoding: 'UTF-8', delimiter: ',', sheetIndex: null, headerRow: 1 }
  await router.replace('/assets?asset_id=synthetic'); await settle()
  expect(state.file).toBe(original); expect(mocks.confirm).not.toHaveBeenCalled(); expect(unloadPrevented()).toBe(true)
  mocks.confirm.mockRejectedValue('cancel')
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(await contextTransitionGuards.approve()).toBe(false)
  expect(state.file).toBe(original); expect(state.selection.headerRow).toBe(1); expect(router.currentRoute.value.path).toBe('/assets')
  mocks.confirm.mockResolvedValue('confirm'); await router.push('/other'); expect(router.currentRoute.value.path).toBe('/other'); expect(unloadPrevented()).toBe(false)
  expect(vi.mocked(api.request).mock.calls.every(([, options]) => !options?.method)).toBe(true)
})
it('approval never clears local source or mapping choices if another route/context guard rejects', async () => {
  const router = await mount(true), original = choose()
  state.mappingDraft = { targets: Object.fromEntries(assetImportTargets.map(target => [target.code, target.code === 'NOTE' ? 1 : null])), ignored: [0] }
  const mapping = JSON.stringify(state.mappingDraft); mocks.secondGuard.mockReturnValue(false)
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(mocks.confirm).toHaveBeenCalledOnce()
  expect(state.file).toBe(original); expect(JSON.stringify(state.mappingDraft)).toBe(mapping)
  expect(await contextTransitionGuards.approve()).toBe(false); expect(state.file).toBe(original); expect(JSON.stringify(state.mappingDraft)).toBe(mapping)
})
it('uncertain source write hard-blocks navigation and unload without offering discard', async () => {
  const router = await mount(), original = choose(); await state.workspace.upload()
  const key = state.uploadAttempt.key; expect(state.uploadAttempt.state).toBe('UNCERTAIN')
  await router.replace('/assets?source=retained'); await settle()
  expect(isNavigationFailure(await router.push('/other'))).toBe(true); expect(await contextTransitionGuards.approve()).toBe(false); expect(unloadPrevented()).toBe(true)
  expect(state.file).toBe(original); expect(state.uploadAttempt.key).toBe(key); expect(mocks.confirm).not.toHaveBeenCalled()
})
it('explicit new-source reset clears only a resolved local selection and removes the unload prompt', async () => {
  const router = await mount(); choose(); expect(unloadPrevented()).toBe(true)
  expect(state.workspace.reset()).toBe(true); expect(state.file).toBeNull(); expect(unloadPrevented()).toBe(false)
  await router.push('/other'); expect(mocks.confirm).not.toHaveBeenCalled()
})
it('a stale discard answer cannot approve a changed selection or forced context', async () => {
  const router = await mount(); choose(); let finish!: (value: string) => void
  mocks.confirm.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const navigation = router.push('/other'); await settle(); state.selection.headerRow = 2; finish('confirm')
  expect(isNavigationFailure(await navigation)).toBe(true); expect(state.selection.headerRow).toBe(2)
  mocks.confirm.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const permission = contextTransitionGuards.approve(); await settle(); contextGeneration.value++; finish('confirm')
  expect(await permission).toBe(false); expect(state.file).toBeNull()
})
