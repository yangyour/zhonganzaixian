// @vitest-environment jsdom
// Actual page forms, Vue Router and Element Plus with synthetic isolated HTTP responses.
// This checks interaction behavior, not test-server or browser acceptance.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import ElementPlus from 'element-plus'
import type { Asset, AssetPage, Session } from '../src/api/types'
import { context, page as ticketPage, ticket } from './ticketReader.fixture'

vi.mock('../src/components/AppTable.vue', () => ({ default: {
  props: ['rows', 'loading', 'error', 'emptyText'],
  setup: (props: { rows: object[]; loading: boolean; error: unknown; emptyText: string }, { slots }: { slots: Record<string, (() => unknown) | undefined> }) =>
    () => !props.loading && !props.error && !props.rows.length ? h('div', { class: 'synthetic-table-empty' }, [props.emptyText, slots['empty-action']?.() as any]) : null,
} }))
vi.mock('../src/components/AssetImportWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetDateQuery.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetMetadataQuery.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/ManualWarrantyWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetMetadataWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/AssetDependencyWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/TicketReportForm.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/WorklogWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/InternalNoteWorkspace.vue', () => ({ default: { render: () => null } }))
vi.mock('../src/components/TicketCommentWorkspace.vue', () => ({ default: { render: () => null } }))

import AssetsPage from '../src/pages/AssetsPage.vue'
import TicketsPage from '../src/pages/TicketsPage.vue'
import { api, ApiError, type RequestOptions } from '../src/api/client'
import { contextGeneration, session, switchingTenant } from '../src/stores/session'

const auth: Session = {
  user: { id: context.actorId, display_name: '合成测试用户', role: 'msp' },
  tenant: { id: context.tenantId, name: '合成测试租户' }, allowed_tenants: [],
  capabilities: ['assets:read', 'tickets:read'], permission_version: 7, csrf_token: 'SYNTHETIC_NOT_CREDENTIAL',
  mode: 'remote_test', identity_source: 'synthetic-unit-only',
}
const asset: Asset = {
  id: '79000000-0000-4000-8000-000000000001', tenant_id: auth.tenant.id, name: '合成测试设备', type: 'switch',
  management_ip: '192.0.2.1', site_id: 'unit-site', status: 'unknown', lifecycle_status: 'DRAFT',
  source: 'test_seed', sampled_at: null, unknown_reason: '合成单元测试数据，未采集',
  version: 1, created_at: '2026-10-08T00:00:00Z', updated_at: '2026-10-08T00:00:00Z',
}
const assetPage = (items = [asset], more = false): AssetPage & Record<string,unknown> => ({ items, next_cursor: more ? 'unit-next' : null, has_more: more,source:'postgresql',page_size_limit:100,page_size_limit_kind:'test_technical_limit' })
const cases = [
  { path: '/assets', component: AssetsPage, label: '搜索资产', form: 'form.asset-toolbar', emptyButton: '清空筛选，查看全部资产', page: (empty = false, more = false) => assetPage(empty ? [] : [asset], more) },
  { path: '/tickets', component: TicketsPage, label: '搜索报障登记', form: 'form.ticket-filter-form', emptyButton: '清空筛选，查看全部工单', page: (empty = false, more = false) => ticketPage(empty ? [] : more ? Array.from({ length: 15 }, (_, index) => ticket(10 + index)) : [ticket()], more) },
]
let app: App | undefined, host: HTMLElement, router: Router
async function settle() { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick() } }
async function mount(config: typeof cases[number], query = '') {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: config.path, component: config.component }] })
  await router.push(`${config.path}${query}`); await router.isReady()
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.use(ElementPlus)
  app.mount(host); await settle()
}
function button(text: string) {
  const result = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === text)
  expect(result, `Missing button: ${text}`).toBeTruthy(); return result!
}
function searchInput(label: string) { return host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)! }
async function type(input: HTMLInputElement, value: string) { input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await settle() }
// Assert list-search IO separately from the independently tested public-field enrichment GET.
function listTransport() {
  const list = vi.fn<(path: string, options?: RequestOptions) => Promise<unknown>>()
  vi.spyOn(api, 'request').mockImplementation(async (path, options) => {
    if (path.startsWith('/assets/ledger-facts?')) return {
      tenant_id: auth.tenant.id, observed_at: '2026-10-23T00:00:00Z', source: 'postgresql', scope: 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS',
      business_relationship_verified: false, proxy_verified: false,
      items: [{asset_id:asset.id,asset_version:asset.version,site_id:asset.site_id,site_name:'Unit site',metadata_version:null,metadata_recorded_at:null,metadata_source:'NOT_RECORDED',business_description:null,owner:null}],
    }
    return list(path, options)
  })
  return list
}
function queryParams(request: ReturnType<typeof listTransport>) { return new URL(request.mock.calls.at(-1)![0] as string, 'https://unit.invalid').searchParams }
beforeEach(() => {
  session.value = structuredClone(auth); switchingTenant.value = false; contextGeneration.value = 0
  host = document.createElement('div'); document.body.append(host)
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => { app?.unmount(); app = undefined; host.remove(); session.value = null; vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe.each(cases)('$path explicit list search', config => {
  it('discards only unsubmitted filters, keeps the original cursor and returns input focus without a request', async () => {
    const request = listTransport().mockResolvedValue(config.page())
    await mount(config, '?q=before&page_size=25&cursor=synthetic-cursor')
    await type(searchInput(config.label), '尚未查询的文字')
    button('撤销修改').click(); await settle()
    expect(searchInput(config.label).value).toBe('before')
    expect(router.currentRoute.value.query.cursor).toBe('synthetic-cursor')
    expect(request).toHaveBeenCalledTimes(1)
    expect(document.activeElement).toBe(searchInput(config.label))
    expect(host.querySelector('[aria-label="未应用筛选操作"]')).toBeNull()
  })

  it('applies the pending filter action to the first page and keeps keyboard focus reachable', async () => {
    const request = listTransport().mockResolvedValue(config.page())
    await mount(config, '?q=before&cursor=synthetic-cursor')
    await type(searchInput(config.label), '  新查询  ')
    button('应用筛选').click()
    await vi.waitFor(() => expect(router.currentRoute.value.query.q).toBe('新查询')); await settle()
    expect(router.currentRoute.value.query.cursor).toBeUndefined()
    expect(queryParams(request).get('q')).toBe('新查询')
    expect(document.activeElement).toBe(searchInput(config.label))
  })

  it('keeps typing separate, trims submitted input and returns to the first page', async () => {
    const request = listTransport().mockResolvedValue(config.page())
    await mount(config, '?q=before&page_size=25&cursor=synthetic-cursor')
    await type(searchInput(config.label), '  新关键词  ')
    expect(request).toHaveBeenCalledTimes(1)
    expect(host.querySelector('.filter-pending')?.textContent).toContain('点击“查询”后应用')
    expect(host.querySelector('.filter-chip')?.textContent).toContain('before')
    button('查询').click()
    await vi.waitFor(() => expect(router.currentRoute.value.query.q).toBe('新关键词')); await settle()
    expect(queryParams(request).get('page_size')).toBe('25'); expect(queryParams(request).has('cursor')).toBe(false)
    expect(searchInput(config.label).value).toBe('新关键词'); expect(host.querySelector('.filter-pending')).toBeNull()
  })

  it('blocks IME candidate Enter and submitting during composition, then accepts a normal submit', async () => {
    const request = listTransport().mockResolvedValue(config.page())
    await mount(config)
    const input = searchInput(config.label)
    input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    await type(input, '中文')
    const candidate = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true })
    input.dispatchEvent(candidate)
    expect(candidate.defaultPrevented).toBe(true)
    host.querySelector(config.form)!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await settle(); expect(request).toHaveBeenCalledTimes(1)
    input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true })); await settle()
    const safariCandidate = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, bubbles: true, cancelable: true })
    input.dispatchEvent(safariCandidate); expect(safariCandidate.defaultPrevented).toBe(true)
    host.querySelector(config.form)!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(router.currentRoute.value.query.q).toBe('中文')); await settle()
    expect(request).toHaveBeenCalledTimes(2)
  })

  it('keeps pending input while paging or refreshing the applied query', async () => {
    const request = listTransport().mockResolvedValue(config.page(false, true))
    await mount(config, '?q=applied')
    await type(searchInput(config.label), '未提交输入')
    button('下一页').click()
    await vi.waitFor(() => expect(router.currentRoute.value.query.cursor).toBeTruthy()); await settle()
    expect(queryParams(request).get('q')).toBe('applied'); expect(searchInput(config.label).value).toBe('未提交输入')
    button('刷新').click(); await settle()
    expect(queryParams(request).get('q')).toBe('applied'); expect(searchInput(config.label).value).toBe('未提交输入')
    button('清空全部').click()
    await vi.waitFor(() => expect(router.currentRoute.value.query.q).toBeUndefined()); await settle()
    expect(searchInput(config.label).value).toBe(''); expect(router.currentRoute.value.query.cursor).toBeUndefined()
    expect(host.querySelector('.filter-selection')).toBeNull()
  })

  it('recovers an empty result and never announces a failed query as zero successful results', async () => {
    const request = listTransport().mockResolvedValue(config.page(true))
    await mount(config, '?q=missing')
    expect(host.querySelector('.filter-result')?.textContent).toContain('本页 0 条匹配记录')
    request.mockResolvedValueOnce(config.page()); button(config.emptyButton).click()
    await vi.waitFor(() => expect(router.currentRoute.value.query.q).toBeUndefined()); await settle()
    expect(host.querySelector('.filter-result')?.textContent).toContain('本页 1 条记录')
    let reject!: (reason: unknown) => void
    request.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail }))
    button('查询').click(); await settle()
    expect(host.querySelector('.filter-result')?.textContent).toContain('正在读取')
    expect(button('查询').disabled).toBe(true)
    reject(new ApiError(503, 'UPSTREAM_UNAVAILABLE', '合成不可用', 'synthetic')); await settle()
    expect(host.querySelector('.filter-result')?.textContent).toContain('查询未完成')
    expect(host.querySelector('.filter-result')?.textContent).not.toContain('本页 0')
    expect(host.querySelector('.table-footer > span')?.textContent).toContain('本页 — 条')
  })
})

it('asset status shortcut and removing one applied filter leave other drafts and applied filters intact', async () => {
  const request = listTransport().mockResolvedValue(assetPage())
  await mount(cases[0]!, '?q=applied&type=switch&site_id=unit-site&cursor=unit-old')
  await type(searchInput('搜索资产'), '尚未提交')
  button('未知').click()
  await vi.waitFor(() => expect(router.currentRoute.value.query.status).toBe('unknown')); await settle()
  expect(router.currentRoute.value.query.q).toBe('applied'); expect(router.currentRoute.value.query.type).toBe('switch')
  expect(router.currentRoute.value.query.cursor).toBeUndefined(); expect(searchInput('搜索资产').value).toBe('尚未提交')
  host.querySelector<HTMLButtonElement>('button[aria-label="站点：unit-site，移除此筛选"]')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.query.site_id).toBeUndefined()); await settle()
  expect(queryParams(request).get('q')).toBe('applied'); expect(queryParams(request).get('status')).toBe('unknown')
  expect(searchInput('搜索资产').value).toBe('尚未提交')
})

it('ticket single-filter removal preserves pending keywords and the other applied condition', async () => {
  const request = listTransport().mockResolvedValue(ticketPage())
  await mount(cases[1]!, '?q=applied&category=网络不通&urgency=normal&cursor=unit-old')
  await type(searchInput('搜索报障登记'), '尚未提交')
  host.querySelector<HTMLButtonElement>('button[aria-label="分类：网络不通，移除此筛选"]')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.query.category).toBeUndefined()); await settle()
  expect(queryParams(request).get('urgency')).toBe('normal'); expect(queryParams(request).get('q')).toBe('applied')
  expect(router.currentRoute.value.query.cursor).toBeUndefined(); expect(searchInput('搜索报障登记').value).toBe('尚未提交')
})
