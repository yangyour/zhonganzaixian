// Local production assets, existing designated-server API, and read-only business
// checks. Delivery faults never replace the server response with fabricated data.
import { chromium } from 'playwright'
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, sep } from 'node:path'
import { randomUUID, createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import assert from 'node:assert/strict'

const root = resolve(import.meta.dirname, '..'), dist = resolve(root, 'apps/web/dist')
const run = randomUUID(), output = resolve(root, `.local/ui-polish-${run}`)
mkdirSync(output, { recursive: true })
const checks = [], requests = [], screenshots = [], faults = [], releases = []
const assets = new Map(), pageErrors = [], unexpectedWrites = [], routeErrors = []
let browser, context, page, base, stage = 'setup', failure, readiness, authenticated = false
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function record(name) { checks.push(name); console.log(JSON.stringify({ stage, passed: name })) }
function errorType(error) { return ['AssertionError', 'TimeoutError', 'TypeError', 'SyntaxError'].includes(error?.name) ? error.name : 'Error' }
const safePath = raw => {
  const path = new URL(raw).pathname
  return ['/api/v1/auth/options', '/api/v1/auth/csrf', '/api/v1/auth/local/login', '/api/v1/session', '/api/v1/assets', '/api/v1/health/ready'].includes(path) ? path : '/api/{other}'
}
async function bounded(promise) {
  let timer
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Controlled delivery timed out')), 30000) })]) }
  finally { clearTimeout(timer) }
}
async function shot(name) {
  await page.evaluate(() => window.scrollTo(0, 0))
  // Mask populated fields even on failure; blank fields remain visible for layout review.
  const masks = []
  for (const id of ['#login-password', '#login-username']) {
    const field = page.locator(id)
    if (await field.count() && await field.inputValue()) masks.push(field)
  }
  await page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled', mask: masks })
  screenshots.push(name + '.png')
}
async function noOverflow() {
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2), 'Page must not overflow horizontally')
}
async function call(path) {
  const response = await context.request.get(base + '/api/v1' + path, { timeout: 30000 })
  requests.push({ method: 'GET', path: safePath(base + '/api/v1' + path), status: response.status(), source: 'api-check' })
  assert.equal(response.status(), 200, 'Read-only API check must return 200')
  const envelope = await response.json()
  assert.ok(envelope && Object.hasOwn(envelope, 'data'), 'API response must contain data')
  return envelope.data
}
// Only known capability flags and symbolic statuses are retained. Never retain
// URLs, hostnames, connection strings, credentials, users or session contents.
function readySummary(value) {
  const result = { mode: value.mode === 'remote_test' ? 'remote_test' : 'OTHER', ready: value.ready === true, durable: value.durable === true, production_ready: value.production_ready === true, component_versions: 'NOT_EXPOSED_BY_READY_API', capabilities: {} }
  const fields = new Set(['ready', 'enabled', 'schema_access_ready', 'unicode_search_ready', 'effective', 'monitoring_applied', 'notification_dispatched', 'report_published', 'active_scan_performed', 'worker_liveness', 'configuration_status', 'publication_status', 'verification_status', 'approval_status', 'matching_policy_status', 'mode'])
  for (const [key, component] of Object.entries(value)) {
    if (!/^[a-z_]+$/.test(key) || !component || typeof component !== 'object' || Array.isArray(component)) continue
    const kept = Object.fromEntries(Object.entries(component).filter(([field, flag]) => fields.has(field) && (flag === null || typeof flag === 'boolean' || typeof flag === 'string' && /^[A-Z_]+$/.test(flag))))
    if (Object.keys(kept).length) result.capabilities[key] = kept
  }
  return result
}
async function holdNextAssets(delivery) {
  const hit = deferred(), release = deferred(), done = deferred()
  let consumed = false
  releases.push(release.resolve)
  const matcher = url => url.origin === base && url.pathname === '/api/v1/assets'
  const handler = async route => {
    if (consumed || route.request().method() !== 'GET') return route.fallback()
    consumed = true
    try {
      const response = await route.fetch({ timeout: 30000 })
      assert.equal(response.status(), 200, 'Delivery checks require a real successful read')
      const body = await response.json()
      assert.ok(Array.isArray(body.data?.items) && body.data.items.length > 0, 'Delivery checks require real nonempty assets')
      faults.push({ path: '/api/v1/assets', real_status: response.status(), real_item_count: body.data.items.length, delivery })
      hit.resolve({ ok: true })
      await release.promise
      if (delivery === 'aborted-after-real-response') await route.abort('failed')
      else await route.fulfill({ response })
    } catch (error) {
      routeErrors.push(errorType(error)); hit.resolve({ ok: false })
      await route.abort('failed').catch(() => {})
    } finally {
      await page.unroute(matcher, handler).catch(() => {})
      done.resolve()
    }
  }
  await page.route(matcher, handler)
  return { hit: async () => assert.equal((await bounded(hit.promise)).ok, true, 'Real response must be obtained'), release: release.resolve, done: () => bounded(done.promise) }
}
async function assetRowsReady() {
  await page.locator('.table-frame .el-table__body .el-table__row').first().waitFor()
  await page.locator('.table-frame[aria-busy="false"]').waitFor()
  assert.ok(await page.locator('.table-frame .el-table__body .el-table__row').count() > 0)
}
function assetCount() { return page.locator('.scope-card > div').filter({ has: page.getByText('本页资产', { exact: true }) }).locator('strong') }

try {
  const cfg = JSON.parse(readFileSync(resolve(root, 'deploy/private/server-test-runtime.json'), 'utf8'))
  base = new URL(cfg.public_origin).origin
  assert.ok(existsSync(resolve(dist, 'index.html')), 'Build the current Web source first')
  const admin = cfg.test_users.find(user => user.role === 'padmin')
  assert.ok(admin?.username && admin?.password, 'Existing designated test administrator required')
  browser = await chromium.launch({ executablePath: process.env.MOP_BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, chromiumSandbox: true })
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  await context.route(base + '/**', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) {
      if (!['GET', 'HEAD'].includes(request.method()) && !(request.method() === 'POST' && url.pathname === '/api/v1/auth/local/login')) {
        unexpectedWrites.push({ method: request.method(), path: safePath(request.url()) })
        return route.abort('blockedbyclient')
      }
      return route.continue()
    }
    if (request.method() !== 'GET') return route.continue()
    const file = request.resourceType() === 'document' ? resolve(dist, 'index.html') : resolve(dist, '.' + decodeURIComponent(url.pathname))
    if (!file.startsWith(dist + sep) || !existsSync(file)) return route.continue()
    const body = readFileSync(file)
    assets.set(file.slice(dist.length + 1).replaceAll('\\', '/'), hash(body))
    const contentType = file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.html') ? 'text/html' : undefined
    await route.fulfill({ status: 200, body, ...(contentType ? { contentType } : {}) })
  })
  page = await context.newPage(); page.setDefaultTimeout(30000)
  page.on('pageerror', error => pageErrors.push(errorType(error)))
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/')) requests.push({ method: request.method(), path: safePath(request.url()), source: 'browser-request' })
  })
  page.on('response', response => {
    if (new URL(response.url()).pathname.startsWith('/api/')) requests.push({ method: response.request().method(), path: safePath(response.url()), status: response.status(), source: 'browser-response' })
  })

  stage = 'login_controls_before_credentials'
  await page.goto(base + '/login')
  const username = page.getByLabel('登录账号', { exact: true }), password = page.getByLabel('登录密码', { exact: true })
  await username.waitFor()
  assert.equal(await page.locator('.login-progress[role="status"]').evaluate(element => element.closest('[aria-busy="true"]') === null), true)
  await password.fill('SYNTHETIC_UNSUBMITTED_INPUT')
  assert.equal(await password.getAttribute('type'), 'password')
  await page.getByRole('button', { name: '显示密码', exact: true }).click()
  assert.equal(await password.getAttribute('type'), 'text')
  assert.equal(await page.getByRole('button', { name: '隐藏密码', exact: true }).getAttribute('aria-pressed'), 'true')
  await page.getByRole('button', { name: '隐藏密码', exact: true }).click()
  assert.equal(await password.getAttribute('type'), 'password')
  assert.equal(await password.inputValue(), 'SYNTHETIC_UNSUBMITTED_INPUT')
  record('password_visibility_preserves_input_and_accessible_state')
  const loginRequests = () => requests.filter(item => item.source === 'browser-request' && item.method === 'POST' && item.path === '/api/v1/auth/local/login').length
  await username.fill('   ')
  const beforeInvalid = loginRequests()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByText('请输入账号，不能只填写空格。', { exact: true }).waitFor()
  assert.equal(await username.getAttribute('aria-invalid'), 'true')
  assert.equal(await username.evaluate(element => element === document.activeElement), true)
  assert.equal(loginRequests(), beforeInvalid, 'Whitespace username must not reach the login API')
  record('whitespace_username_is_rejected_locally_with_focus')
  await username.fill(''); await password.fill('')
  await page.getByRole('button', { name: '忘记密码？', exact: true }).click()
  assert.equal(await page.getByRole('button', { name: '收起找回说明', exact: true }).getAttribute('aria-expanded'), 'true')
  await page.getByText('请联系项目管理员重置密码。目前还不能通过邮件或短信找回。', { exact: true }).waitFor()
  record('password_recovery_help_expands_with_available_action')
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
    await noOverflow(); await shot(`${width}-login-help`); record(`${width}px_login_no_horizontal_overflow`)
  }
  await page.getByRole('button', { name: '收起找回说明', exact: true }).click()
  assert.equal(await page.locator('#login-password-help').count(), 0)

  stage = 'real_project_login_and_asset_read'
  await page.setViewportSize({ width: 1440, height: 1000 })
  await username.fill(admin.username); await password.fill(admin.password)
  const loginResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/auth/local/login' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  assert.equal((await loginResponse).status(), 200)
  await page.waitForURL('**/assets')
  await page.getByRole('heading', { name: '资产管理', exact: true }).waitFor()
  await assetRowsReady()
  const session = await call('/session')
  assert.equal(session.identity_source, 'project_local')
  authenticated = true
  assert.equal(loginRequests(), beforeInvalid + 1)
  record('existing_project_account_reads_nonempty_real_asset_list')
  const ready = await call('/health/ready'); readiness = readySummary(ready)
  assert.equal(ready.ready, true); assert.equal(ready.mode, 'remote_test'); assert.equal(ready.durable, true)
  record('existing_designated_api_ready_and_durable')

  for (const width of [1440, 390]) {
    stage = `${width}px_asset_loading_error_retry`
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
    await noOverflow(); await shot(`${width}-assets-ready`)
    const delayed = await holdNextAssets('delayed-real-response')
    await page.getByRole('button', { name: '刷新', exact: true }).click(); await delayed.hit()
    await page.locator('.table-frame[aria-busy="true"]').waitFor()
    const tableText = await page.locator('.table-frame').innerText()
    assert.equal(await page.locator('.table-region [role="status"]').evaluate(element => element.closest('[aria-busy="true"]') === null), true)
    assert.match(tableText, /正在加载/)
    assert.doesNotMatch(tableText, /当前筛选下没有资产|暂无符合条件的数据|数据加载失败|未能读取资产/)
    assert.match(await assetCount().innerText(), /—/)
    await noOverflow(); await shot(`${width}-assets-loading`)
    delayed.release(); await delayed.done(); await assetRowsReady()
    record(`${width}px_loading_has_progress_without_empty_result`)

    const failed = await holdNextAssets('aborted-after-real-response')
    await page.getByRole('button', { name: '刷新', exact: true }).click(); await failed.hit()
    failed.release(); await failed.done()
    await page.locator('.api-error').waitFor()
    await page.locator('.table-frame[aria-busy="false"]').waitFor()
    assert.equal(await page.locator('.table-frame .el-table__body .el-table__row').count(), 0)
    assert.match(await assetCount().innerText(), /—/)
    assert.doesNotMatch(await page.locator('.table-footer').innerText(), /本页\s*0\s*条/)
    const details = page.locator('details.error-details')
    assert.equal(await details.evaluate(element => element.open), false)
    await noOverflow(); await shot(`${width}-assets-error-collapsed`)
    await details.locator('summary').click()
    assert.equal(await details.evaluate(element => element.open), true)
    await details.getByText('错误代码：NETWORK_ERROR', { exact: true }).waitFor()
    await noOverflow(); await shot(`${width}-assets-error-expanded`)
    record(`${width}px_read_failure_has_unknown_count_and_collapsed_diagnostics`)
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await assetRowsReady(); await page.locator('.api-error').waitFor({ state: 'hidden' })
    assert.match(await assetCount().innerText(), /[1-9]\d*/)
    await noOverflow(); await shot(`${width}-assets-recovered`)
    record(`${width}px_retry_restores_nonempty_real_asset_list`)
  }
  stage = 'final_checks'
  assert.deepEqual(pageErrors, []); assert.deepEqual(routeErrors, []); assert.deepEqual(unexpectedWrites, [])
  record('no_pageerror_or_business_write_requests')
} catch (error) {
  // Exception messages and stacks may contain origins or request headers. They
  // are deliberately excluded from both console output and the saved report.
  failure = { stage, error_type: errorType(error) }
  await shot('failure-private').catch(() => {})
  console.error(JSON.stringify({ stage, passed: false, error_type: failure.error_type })); process.exitCode = 1
} finally {
  for (const release of releases) release()
  const report = {
    verified_at: new Date().toISOString(), passed: !failure, run, stage, checks, failure: failure ?? null,
    source_commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    tracked_source_worktree_dirty: execFileSync('git', ['diff', '--name-only', '--', 'apps/web/src'], { cwd: root, encoding: 'utf8' }).trim().length > 0,
    verification_script_sha256: hash(readFileSync(new URL(import.meta.url))),
    browser: browser ? browser.version() : null,
    frontend: 'current-local-production-build-served-to-browser', backend: 'designated-server-existing-api',
    deployed_latest_frontend: false, readiness: readiness ?? null,
    assets: Object.fromEntries(assets), requests, controlled_delivery_faults: faults,
    technical_checks: checks, visual_evidence: { screenshots, review: 'CAPTURED_REQUIRES_SEPARATE_VISUAL_REVIEW' },
    page_errors: pageErrors, route_errors: routeErrors, unexpected_business_writes: unexpectedWrites,
    business_mutations: 'NONE', authentication: authenticated ? 'existing-project-test-account-session-created' : 'NOT_CONFIRMED',
    formal_contract_acceptance: 'NOT_RUN', physical_device_acceptance: 'NOT_RUN',
  }
  writeFileSync(resolve(output, 'result.json'), JSON.stringify(report, null, 2) + '\n')
  await browser?.close()
  console.log(JSON.stringify({ stage: 'report_written', passed: report.passed, checks: checks.length, screenshots: screenshots.length }))
}
