import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { actionableClock, actionablePage, unknownActionableClock } from '../../tests/dashboardActionable.fixture'
import { row, scope } from '../../tests/dashboard.fixture'

type Mode = 'known' | 'unknown' | 'zero' | 'failure'
async function intercept(page: Page, mode: () => Mode = () => 'known') {
  const calls: string[] = []
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/dashboard') return route.fulfill({ json: { data: row((url.searchParams.get('window') ?? 'HOURS_24') as any), trace_id: 'SYNTHETIC' } })
    if (url.pathname !== '/api/v1/dashboard/actionable-tickets') throw Error('Unexpected synthetic request ' + url.pathname)
    expect(route.request().method()).toBe('GET')
    calls.push(url.search)
    if (mode() === 'failure') return route.fulfill({ status: 503, json: { error: { code: 'UPSTREAM_UNAVAILABLE', message: '合成读取失败' }, trace_id: 'SYNTHETIC' } })
    const data = actionablePage(Number(url.searchParams.get('page')), Number(url.searchParams.get('page_size')), mode() === 'zero' ? 0 : 42)
    Object.assign(data, { tenant_id: scope.tenantId, actor_id: scope.actorId, scope: 'REPORTER_ONLY' })
    if (mode() !== 'zero') {
      data.known_overdue = 1; data.overdue_sla = 1
      const first = data.items.find(item => item.receipt_no === 'SYN-500')
      if (first) { first.overdue = true; first.response_clock = actionableClock({ status: 'BREACHED', breached: true, remaining_millis: 0 }) }
      if (mode() === 'unknown') {
        data.overdue_sla = null; data.unknown_sla = 1; data.unavailable_reasons = ['SLA_INPUTS_OR_READER_UNAVAILABLE']
        const second = data.items.find(item => item.receipt_no === 'SYN-499')
        if (second) { second.overdue = null; second.recovery_clock = unknownActionableClock() }
      }
    }
    return route.fulfill({ json: { data, trace_id: 'SYNTHETIC' } })
  })
  return calls
}

for (const width of [1024, 1440]) test(`actionable homepage SLA, complete keyboard drilldown and axe at ${width}px`, async ({ page }) => {
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  await page.setViewportSize({ width, height: 1000 })
  const calls = await intercept(page)
  await page.goto('/qa/dashboard-actionable.fixture.html')
  await page.getByRole('checkbox', { name: '每30秒自动刷新' }).uncheck()
  const kpi = page.locator('[data-kpi="PENDING_TICKETS"]')
  await expect(page.locator('.dashboard-kpis')).toHaveCSS('display', 'grid')
  await expect(kpi.locator('.mop-kpi-link svg')).toHaveCSS('width', '12px')
  await expect(kpi.locator('.mop-kpi-value')).toHaveText('42')
  await expect(kpi.locator('.kpi-parts')).toContainText('已超 SLA 1')
  await expect(page.locator('.dashboard-workflows tbody tr')).toHaveCount(5)
  await expect(page.locator('.wf-clock.wf-overdue')).toHaveText('响应：已超时')
  await expect(page.locator('.wf-clock').filter({ hasText: '恢复：剩余 1小时0分' }).first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  expect((await new AxeBuilder({ page }).include('.dashboard-workflows').withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([])
  await page.screenshot({ path: `../../tests/artifacts/dashboard-actionable-home-${width}.png`, fullPage: true })
  await kpi.getByRole('link', { name: '待处理工单：打开全部待处理与SLA' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('navigation', { name: '待处理工单分页' })).toBeVisible()
  await expect(page.locator('tbody tr')).toHaveCount(20)
  const receipts = await page.locator('tbody tr td:first-child').allTextContents()
  for (let target = 2; target <= 3; target++) {
    await page.getByRole('button', { name: '下一页', exact: true }).focus(); await page.keyboard.press('Enter')
    await expect(page.getByRole('navigation', { name: '待处理工单分页' })).toContainText(`第 ${target} 页`)
    await expect(page.locator('p[role="status"]')).toBeFocused()
    receipts.push(...await page.locator('tbody tr td:first-child').allTextContents())
    if (target === 2) {
      await page.getByRole('link', { name: '查看工单处理SYN-480' }).focus(); await page.keyboard.press('Enter')
      await expect(page.getByRole('status')).toContainText('合成工单详情目标')
      await page.goBack()
      await expect(page.getByRole('navigation', { name: '待处理工单分页' })).toContainText('第 2 页')
      await expect(page.locator('tbody tr td:first-child').first()).toHaveText('SYN-480')
    }
  }
  expect(new Set(receipts).size).toBe(42)
  await expect(page.getByRole('button', { name: '下一页', exact: true })).toBeDisabled()
  expect((await new AxeBuilder({ page }).include('.dashboard-workflows').withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  await page.screenshot({ path: `../../tests/artifacts/dashboard-actionable-page3-${width}.png`, fullPage: true })
  await page.getByRole('link', { name: '查看工单处理SYN-460' }).focus(); await page.keyboard.press('Enter')
  await expect(page.getByRole('status')).toContainText('合成工单详情目标')
  expect(calls).toEqual(['?page=1&page_size=5', '?page=1&page_size=20', '?page=2&page_size=20', '?page=2&page_size=20', '?page=3&page_size=20'])
  expect(failures).toEqual([])
})

test('unknown SLA remains unknown, zero remains zero, and failure clears the shared count before retry', async ({ page }) => {
  let mode: Mode = 'unknown'
  await intercept(page, () => mode)
  await page.goto('/qa/dashboard-actionable.fixture.html')
  await page.getByRole('checkbox', { name: '每30秒自动刷新' }).uncheck()
  const kpi = page.locator('[data-kpi="PENDING_TICKETS"]')
  await expect(kpi.locator('.mop-kpi-value')).toHaveText('42')
  await expect(kpi.locator('.kpi-parts')).toContainText('已超 SLA 未知')
  await expect(kpi.locator('.kpi-parts')).toContainText('已确认 1 · 未知 1')
  mode = 'failure'; await page.getByRole('button', { name: '刷新工单', exact: true }).click()
  await expect(kpi.locator('.mop-kpi-value')).toHaveText('读取失败')
  await expect(page.locator('.dashboard-workflows table')).toHaveCount(0)
  mode = 'zero'; await page.getByRole('button', { name: '重试待办汇总' }).click()
  await expect(kpi.locator('.mop-kpi-value')).toHaveText('0')
  await expect(kpi.locator('.kpi-parts')).toContainText('已超 SLA 0')
  await expect(page.locator('.dashboard-workflows [role="status"]')).toBeFocused()
})

test('revocation clears both the count and pending rows', async ({ page }) => {
  await intercept(page)
  await page.goto('/qa/dashboard-actionable.fixture.html')
  await expect(page.locator('.dashboard-workflows table')).toBeVisible()
  await page.getByRole('button', { name: '模拟撤销权限' }).click()
  await expect(page.locator('[data-kpi="PENDING_TICKETS"]')).toHaveCount(0)
  await expect(page.locator('.dashboard-workflows')).toHaveCount(0)
})
