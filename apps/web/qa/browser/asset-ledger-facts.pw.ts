import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { ledgerAssets, ledgerFacts, ledgerId } from '../../tests/assetLedgerFacts.fixture'

type Mode = 'valid' | 'stale' | 'reconciled' | 'future'
async function intercept(page: Page, mode: () => Mode = () => 'valid', holdFirst?: Promise<void>) {
  const requests: string[] = [], batches: string[][] = []
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    expect(route.request().method()).toBe('GET')
    requests.push(url.pathname)
    if (url.pathname === '/api/v1/assets/ledger-facts') {
      const ids = (url.searchParams.get('ids') ?? '').split(',')
      batches.push(ids)
      if (holdFirst && batches.length === 1) await holdFirst
      const data = ledgerFacts(ids, mode() === 'stale' || mode() === 'reconciled' ? 4 : 3)
      if (mode() === 'future') data.items[0]!.metadata_recorded_at = '2026-10-23T02:00:00.000000001Z'
      return route.fulfill({ json: { data, trace_id: 'SYNTHETIC' } }).catch(() => {})
    }
    const assets = ledgerAssets(mode() === 'reconciled' ? 4 : 3)
    if (url.pathname === '/api/v1/assets') return route.fulfill({ json: { data: { items: assets, has_more: false, next_cursor: null,
      source: 'postgresql', page_size_limit: 100, page_size_limit_kind: 'test_technical_limit' }, trace_id: 'SYNTHETIC' } })
    const asset = assets.find(item => url.pathname === '/api/v1/assets/' + item.id)
    if (asset) return route.fulfill({ json: { data: asset, trace_id: 'SYNTHETIC' } })
    if (url.pathname === '/api/v1/network-monitoring' || url.pathname === '/api/v1/network-monitoring/refresh-policy') {
      return route.fulfill({ status: 503, json: { error: { code: 'UPSTREAM_UNAVAILABLE', message: '合成监控未接入，状态未知' }, trace_id: 'SYNTHETIC' } })
    }
    throw Error('Unexpected synthetic request ' + url.pathname)
  })
  return { requests, batches }
}

for (const width of [1024, 1440]) test(`batch provenance, detail reread, keyboard and axe at ${width}px`, async ({ page }) => {
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  await page.setViewportSize({ width, height: 1000 })
  const observed = await intercept(page)
  await page.goto('/qa/asset-ledger-facts.fixture.html')
  const workspace = page.locator('.asset-list-workspace')
  await expect(workspace).toContainText('合成登记负责人 1')
  await expect(workspace).toContainText('合成站点名称 2')
  await expect(workspace).toContainText('登记说明 · 资料 v2 · 未核验业务系统关联')
  await expect(workspace.locator('.asset-ledger-note')).toBeVisible()
  expect(observed.batches).toEqual([[ledgerId(1), ledgerId(2)]])
  await expect(page.locator('#main-content')).toHaveCSS('font-size', '13px')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  expect((await new AxeBuilder({ page }).include('.asset-list-workspace').withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({ path: `../../tests/artifacts/asset-ledger-facts-list-${width}.png`, fullPage: true })
  await page.getByRole('button', { name: '合成设备 1', exact: true }).focus(); await page.keyboard.press('Enter')
  const detail = page.getByRole('region', { name: '资产公开登记资料' })
  await expect(detail).toContainText('合成登记负责人 1')
  await expect(detail).toContainText('合成业务登记说明 1')
  await expect(detail).not.toContainText('合成登记负责人 2')
  await expect(page.locator('.asset-detail-hero')).toContainText('合成站点名称 1')
  expect(observed.batches).toEqual([[ledgerId(1), ledgerId(2)], [ledgerId(1)]])
  await page.getByRole('button', { name: '刷新详情', exact: true }).click()
  await expect.poll(() => observed.batches.length).toBe(3)
  await expect(detail).toContainText('合成登记负责人 1')
  await page.screenshot({ path: `../../tests/artifacts/asset-ledger-facts-detail-${width}.png`, fullPage: true })
  await page.getByRole('button', { name: '返回资产列表' }).click()
  await expect(workspace).toContainText('合成登记负责人 2')
  await expect.poll(() => observed.batches.length).toBe(4)
  expect(observed.requests.some(path => path.includes('manual-metadata'))).toBe(false)
  expect(failures).toEqual([])
})

test('stale metadata stays hidden until the basic asset version is reread; future timestamps fail the whole batch', async ({ page }) => {
  let mode: Mode = 'stale'
  await intercept(page, () => mode)
  await page.goto('/qa/asset-ledger-facts.fixture.html')
  const workspace = page.locator('.asset-list-workspace')
  await expect(workspace).toContainText('2 条资料的资产版本已变化')
  await expect(workspace).not.toContainText('合成登记负责人')
  await expect(workspace).toContainText('合成设备 1')
  mode = 'reconciled'; await page.getByRole('button', { name: '刷新已应用的资产筛选' }).click()
  await expect(workspace).toContainText('合成登记负责人 1')
  mode = 'future'; await page.getByRole('button', { name: '刷新已应用的资产筛选' }).click()
  await expect(workspace).toContainText('INVALID_ASSET_LEDGER_FACTS')
  await expect(workspace).not.toContainText('合成登记负责人')
  await expect(workspace).not.toContainText('合成业务登记说明')
  mode = 'valid'; await page.getByRole('button', { name: '重读本页资料' }).click()
  await expect(workspace).toContainText('合成登记负责人 1')
  await expect(page.locator('[aria-label="台账公开资料读取状态"]')).toBeFocused()
})

test('cancel returns focus to retry, ignores the late batch, and allows a fresh read', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const observed = await intercept(page, () => 'valid', gate)
  await page.goto('/qa/asset-ledger-facts.fixture.html')
  await page.getByRole('button', { name: '取消资料读取' }).focus(); await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: '重读本页资料' })).toBeFocused()
  release()
  await expect(page.locator('.asset-list-workspace')).not.toContainText('合成登记负责人')
  await page.getByRole('button', { name: '重读本页资料' }).click()
  await expect(page.locator('.asset-list-workspace')).toContainText('合成登记负责人 1')
  expect(observed.batches).toHaveLength(2)
})

test('revoking asset access removes base rows, public names and old source details', async ({ page }) => {
  await intercept(page)
  await page.goto('/qa/asset-ledger-facts.fixture.html')
  await expect(page.locator('.asset-list-workspace')).toContainText('合成登记负责人 1')
  await page.getByRole('button', { name: '模拟撤销资产读取权限' }).click()
  await expect(page.locator('.asset-list-workspace')).toHaveCount(0)
  await expect(page.locator('#main-content')).not.toContainText('合成登记负责人')
  await expect(page.locator('#main-content')).not.toContainText('合成站点名称')
})
