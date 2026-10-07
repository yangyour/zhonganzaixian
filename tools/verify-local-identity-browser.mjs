// Local production build + real designated-server API. Faults affect delivery of
// real responses only; no fabricated users, sessions, permissions or success DTOs.
import { chromium } from 'playwright'
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, sep } from 'node:path'
import { randomUUID, createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import assert from 'node:assert/strict'

const root = resolve(import.meta.dirname, '..')
const cfg = JSON.parse(readFileSync(resolve(root, 'deploy/private/server-test-runtime.json'), 'utf8'))
const base = cfg.public_origin, dist = resolve(root, 'apps/web/dist')
const run = randomUUID(), output = resolve(root, process.env.MOP_UI_EVIDENCE_DIR || `.local/identity-browser-${run}`)
assert.ok(output.startsWith(resolve(root, '.local') + sep), 'Raw browser artifacts must stay in the ignored .local directory')
assert.ok(!existsSync(resolve(output, 'result.json')), 'Use a fresh evidence directory; previous results must be retained')
mkdirSync(output, { recursive: true })
const checks = [], requests = [], screenshots = [], assets = new Map(), faults = []
let browser, context, page, stage = 'launch', csrf, policyBefore, createdRole, originalTenant
let policyUnchanged = false
const releases = []
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function record(name) { checks.push(name); console.log(JSON.stringify({ passed: name })) }
async function call(path, method = 'GET', body, expected = 200) {
  const response = await context.request.fetch(base + '/api/v1' + path, {
    method, headers: { Origin: base, ...(csrf ? { 'X-CSRF-Token': csrf } : {}), 'Idempotency-Key': randomUUID() },
    ...(body === undefined ? {} : { data: body }), timeout: 30000,
  })
  assert.equal(response.status(), expected, `API status at ${stage}`)
  requests.push({ method, path: path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, '{id}').split('?')[0], status: response.status(), source: 'api-check' })
  const json = await response.json(); return json.data ?? json.error
}
async function shot(name) {
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: resolve(output, name + '.png'), fullPage: true, animations: 'disabled' })
  screenshots.push(name + '.png')
}
async function noOverflow() { assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)) }
async function navigate(path, title) {
  await page.goto(base + path)
  await page.getByRole('heading', { name: title, exact: true }).waitFor()
}
async function failNext(path) {
  const hit = deferred(), release = deferred()
  let consumed = false
  releases.push(release.resolve)
  const handler = async route => {
    if (route.request().method() !== 'GET' || consumed) return route.fallback()
    consumed = true
    const response = await route.fetch(); assert.equal(response.status(), 200)
    faults.push({ path: path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, '{id}'), real_status: 200, delivery: 'aborted-after-real-response' })
    hit.resolve(); await release.promise; await route.abort('failed').catch(() => {})
    await page.unroute(base + '/api/v1' + path, handler)
  }
  await page.route(base + '/api/v1' + path, handler)
  return { hit: hit.promise, release: release.resolve }
}
async function delayNext(path, method = 'GET') {
  const hit = deferred(), release = deferred(), done = deferred()
  let consumed = false
  releases.push(release.resolve)
  const handler = async route => {
    if (consumed || route.request().method() !== method) return route.fallback()
    consumed = true
    const response = await route.fetch(); assert.equal(response.status(), 200)
    faults.push({ path: path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, '{id}'), real_status: 200, delivery: 'delayed-real-response' })
    hit.resolve(); await release.promise; await route.fulfill({ response }).catch(() => {})
    await page.unroute(base + '/api/v1' + path, handler); done.resolve()
  }
  await page.route(base + '/api/v1' + path, handler)
  return { hit: hit.promise, release: release.resolve, done: done.promise }
}
async function confirmLeave() { await page.getByRole('button', { name: '离开', exact: true }).click() }
const errors = []
try {
  assert.ok(existsSync(resolve(dist, 'index.html')), 'Build the current Web source first')
  browser = await chromium.launch({ executablePath: process.env.MOP_BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, chromiumSandbox: true })
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  // Serve only the current local build under the application's existing origin.
  // API traffic retains the real origin, cookies, CSRF and server authorization.
  await context.route(base + '/**', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.pathname.startsWith('/api/') || request.method() !== 'GET') return route.continue()
    const path = request.resourceType() === 'document' ? resolve(dist, 'index.html') : resolve(dist, '.' + decodeURIComponent(url.pathname))
    if (!path.startsWith(dist + sep) || !existsSync(path)) return route.continue()
    const body = readFileSync(path); assets.set(path.slice(dist.length + 1).replaceAll('\\', '/'), hash(body))
    const type = path.endsWith('.js') ? 'application/javascript' : path.endsWith('.css') ? 'text/css' : path.endsWith('.html') ? 'text/html' : undefined
    await route.fulfill({ status: 200, body, ...(type ? { contentType: type } : {}) })
  })
  page = await context.newPage(); page.setDefaultTimeout(30000)
  page.on('pageerror', error => errors.push(error.name))
  page.on('response', response => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/')) requests.push({ method: response.request().method(), path: url.pathname.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, '{id}'), status: response.status(), source: 'browser' })
  })
  stage = 'local_build_real_project_login'
  const admin = cfg.test_users.find(user => user.role === 'padmin'); assert.ok(admin)
  await page.goto(base + '/login')
  await page.getByLabel('登录账号', { exact: true }).fill(admin.username)
  await page.getByLabel('登录密码', { exact: true }).fill(admin.password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL('**/assets')
  const session = await call('/session'); csrf = session.csrf_token
  originalTenant = session.tenant.id
  assert.equal(session.identity_source, 'project_local'); policyBefore = await call('/identity/policy')
  for (const [key, value] of Object.entries(policyBefore)) if (key !== 'version') assert.equal(value, null)
  record(stage)

  stage = 'old_user_detail_cannot_overwrite_new_form'
  await navigate('/identity/users', '用户与成员授权')
  await page.getByLabel('搜索账号或姓名', { exact: true }).fill(admin.username)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const card = page.locator('article.identity-card').filter({ hasText: admin.username })
  await card.waitFor()
  const delay = await delayNext(`/identity/users/${session.user.id}/memberships`)
  await card.getByRole('button', { name: '管理账号', exact: true }).click(); await delay.hit
  assert.equal(await page.locator('section.identity-form').count(), 0)
  await page.getByRole('button', { name: '新建用户', exact: true }).click()
  await page.getByLabel('初始密码', { exact: true }).waitFor()
  delay.release(); await delay.done
  assert.equal(await page.getByLabel('显示名称', { exact: true }).inputValue(), '')
  assert.equal(await page.getByLabel('登录账号', { exact: true }).inputValue(), '')
  await shot('desktop-new-user-after-old-detail'); record(stage)

  stage = 'failed_selected_user_detail_hides_editable_form'
  const failedDetail = await failNext(`/identity/users/${session.user.id}/memberships`)
  await card.getByRole('button', { name: '管理账号', exact: true }).click(); await failedDetail.hit
  assert.equal(await page.locator('section.identity-form').count(), 0, 'Editable form must be hidden while detail is in flight')
  failedDetail.release(); await page.getByText('正在读取所选账号详情…', { exact: true }).waitFor({ state: 'hidden' })
  assert.equal(await page.locator('section.identity-form').count(), 0, 'Failed selected detail must not restore an editable form'); record(stage)

  stage = 'accepted_role_write_failed_readback_does_not_report_failure_or_repeat'
  await navigate('/identity/roles', '角色与权限')
  await page.locator('article.identity-card').first().waitFor()
  await page.getByRole('button', { name: '新建角色', exact: true }).click()
  const roleName = '虚构本地补测角色 ' + run
  await page.getByLabel('角色名称', { exact: true }).fill(roleName)
  await page.getByLabel(/读取资产记录/).check()
  const failedReadback = await failNext('/identity/roles')
  const accepted = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/identity/roles' && r.request().method() === 'POST')
  await page.getByRole('button', { name: '保存角色', exact: true }).click()
  const acceptedResponse = await accepted; assert.equal(acceptedResponse.status(), 200)
  createdRole = (await acceptedResponse.json()).data
  await failedReadback.hit
  await page.getByText('操作已保存，正在回读最新记录。', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '新建角色', exact: true }).isDisabled(), true)
  failedReadback.release()
  await page.getByText('操作已保存，但最新记录尚未确认；请重新读取，不要重复提交。', { exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '原请求重试', exact: true }).count(), 0)
  assert.equal(await page.locator('.el-alert--success').count(), 0)
  await shot('desktop-accepted-write-readback-failed')
  await page.getByRole('button', { name: '刷新角色列表', exact: true }).click()
  await page.locator('article.identity-card').filter({ hasText: roleName }).waitFor()
  const roleRows = await call('/identity/roles')
  assert.equal(roleRows.items.filter(role => role.name === roleName).length, 1)
  assert.equal(requests.filter(r => r.source === 'browser' && r.method === 'POST' && r.path === '/api/v1/identity/roles').length, 1)
  record(stage)

  stage = 'roles_refresh_error_clears_old_rows'
  const failedRoles = await failNext('/identity/roles')
  await page.getByRole('button', { name: '刷新角色列表', exact: true }).click(); await failedRoles.hit
  assert.equal(await page.locator('article.identity-card').count(), 0); failedRoles.release()
  await page.getByRole('button', { name: '重新读取', exact: true }).waitFor()
  assert.equal(await page.locator('article.identity-card').count(), 0); record(stage)

  stage = 'settings_partial_read_failure_removes_old_policy_form'
  await navigate('/identity/settings', '身份认证设置')
  await page.getByLabel('密码最低长度', { exact: true }).waitFor()
  const failedProvider = await failNext('/identity/providers')
  await page.getByRole('button', { name: '重新读取设置', exact: true }).click(); await failedProvider.hit
  assert.equal(await page.getByLabel('密码最低长度', { exact: true }).count(), 0); failedProvider.release()
  await page.getByRole('button', { name: '重新读取', exact: true }).waitFor()
  assert.equal(await page.getByLabel('密码最低长度', { exact: true }).count(), 0)
  await shot('desktop-settings-incomplete-read'); record(stage)

  stage = 'account_failed_refresh_clears_unsaved_passwords'
  await navigate('/account', '我的账号'); await page.getByLabel('当前密码', { exact: true }).waitFor()
  await page.getByLabel('当前密码', { exact: true }).fill('SYNTHETIC_UNSUBMITTED_INPUT')
  const failedAccount = await failNext('/account')
  await page.getByRole('button', { name: '重新读取账号', exact: true }).click(); await confirmLeave(); await failedAccount.hit
  assert.equal(await page.locator('input[type=password]').count(), 0); failedAccount.release()
  await page.getByRole('button', { name: '重新读取', exact: true }).waitFor()
  assert.equal(await page.locator('input[type=password]').count(), 0)
  assert.equal(requests.filter(r => r.method !== 'GET' && r.path.includes('/account')).length, 0); record(stage)

  stage = 'desktop_and_390px_identity_layouts'
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
    for (const [path, title, ready] of [
      ['/identity/users', '用户与成员授权', '搜索账号或姓名'],
      ['/identity/roles', '角色与权限', null],
      ['/identity/settings', '身份认证设置', '密码最低长度'],
      ['/account', '我的账号', '当前密码'],
    ]) {
      await navigate(path, title)
      if (ready) await page.getByLabel(ready, { exact: true }).waitFor()
      if (path.endsWith('/users')) {
        await page.locator('article.identity-card').first().waitFor()
        await page.locator('.el-loading-mask').waitFor({ state: 'hidden' })
        await page.getByLabel('搜索账号或姓名', { exact: true }).fill(admin.username)
        const queried = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/identity/users' && r.request().method() === 'GET')
        await page.getByRole('button', { name: '查询', exact: true }).click(); assert.equal((await queried).status(), 200)
        await page.locator('article.identity-card').filter({ hasText: admin.username }).waitFor()
      }
      if (path.endsWith('/roles')) await page.locator('article.identity-card').first().waitFor()
      if (path.endsWith('/settings')) assert.equal(await page.getByLabel('密码最低长度', { exact: true }).inputValue(), '')
      await page.locator('.el-loading-mask').waitFor({ state: 'hidden' })
      await noOverflow(); await shot(`${width}-${path.split('/').at(-1)}`); record(`${width}px_${path}`)
    }
  }
  stage = 'tenant_changes_clear_identity_views_and_unsaved_secrets'
  assert.ok(session.allowed_tenants.length >= 2, 'This acceptance account needs two already authorized tenants')
  let currentTenant = session.tenant.id
  for (const [path, title, field] of [
    ['/identity/users', '用户与成员授权', '初始密码'],
    ['/identity/roles', '角色与权限', '角色名称'],
    ['/identity/settings', '身份认证设置', '密码最低长度'],
    ['/account', '我的账号', '当前密码'],
  ]) {
    await navigate(path, title)
    if (path.endsWith('/users')) {
      await page.locator('article.identity-card').first().waitFor()
      await page.getByRole('button', { name: '新建用户', exact: true }).click()
    }
    if (path.endsWith('/roles')) {
      await page.locator('article.identity-card').first().waitFor()
      await page.getByRole('button', { name: '新建角色', exact: true }).click()
    }
    await page.getByLabel(field, { exact: true }).fill(path.endsWith('/settings') ? '23' : 'SYNTHETIC_DISCARDED_INPUT')
    const target = session.allowed_tenants.find(tenant => tenant.id !== currentTenant)
    const selectTarget = async () => {
      await page.locator('.tenant-select .el-select__wrapper').click()
      await page.getByRole('option', { name: target.name, exact: true }).click()
    }
    if (path.endsWith('/users')) {
      await selectTarget(); await page.getByRole('button', { name: '继续编辑', exact: true }).click()
      assert.equal(await page.getByLabel(field, { exact: true }).inputValue(), 'SYNTHETIC_DISCARDED_INPUT')
      record('tenant_change_cancel_preserves_unsaved_form')
    }
    const delayedSwitch = await delayNext('/session/tenant', 'POST')
    await selectTarget(); await confirmLeave(); await delayedSwitch.hit
    await page.locator('.switching-panel').waitFor()
    assert.equal(await page.locator('.identity-page').count(), 0)
    assert.equal(await page.locator('input[type=password]').count(), 0)
    if (path.endsWith('/account')) await shot('390-account-hidden-during-tenant-switch')
    delayedSwitch.release(); await delayedSwitch.done
    await page.getByRole('heading', { name: title, exact: true }).waitFor()
    if (path.endsWith('/users')) assert.equal(await page.getByLabel('初始密码', { exact: true }).count(), 0)
    else if (path.endsWith('/roles')) assert.equal(await page.getByLabel('角色名称', { exact: true }).count(), 0)
    else { await page.getByLabel(field, { exact: true }).waitFor(); assert.equal(await page.getByLabel(field, { exact: true }).inputValue(), '') }
    const current = await call('/session'); assert.equal(current.tenant.id, target.id); csrf = current.csrf_token
    currentTenant = target.id; record('tenant_change_clears_' + path.split('/').at(-1))
  }
  assert.equal(currentTenant, session.tenant.id)
  assert.deepEqual(await call('/identity/policy'), policyBefore)
  policyUnchanged = true
  assert.equal((await call('/health/ready')).ready, true)
  assert.deepEqual(errors, [])
} catch (error) {
  writeFileSync(resolve(output, 'failure-private.json'), JSON.stringify({ stage, error_type: error.name, message: error.message, stack: error.stack }, null, 2))
  await page?.screenshot({ path: resolve(output, 'failure-private.png'), fullPage: true }).catch(() => {})
  console.error(JSON.stringify({ passed: false, stage, error_type: error.name, details: 'ignored private failure log' })); process.exitCode = 1
} finally {
  for (const release of releases) release()
  let cleanup = createdRole ? 'PENDING' : 'NO_CREATED_ROLE'
  try {
    if (createdRole) {
      const latestSession = await call('/session'); csrf = latestSession.csrf_token
      if (latestSession.tenant.id !== originalTenant) {
        const restored = await call('/session/tenant', 'POST', { tenant_id: originalTenant }); csrf = restored.csrf_token
        assert.equal(restored.tenant.id, originalTenant)
      }
      const row = (await call('/identity/roles')).items.find(role => role.id === createdRole.id)
      assert.ok(row && row.name.endsWith(run) && !row.builtin)
      await call('/identity/roles/' + row.id, 'PATCH', { expected_version: row.version, name: row.name, description: row.description, base_role: row.base_role, permissions: row.permissions, enabled: false })
      cleanup = 'SYNTHETIC_ROLE_DISABLED'
    }
  } catch { cleanup = 'FAILED_REQUIRES_FOLLOWUP'; process.exitCode = 1 }
  const report = {
    verified_at: new Date().toISOString(), passed: !process.exitCode, run, checks, stage,
    source_commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
    verification_script_sha256: hash(readFileSync(new URL(import.meta.url))),
    browser: browser ? browser.version() : null, frontend: 'current-local-production-build-served-to-browser',
    backend: 'designated-server-existing-api-0.45', deployed_latest_frontend: false,
    assets: Object.fromEntries(assets), requests, controlled_delivery_faults: faults, screenshots, page_errors: errors,
    cleanup, policy_unchanged: policyUnchanged, formal_contract_acceptance: 'NOT_RUN',
  }
  writeFileSync(resolve(output, 'result.json'), JSON.stringify(report, null, 2) + '\n')
  await browser?.close()
  console.log(JSON.stringify({ passed: report.passed, checks: checks.length, screenshots: screenshots.length, cleanup }))
}
