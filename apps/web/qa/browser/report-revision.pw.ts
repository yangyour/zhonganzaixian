import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { report, page as sourcePage, summary, scope, id } from '../../tests/inspectionQuarterlyReport.fixture'

const parent = id(1000), child = id(1001), source = report()
function publication(revised = false) {
  return { label: 'SYNTHETIC 季度报告', source_id: source.id, source_document_sha256: source.document_sha256,
    customer_acceptance_confirmed: false, external_dispatch_enabled: false,
    state: { version: revised ? 1 : 6, phase: revised ? 'DRAFT' : 'RETURNED', reason: revised ? '修正巡检来源' : '待核对来源',
      recipients: [], html: null, pdf: null,
      document: { id: revised ? child : parent, tenant_id: scope.tenantId, family_id: parent,
        parent_id: revised ? parent : null, revision: revised ? 2 : 1, kind: 'QUARTERLY',
        document_sha256: 'b'.repeat(64), captured_at: '2026-10-24T00:00:00Z',
        incomplete_reasons: ['SELF_REPORTED_SOURCES_UNVERIFIED'], metric_version: null, template_version: null } } }
}
async function transport(page: Page, loseFirst = false) {
  const writes: { path: string; body: unknown; key: string | undefined }[] = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace('/api/v1', '')
    if (request.method() === 'POST') {
      expect(path).toBe(`/report-publications/${parent}/revisions`)
      writes.push({ path, body: request.postDataJSON(), key: request.headers()['idempotency-key'] })
      if (loseFirst && writes.length === 1) return route.abort('failed')
      return route.fulfill({ json: { data: publication(true), trace_id: 'SYNTHETIC' } })
    }
    let data: unknown
    if (path === '/report-publications') data = { items: [publication()], next: null, external_dispatch_enabled: false }
    else if (path === `/report-publications/${parent}`) data = publication()
    else if (path === `/report-publications/${child}`) data = publication(true)
    else if (path === '/inspection-quarterly-reports') data = sourcePage([summary(source)])
    else if (path === `/inspection-quarterly-reports/${source.id}`) data = source
    else throw new Error('Unexpected synthetic API request: ' + path)
    return route.fulfill({ json: { data, trace_id: 'SYNTHETIC' } })
  })
  return writes
}
async function selectSource(page: Page) {
  await page.goto('/qa/report-revision.fixture.html')
  await page.getByRole('link', { name: '选择冻结来源并创建新修订' }).click()
  await page.getByRole('button', { name: '读取冻结报告', exact: true }).click()
  await page.getByRole('link', { name: '使用此来源修订原报告' }).click()
  await page.getByLabel('更正原因').fill('修正巡检来源')
}

for (const width of [1440, 1024]) test(`revision selection and parent history at ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const failures: string[] = []
  page.on('pageerror', error => failures.push(error.message))
  const writes = await transport(page)
  await selectSource(page)
  expect(writes).toHaveLength(0)
  await page.getByRole('button', { name: '创建新修订', exact: true }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('region', { name: '报告详情' })).toContainText('第 2 次修订')
  expect(writes).toHaveLength(1)
  expect(writes[0].body).toEqual({ expected_version: 6, source_id: source.id,
    expected_source_sha256: source.document_sha256, reason: '修正巡检来源' })
  expect((await new AxeBuilder({ page }).include('section[aria-label="报告中心"]')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa']).analyze()).violations).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)).toBe(false)
  await page.screenshot({ path: `../../tests/artifacts/report-revision-${width}.png`, fullPage: true })
  await page.getByRole('link', { name: '查看上一修订' }).click()
  await expect(page.getByRole('region', { name: '报告详情' })).toContainText('第 1 次修订')
  await page.getByRole('button', { name: '模拟撤销权限' }).click()
  await expect(page.getByRole('region', { name: '报告详情' })).toHaveCount(0)
  expect(failures).toEqual([])
})

test('unknown revision restores the same command without automatically resending', async ({ page }) => {
  const writes = await transport(page, true)
  await selectSource(page)
  await page.getByRole('button', { name: '创建新修订', exact: true }).click()
  await expect(page.getByText('上次操作结果待确认，请按原请求核对。')).toBeVisible()
  await page.goto('/qa/report-revision.fixture.html')
  await expect(page.getByRole('button', { name: '核对原请求' })).toBeVisible()
  expect(writes).toHaveLength(1)
  await page.getByRole('button', { name: '核对原请求' }).click()
  await expect(page.getByRole('region', { name: '报告详情' })).toContainText('第 2 次修订')
  expect(writes).toHaveLength(2)
  expect(writes[1]).toEqual(writes[0])
})
