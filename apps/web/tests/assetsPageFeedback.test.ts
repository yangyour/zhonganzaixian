// @vitest-environment jsdom
// Actual AssetsPage, Vue Router and Element Plus form controls with isolated HTTP responses.
// Secondary workspaces/table are stubbed; this is not browser or test-server acceptance.
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import type { Asset, AssetPage, Session } from '../src/api/types'

vi.mock('../src/components/AppTable.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetImportWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetDateQuery.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetMetadataQuery.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/ManualWarrantyWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetMetadataWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetDependencyWorkspace.vue', () => ({ default: { render: () => null } }))

import AssetsPage from '../src/pages/AssetsPage.vue'
import { api, ApiError } from '../src/api/client'
import { session, switchingTenant } from '../src/stores/session'

const auth: Session = {
  user: { id: 'unit-actor', display_name: '合成测试用户', role: 'viewer' },
  tenant: { id: 'unit-tenant', name: '合成测试租户' }, allowed_tenants: [],
  capabilities: ['assets:read'], permission_version: 1, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL',
  mode: 'remote_test', identity_source: 'synthetic-unit-only',
}
const asset: Asset = {
  id: 'unit-asset', tenant_id: auth.tenant.id, name: '合成测试设备', type: 'switch',
  management_ip: '192.0.2.1', site_id: 'unit-site', status: 'unknown', lifecycle_status: 'DRAFT',
  source: 'test_seed', sampled_at: null, unknown_reason: '合成单元测试数据，未采集',
  version: 1, created_at: '2026-10-08T00:00:00Z', updated_at: '2026-10-08T00:00:00Z',
}
const page = (items: Asset[] = [asset], nextCursor: string | null = null): AssetPage => ({
  items, next_cursor: nextCursor, has_more: nextCursor !== null,
})
function deferred() {
  let resolve!: (value: AssetPage) => void, reject!: (reason: unknown) => void
  const promise = new Promise<AssetPage>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
let app: App | undefined, host: HTMLElement, router: Router
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
async function mount(url = '/assets?page_size=15') {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/assets', component: AssetsPage }] })
  await router.push(url); await router.isReady()
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.use(ElementPlus)
  app.mount(host); await settle()
}
function button(text: string): HTMLButtonElement {
  const result = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === text)
  expect(result, `Missing button: ${text}`).toBeTruthy()
  return result!
}
function expectCount(value: '—' | '0' | '1') {
  const count = [...host.querySelectorAll('.scope-card > div')].find(item => item.textContent?.includes('本页资产'))
  expect(count?.querySelector('strong')?.textContent?.trim()).toBe(value)
  expect(host.querySelector('.table-footer > span')?.textContent).toContain(`本页 ${value} 条`)
}
beforeEach(() => {
  session.value = structuredClone(auth); switchingTenant.value = false
  host = document.createElement('div'); document.body.append(host)
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => { app?.unmount(); app = undefined; host.remove(); session.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('distinguishes pending and failed counts from an authoritative empty response', async () => {
  const first = deferred(), request = vi.spyOn(api, 'request').mockReturnValueOnce(first.promise)
  await mount(); expectCount('—')
  first.resolve(page()); await settle(); expectCount('1')

  const refresh = deferred(); request.mockReturnValueOnce(refresh.promise)
  button('刷新').click(); await settle(); expectCount('—')
  refresh.reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成服务暂不可用', 'unit-trace'))
  await settle(); expectCount('—')
  expect(host.querySelector('.api-error')?.textContent).toContain('合成服务暂不可用')

  request.mockResolvedValueOnce(page([])); button('重试').click(); await settle()
  expectCount('0'); expect(request).toHaveBeenCalledTimes(3)
})

it('queries unchanged filters again and blocks repeated submit while the query is pending', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(page())
  await mount('/assets?q=synthetic&page_size=15')
  const pending = deferred(); request.mockReturnValueOnce(pending.promise)
  button('查询').click(); await settle()
  expect(request).toHaveBeenCalledTimes(2)
  expect(request.mock.calls[1]?.[0]).toBe(request.mock.calls[0]?.[0])
  expect(button('查询').disabled).toBe(true); expect(button('重置').disabled).toBe(true)
  button('查询').click()
  host.querySelector('form.asset-toolbar')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle(); expect(request).toHaveBeenCalledTimes(2)
  pending.resolve(page()); await settle(); expect(button('查询').disabled).toBe(false)
  button('查询').click(); await settle(); expect(request).toHaveBeenCalledTimes(3)
  expect(request.mock.calls.every(([, options]) => !options?.body && (!options?.method || options.method === 'GET'))).toBe(true)
})

it('offers a first-page action for a cursor deep link and keeps the active filters', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValue(page())
  await mount('/assets?q=synthetic&status=unknown&type=switch&site_id=unit-site&page_size=25&cursor=unit-cursor')
  expect(button('返回首页').disabled).toBe(false)
  button('返回首页').click()
  await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({ q: 'synthetic', status: 'unknown', type: 'switch', site_id: 'unit-site', page_size: '25' }))
  await settle()
  expect(request).toHaveBeenCalledTimes(2)
  const last = new URL(request.mock.calls[1]![0], 'https://unit.invalid').searchParams
  expect(last.has('cursor')).toBe(false); expect(last.get('q')).toBe('synthetic')
  expect(last.get('site_id')).toBe('unit-site'); expect(last.get('page_size')).toBe('25')
  expect(button('上一页').disabled).toBe(true)
})

it('keeps ordinary next and previous navigation tied to the original filters', async () => {
  const request = vi.spyOn(api, 'request').mockResolvedValueOnce(page([asset], 'unit-next')).mockResolvedValueOnce(page()).mockResolvedValueOnce(page([asset], 'unit-next'))
  await mount('/assets?q=synthetic&page_size=15')
  expect(button('上一页').disabled).toBe(true); expect(button('下一页').disabled).toBe(false)
  button('下一页').click()
  await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({ q: 'synthetic', page_size: '15', cursor: 'unit-next' }))
  await settle()
  expect(button('上一页').disabled).toBe(false); expect(button('下一页').disabled).toBe(true)
  button('上一页').click()
  await vi.waitFor(() => expect(router.currentRoute.value.query).toEqual({ q: 'synthetic', page_size: '15' }))
  await settle()
  expect(button('上一页').disabled).toBe(true); expect(request).toHaveBeenCalledTimes(3)
  expect(request.mock.calls[2]?.[0]).toBe(request.mock.calls[0]?.[0])
})
