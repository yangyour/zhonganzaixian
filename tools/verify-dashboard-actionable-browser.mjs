/** Real browser with the candidate API and existing isolated DEMO identities; no business writes. */
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { chromium } from 'playwright';

const origin = process.env.MOP_UI01_WEB_ORIGIN ?? 'http://127.0.0.1:15217';
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
const ledger = process.argv.includes('--assets');
const readiness = process.argv.includes('--readiness');
assert.ok(!(ledger && readiness));
const output = new URL(readiness ? '../.local/p01-live-browser/' : ledger ? '../.local/a05-live-browser/' : '../.local/ui01-live-browser/', import.meta.url);
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
    if (readiness) {
      if (role !== 'padmin') {
        await context.close();
        continue;
      }
      const diagnostic = await call('/operations/production-readiness');
      await page.goto(origin + '/runtime-configurations');
      const panel = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: '生产启用检查' }) });
      await panel.locator('summary').click();
      await panel.getByRole('button', { name: '检查运行隔离' }).click();
      await panel.getByText('生产未就绪；当前测试数据库账号符合运行限制。', { exact: true }).waitFor();
      assert.equal(await panel.locator('li').count(), diagnostic.checks.length);
      assert.ok((await panel.textContent()).includes(diagnostic.database_version));
      assert.ok((await panel.textContent()).includes('待客户确认'));
      assert.ok((await panel.textContent()).includes('未检查'));
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
      assert.deepEqual(pageErrors, []);
      await page.screenshot({ path: new URL('padmin-readiness.png', output).pathname.replace(/^\/([A-Z]:)/i, '$1'), fullPage: true });
      result.checks.push({ role, viewport: '1440x1000', projection: 'production-readiness', checks: diagnostic.checks.length,
        actual_database_observed: true, production_ready: false, page_errors: 0, overflow: false, status: 'PASSED' });
      await context.close();
      continue;
    }
    if (ledger) {
      const assets = (await call('/assets?page_size=100')).items;
      assert.ok(assets.length > 0 && assets.length <= 100);
      const facts = await call('/assets/ledger-facts?ids=' + assets.map(row => row.id).join(','));
      assert.equal(facts.items.length, assets.length);
      await page.goto(origin + '/assets');
      await page.getByText('已读取本页登记资料快照', { exact: false }).waitFor();
      const workspace = page.locator('.asset-list-workspace');
      const content = await workspace.textContent();
      for (const fact of facts.items) {
        assert.ok(content.includes(fact.site_name), 'Current site name absent');
        if (fact.owner) assert.ok(content.includes(fact.owner.display_name), 'Current public owner absent');
        if (fact.business_description) assert.ok(content.includes(fact.business_description), 'Current declaration absent');
      }
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
      const selected = facts.items.find(row => row.metadata_source === 'MANUAL_DECLARATION') ?? facts.items[0];
      await page.locator(`[data-list-record="${selected.asset_id}"]`).first().click();
      await page.getByText('登记资料快照查询时间', { exact: false }).waitFor();
      const detail = page.getByRole('region', { name: '资产公开登记资料' });
      const detailText = await detail.textContent();
      if (selected.owner) assert.ok(detailText.includes(selected.owner.display_name));
      if (selected.business_description) assert.ok(detailText.includes(selected.business_description));
      assert.ok((await page.locator('.asset-detail-hero').textContent()).includes(selected.site_name));
      await page.screenshot({ path: new URL(role + '-asset-detail.png', output).pathname.replace(/^\/([A-Z]:)/i, '$1'), fullPage: true });
      await page.getByRole('button', { name: '返回资产列表' }).click();
      await page.getByText('已读取本页登记资料快照', { exact: false }).waitFor();
      assert.deepEqual(pageErrors, []);
      result.checks.push({ role, viewport: '1440x1000', projection: 'asset-ledger-facts', assets: assets.length,
        list_detail_back_verified: true, public_owner_and_site_matched: true, overflow: false, page_errors: 0, status: 'PASSED' });
      await context.close();
      continue;
    }
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
