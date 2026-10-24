import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
const wire=JSON.parse(readFileSync(new URL('../../../../fixtures/customer-sla-controller.json',import.meta.url),'utf8'))

for (const width of [1440, 1024]) test(`monitoring timing distinguishes unknown from zero at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  let unknown = true
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  await page.route('**/api/v1/**', async route => {
    expect(route.request().method()).toBe('GET')
    expect(new URL(route.request().url()).pathname).toBe('/api/v1/sla/reports')
    const value = structuredClone(wire.report)
    for (const metric of [value.kpis, ...value.services]) {
      Object.assign(metric.mtta, { source_version: 'mop-event-acknowledgement-v1', unknown_count: unknown ? 1 : 0,
        sample_count: 1, mean_sec: unknown ? null : 0, p50_sec: unknown ? null : 0, p95_sec: unknown ? null : 0 })
      Object.assign(metric.mttr, { source_version: 'mop-monitoring-recovery-t2-v1', unknown_count: unknown ? 1 : 0,
        sample_count: 1, mean_sec: unknown ? null : 0, p50_sec: unknown ? null : 0, p95_sec: unknown ? null : 0 })
    }
    await route.fulfill({ json: { data: value, trace_id: 'SYNTHETIC' } })
  })
  await page.goto('/qa/sla-monitoring.fixture.html')
  await page.locator('input[type="month"]').fill('2026-10')
  await page.getByRole('button', { name: '查询月报', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByText('P50 来源待核验', { exact: true })).toHaveCount(2)
  await expect(page.getByText(/恢复待核验 1/)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  if(width===1024){
    const table=page.getByRole('region',{name:'SLA 数据表，可横向滚动'}).first()
    await table.focus()
    for(let step=0;step<12;step++)await page.keyboard.press('ArrowRight')
    await expect.poll(()=>table.evaluate(element=>element.scrollLeft)).toBeGreaterThan(0)
  }
  await page.screenshot({ path: `../../tests/artifacts/sla-monitoring-unknown-${width}.png`, fullPage: true })
  unknown = false
  await page.getByRole('button', { name: '查询月报', exact: true }).click()
  await expect(page.getByText('P50 0s', { exact: true })).toHaveCount(2)
  await expect(page.getByText('P50 来源待核验', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '模拟撤销权限' }).click()
  await expect(page.getByText('P50 0s', { exact: true })).toHaveCount(0)
  expect(failures).toEqual([])
})
