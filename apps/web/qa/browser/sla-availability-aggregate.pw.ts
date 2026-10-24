import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import Papa from 'papaparse'
import { readFileSync } from 'node:fs'
import {
  weightedAvailabilityReport,
  insufficientAvailabilityReport
} from '../../tests/slaAvailabilityAggregate.fixture'

for (const [width, partial] of [
  [1440, false],
  [1024, true]
] as const)
  test(`availability duration basis and review CSV at ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.route('**/api/v1/**', async (route) => {
      expect(route.request().method()).toBe('GET')
      expect(new URL(route.request().url()).pathname).toBe('/api/v1/sla/reports')
      await route.fulfill({
        json: {
          data: partial ? insufficientAvailabilityReport() : weightedAvailabilityReport(),
          trace_id: 'SYNTHETIC_AVAILABILITY'
        }
      })
    })
    await page.goto('/qa/sla-monitoring.fixture.html')
    await page.locator('input[type="month"]').fill('2026-10')
    await page.getByRole('button', { name: '查询月报', exact: true }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText('按有效时长汇总可用率', { exact: true })).toBeVisible()
    if (partial) await expect(page.locator('.compact-result-summary')).toContainText('样本不足')
    else await expect(page.getByText('90.9091%', { exact: true })).toBeVisible()
    await expect(page.locator('.compact-result-summary')).toContainText(partial ? '合格服务 1 / 2' : '合格服务 2 / 2')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    expect(
      (
        await new AxeBuilder({ page })
          .include('#main-content')
          .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
          .analyze()
      ).violations
    ).toEqual([])
    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出当前服务核对 CSV', exact: true }).click()
    const file = await download,
      path = await file.path()
    expect(path).not.toBeNull()
    const rows = Papa.parse<Record<string, string>>(readFileSync(path!, 'utf8'), {
      header: true,
      skipEmptyLines: true
    }).data
    expect(rows[0]!.availability_source_version).toBe('mop-availability-effective-milliseconds-v1')
    expect(rows[0]!.availability_effective_service_milliseconds).toBe(partial ? '1000000' : '1100000')
    expect(rows[0]!.availability_verified_percentage).toBe(partial ? '' : '90.9091')
    await page.screenshot({ path: info.outputPath(`sla-duration-${width}.png`), fullPage: true })
    await page.getByRole('button', { name: '模拟撤销权限' }).click()
    await expect(page.getByText('按有效时长汇总可用率', { exact: true })).toHaveCount(0)
  })
