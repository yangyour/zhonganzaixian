import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { deliveryEntry, deliveryFlags } from '../../tests/deliveryJournal.fixture'

for (const width of [1440, 1024]) test(`attention reads and exact failure recovery at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const calls: string[] = [], errors: string[] = []
  let lost = false
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/v1/notification-deliveries/attention?**', async route => {
    const request = route.request(), url = new URL(request.url())
    expect(request.method()).toBe('GET'); calls.push(url.search)
    if (url.searchParams.has('before') && !lost) { lost = true; return route.abort('failed') }
    const status = url.searchParams.get('status')!
    const data = { ...deliveryFlags(), page_size: 10, total_count: 12, scanned_roots: 25, status_filter: status,
      population_scope: 'COMPLETE_BOUNDED_VERIFIED_JOURNAL_HEADS', root_scan_limit: 1000, revision_scan_limit: 24002,
      snapshot_scope: 'CURRENT_READ_PAGE_NOT_GLOBAL_SNAPSHOT',
      items: Array.from({ length: url.searchParams.has('before') ? 2 : 10 }, (_, index) => ({ ...deliveryEntry(),
        delivery_id: `79000000-0000-4000-8000-${(100 - index - (url.searchParams.has('before') ? 10 : 0)).toString(16).padStart(12, '0')}`,
        recorded_status: status === 'ATTENTION' ? 'UNKNOWN' : status,
        version: status === 'DEAD_LETTER' ? 3 : status === 'FAILED' ? 2 : 1,
        receipt_count: status === 'DEAD_LETTER' || status === 'FAILED' ? 1 : 0 })), next: null as null | { before: string; before_id: string } }
    if (!url.searchParams.has('before')) data.next = { before: data.items[9].first_recorded_at, before_id: data.items[9].delivery_id }
    return route.fulfill({ json: { data, trace_id: 'SYNTHETIC_ATTENTION' } })
  })
  await page.goto('/qa/delivery-attention.fixture.html')
  expect(calls).toHaveLength(0)
  await page.getByLabel('日志范围').selectOption('ATTENTION')
  await page.getByRole('button', { name: '读取日志', exact: true }).click()
  await expect(page.getByRole('table')).toBeVisible()
  await expect(page.getByRole('cell', { name: '结果不明', exact: true })).toHaveCount(10)
  await page.getByRole('button', { name: '下一页', exact: true }).click()
  await page.getByRole('button', { name: '重试原读取', exact: true }).click()
  await expect(page.getByRole('cell', { name: '结果不明', exact: true })).toHaveCount(2)
  expect(calls.at(-1)).toBe(calls.at(-2))
  await page.getByLabel('日志范围').selectOption('DEAD_LETTER')
  await expect(page.getByRole('table')).toHaveCount(0)
  await page.getByRole('button', { name: '读取日志', exact: true }).click()
  await expect(page.getByRole('cell', { name: '死信记录，待核对', exact: true })).toHaveCount(10)
  expect(calls.at(-1)).toBe('?page_size=10&status=DEAD_LETTER')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  expect((await new AxeBuilder({ page }).include('section.delivery-journal')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({ path: `../../tests/artifacts/delivery-attention-${width}.png`, fullPage: true })
  await page.getByRole('button', { name: '模拟撤权' }).click()
  await expect(page.getByRole('table')).toHaveCount(0)
  expect(errors).toEqual([])
})
