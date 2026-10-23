// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import Panel from '../src/components/CmdbImportExecutionPanel.vue'
import { api, ApiError } from '../src/api/client'
import { session, contextGeneration, switchingTenant } from '../src/stores/session'
import { cmdbSource, actor } from './fixtures/cmdbSource'

let app: App | undefined, host: HTMLElement, state: any
const source = cmdbSource()
const absent = () => ({ tenant_id: source.tenant_id, source_id: source.id, status: 'NO_COMMITTED_RECEIPT', receipt: null, absence_proves_failure: false })
const receipt = () => ({ tenant_id: source.tenant_id, source_id: source.id, source_version: source.version,
  artifact_id: source.parsed_artifact!.id, parse_hash: source.parsed_artifact!.parse_hash, status: 'IMPORTED_DRAFTS', imported_count: 1,
  assets: [{ record: 2, source_row: 2, asset_id: '64000000-0000-4000-8000-000000000009', asset_version: 3 }],
  quota: { limit: 20, before: 7, after: 8 }, extended_attributes: 'RETAINED_IN_IMMUTABLE_SOURCE', monitoring_started: false, production_approved: false,
  configuration_releases: Object.fromEntries(['asset.import_steps','asset.ip_scope','tenant.contract_device_quota'].map(key => [key, { id: source.id, version: 1 }])) })
async function settle() { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick() } }
async function mount(events: Record<string, unknown> = {}) {
  host = document.createElement('div'); document.body.append(host)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }, { path: '/assets', component: { template: '<div />' } }] })
  await router.push('/')
  app = createApp(Panel, { source, ...events })
  app.component('el-button', { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' })
  app.component('el-checkbox', { props: ['modelValue','disabled'], emits: ['update:modelValue'], template: '<label><input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)"><slot /></label>' })
  app.use(router); state = (app.mount(host).$ as any).setupState; await settle()
}
function button(text: string) { return [...host.querySelectorAll('button')].find(b => b.textContent?.includes(text))! }
beforeEach(() => {
  sessionStorage.clear(); switchingTenant.value = false; contextGeneration.value = 0
  session.value = { user: { id: actor, display_name: 'Synthetic', role: 'msp' }, tenant: { id: source.tenant_id, name: 'Synthetic' },
    allowed_tenants: [], capabilities: ['assets:write','assets:read'], permission_version: 7, account_version: 1, csrf_token: 'unit', mode: 'remote_test', identity_source: 'project_local' }
  vi.spyOn(api, 'request').mockResolvedValue(absent())
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; sessionStorage.clear(); vi.restoreAllMocks() })

it('uses explicit acknowledgement and real command; renders the receipt and asset link', async () => {
  const imported = vi.fn(); await mount({ onImported: imported })
  expect(button('导入草稿').disabled).toBe(true)
  const ack = host.querySelector<HTMLInputElement>('input[type=checkbox]')!; ack.checked = true; ack.dispatchEvent(new Event('change')); await settle()
  expect(button('导入草稿').disabled).toBe(false)
  vi.mocked(api.request).mockResolvedValueOnce(receipt()); button('导入草稿').click(); await settle()
  expect(host.textContent).toContain('已确认导入 1 条资产草稿'); expect(host.textContent).toContain('提交时的事实')
  expect(host.querySelector('a')?.getAttribute('href')).toContain('/assets?asset_id='); expect(imported).toHaveBeenCalledOnce()
  expect(document.activeElement?.getAttribute('role')).toBe('status')
})
it('shows all 33 column dispositions without treating preserved relationships as active', async () => {
  await mount(); expect(host.querySelectorAll('[aria-label="33列字段处置"] tbody tr')).toHaveLength(33)
  expect(host.textContent).toContain('尚不建立关联或生效'); expect(host.textContent).toContain('不自动套用监控模板')
})
it('uncertain response exposes original retry and emits navigation protection', async () => {
  const pending = vi.fn(); await mount({ onPending: pending }); state.acknowledged = true; await settle()
  vi.mocked(api.request).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'synthetic offline', 'unit'))
  button('导入草稿').click(); await settle(); expect(host.textContent).toContain('原请求结果待确认')
  expect(button('使用原请求重试')).toBeTruthy(); expect(pending).toHaveBeenLastCalledWith(true)
  const original = vi.mocked(api.request).mock.calls.at(-1)![1]!
  vi.mocked(api.request).mockResolvedValueOnce(receipt()); button('使用原请求重试').click(); await settle()
  expect(vi.mocked(api.request).mock.calls.at(-1)![1]!.idempotencyKey).toBe(original.idempotencyKey)
  expect(pending).toHaveBeenLastCalledWith(false)
})
it('stop waiting retains original request and focuses the remaining recovery action', async () => {
  await mount(); state.acknowledged = true; await settle()
  let resolve!: (v: unknown) => void; vi.mocked(api.request).mockImplementationOnce(() => new Promise(r => { resolve = r }))
  button('导入草稿').click(); await settle(); button('停止等待').click(); await settle()
  expect(document.activeElement).toBe(button('使用原请求重试')); expect(host.textContent).toContain('原请求结果待确认')
  resolve(receipt()); await settle(); expect(host.textContent).not.toContain('已确认导入 1')
})
it('a customer or foreign creator sees no execution controls or request dispatch', async () => {
  session.value = { ...session.value!, user: { ...session.value!.user, role: 'tadmin' } }
  await mount(); expect(api.request).not.toHaveBeenCalled(); expect(host.querySelectorAll('button')).toHaveLength(0)
})
it('context revocation removes known receipt and its asset links immediately', async () => {
  vi.mocked(api.request).mockResolvedValueOnce({ ...absent(), status: 'COMPLETED', receipt: receipt() })
  await mount(); expect(host.querySelector('a')).not.toBeNull(); session.value = { ...session.value!, capabilities: [] }; await settle()
  expect(host.querySelector('a')).toBeNull(); expect(host.textContent).not.toContain('已确认导入')
})
it('normal asset link navigation closes its enclosing import surface while modified clicks preserve it', async () => {
  const navigate = vi.fn()
  vi.mocked(api.request).mockResolvedValueOnce({ ...absent(), status: 'COMPLETED', receipt: receipt() })
  await mount({ onNavigate: navigate })
  state.navigateToAsset(new MouseEvent('click', { ctrlKey: true })); expect(navigate).not.toHaveBeenCalled()
  host.querySelector('a')!.click(); await settle(); expect(navigate).toHaveBeenCalledOnce()
})
