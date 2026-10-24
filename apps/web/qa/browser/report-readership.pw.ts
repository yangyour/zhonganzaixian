import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { expected, id, readership, reportId, tenant } from '../../tests/reportReadership.fixture'

for (const width of [1440, 1024]) test(`readership filters, cursor recovery and revocation at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1100 })
  const errors: string[] = [], requests: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  let lost = false
  const publication = { label: 'SYNTHETIC 季度报告', source_id: id(900), source_document_sha256: 'a'.repeat(64),
    customer_acceptance_confirmed: false, external_dispatch_enabled: false,
    state: { version: 5, phase: 'PUBLISHED', reason: null, recipients: Array.from({ length: 52 }, (_, index) => id(index + 1)),
      html: { content_sha256: 'c'.repeat(64) }, pdf: { content_sha256: 'd'.repeat(64) },
      document: { id: reportId, tenant_id: tenant, family_id: reportId, parent_id: null, revision: 1,
        kind: 'QUARTERLY', document_sha256: expected.hash, captured_at: '2026-10-14T00:00:00Z', incomplete_reasons: [] } } }
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/api/v1', '')
    expect(request.method()).toBe('GET')
    if (path.endsWith('/readership')) {
      requests.push(url.search)
      if (url.searchParams.has('after') && !lost) { lost = true; return route.abort('failed') }
      const data = readership()
      const filter = url.searchParams.get('filter')
      if (filter === 'UNREAD' || filter === 'FOLLOWUP_DUE') {
        data.filter = filter; data.matching_count = 51
        data.items = data.items.slice(1).concat({ customer_id: id(51), display_name: 'SYNTHETIC 接收人 51', first_read_at: null, followup_due: true })
        data.next_after = id(51)
      }
      if (url.searchParams.has('after')) {
        data.items = [51, 52].map(value => ({ customer_id: id(value), display_name: `SYNTHETIC 接收人 ${value}`, first_read_at: null, followup_due: true }))
        data.next_after = null
      }
      return route.fulfill({ json: { data, trace_id: 'SYNTHETIC_READERSHIP' } })
    }
    const data = path === '/report-publications' ? { items: [publication], next: null, external_dispatch_enabled: false } : publication
    return route.fulfill({ json: { data, trace_id: 'SYNTHETIC_READERSHIP' } })
  })
  await page.goto('/qa/report-revision.fixture.html')
  const board = page.getByRole('region', { name: '报告阅读看板' })
  await expect(board).toContainText('接收人 52 · 已读 1 · 未读 51 · 超过 7 天未读 51')
  await board.getByRole('button', { name: '阅读记录下一页' }).click()
  await board.getByRole('button', { name: '重试当前阅读页' }).click()
  await expect(board).toContainText('SYNTHETIC 接收人 52')
  expect(requests.at(-1)).toBe(requests.at(-2))
  await board.getByLabel('接收人筛选').selectOption('FOLLOWUP_DUE')
  await expect(board.getByRole('table')).toBeVisible()
  expect(requests.at(-1)).toBe('?filter=FOLLOWUP_DUE')
  await expect(board.getByRole('row')).toHaveCount(51)
  await board.getByRole('region', { name: '接收人阅读记录表格' }).focus()
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => board.locator('.readership-table').evaluate(element => element.scrollTop)).toBeGreaterThan(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  expect((await new AxeBuilder({ page }).include('section[aria-label="报告阅读看板"]')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({ path: `../../tests/artifacts/report-readership-${width}.png`, fullPage: true })
  await page.getByRole('button', { name: '模拟撤销权限' }).click()
  await expect(board).toHaveCount(0)
  expect(errors).toEqual([])
})
