// Real browser on the designated server IP. Secrets and original requests stay private.
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { chromium } from 'playwright'
const root = new URL('../', import.meta.url)
const cfg = JSON.parse(readFileSync(new URL('deploy/private/server-test-runtime.json', root), 'utf8'))
const base = cfg.public_origin
assert.equal(cfg.project, 'mop-platform-test')
const mode = process.argv[2]
assert.ok(['login', 'pages', 'actions'].includes(mode))
const privateDir = new URL('.local/server-acceptance/private/', root)
const sessionDir = new URL('sessions/', privateDir)
const shots = new URL('docs/evidence/server-ui-20261005/', root)
mkdirSync(sessionDir, { recursive: true }); mkdirSync(shots, { recursive: true })
const evidence = { verified_at: new Date().toISOString(), environment: 'designated-server-acceptance', mode, real_browser: true, physical_mobile: false, checks: [], screenshots: [], page_errors: 0, failed_api_gets: [], completed: false, formal_acceptance: 'NOT_RUN' }
let browser, stage = 'launch', currentPage
let attempt = 1
let output = new URL('docs/evidence/server-browser-' + mode + '-20261005.json', root)
while (existsSync(output)) { attempt++; output = new URL('docs/evidence/server-browser-' + mode + '-20261005-attempt' + attempt + '.json', root) }
evidence.attempt = attempt
const save = () => writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n')
const check = (name, extra = {}) => { evidence.checks.push({ name, ...extra }); save() }
const stateFile = role => new URL('state-' + role + '.json', sessionDir)
const executablePath = ['C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Microsoft/Edge/Application/msedge.exe'].find(existsSync)
const routes = [
  ['/assets', '资产管理'], ['/dashboard', '运营总览'], ['/tickets', '报障与受理'], ['/manual-incidents', '手工来源登记'],
  ['/my-notification-preferences', '我的通知偏好请求'], ['/asset-export-jobs', '台账导出准备'],
  ['/backup-plan-drafts', '每日备份计划草稿'], ['/change-notice-drafts', '变更报备草稿'],
  ['/inspection-source-collections', '巡检来源集合草稿'], ['/inspection-templates', '巡检模板候选'],
  ['/inspection-worksheets', '人工巡检执行单'], ['/customer-contacts', '客户联系人'],
  ['/contact-imports', '联系人批量新建与作业'], ['/contact-import-preview', '联系人导入格式预览'],
  ['/configurations', '配置确认登记'], ['/threshold-diagnostics', '阈值候选诊断'], ['/sla-scenarios', 'SLA 显式对照'],
  ['/operational-comparison', '经营样本与人力对照'], ['/capacity-estimates', '容量与缓冲估算'],
  ['/capability-catalog', '代码能力声明核对'], ['/tenant-inventory', '租户登记资源'], ['/audit', '审计日志'],
]
async function attach(context) {
  const page = await context.newPage(); page.setDefaultTimeout(20000); currentPage = page
  page.on('pageerror', () => { evidence.page_errors++ })
  page.on('response', response => {
    if (response.request().method() === 'GET' && response.url().startsWith(base + '/api/v1/') && response.status() >= 500) {
      evidence.failed_api_gets.push({ path: new URL(response.url()).pathname, status: response.status() })
    }
  })
  return page
}
async function screenshot(page, name) {
  assert.equal(new URL(page.url()).origin, base)
  assert.equal(new URL(page.url()).pathname.startsWith('/login'), false)
  const file = name + (attempt > 1 ? '-attempt' + attempt : '') + '.png'
  await page.screenshot({ path: new URL(file, shots).pathname.replace(/^\/([A-Za-z]:)/, '$1'), fullPage: true })
  evidence.screenshots.push(file); save()
}
async function session(context) {
  const response = await context.request.get(base + '/api/v1/session')
  assert.equal(response.status(), 200)
  return (await response.json()).data
}
async function login(role) {
  const user = cfg.test_users.find(value => value.role === role)
  assert.ok(user)
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await attach(context)
  stage = 'oidc_' + role
  await page.goto(base + '/login', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '使用 Keycloak 登录', exact: true }).click()
  await page.locator('#username').waitFor()
  const provider = new URL(page.url())
  assert.equal(provider.origin, base)
  assert.equal(provider.pathname, '/realms/mop-acceptance/protocol/openid-connect/auth')
  assert.equal(provider.searchParams.get('code_challenge_method'), 'S256')
  assert.ok(provider.searchParams.get('state')); assert.ok(provider.searchParams.get('nonce'))
  await page.locator('#username').fill(user.username)
  await page.locator('#password').fill(user.password)
  await page.locator('#kc-login').click()
  await page.getByRole('heading', { name: '资产管理', exact: true }).waitFor()
  const data = await session(context)
  assert.equal(data.user.id, user.platform_user_id)
  assert.equal(data.user.role, role === 'reviewer' ? 'padmin' : role.startsWith('viewer') ? 'viewer' : role)
  assert.equal(data.mode, 'remote_test'); assert.equal(data.identity_source, 'test_oidc')
  const cookie = (await context.cookies(base)).find(item => item.name === 'MOP_SESSION')
  assert.equal(cookie.httpOnly, true); assert.equal(cookie.sameSite, 'Strict')
  if (!process.argv.includes('--browser-only')) writeFileSync(new URL('qa-' + role + '.json', sessionDir), JSON.stringify({ cookie: 'MOP_SESSION=' + cookie.value, csrf_token: data.csrf_token, user_id: data.user.id, environment: 'designated-server-acceptance', created_at: new Date().toISOString() }) + '\n')
  await context.storageState({ path: stateFile(role).pathname.replace(/^\/([A-Za-z]:)/, '$1') })
  check('real_oidc_' + role, { pkce: 'S256', identity_mapping: true, role: data.user.role })
  if (role === 'padmin') {
    check('http_context', await page.evaluate(() => ({ secure_context: isSecureContext, native_uuid: typeof crypto.randomUUID === 'function', native_digest: typeof crypto.subtle?.digest === 'function', secure_random: typeof crypto.getRandomValues === 'function' })))
    await screenshot(page, 'desktop-assets-login')
  }
  await context.close()
}
async function pages() {
  for (const width of [1440, 390]) {
    const context = await browser.newContext({ storageState: stateFile('padmin').pathname.replace(/^\/([A-Za-z]:)/, '$1'), viewport: { width, height: width === 390 ? 844 : 900 } })
    const page = await attach(context)
    for (const [path, title] of routes) {
      stage = 'page_' + width + path
      await page.goto(base + path, { waitUntil: 'networkidle' })
      assert.equal(new URL(page.url()).pathname, path, 'authorized route should remain selected')
      await page.locator('h1').first().waitFor()
      const fit = await page.evaluate(() => ({ width: innerWidth, content_width: document.documentElement.scrollWidth, heading_visible: !!document.querySelector('h1')?.getBoundingClientRect().height }))
      assert.equal(fit.heading_visible, true)
      assert.ok(fit.content_width <= width + 1, 'horizontal viewport overflow')
      check('page_' + width + path, { title, ...fit })
      if (['/dashboard', '/backup-plan-drafts', '/inspection-source-collections', '/my-notification-preferences', '/asset-export-jobs'].includes(path)) await screenshot(page, (width === 390 ? 'mobile' : 'desktop') + path.replaceAll('/', '-'))
    }
    await context.close()
  }
  const context = await browser.newContext({ storageState: stateFile('msp').pathname.replace(/^\/([A-Za-z]:)/, '$1'), viewport: { width: 1440, height: 900 } })
  const page = await attach(context); stage = 'msp_own_worklogs'
  await page.goto(base + '/my-worklogs', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '我的实填工时', exact: true }).waitFor()
  check('msp_own_worklogs'); await screenshot(page, 'desktop-own-worklogs'); await context.close()
}
async function actions() {
  const controlFile = new URL('browser-actions-control.json', privateDir)
  assert.equal(existsSync(controlFile), false, 'Do not repeat a previous write run blindly')
  const control = { run: randomUUID(), requests: [], receipts: [], complete: false }
  const persist = () => writeFileSync(controlFile, JSON.stringify(control, null, 2) + '\n')
  persist()
  const context = await browser.newContext({ storageState: stateFile('padmin').pathname.replace(/^\/([A-Za-z]:)/, '$1'), viewport: { width: 1440, height: 900 } })
  const page = await attach(context)
  page.on('request', request => {
    if (request.url().startsWith(base + '/api/v1/') && !['GET', 'HEAD'].includes(request.method())) {
      control.observed_writes ??= []
      control.observed_writes.push({ path: new URL(request.url()).pathname, method: request.method(), key: request.headers()['idempotency-key'], body: request.postData() })
      persist()
    }
  })
  stage = 'asset_original_request_recovery'
  await page.goto(base + '/assets', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '＋ 创建资产草稿', exact: true }).click()
  await page.getByLabel('资产名称', { exact: true }).fill('验收虚构设备 ' + control.run.slice(0, 8))
  await page.getByLabel('设备类型', { exact: true }).selectOption('switch')
  await page.getByLabel('管理 IP', { exact: true }).fill('192.0.2.210')
  await page.getByLabel('站点 ID', { exact: true }).fill('site-a-main')
  await page.getByLabel('确认仅创建资产草稿', { exact: true }).check()
  let first = true, accepted
  await page.route(base + '/api/v1/assets', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    const request = { key: route.request().headers()['idempotency-key'], body: route.request().postData() }
    control.requests.push(request); persist()
    const response = await route.fetch()
    const value = await response.json()
    control.receipts.push({ status: response.status(), data: value.data ?? null }); persist()
    assert.equal(response.status(), 200)
    if (first) { first = false; accepted = value.data; await route.abort('connectionfailed') }
    else { assert.deepEqual(request, control.requests[0]); assert.deepEqual(value.data, accepted); await route.fulfill({ response }) }
  })
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await page.getByRole('heading', { name: '原请求结果未确认', exact: true }).waitFor()
  assert.equal(await page.getByLabel('资产名称', { exact: true }).isDisabled(), true)
  await page.getByRole('button', { name: '重试完全相同的原请求', exact: true }).click()
  await page.getByText(/资产草稿创建已由原请求回执确认/).waitFor()
  assert.equal(control.requests.length, 2); assert.equal(accepted.availability, 'UNKNOWN')
  assert.equal(accepted.lifecycle_status, 'DRAFT')
  check('asset_same_request_recovery', { dispatches: 2, same_key_body_and_asset: true, unknown_monitoring: true })
  await page.unroute(base + '/api/v1/assets')
  await screenshot(page, 'desktop-created-asset')
  stage = 'source_collection_native_form'
  await page.goto(base + '/inspection-source-collections', { waitUntil: 'networkidle' })
  await page.getByLabel('来源集合标签', { exact: true }).fill('验收明确空来源 ' + control.run.slice(0, 8))
  await page.getByLabel('明确确认保存空来源选择', { exact: true }).check()
  const sourceResponse = page.waitForResponse(response => response.url() === base + '/api/v1/inspection-source-collections' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '保存明确来源集合草稿', exact: true }).click()
  const source = await sourceResponse; assert.equal(source.status(), 200)
  const sourceData = (await source.json()).data; control.source_id = sourceData.id; persist()
  await page.getByText('原保存回执已核对。它只含元数据，完整来源正文需单独读取和核对。', { exact: true }).waitFor()
  const detailResponse = page.waitForResponse(response => response.url() === base + '/api/v1/inspection-source-collections/' + sourceData.id && response.request().method() === 'GET')
  await page.getByRole('button', { name: '回读这份集合正文', exact: true }).click()
  assert.equal((await detailResponse).status(), 200)
  await page.locator('.collection-detail').waitFor()
  check('source_collection_native_form', { sha256_verification_retained: true, explicit_empty_selection: true })
  await screenshot(page, 'desktop-source-collection-created')
  stage = 'own_preference_native_form'
  await page.goto(base + '/my-notification-preferences', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '开始新建本人请求草稿', exact: true }).click()
  await page.getByLabel('请求通知等级', { exact: true }).selectOption('P1_P2')
  await page.getByLabel('确认仅保存本人请求', { exact: true }).check()
  const preferenceResponse = page.waitForResponse(response => response.url() === base + '/api/v1/my/notification-preferences' && response.request().method() === 'POST')
  await page.getByRole('button', { name: '保存本人请求草稿', exact: true }).click()
  const preference = await preferenceResponse; assert.equal(preference.status(), 200)
  const prefData = (await preference.json()).data; control.preference_id = prefData.id; persist()
  assert.equal(prefData.effective, false); assert.equal(prefData.notification_dispatched, false)
  await page.getByText('已收到原保存回执', { exact: false }).first().waitFor()
  check('own_preference_native_form', { effective: false, dispatched: false, sha256_verified: true })
  await screenshot(page, 'desktop-preference-created')
  control.complete = true; persist(); await context.close()
}
try {
  browser = await chromium.launch({ headless: true, chromiumSandbox: true, executablePath })
  evidence.browser_version = browser.version()
  if (mode === 'login') for (const role of ['padmin', 'msp', 'tadmin', 'viewerA', 'viewerB', 'reviewer']) await login(role)
  if (mode === 'pages') await pages()
  if (mode === 'actions') await actions()
  assert.equal(evidence.page_errors, 0)
  assert.equal(evidence.failed_api_gets.length, 0)
  evidence.completed = true; save()
  console.log(JSON.stringify({ mode, completed: true, checks: evidence.checks.length, screenshots: evidence.screenshots.length, page_errors: evidence.page_errors }))
} catch (error) {
  evidence.failure = { stage, type: error?.name ?? 'Error' }; save()
  let detail = String(error?.stack ?? error)
  for (const secret of [cfg.client_secret, ...cfg.test_users.map(user => user.password)]) detail = detail.replaceAll(secret, '[REDACTED]')
  writeFileSync(new URL('browser-' + mode + '-attempt' + attempt + '-failure.log', privateDir), detail)
  console.error(JSON.stringify({ mode, completed: false, stage, error_type: error?.name ?? 'Error' })); process.exitCode = 1
} finally { if (browser) await browser.close() }
