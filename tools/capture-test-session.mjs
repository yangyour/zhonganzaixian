import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { chromium } from 'playwright';

const root = new URL('../', import.meta.url);
let browser;
try {
  // Private JSON errors can contain source fragments, so secret loading belongs inside the redacted boundary.
  const webOrigin = process.env.MOP_WEB_ORIGIN ?? 'http://127.0.0.1:5173';
  assert.equal(new URL(webOrigin).hostname, '127.0.0.1', '登录捕获仅用于本机共享测试环境应用');
  const runtime = JSON.parse(readFileSync(new URL('deploy/private/runtime.json', root), 'utf8').replace(/^\uFEFF/, ''));
  const allRoles = process.argv[2] === '--all';
  const roles = allRoles ? ['padmin', 'msp', 'tadmin', 'viewerA', 'viewerB', 'reviewer'] : [process.argv[2] ?? 'padmin'];
  assert.ok(roles.every(role => runtime.test_users.some(candidate => candidate.role === role)), '私有运行文件中没有所需测试角色');
  const sessionDirectory = new URL('.local/designated-test/private/sessions/', root);
  if (allRoles) mkdirSync(sessionDirectory, { recursive: true });
  const candidates = [process.env.MOP_BROWSER_EXECUTABLE, 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Microsoft/Edge/Application/msedge.exe', 'C:/Program Files/Google/Chrome/Application/chrome.exe'];
  const executablePath = candidates.find(candidate => candidate && existsSync(candidate));
  browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
  for (const role of roles) {
  const user = runtime.test_users.find(candidate => candidate.role === role);
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  await page.goto(webOrigin + '/login', { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: '使用 Keycloak 登录', exact: true }).click();
  await page.locator('#username').waitFor();
  const provider = new URL(page.url());
  const expectedIssuer = new URL(runtime.environment.MOP_OIDC_ISSUER);
  assert.equal(provider.origin, expectedIssuer.origin);
  assert.equal(provider.pathname, '/realms/mop-test/protocol/openid-connect/auth');
  assert.equal(provider.searchParams.get('code_challenge_method'), 'S256');
  assert.ok(provider.searchParams.get('state'));
  assert.ok(provider.searchParams.get('nonce'));
  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click();
  await page.getByRole('heading', { name: '资产管理', exact: true }).waitFor();
  const response = await context.request.get(webOrigin + '/api/v1/session');
  assert.equal(response.status(), 200);
  const session = (await response.json()).data;
  assert.equal(session.mode, 'remote_test');
  assert.equal(session.identity_source, 'test_oidc');
  assert.equal(session.user.role, role === 'reviewer' ? 'padmin' : role.startsWith('viewer') ? 'viewer' : role);
  const cookie = (await context.cookies(webOrigin)).find(candidate => candidate.name === 'MOP_SESSION');
  assert.ok(cookie?.httpOnly);
  const destination = allRoles ? new URL(`qa-${role}.json`, sessionDirectory) : new URL('deploy/private/qa-session.json', root);
  writeFileSync(destination, JSON.stringify({ cookie: `MOP_SESSION=${cookie.value}`, user_id: session.user.id, csrf_token: session.csrf_token, mode: session.mode, environment: 'designated-server', created_at: new Date().toISOString() }) + '\n');
  console.log(JSON.stringify({ status: 'authenticated', environment: 'designated-server', mode: session.mode, identity_source: session.identity_source, role, pkce: 'S256', credential_output: 'none', session_file: allRoles ? 'ignored_designated_sessions' : 'ignored_deploy_private_qa_session_json' }));
  await context.close();
  }
} catch {
  // JSON and Playwright errors can echo private data or protocol URLs; never print the caught error.
  console.error('真实OIDC登录捕获未完成。检查API/SSH隧道、项目身份映射及已安装浏览器；未输出秘密。');
  process.exitCode = 1;
} finally {
  if (browser) await browser.close();
}
