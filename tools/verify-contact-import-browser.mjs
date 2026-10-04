// Real designated PG/OIDC/Worker only. Fault injection discards a real 202 response;
// no response body or successful business result is fabricated.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
const root = new URL('../', import.meta.url), base = 'http://127.0.0.1:5173';
const runName = process.argv[2] ?? 'local-contact-import-dom-20261004';
assert.match(runName, /^local-contact-import-dom-[a-z0-9-]{1,60}$/);
assert.ok(process.argv.length <= 3);
const output = new URL(`docs/evidence/${runName}.json`, root);
const privateOutput = new URL(`.local/${runName}-original.json`, root);
const images = new URL('docs/evidence/local-ui-20261004/', root);
let browser, stage = 'preflight';
const evidence = { verified_at: new Date().toISOString(), environment: 'designated-server', passed: false, checks: [],
  real_response_discarded: false, fabricated_business_responses: 0, credentials_output: false, notifications_sent: false };
try {
  assert.equal(existsSync(output) || existsSync(privateOutput), false, 'Preserve previous execution evidence');
  const runtime = JSON.parse(readFileSync(new URL('deploy/private/runtime.json', root), 'utf8').replace(/^\uFEFF/, ''));
  const user = runtime.test_users.find(x => x.role === 'padmin'); assert.ok(user);
  const executablePath = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Microsoft/Edge/Application/msedge.exe'].find(existsSync);
  assert.ok(executablePath); mkdirSync(images, { recursive: true });
  browser = await chromium.launch({ headless: true, executablePath });
  evidence.browser = await browser.version();
  const check = (name, condition) => { assert.ok(condition, name); evidence.checks.push(name); };
  async function login(page) {
    await page.goto(base + '/login');
    await page.getByRole('button', { name: '使用 Keycloak 登录', exact: true }).click();
    await page.locator('#username').waitFor();
    assert.equal(new URL(page.url()).origin, new URL(runtime.environment.MOP_OIDC_ISSUER).origin);
    await page.locator('#username').fill(user.username); await page.locator('#password').fill(user.password);
    await page.locator('#kc-login').click(); await page.getByRole('heading', { name: '资产管理', exact: true }).waitFor();
    await page.goto(base + '/contact-imports');
    await page.getByRole('heading', { name: '联系人批量新建与作业', exact: true }).waitFor();
  }
  async function fit(page) { return page.evaluate(() => document.documentElement.scrollWidth <= innerWidth); }
  const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await desktop.newPage(); page.setDefaultTimeout(15000);
  stage = 'real_oidc'; await login(page);
  const input = page.getByRole('textbox', { name: '批量新建联系人原始JSON', exact: true });
  check('initial_input_empty', await input.inputValue() === '');
  const submit = page.getByRole('button', { name: '确认并受理新建作业', exact: true });
  await input.fill('{"rows":['); await page.getByRole('button', { name: '只读检查格式', exact: true }).click();
  check('malformed_json_cannot_submit', !await submit.isEnabled());
  const row = { name: 'MOP-FAKE-UI-BATCH-' + randomUUID(), roles: ['REPORTER'], mobile: null, email: 'mop-local@example.invalid',
    wecom_user_id: null, preferred_priorities: null, quiet_window: null, enabled: false };
  await input.fill(JSON.stringify({ rows: [row, row] }, null, 2));
  await page.getByRole('button', { name: '只读检查格式', exact: true }).click();
  await page.getByText('服务端格式通过 2 行；无效 0 行；完全相同行 1 行', { exact: true }).waitFor();
  check('identical_rows_require_explicit_ack', !await submit.isEnabled());
  await page.locator('label.el-checkbox').filter({ hasText: '如有字段完全相同行' }).click();
  const fault = { captured: null, error: null };
  let settleFault;
  const faultDone = new Promise(resolve => { settleFault = resolve; });
  await page.route('**/api/v1/contact-imports', async route => {
    if (route.request().method() !== 'POST' || fault.captured) { await route.continue(); return; }
    try {
      const original = { body: route.request().postData(), key: route.request().headers()['idempotency-key'], status: 'REQUESTING' };
      writeFileSync(privateOutput, JSON.stringify(original) + '\n', { flag: 'wx' });
      const response = await route.fetch(); assert.equal(response.status(), 202);
      fault.captured = { ...original, status: 'ACCEPTED', receipt: (await response.json()).data };
      writeFileSync(privateOutput, JSON.stringify(fault.captured) + '\n');
      await route.abort('failed');
    } catch { fault.error = 'real_acceptance_or_fault_injection_failed'; await route.abort('failed').catch(() => {}); }
    finally { settleFault(); }
  });
  stage = 'real_202_discard'; await submit.click();
  await page.getByRole('button', { name: '确认受理', exact: true }).click();
  // The workspace represents an in-flight POST as uncertain too. Wait for the
  // actual accepted response and completed discard, not just the early heading.
  let faultTimer;
  try { await Promise.race([faultDone, new Promise((_, reject) => { faultTimer = setTimeout(() => reject(new Error('Response capture timed out; original request preserved')), 30000); })]); }
  finally { clearTimeout(faultTimer); }
  await page.getByRole('heading', { name: '原请求结果未知', exact: true }).waitFor();
  assert.equal(fault.error, null); assert.ok(fault.captured?.receipt?.operation_id);
  evidence.real_response_discarded = true; evidence.job_id = fault.captured.receipt.operation_id;
  check('uncertain_input_locked', !await input.isEnabled());
  await page.getByRole('button', { name: '刷新第一页', exact: true }).click();
  await page.getByRole('heading', { name: '原请求结果未知', exact: true }).waitFor();
  check('ordinary_read_does_not_confirm_original_post', true);
  stage = 'original_retry';
  const retryEvent = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/contact-imports');
  await page.getByRole('button', { name: '完全相同原请求恢复', exact: true }).click();
  const retry = await retryEvent; assert.equal(retry.status(), 202);
  check('original_body_bytes_preserved', retry.request().postData() === fault.captured.body);
  check('original_idempotency_key_preserved', retry.request().headers()['idempotency-key'] === fault.captured.key);
  const receipt = (await retry.json()).data;
  check('same_original_job_receipt', receipt.operation_id === evidence.job_id && receipt.status === 'PENDING');
  evidence.original_body_sha256 = createHash('sha256').update(fault.captured.body).digest('hex');
  stage = 'worker_completion';
  await page.getByRole('button', { name: '自动刷新本批进度', exact: true }).click();
  await page.getByRole('heading', { name: '整批已新建 · 全部行原子完成', exact: true }).waitFor({ timeout: 45000 });
  const response = await desktop.request.get(base + '/api/v1/contact-imports/' + evidence.job_id);
  assert.equal(response.status(), 200); const job = (await response.json()).data;
  check('dedicated_worker_completed_both_rows', job.status === 'SUCCEEDED' && job.completed_rows === 2 && job.results.length === 2);
  check('identical_rows_create_distinct_contacts', new Set(job.results.map(x => x.contact_id)).size === 2);
  check('completion_refs_are_v1', job.results.every(x => x.contact_version === 1));
  check('receipt_still_pending_after_job_completed', await page.getByText('作业 ' + evidence.job_id + ' · 原始状态 PENDING', { exact: true }).isVisible());
  await page.getByRole('heading', { name: '当前作业结果', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: fileURLToPath(new URL('desktop-contact-import-complete.png', images)), scale: 'css' });
  check('desktop_no_document_horizontal_overflow', await fit(page));
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const mobilePage = await mobile.newPage(); mobilePage.setDefaultTimeout(15000);
  stage = 'mobile_creator_readback'; await login(mobilePage);
  await mobilePage.getByRole('button', { name: '刷新第一页', exact: true }).click();
  const item = mobilePage.locator('.jobs > li').filter({ hasText: evidence.job_id });
  await item.getByRole('button', { name: '查看当前结果', exact: true }).click();
  await mobilePage.getByRole('heading', { name: '整批已新建 · 全部行原子完成', exact: true }).waitFor();
  check('mobile_same_actor_sees_completed_job', true);
  check('mobile_no_document_horizontal_overflow', await fit(mobilePage));
  await mobilePage.locator('.import-panel').filter({ has: mobilePage.getByRole('heading', { name: '当前作业结果', exact: true }) }).scrollIntoViewIfNeeded();
  await mobilePage.screenshot({ path: fileURLToPath(new URL('mobile-contact-import-complete.png', images)), scale: 'css' });
  evidence.contacts = job.results; evidence.passed = true;
  evidence.remaining = ['Physical mobile/enterprise WeChat', 'Actual notification delivery', 'In-flight Windows worker crash and fencing'];
} catch {
  evidence.failed_stage = stage; process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  if (!existsSync(output)) writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' });
}
console.log(JSON.stringify({ passed: evidence.passed, stage, checks: evidence.checks.length, job_id: evidence.job_id, details: 'redacted' }));
