// @vitest-environment jsdom
// Actual page, source reader and router with isolated HTTP responses; browser/PG proof is separate.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus from 'element-plus'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant, contextGeneration } from '../src/stores/session'
import type { Asset } from '../src/api/types'

vi.mock('../src/components/LiveMonitoringPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/components/AssetImportWorkspace.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/components/AssetMetadataWorkspace.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/components/ManualWarrantyWorkspace.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/components/AssetDependencyWorkspace.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../src/components/AssetDateAssessmentPanel.vue', () => ({ default: { template: '<div />' } }))
import Page from '../src/pages/AssetsPage.vue'

const tenant = '21000000-0000-4000-8000-000000000001', actor = '22000000-0000-4000-8000-000000000001', id = '23000000-0000-4000-8000-000000000001'
const asset: Asset = { id, tenant_id: tenant, name: 'Synthetic public-facts asset', type: 'switch', management_ip: '192.0.2.1', site_id: 'site-a', status: 'unknown', lifecycle_status: 'DRAFT', source: 'manual', sampled_at: null, unknown_reason: 'NOT_CONNECTED', version: 3, created_at: '2026-10-23T00:00:00Z', updated_at: '2026-10-23T01:00:00Z', availability: 'UNKNOWN', sync_state: 'PENDING_ONBOARDING' }
const response = () => ({ tenant_id: tenant, observed_at: '2026-10-23T02:00:00Z', source: 'postgresql', scope: 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS', business_relationship_verified: false, proxy_verified: false,
  items: [{ asset_id: id, asset_version: 3, site_id: 'site-a', site_name: 'Declared site', metadata_version: 2, metadata_recorded_at: '2026-10-23T01:00:00Z', metadata_source: 'MANUAL_DECLARATION', business_description: 'Declared business', owner: { id: actor, display_name: 'Declared owner', status: 'ACTIVE' } }] })
let app: App | undefined, host: HTMLElement, fail = false
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
beforeEach(() => {
  fail = false; contextGeneration.value = 0; switchingTenant.value = false
  session.value = { user: { id: actor, display_name: 'Synthetic reader', role: 'viewer' }, tenant: { id: tenant, name: 'Synthetic' }, allowed_tenants: [], capabilities: ['assets:read'], permission_version: 7, account_version: 1, csrf_token: 'unit', mode: 'remote_test', identity_source: 'project_local' }
  vi.spyOn(api, 'requestWithClock').mockResolvedValue({ data: asset, clock: { status: 'UNKNOWN', reason: 'NOT_REQUESTED', clock_accuracy_verified: false } } as any)
  vi.spyOn(api, 'request').mockImplementation(async path => {
    if (path.startsWith('/assets/ledger-facts?')) {
      if (fail) throw new ApiError(503, 'ASSET_LEDGER_FACTS_UNAVAILABLE', 'Synthetic unavailable', 'unit')
      return response()
    }
    if (path.startsWith('/assets?')) return { items: [asset], has_more: false, next_cursor: null, source: 'postgresql', page_size_limit: 100, page_size_limit_kind: 'test_technical_limit' }
    throw new Error('Unexpected isolated path')
  })
})
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); session.value = null; vi.restoreAllMocks() })
async function mount(detail = true) {
  host = document.createElement('div'); document.body.append(host)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/assets', component: Page }] })
  await router.push('/assets' + (detail ? '?asset_id=' + id : ''))
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.use(ElementPlus); app.mount(host); await settle()
}
it('shows public owner/business provenance in actual detail without internal metadata API access', async () => {
  await mount()
  const facts = host.querySelector('[aria-label="资产公开登记资料"]')!
  expect(facts.textContent).toContain('Declared owner'); expect(facts.textContent).toContain('Declared business')
  expect(facts.textContent).toContain('未核验业务系统关联'); expect(facts.textContent).toContain('资料 v2')
  expect(vi.mocked(api.request).mock.calls.filter(([p]) => p.includes('ledger-facts'))).toHaveLength(1)
  expect(vi.mocked(api.request).mock.calls.some(([p]) => p.includes('manual-metadata'))).toBe(false)
})
it('keeps basic asset detail while failed source fields remain unknown and explicitly retryable', async () => {
  fail = true; await mount()
  expect(host.querySelector('.asset-detail-hero h1')?.textContent).toBe(asset.name)
  expect(host.querySelector('[aria-label="资产公开登记资料"]')?.textContent).not.toContain('Declared owner')
  const retry = [...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '重读登记资料')!
  expect(retry).toBeTruthy(); fail = false; retry.click(); await settle()
  expect(host.querySelector('[aria-label="资产公开登记资料"]')?.textContent).toContain('Declared owner')
})
it('clears the declared facts together with the page on read permission revocation', async () => {
  await mount(); expect(host.textContent).toContain('Declared owner')
  session.value = { ...session.value!, capabilities: [] }; await settle()
  expect(host.textContent).not.toContain('Declared owner'); expect(host.textContent).not.toContain('Declared business')
})
it('attaches one batch of bound facts to the actual twelve-column ledger', async () => {
  await mount(false)
  expect(host.textContent).toContain('Declared owner'); expect(host.textContent).toContain('Declared business')
  expect(host.textContent).toContain('未核验业务系统关联')
  expect(vi.mocked(api.request).mock.calls.filter(([p]) => p.includes('ledger-facts'))).toHaveLength(1)
})
it('cancelled detail reads retain a focusable retry and cannot install late facts', async () => {
  let resolve!: (value: unknown) => void
  vi.mocked(api.request).mockImplementation(async path => {
    if (path.startsWith('/assets/ledger-facts?')) return new Promise(r => { resolve = r })
    return { items: [asset], has_more: false, next_cursor: null, source: 'postgresql', page_size_limit: 100, page_size_limit_kind: 'test_technical_limit' }
  })
  await mount()
  const cancel = [...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '取消资料读取')!
  cancel.focus(); cancel.click(); await settle()
  const retry = [...host.querySelectorAll('button')].find(b => b.textContent?.trim() === '重读登记资料')!
  expect(document.activeElement).toBe(retry); resolve(response()); await settle()
  expect(host.querySelector('[aria-label="资产公开登记资料"]')?.textContent).not.toContain('Declared owner')
})
