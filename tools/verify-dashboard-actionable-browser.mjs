/** Real browser with the candidate API and existing isolated DEMO identities; no business writes. */
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';

const origin = process.env.MOP_UI01_WEB_ORIGIN ?? 'http://127.0.0.1:15217';
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
const output = new URL('../.local/ui01-live-browser/', import.meta.url);
mkdirSync(output, { recursive: false });
const privatePath = 'E:/work/wxw9/zhonganzaixian/mop-platform/deploy/private/demo-full-20261023.json';
const demo = JSON.parse(readFileSync(privatePath, 'utf8').replace(/^\uFEFF/, ''));
assert.equal(demo.bootstrap_complete, true);
const tenants = Object.values(demo.contexts).map(row => row.tenant_id);
assert.equal(new Set(tenants).size, 3);
assert.ok(tenants.every(value => !value.startsWith('55000000-')));
const browser = await chromium.launch({ headless: true,
  executablePath: process.env.MOP_CHROMIUM_EXECUTABLE ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
const result = { status: 'NOT_RUN', environment: 'lan-test-20261008', browser: browser.version(),
  source: 'LOCAL_CANDIDATE_API_WITH_DESIGNATED_POSTGRESQL', business_writes: 0, external_messages: 0,
  formal_contract_acceptance: 'NOT_RUN', checks: [] };
try {
  for (const role of ['padmin', 'msp', 'tadmin']) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    let csrf;
    const call = async (path, body) => {
      assert.ok(body === undefined || ['/auth/local/login', '/session/tenant'].includes(path));
      const headers = { Origin: origin, Accept: 'application/json' };
      if (csrf) headers['X-CSRF-Token'] = csrf;
      if (body !== undefined) headers['Idempotency-Key'] = randomUUID();
      const response = await context.request.fetch(origin + '/api/v1' + path,
        { method: body === undefined ? 'GET' : 'POST', data: body, headers });
      assert.equal(response.status(), 200, path + ' rejected');
      return (await response.json()).data;
    };
    csrf = (await call('/auth/csrf')).csrf_token;
    let session = await call('/auth/local/login', { username: demo.users.main.username, password: demo.users.main.password });
    csrf = session.csrf_token;
    const tenant = demo.contexts[role].tenant_id;
    if (session.tenant.id !== tenant) session = await call('/session/tenant', { tenant_id: tenant });
    csrf = session.csrf_token;
    assert.equal(session.user.role, role);
    assert.equal(session.tenant.id, tenant);
    const expected = await call('/dashboard/actionable-tickets');
    const page = await context.newPage();
    const pageErrors = [];
    page.on('pageerror', failure => pageErrors.push(failure.name));
    await page.goto(origin + '/dashboard');
    const kpi = page.locator('[data-kpi="PENDING_TICKETS"]');
    await kpi.locator('.mop-kpi-value').filter({ hasText: String(expected.pending_tickets ?? '未知') }).waitFor();
    assert.equal(await kpi.locator('.mop-kpi-value').textContent(), String(expected.pending_tickets ?? '未知'));
    assert.ok((await kpi.locator('.kpi-parts').textContent()).includes(String(expected.overdue_sla ?? '未知')));
    assert.equal(await page.locator('.dashboard-workflows tbody tr').count(), expected.items.length);
    await page.screenshot({ path: new URL(role + '-home.png', output).pathname.replace(/^\/([A-Z]:)/i, '$1'), fullPage: true });
    await kpi.getByRole('link', { name: '待处理工单：打开全部待处理与SLA' }).click();
    await page.waitForURL('**/dashboard/actionable-tickets**');
    await page.getByRole('navigation', { name: '待处理工单分页' }).waitFor();
    const full = await call('/dashboard/actionable-tickets?page=1&page_size=20');
    await page.waitForFunction(count => document.querySelectorAll('.dashboard-workflows tbody tr').length === count, full.items.length);
    assert.equal(await page.locator('.dashboard-workflows tbody tr').count(), full.items.length);
    assert.ok((await page.locator('.dashboard-workflows').textContent()).includes('SLA'));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    assert.deepEqual(pageErrors, []);
    result.checks.push({ role, viewport: '1440x1000', pending: expected.pending_tickets,
      visible_home: expected.items.length, visible_full: full.items.length, overdue_sla: expected.overdue_sla,
      unknown_sla: expected.unknown_sla, overflow: false, page_errors: 0, status: 'PASSED' });
    await context.close();
  }
  result.status = 'PASSED';
} catch (failure) {
  result.status = 'FAILED';
  result.failure_type = failure.name;
  // Assertion/error descriptions may contain payloads. Keep details out of public stdout.
  writeFileSync(new URL('private-error.log', output), String(failure.stack));
  process.exitCode = 1;
} finally {
  await browser.close();
  result.finished_at = new Date().toISOString();
  writeFileSync(new URL('summary.json', output), JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result));
}
